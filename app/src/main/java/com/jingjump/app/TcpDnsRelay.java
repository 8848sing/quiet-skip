package com.jingjump.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal DNS-over-TCP relay for the local TUN device.
 *
 * <p>Every Android resolver retries a truncated UDP answer over TCP, and some
 * apps simply prefer TCP. Until 1.9.0 the service parsed those queries but
 * never completed the TCP handshake, so a TCP fallback could only hang until
 * the app timed out — the "TCP DNS support" claimed by 1.8.0 was unreachable
 * code. This class terminates the handshake locally, forwards the DNS message
 * over a real protected TCP socket, and streams the answer back with correct
 * sequence numbers.</p>
 *
 * <p>It is deliberately small and bounded: at most {@value #MAX_CONNECTIONS}
 * concurrent connections, a 10 s idle timeout, one in-flight query per
 * connection (DNS-over-TCP requires answers in order), and a hard RST for
 * anything that cannot be served so a client fails fast instead of hanging.</p>
 */
final class TcpDnsRelay {
    interface Emitter {
        void emit(byte[] packet);
    }

    interface Upstream {
        /** Sends one framed DNS query and returns the unframed response. */
        byte[] query(byte[] dnsQuery) throws IOException;
    }

    static final int MAX_CONNECTIONS = 16;
    static final long IDLE_TIMEOUT_MS = 10_000L;
    static final int MAX_PENDING_BYTES = 8_192;
    static final int MAX_PAYLOAD_CHUNK = 1_200;

    private final Emitter emitter;
    private final Upstream upstream;
    private final ExecutorService workers;
    private final Map<String, Connection> connections = new HashMap<>();
    private final AtomicInteger ipIds = new AtomicInteger(1);
    private volatile boolean closed;

    TcpDnsRelay(Emitter emitter, Upstream upstream) {
        this.emitter = emitter;
        this.upstream = upstream;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "jingjump-dns-tcp");
            thread.setDaemon(true);
            return thread;
        };
        this.workers = Executors.newFixedThreadPool(2, factory);
    }

    /** Handles one TCP segment captured from the tunnel. */
    void handle(byte[] packet, int base, int available) {
        if (closed) {
            return;
        }
        DnsPacket.Segment segment;
        try {
            segment = DnsPacket.parseTcpSegment(packet, base, available);
        } catch (Throwable ignored) {
            return;
        }
        if (segment == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (connections) {
            purgeIdle(now);
            String key = keyOf(segment);
            Connection connection = connections.get(key);
            if (segment.isSyn()) {
                if (connection == null) {
                    if (connections.size() >= MAX_CONNECTIONS) {
                        emitRst(segment);
                        return;
                    }
                    connection = new Connection(key, segment);
                    connections.put(key, connection);
                }
                connection.lastActive = now;
                connection.clientNextSeq = segment.sequence + 1;
                emit(DnsPacket.buildTcpPacket(
                        segment.ipVersion,
                        segment.destinationAddress,
                        segment.sourceAddress,
                        segment.destinationPort,
                        segment.sourcePort,
                        connection.serverIsn,
                        connection.clientNextSeq,
                        DnsPacket.TCP_FLAG_SYN | DnsPacket.TCP_FLAG_ACK,
                        null
                ));
                return;
            }
            if (connection == null) {
                // A stray ACK (for example the last handshake ack of a
                // connection we already closed) is simply dropped; anything that
                // still expects an answer gets a reset so it fails fast.
                if (segment.payloadLength > 0) {
                    emitRst(segment);
                }
                return;
            }
            connection.lastActive = now;
            if (segment.hasFlag(DnsPacket.TCP_FLAG_RST)) {
                connections.remove(key);
                return;
            }
            if (segment.payloadLength > 0 && segment.sequence == connection.clientNextSeq) {
                if (connection.pending.size() + segment.payloadLength > MAX_PENDING_BYTES) {
                    emitRst(segment);
                    connections.remove(key);
                    return;
                }
                connection.pending.write(packet, segment.payloadOffset, segment.payloadLength);
                connection.clientNextSeq = segment.sequence + segment.payloadLength;
            }
            if (segment.payloadLength > 0) {
                emit(DnsPacket.buildTcpPacket(
                        segment.ipVersion,
                        segment.destinationAddress,
                        segment.sourceAddress,
                        segment.destinationPort,
                        segment.sourcePort,
                        connection.serverSeq,
                        connection.clientNextSeq,
                        DnsPacket.TCP_FLAG_ACK,
                        null
                ));
                drain(connection);
            }
            if (segment.hasFlag(DnsPacket.TCP_FLAG_FIN)) {
                connection.clientNextSeq = segment.sequence + segment.payloadLength + 1;
                emit(DnsPacket.buildTcpPacket(
                        connection.ipVersion,
                        connection.serverAddress,
                        connection.clientAddress,
                        connection.serverPort,
                        connection.clientPort,
                        connection.serverSeq,
                        connection.clientNextSeq,
                        DnsPacket.TCP_FLAG_FIN | DnsPacket.TCP_FLAG_ACK,
                        null
                ));
                connections.remove(key);
            }
        }
    }

    /** Drops every connection and stops the workers (service shutdown). */
    void close() {
        closed = true;
        synchronized (connections) {
            connections.clear();
        }
        workers.shutdownNow();
    }

    int openConnections() {
        synchronized (connections) {
            return connections.size();
        }
    }

    private void drain(Connection connection) {
        if (connection.busy) {
            return;
        }
        byte[] frame = connection.nextFrame();
        if (frame == null) {
            return;
        }
        connection.busy = true;
        try {
            workers.execute(() -> serve(connection, frame));
        } catch (Throwable rejected) {
            connection.busy = false;
        }
    }

    private void serve(Connection connection, byte[] frame) {
        byte[] response = null;
        try {
            response = upstream.query(frame);
        } catch (Throwable ignored) {
            response = null;
        }
        synchronized (connections) {
            if (closed || !connections.containsKey(connection.key)) {
                connection.busy = false;
                return;
            }
            if (response == null || response.length < 12) {
                sendReset(connection);
                connections.remove(connection.key);
                connection.busy = false;
                return;
            }
            int offset = 0;
            while (offset < response.length) {
                int chunk = Math.min(MAX_PAYLOAD_CHUNK, response.length - offset);
                byte[] part = new byte[chunk];
                System.arraycopy(response, offset, part, 0, chunk);
                byte[] packet = DnsPacket.buildTcpPacket(
                        connection.ipVersion,
                        connection.serverAddress,
                        connection.clientAddress,
                        connection.serverPort,
                        connection.clientPort,
                        connection.serverSeq,
                        connection.clientNextSeq,
                        DnsPacket.TCP_FLAG_ACK | DnsPacket.TCP_FLAG_PSH,
                        part
                );
                if (packet == null) {
                    break;
                }
                emit(packet);
                connection.serverSeq += chunk;
                offset += chunk;
            }
            connection.busy = false;
            drain(connection);
        }
    }

    private void purgeIdle(long now) {
        Iterator<Map.Entry<String, Connection>> iterator = connections.entrySet().iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next().getValue();
            if (now - connection.lastActive > IDLE_TIMEOUT_MS) {
                iterator.remove();
            }
        }
    }

    private void emit(byte[] packet) {
        if (packet == null || closed) {
            return;
        }
        try {
            emitter.emit(packet);
        } catch (Throwable ignored) {
            // The tunnel is closing; dropping the segment is the right answer.
        }
    }

    private void emitRst(DnsPacket.Segment segment) {
        try {
            emit(DnsPacket.buildTcpRst(segment));
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private void sendReset(Connection connection) {
        emit(DnsPacket.buildTcpPacket(
                connection.ipVersion,
                connection.serverAddress,
                connection.clientAddress,
                connection.serverPort,
                connection.clientPort,
                connection.serverSeq,
                connection.clientNextSeq,
                DnsPacket.TCP_FLAG_RST | DnsPacket.TCP_FLAG_ACK,
                null
        ));
    }

    private static String keyOf(DnsPacket.Segment segment) {
        return segment.ipVersion + "|" + segment.sourcePort + "|"
                + java.util.Arrays.toString(segment.sourceAddress) + "|"
                + java.util.Arrays.toString(segment.destinationAddress);
    }

    private final class Connection {
        final String key;
        final int ipVersion;
        final byte[] clientAddress;
        final byte[] serverAddress;
        final int clientPort;
        final int serverPort;
        final int serverIsn;
        int serverSeq;
        int clientNextSeq;
        long lastActive;
        boolean busy;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Connection(String key, DnsPacket.Segment segment) {
            this.key = key;
            this.ipVersion = segment.ipVersion;
            this.clientAddress = segment.sourceAddress;
            this.serverAddress = segment.destinationAddress;
            this.clientPort = segment.sourcePort;
            this.serverPort = segment.destinationPort;
            this.serverIsn = nextSequence();
            this.serverSeq = serverIsn + 1;
            this.clientNextSeq = segment.sequence + 1;
            this.lastActive = System.currentTimeMillis();
        }

        /** Extracts the next complete length-prefixed DNS message, if any. */
        byte[] nextFrame() {
            byte[] buffer = pending.toByteArray();
            if (buffer.length < 2) {
                return null;
            }
            int length = ((buffer[0] & 0xff) << 8) | (buffer[1] & 0xff);
            if (length <= 0 || length > 65_535) {
                return null;
            }
            if (buffer.length < 2 + length) {
                return null;
            }
            byte[] frame = new byte[length];
            System.arraycopy(buffer, 2, frame, 0, length);
            pending.reset();
            if (buffer.length > 2 + length) {
                pending.write(buffer, 2 + length, buffer.length - 2 - length);
            }
            return frame;
        }
    }

    private int nextSequence() {
        // A non-negative 32-bit sequence number, different on every connection.
        return ipIds.getAndIncrement() * 65_521 & 0x7fffffff;
    }
}