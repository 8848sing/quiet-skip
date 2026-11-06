package com.jingjump.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the TCP relay with synthetic IPv4 segments: handshake, framed query,
 * in-order answer, retransmission handling, reset for stray payloads and the
 * connection cap.
 */
public final class TcpDnsRelayTest {
    private static final byte[] CLIENT = {10, 0, 1, 2};
    private static final byte[] SERVER = {(byte) 223, 5, 5, 5};
    private static final int CLIENT_PORT = 41_000;

    private final List<byte[]> emitted = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    private TcpDnsRelay relayWithAnswer(byte[] answer) {
        return new TcpDnsRelay(emitted::add, query -> {
            upstreamCalls.incrementAndGet();
            return answer;
        });
    }

    @Test
    public void completesHandshakeAndAnswersFramedQuery() {
        byte[] answer = dnsAnswer(0x1234, 30);
        TcpDnsRelay relay = relayWithAnswer(answer);
        byte[] query = dnsQuery(0x1234, "ad.toutiao.com");
        byte[] framed = frame(query);

        byte[] syn = tcpSegment(1_000, 0, DnsPacket.TCP_FLAG_SYN, new byte[0]);
        relay.handle(syn, 0, syn.length);
        assertEquals(1, emitted.size());
        byte[] synAck = emitted.get(0);
        assertEquals(DnsPacket.TCP_FLAG_SYN | DnsPacket.TCP_FLAG_ACK, tcpFlags(synAck));
        int serverIsn = tcpSequence(synAck);
        assertEquals(1_001, tcpAcknowledgement(synAck));

        byte[] data = tcpSegment(1_001, serverIsn + 1, DnsPacket.TCP_FLAG_ACK, framed);
        relay.handle(data, 0, data.length);
        assertEquals(2, emitted.size());
        byte[] ack = emitted.get(1);
        assertEquals(
                1_001 + framed.length,
                tcpAcknowledgement(ack)
        );
        assertEquals(serverIsn + 1, tcpSequence(ack));

        awaitEmitted(3);
        byte[] reply = emitted.get(2);
        assertEquals(DnsPacket.TCP_FLAG_ACK | DnsPacket.TCP_FLAG_PSH, tcpFlags(reply));
        assertEquals(serverIsn + 1, tcpSequence(reply));
        assertEquals(1_001 + framed.length, tcpAcknowledgement(reply));
        assertArrayEquals(answer, tcpPayload(reply));
        assertEquals(1, upstreamCalls.get());
        relay.close();
    }

    @Test
    public void splitsLargeAnswersIntoSeveralSegmentsInOrder() {
        byte[] answer = dnsAnswer(0x2222, 3_000);
        TcpDnsRelay relay = relayWithAnswer(answer);
        byte[] query = dnsQuery(0x2222, "big.example");
        byte[] framed = frame(query);

        byte[] syn = tcpSegment(5_000, 0, DnsPacket.TCP_FLAG_SYN, new byte[0]);
        relay.handle(syn, 0, syn.length);
        int serverIsn = tcpSequence(emitted.get(0));
        byte[] data = tcpSegment(5_001, serverIsn + 1, DnsPacket.TCP_FLAG_ACK, framed);
        relay.handle(data, 0, data.length);

        awaitEmitted(3 + 2);
        int expected = serverIsn + 1;
        int reassembled = 0;
        for (int i = 2; i < emitted.size(); i++) {
            byte[] segment = emitted.get(i);
            assertEquals(expected, tcpSequence(segment));
            expected += tcpPayload(segment).length;
            reassembled += tcpPayload(segment).length;
            assertTrue(tcpPayload(segment).length <= TcpDnsRelay.MAX_PAYLOAD_CHUNK);
        }
        assertEquals(answer.length, reassembled);
        relay.close();
    }

    @Test
    public void retransmittedFrameIsAcknowledgedWithoutSecondUpstreamCall() {
        TcpDnsRelay relay = relayWithAnswer(dnsAnswer(0x3333, 20));
        byte[] framed = frame(dnsQuery(0x3333, "retry.example"));
        byte[] syn = tcpSegment(9_000, 0, DnsPacket.TCP_FLAG_SYN, new byte[0]);
        relay.handle(syn, 0, syn.length);
        int serverIsn = tcpSequence(emitted.get(0));
        byte[] data = tcpSegment(9_001, serverIsn + 1, DnsPacket.TCP_FLAG_ACK, framed);
        relay.handle(data, 0, data.length);
        awaitEmitted(3);
        int before = emitted.size();
        relay.handle(data, 0, data.length);
        assertTrue(emitted.size() > before);
        assertEquals(1, upstreamCalls.get());
        relay.close();
    }

    @Test
    public void payloadWithoutHandshakeIsReset() {
        TcpDnsRelay relay = relayWithAnswer(dnsAnswer(0x4444, 20));
        byte[] framed = frame(dnsQuery(0x4444, "stray.example"));
        byte[] data = tcpSegment(7_000, 0, DnsPacket.TCP_FLAG_ACK, framed);
        relay.handle(data, 0, data.length);
        assertEquals(1, emitted.size());
        assertEquals(DnsPacket.TCP_FLAG_RST, tcpFlags(emitted.get(0)) & DnsPacket.TCP_FLAG_RST);
        assertEquals(0, upstreamCalls.get());
        relay.close();
    }

    @Test
    public void connectionCapRefusesExtraHandshakes() {
        TcpDnsRelay relay = relayWithAnswer(dnsAnswer(0x5555, 20));
        for (int i = 0; i < TcpDnsRelay.MAX_CONNECTIONS; i++) {
            byte[] syn = tcpSegment(CLIENT_PORT + i, 1_000 + i, 0,
                    DnsPacket.TCP_FLAG_SYN, new byte[0]);
            relay.handle(syn, 0, syn.length);
        }
        assertEquals(TcpDnsRelay.MAX_CONNECTIONS, relay.openConnections());
        int before = emitted.size();
        byte[] extra = tcpSegment(CLIENT_PORT + 99, 3_000, 0, DnsPacket.TCP_FLAG_SYN,
                new byte[0]);
        relay.handle(extra, 0, extra.length);
        assertEquals(before + 1, emitted.size());
        assertEquals(DnsPacket.TCP_FLAG_RST, tcpFlags(emitted.get(before)));
        relay.close();
    }

    @Test
    public void finIsAnsweredAndClosesTheConnection() {
        TcpDnsRelay relay = relayWithAnswer(dnsAnswer(0x6666, 20));
        byte[] syn = tcpSegment(11_000, 0, DnsPacket.TCP_FLAG_SYN, new byte[0]);
        relay.handle(syn, 0, syn.length);
        byte[] fin = tcpSegment(11_001, 0, DnsPacket.TCP_FLAG_FIN | DnsPacket.TCP_FLAG_ACK,
                new byte[0]);
        relay.handle(fin, 0, fin.length);
        byte[] reply = emitted.get(emitted.size() - 1);
        assertEquals(DnsPacket.TCP_FLAG_FIN | DnsPacket.TCP_FLAG_ACK, tcpFlags(reply));
        assertEquals(11_002, tcpAcknowledgement(reply));
        assertEquals(0, relay.openConnections());
        relay.close();
    }

    // -------------------------------------------------------------- fixtures

    private void awaitEmitted(int minimum) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (emitted.size() < minimum && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue("relay produced " + emitted.size() + " of " + minimum + " packets",
                emitted.size() >= minimum);
    }

    private static byte[] tcpSegment(int sequence, int acknowledgement, int flags, byte[] payload) {
        return tcpSegment(CLIENT_PORT, sequence, acknowledgement, flags, payload);
    }

    private static byte[] tcpSegment(
            int clientPort,
            int sequence,
            int acknowledgement,
            int flags,
            byte[] payload
    ) {
        int tcpLength = 20 + payload.length;
        byte[] packet = new byte[20 + tcpLength];
        packet[0] = 0x45;
        writeU16(packet, 2, packet.length);
        writeU16(packet, 4, 0x4242);
        writeU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 6;
        System.arraycopy(CLIENT, 0, packet, 12, 4);
        System.arraycopy(SERVER, 0, packet, 16, 4);
        writeU16(packet, 10, ipChecksum(packet));
        writeU16(packet, 20, clientPort);
        writeU16(packet, 22, 53);
        writeU32(packet, 24, sequence);
        writeU32(packet, 28, acknowledgement);
        packet[32] = (byte) (5 << 4);
        packet[33] = (byte) flags;
        writeU16(packet, 34, 65_535);
        System.arraycopy(payload, 0, packet, 40, payload.length);
        return packet;
    }

    private static int tcpOffset(byte[] packet) {
        return (packet[0] & 0x0f) * 4;
    }

    private static int tcpFlags(byte[] packet) {
        return packet[tcpOffset(packet) + 13] & 0xff;
    }

    private static int tcpSequence(byte[] packet) {
        int offset = tcpOffset(packet);
        return readU32(packet, offset + 4);
    }

    private static int tcpAcknowledgement(byte[] packet) {
        int offset = tcpOffset(packet);
        return readU32(packet, offset + 8);
    }

    private static byte[] tcpPayload(byte[] packet) {
        int start = tcpOffset(packet) + 20;
        byte[] payload = new byte[packet.length - start];
        System.arraycopy(packet, start, payload, 0, payload.length);
        return payload;
    }

    private static byte[] frame(byte[] dns) {
        byte[] framed = new byte[dns.length + 2];
        framed[0] = (byte) ((dns.length >>> 8) & 0xff);
        framed[1] = (byte) (dns.length & 0xff);
        System.arraycopy(dns, 0, framed, 2, dns.length);
        return framed;
    }

    private static byte[] dnsQuery(int id, String name) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, 0x0100);
        writeU16(out, 1);
        writeU16(out, 0);
        writeU16(out, 0);
        writeU16(out, 0);
        for (String label : name.split("\\.")) {
            out.write(label.length());
            for (byte value : label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                out.write(value);
            }
        }
        out.write(0);
        writeU16(out, DnsPacket.TYPE_A);
        writeU16(out, 1);
        return out.toByteArray();
    }

    private static byte[] dnsAnswer(int id, int size) {
        byte[] answer = new byte[Math.max(size, 32)];
        answer[0] = (byte) ((id >>> 8) & 0xff);
        answer[1] = (byte) (id & 0xff);
        answer[2] = (byte) 0x81;
        answer[3] = (byte) 0x80;
        answer[5] = 1;
        answer[11] = 1;
        for (int i = 12; i < answer.length; i++) {
            answer[i] = (byte) (i & 0xff);
        }
        return answer;
    }

    private static int ipChecksum(byte[] header) {
        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum += ((header[i] & 0xff) << 8) | (header[i + 1] & 0xff);
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (~sum) & 0xffff;
    }

    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xff);
        data[offset + 1] = (byte) ((value >>> 16) & 0xff);
        data[offset + 2] = (byte) ((value >>> 8) & 0xff);
        data[offset + 3] = (byte) (value & 0xff);
    }

    private static int readU32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static void writeU16(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}