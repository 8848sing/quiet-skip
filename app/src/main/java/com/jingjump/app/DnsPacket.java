package com.jingjump.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

final class DnsPacket {
    static final int RCODE_NOERROR = 0;
    static final int RCODE_SERVFAIL = 2;
    static final int RCODE_NXDOMAIN = 3;

    static final int BLOCK_MODE_NXDOMAIN = 0;
    static final int BLOCK_MODE_SINKHOLE = 1;

    static final int TYPE_A = 1;
    static final int TYPE_CNAME = 5;
    static final int TYPE_AAAA = 28;

    static final byte[] SINKHOLE_A = {0, 0, 0, 0};
    static final byte[] SINKHOLE_AAAA = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
    };

    private static final int SINKHOLE_TTL = 300;

    private DnsPacket() {
    }

    // ------------------------------------------------------------------ parse

    static Query parseQuery(byte[] packet, int base, int available) {
        if (packet == null || base < 0 || base + available > packet.length) {
            return null;
        }
        int version = (packet[base] >>> 4) & 0x0f;
        if (version == 4) {
            return parseIpv4Query(packet, base, available);
        }
        if (version == 6) {
            return parseIpv6Query(packet, base, available);
        }
        return null;
    }

    private static Query parseIpv4Query(byte[] packet, int base, int available) {
        if (available < 28) {
            return null;
        }
        int headerLength = (packet[base] & 0x0f) * 4;
        int totalLength = u16(packet, base + 2);
        if (headerLength < 20 || totalLength < headerLength + 8 || totalLength > available) {
            return null;
        }
        int protocol = packet[base + 9] & 0xff;
        int transportOffset = base + headerLength;
        if (protocol == 17) {
            int destinationPort = u16(packet, transportOffset + 2);
            int transportLength = u16(packet, transportOffset + 4);
            if (destinationPort != 53 || transportLength < 20
                    || transportLength > totalLength - headerLength) {
                return null;
            }
            int dnsOffset = transportOffset + 8;
            int dnsLength = transportLength - 8;
            return buildQuery(packet, base, 4, protocol, dnsOffset, dnsLength, null);
        }
        if (protocol == 6) {
            int destinationPort = u16(packet, transportOffset + 2);
            if (destinationPort != 53) {
                return null;
            }
            int tcpHeaderLength = ((packet[transportOffset + 12] >>> 4) & 0x0f) * 4;
            int dnsOffset = transportOffset + tcpHeaderLength + 2;
            int dnsLength = totalLength - (dnsOffset - base);
            if (tcpHeaderLength < 20 || dnsLength < 12) {
                return null;
            }
            int framedLength = u16(packet, transportOffset + tcpHeaderLength);
            if (framedLength > dnsLength) {
                return null;
            }
            return buildQuery(packet, base, 4, protocol, dnsOffset, framedLength,
                    new int[]{
                            u32(packet, transportOffset + 4),
                            u32(packet, transportOffset + 8),
                            totalLength - headerLength - tcpHeaderLength - 2
                    });
        }
        return null;
    }

    private static Query parseIpv6Query(byte[] packet, int base, int available) {
        if (available < 48) {
            return null;
        }
        int payloadLength = u16(packet, base + 4);
        int protocol = packet[base + 6] & 0xff;
        if (payloadLength < 8 || 40 + payloadLength > available) {
            return null;
        }
        int transportOffset = base + 40;
        if (protocol == 17) {
            int destinationPort = u16(packet, transportOffset + 2);
            int transportLength = u16(packet, transportOffset + 4);
            if (destinationPort != 53 || transportLength < 20
                    || transportLength > payloadLength) {
                return null;
            }
            int dnsOffset = transportOffset + 8;
            int dnsLength = transportLength - 8;
            return buildQuery(packet, base, 6, protocol, dnsOffset, dnsLength, null);
        }
        if (protocol == 6) {
            int destinationPort = u16(packet, transportOffset + 2);
            if (destinationPort != 53) {
                return null;
            }
            int tcpHeaderLength = ((packet[transportOffset + 12] >>> 4) & 0x0f) * 4;
            int dnsOffset = transportOffset + tcpHeaderLength + 2;
            int dnsLength = 40 + payloadLength - (dnsOffset - base);
            if (tcpHeaderLength < 20 || dnsLength < 12) {
                return null;
            }
            int framedLength = u16(packet, transportOffset + tcpHeaderLength);
            if (framedLength > dnsLength) {
                return null;
            }
            return buildQuery(packet, base, 6, protocol, dnsOffset, framedLength,
                    new int[]{
                            u32(packet, transportOffset + 4),
                            u32(packet, transportOffset + 8),
                            payloadLength - tcpHeaderLength - 2
                    });
        }
        return null;
    }

    private static Query buildQuery(
            byte[] packet,
            int base,
            int ipVersion,
            int protocol,
            int dnsOffset,
            int dnsLength,
            int[] tcpMeta
    ) {
        Question question = parseQuestion(packet, dnsOffset, dnsLength);
        if (question == null) {
            return null;
        }
        int addressLength = ipVersion == 4 ? 4 : 16;
        int sourceOffset = ipVersion == 4 ? base + 12 : base + 8;
        int destinationOffset = ipVersion == 4 ? base + 16 : base + 24;
        int transportOffset = ipVersion == 4
                ? base + ((packet[base] & 0x0f) * 4)
                : base + 40;
        return new Query(
                ipVersion,
                protocol,
                u16(packet, transportOffset),
                Arrays.copyOfRange(packet, sourceOffset, sourceOffset + addressLength),
                Arrays.copyOfRange(packet, destinationOffset, destinationOffset + addressLength),
                ipVersion == 4 ? u16(packet, base + 4) : 0,
                tcpMeta == null ? 0 : tcpMeta[0],
                tcpMeta == null ? 0 : tcpMeta[1],
                tcpMeta == null ? 0 : tcpMeta[2],
                Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength),
                question
        );
    }

    static Question parseQuestion(byte[] dns, int offset, int length) {
        if (dns == null || offset < 0 || length < 12 || offset + length > dns.length) {
            return null;
        }
        int queryCount = u16(dns, offset + 4);
        int flags = u16(dns, offset + 2);
        if (queryCount != 1 || (flags & 0x8000) != 0) {
            return null;
        }
        int cursor = offset + 12;
        int end = offset + length;
        StringBuilder name = new StringBuilder(64);
        int labels = 0;
        while (cursor < end && labels < 32) {
            int labelLength = dns[cursor] & 0xff;
            cursor++;
            if (labelLength == 0) {
                int questionEnd = cursor + 4;
                if (questionEnd > end || name.length() == 0) {
                    return null;
                }
                String domain = name.toString().toLowerCase(Locale.ROOT);
                int type = u16(dns, cursor);
                return new Question(domain, type, questionEnd - offset);
            }
            if (labelLength > 63 || cursor + labelLength > end) {
                return null;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(dns, cursor, labelLength, StandardCharsets.US_ASCII));
            cursor += labelLength;
            labels++;
        }
        return null;
    }

    /**
     * TCP segment probe for bypass interception (DoT on port 853 and similar).
     * Only IPv4/IPv6 TCP is inspected; anything else returns null.
     */
    static Segment parseTcpSegment(byte[] packet, int base, int available) {
        if (packet == null || base < 0 || base + available > packet.length) {
            return null;
        }
        int version = (packet[base] >>> 4) & 0x0f;
        int headerLength;
        int totalLength;
        int protocol;
        if (version == 4) {
            headerLength = (packet[base] & 0x0f) * 4;
            totalLength = u16(packet, base + 2);
            protocol = packet[base + 9] & 0xff;
            if (headerLength < 20 || totalLength > available) {
                return null;
            }
        } else if (version == 6) {
            headerLength = 40;
            protocol = packet[base + 6] & 0xff;
            totalLength = 40 + u16(packet, base + 4);
            if (totalLength > available) {
                return null;
            }
        } else {
            return null;
        }
        if (protocol != 6 || totalLength < headerLength + 20) {
            return null;
        }
        int transportOffset = base + headerLength;
        int tcpHeaderLength = ((packet[transportOffset + 12] >>> 4) & 0x0f) * 4;
        if (tcpHeaderLength < 20 || totalLength - headerLength < tcpHeaderLength) {
            return null;
        }
        int addressLength = version == 4 ? 4 : 16;
        int sourceOffset = version == 4 ? base + 12 : base + 8;
        int destinationOffset = version == 4 ? base + 16 : base + 24;
        return new Segment(
                version,
                u16(packet, transportOffset),
                u16(packet, transportOffset + 2),
                Arrays.copyOfRange(packet, sourceOffset, sourceOffset + addressLength),
                Arrays.copyOfRange(packet, destinationOffset, destinationOffset + addressLength),
                version == 4 ? u16(packet, base + 4) : 0,
                u32(packet, transportOffset + 4),
                u32(packet, transportOffset + 8),
                packet[transportOffset + 13] & 0xff,
                totalLength - headerLength - tcpHeaderLength,
                transportOffset + tcpHeaderLength
        );
    }

    // ----------------------------------------------------------------- build

    static byte[] buildError(byte[] dns, int offset, Question question, int rcode) {
        if (dns == null || question == null) {
            return null;
        }
        int end = question.end;
        if (offset < 0 || end <= offset + 12 || end > dns.length) {
            return null;
        }
        byte[] response = Arrays.copyOfRange(dns, offset, end);
        boolean recursionDesired = (response[2] & 0x01) != 0;
        response[2] = (byte) (0x80 | (recursionDesired ? 0x01 : 0));
        response[3] = (byte) (0x80 | (rcode & 0x0f));
        response[6] = 0;
        response[7] = 0;
        response[8] = 0;
        response[9] = 0;
        response[10] = 0;
        response[11] = 0;
        return response;
    }

    /**
     * Blocking response. NXDOMAIN keeps the historical behaviour; sinkhole mode
     * answers with a non-routable address for A/AAAA (Pi-hole / AdGuard Home
     * style) and an empty NOERROR answer for every other query type.
     */
    static byte[] buildBlockedResponse(byte[] dns, Question question, int mode) {
        if (mode == BLOCK_MODE_NXDOMAIN) {
            return buildError(dns, 0, question, RCODE_NXDOMAIN);
        }
        if (dns == null || question == null) {
            return null;
        }
        int end = question.end;
        if (end <= 12 || end > dns.length) {
            return null;
        }
        byte[] rdata = question.type == TYPE_A
                ? SINKHOLE_A
                : question.type == TYPE_AAAA ? SINKHOLE_AAAA : null;
        int answerLength = rdata == null ? 0 : 12 + rdata.length;
        byte[] response = new byte[12 + (end - 12) + answerLength];
        response[0] = dns[0];
        response[1] = dns[1];
        boolean recursionDesired = (dns[2] & 0x01) != 0;
        response[2] = (byte) (0x80 | (recursionDesired ? 0x01 : 0));
        response[3] = (byte) 0x80;
        putU16(response, 4, 1);
        putU16(response, 6, rdata == null ? 0 : 1);
        System.arraycopy(dns, 12, response, 12, end - 12);
        if (rdata != null) {
            int cursor = end;
            putU16(response, cursor, 0xC00C);
            cursor += 2;
            putU16(response, cursor, question.type);
            cursor += 2;
            putU16(response, cursor, 1);
            cursor += 2;
            putU32(response, cursor, SINKHOLE_TTL);
            cursor += 4;
            putU16(response, cursor, rdata.length);
            cursor += 2;
            System.arraycopy(rdata, 0, response, cursor, rdata.length);
        }
        return response;
    }

    static byte[] buildReply(Query query, byte[] dnsPayload) {
        if (query == null || dnsPayload == null) {
            return null;
        }
        return buildUdpReply(query, dnsPayload);
    }

    static byte[] buildUdpReply(Query query, byte[] dnsPayload) {
        if (query == null || dnsPayload == null || dnsPayload.length > 1472) {
            return null;
        }
        if (query.ipVersion == 6) {
            return buildIpv6UdpReply(query, dnsPayload);
        }
        int length = 28 + dnsPayload.length;
        byte[] packet = new byte[length];
        packet[0] = 0x45;
        putU16(packet, 2, length);
        putU16(packet, 4, query.ipId);
        putU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 17;
        System.arraycopy(query.destinationAddress, 0, packet, 12, 4);
        System.arraycopy(query.sourceAddress, 0, packet, 16, 4);
        putU16(packet, 10, checksum(packet, 0, 20));

        putU16(packet, 20, 53);
        putU16(packet, 22, query.sourcePort);
        putU16(packet, 24, dnsPayload.length + 8);
        putU16(packet, 26, 0);
        System.arraycopy(dnsPayload, 0, packet, 28, dnsPayload.length);
        return packet;
    }

    private static byte[] buildIpv6UdpReply(Query query, byte[] dnsPayload) {
        int transportLength = 8 + dnsPayload.length;
        byte[] packet = new byte[40 + transportLength];
        packet[0] = 0x60;
        putU16(packet, 4, transportLength);
        packet[6] = 17;
        packet[7] = 64;
        System.arraycopy(query.destinationAddress, 0, packet, 8, 16);
        System.arraycopy(query.sourceAddress, 0, packet, 24, 16);

        putU16(packet, 40, 53);
        putU16(packet, 42, query.sourcePort);
        putU16(packet, 44, transportLength);
        System.arraycopy(dnsPayload, 0, packet, 48, dnsPayload.length);
        putU16(packet, 46, transportChecksum(
                query.destinationAddress,
                query.sourceAddress,
                transportLength,
                17,
                packet,
                40,
                transportLength
        ));
        return packet;
    }

    /**
     * Builds one TCP segment carrying {@code payload}. 1.9.0 added this so the
     * TCP DNS relay can answer with real, correctly sequenced segments. It
     * replaces the old {@code buildTcpReply()} helper, which used the client's
     * sequence number as its own and could never have produced a valid
     * connection (TCP queries are relayed now, not answered packet by packet).
     */
    static byte[] buildTcpPacket(
            int ipVersion,
            byte[] sourceAddress,
            byte[] destinationAddress,
            int sourcePort,
            int destinationPort,
            int sequence,
            int acknowledgement,
            int flags,
            byte[] payload
    ) {
        int payloadLength = payload == null ? 0 : payload.length;
        byte[] tcp = new byte[20 + payloadLength];
        putU16(tcp, 0, sourcePort);
        putU16(tcp, 2, destinationPort);
        putU32(tcp, 4, sequence);
        putU32(tcp, 8, acknowledgement);
        tcp[12] = (byte) (5 << 4);
        tcp[13] = (byte) flags;
        putU16(tcp, 14, 65_535);
        putU16(tcp, 16, 0);
        putU16(tcp, 18, 0);
        if (payloadLength > 0) {
            System.arraycopy(payload, 0, tcp, 20, payloadLength);
        }
        return wrapTcp(ipVersion, 0, sourceAddress, destinationAddress, tcp);
    }

    static final int TCP_FLAG_FIN = 0x01;
    static final int TCP_FLAG_SYN = 0x02;
    static final int TCP_FLAG_RST = 0x04;
    static final int TCP_FLAG_PSH = 0x08;
    static final int TCP_FLAG_ACK = 0x10;

    /** True when the segment carries no acknowledgement number of its own. */
    static boolean tcpHasPayload(Segment segment) {
        return segment != null && segment.payloadLength > 0;
    }

    /**
     * Sets the TC bit and keeps only the header and the question, which is the
     * answer a resolver gives when the response does not fit the UDP path; the
     * client then retries over TCP. Before 1.9.0 an oversized upstream answer
     * became SERVFAIL, which clients treat as a broken server rather than as a
     * "retry over TCP" hint.
     */
    static byte[] buildTruncatedResponse(byte[] dns, Question question) {
        if (dns == null || question == null || question.end <= 12
                || question.end > dns.length) {
            return null;
        }
        byte[] response = Arrays.copyOfRange(dns, 0, question.end);
        boolean recursionDesired = (dns[2] & 0x01) != 0;
        response[2] = (byte) (0x80 | (recursionDesired ? 0x01 : 0) | 0x02);
        response[3] = (byte) 0x80;
        putU16(response, 4, 1);
        putU16(response, 6, 0);
        putU16(response, 8, 0);
        putU16(response, 10, 0);
        return response;
    }

    /**
     * Subtracts the time a cached answer has been stored from every record TTL,
     * so the client never caches a record for longer than the upstream allowed.
     */
    static void ageTtls(byte[] dns, long elapsedSeconds) {
        if (dns == null || dns.length < 12 || elapsedSeconds <= 0) {
            return;
        }
        int queryCount = u16(dns, 4);
        int total = u16(dns, 6) + u16(dns, 8) + u16(dns, 10);
        if (queryCount != 1 || total <= 0) {
            return;
        }
        int cursor = skipName(dns, 12);
        if (cursor < 0) {
            return;
        }
        cursor += 4;
        for (int i = 0; i < total; i++) {
            if (cursor < 0) {
                return;
            }
            int nameEnd = skipName(dns, cursor);
            if (nameEnd < 0 || nameEnd + 10 > dns.length) {
                return;
            }
            long ttl = u32(dns, nameEnd + 4) & 0xffffffffL;
            long remaining = ttl - elapsedSeconds;
            putU32(dns, nameEnd + 4, (int) (remaining < 0 ? 0 : remaining));
            int rdLength = u16(dns, nameEnd + 8);
            cursor = nameEnd + 10 + rdLength;
            if (cursor > dns.length) {
                return;
            }
        }
    }

    static byte[] buildTcpRst(Segment segment) {
        if (segment == null) {
            return null;
        }
        byte[] tcp = new byte[20];
        putU16(tcp, 0, segment.destinationPort);
        putU16(tcp, 2, segment.sourcePort);
        boolean acknowledged = (segment.flags & 0x10) != 0;
        putU32(tcp, 4, acknowledged ? segment.acknowledgement : 0);
        putU32(tcp, 8, segment.sequence + segment.payloadLength
                + ((segment.flags & 0x02) != 0 ? 1 : 0));
        tcp[12] = (byte) (5 << 4);
        tcp[13] = 0x04;
        putU16(tcp, 14, 0);
        return wrapTcp(segment.ipVersion, segment.ipId,
                segment.destinationAddress, segment.sourceAddress, tcp);
    }

    private static byte[] wrapTcp(
            int ipVersion,
            int ipId,
            byte[] sourceAddress,
            byte[] destinationAddress,
            byte[] tcp
    ) {
        if (tcp.length > 1440) {
            return null;
        }
        if (ipVersion == 6) {
            byte[] packet = new byte[40 + tcp.length];
            packet[0] = 0x60;
            putU16(packet, 4, tcp.length);
            packet[6] = 6;
            packet[7] = 64;
            System.arraycopy(sourceAddress, 0, packet, 8, 16);
            System.arraycopy(destinationAddress, 0, packet, 24, 16);
            putU16(tcp, 16, transportChecksum(
                    sourceAddress, destinationAddress, tcp.length, 6, tcp, 0, tcp.length));
            System.arraycopy(tcp, 0, packet, 40, tcp.length);
            return packet;
        }
        byte[] packet = new byte[20 + tcp.length];
        packet[0] = 0x45;
        putU16(packet, 2, packet.length);
        putU16(packet, 4, ipId);
        putU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 6;
        System.arraycopy(sourceAddress, 0, packet, 12, 4);
        System.arraycopy(destinationAddress, 0, packet, 16, 4);
        putU16(packet, 10, checksum(packet, 0, 20));
        putU16(tcp, 16, transportChecksum(
                sourceAddress, destinationAddress, tcp.length, 6, tcp, 0, tcp.length));
        System.arraycopy(tcp, 0, packet, 20, tcp.length);
        return packet;
    }

    // ------------------------------------------------------------ dns bodies

    /** Reports every answer record name and every CNAME chain end. */
    interface AnswerVisitor {
        void visit(String name, int type);
    }

    /**
     * Walks the answer section. Returns the smallest answer TTL (starting from
     * {@code initialMinimum}), or -1 when the message cannot be parsed, is
     * truncated, or carries no answers.
     */
    static long scanAnswers(byte[] dns, AnswerVisitor visitor, long initialMinimum) {
        if (dns == null || dns.length < 12 || (dns[2] & 0x80) == 0) {
            return -1;
        }
        int flags = u16(dns, 2);
        int queryCount = u16(dns, 4);
        int answerCount = u16(dns, 6);
        if ((flags & 0x0200) != 0 || queryCount != 1 || answerCount == 0) {
            return -1;
        }
        int cursor = skipName(dns, 12);
        if (cursor < 0) {
            return -1;
        }
        cursor += 4;
        if (cursor > dns.length) {
            return -1;
        }
        long minimum = initialMinimum;
        for (int i = 0; i < answerCount; i++) {
            NameRef name = readName(dns, cursor);
            if (name == null || name.end + 10 > dns.length) {
                return -1;
            }
            cursor = name.end;
            int type = u16(dns, cursor);
            long ttl = u32(dns, cursor + 4) & 0xffffffffL;
            int rdLength = u16(dns, cursor + 8);
            int rdataOffset = cursor + 10;
            if (rdataOffset + rdLength > dns.length) {
                return -1;
            }
            if (visitor != null) {
                visitor.visit(name.value, type);
                if (type == TYPE_CNAME) {
                    NameRef target = readName(dns, rdataOffset);
                    if (target != null) {
                        visitor.visit(target.value, TYPE_CNAME);
                    }
                }
            }
            if (ttl < minimum) {
                minimum = ttl;
            }
            cursor = rdataOffset + rdLength;
        }
        return minimum;
    }

    /** Returns the blocked domain if any answer name or CNAME target matches. */
    static String findBlockedTarget(byte[] dns, DnsFilterSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        String[] hit = new String[1];
        scanAnswers(dns, (name, type) -> {
            if (hit[0] == null && snapshot.blockSource(name) != DnsFilterSnapshot.SOURCE_NONE) {
                hit[0] = name;
            }
        }, Long.MAX_VALUE);
        return hit[0];
    }

    /**
     * TTL (seconds) for caching an upstream response, or -1 when it must not
     * be cached. Positive answers use the smallest answer TTL; NODATA and
     * NXDOMAIN use the smallest authority SOA TTL (RFC 2308 negative caching)
     * with a conservative cap, so ad SDKs that keep querying nonexistent
     * names stop waking the radio.
     */
    static long cacheTtlSeconds(byte[] dns) {
        if (dns == null || dns.length < 12 || (dns[2] & 0x80) == 0) {
            return -1;
        }
        int flags = u16(dns, 2);
        if ((flags & 0x0200) != 0) {
            return -1; // truncated: the client is expected to retry over TCP
        }
        int rcode = flags & 0x0f;
        int queryCount = u16(dns, 4);
        int answerCount = u16(dns, 6);
        int authorityCount = u16(dns, 8);
        if (queryCount != 1 || (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN)) {
            return -1; // SERVFAIL/REFUSED are transient by definition
        }
        int cursor = skipName(dns, 12);
        if (cursor < 0) {
            return -1;
        }
        cursor += 4;
        if (cursor > dns.length) {
            return -1;
        }
        long minimum = Long.MAX_VALUE;
        int total = answerCount + authorityCount;
        for (int i = 0; i < total; i++) {
            NameRef name = readName(dns, cursor);
            if (name == null || name.end + 10 > dns.length) {
                return -1;
            }
            cursor = name.end;
            int type = u16(dns, cursor);
            long ttl = u32(dns, cursor + 4) & 0xffffffffL;
            int rdLength = u16(dns, cursor + 8);
            int next = cursor + 10 + rdLength;
            if (next > dns.length) {
                return -1;
            }
            boolean inAnswer = i < answerCount;
            boolean negativeSoa = !inAnswer && answerCount == 0 && type == 6;
            if (inAnswer || negativeSoa) {
                if (ttl < minimum) {
                    minimum = ttl;
                }
            }
            cursor = next;
        }
        if (minimum == Long.MAX_VALUE) {
            return answerCount == 0 ? 60 : -1;
        }
        if (minimum < 15) {
            minimum = 15;
        }
        return Math.min(minimum, answerCount == 0 ? 300 : 600);
    }

    private static final class NameRef {
        final String value;
        final int end;

        NameRef(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static NameRef readName(byte[] dns, int cursor) {
        if (dns == null || cursor < 0 || cursor >= dns.length) {
            return null;
        }
        StringBuilder name = new StringBuilder(64);
        int end = -1;
        int jumps = 0;
        int labels = 0;
        while (cursor < dns.length && labels < 64) {
            int length = dns[cursor] & 0xff;
            if (length == 0) {
                if (name.length() == 0) {
                    return null;
                }
                return new NameRef(
                        name.toString().toLowerCase(Locale.ROOT),
                        end >= 0 ? end : cursor + 1
                );
            }
            if ((length & 0xc0) == 0xc0) {
                if (cursor + 1 >= dns.length || ++jumps > 8) {
                    return null;
                }
                if (end < 0) {
                    end = cursor + 2;
                }
                int pointer = ((length & 0x3f) << 8) | (dns[cursor + 1] & 0xff);
                if (pointer < 12 || pointer >= dns.length) {
                    return null;
                }
                cursor = pointer;
                continue;
            }
            if ((length & 0xc0) != 0 || cursor + 1 + length > dns.length) {
                return null;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(dns, cursor + 1, length, StandardCharsets.US_ASCII));
            cursor += 1 + length;
            labels++;
        }
        return null;
    }

    private static int skipName(byte[] dns, int cursor) {
        if (dns == null || cursor < 0 || cursor >= dns.length) {
            return -1;
        }
        while (cursor < dns.length) {
            int length = dns[cursor] & 0xff;
            if (length == 0) {
                return cursor + 1;
            }
            if ((length & 0xc0) == 0xc0) {
                return cursor + 2;
            }
            if ((length & 0xc0) != 0) {
                return -1;
            }
            cursor += 1 + length;
        }
        return -1;
    }

    // --------------------------------------------------------------- helpers

    private static int transportChecksum(
            byte[] sourceAddress,
            byte[] destinationAddress,
            int transportLength,
            int protocol,
            byte[] transport,
            int offset,
            int length
    ) {
        int sum = 0;
        int addressLength = sourceAddress.length;
        for (int i = 0; i < addressLength; i += 2) {
            sum += ((sourceAddress[i] & 0xff) << 8) | (sourceAddress[i + 1] & 0xff);
            sum += ((destinationAddress[i] & 0xff) << 8) | (destinationAddress[i + 1] & 0xff);
        }
        if (addressLength == 4) {
            sum += protocol;
            sum += transportLength;
        } else {
            sum += transportLength;
            sum += protocol;
        }
        for (int i = 0; i < length; i += 2) {
            int high = transport[offset + i] & 0xff;
            int low = i + 1 < length ? transport[offset + i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        int result = (~sum) & 0xffff;
        return result == 0 ? 0xffff : result;
    }

    private static int checksum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) {
            sum += ((data[offset + i] & 0xff) << 8);
            if (i + 1 < length) {
                sum += data[offset + i + 1] & 0xff;
            }
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (~sum) & 0xffff;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static int u32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    private static void putU32(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xff);
        data[offset + 1] = (byte) ((value >>> 16) & 0xff);
        data[offset + 2] = (byte) ((value >>> 8) & 0xff);
        data[offset + 3] = (byte) (value & 0xff);
    }

    static final class Question {
        final String name;
        final int type;
        final int end;

        Question(String name, int type, int end) {
            this.name = name;
            this.type = type;
            this.end = end;
        }
    }

    static final class Segment {
        final int ipVersion;
        final int sourcePort;
        final int destinationPort;
        final byte[] sourceAddress;
        final byte[] destinationAddress;
        final int ipId;
        final int sequence;
        final int acknowledgement;
        final int flags;
        final int payloadLength;
        /** Absolute offset of the payload inside the packet array. */
        final int payloadOffset;

        Segment(
                int ipVersion,
                int sourcePort,
                int destinationPort,
                byte[] sourceAddress,
                byte[] destinationAddress,
                int ipId,
                int sequence,
                int acknowledgement,
                int flags,
                int payloadLength,
                int payloadOffset
        ) {
            this.ipVersion = ipVersion;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.ipId = ipId;
            this.sequence = sequence;
            this.acknowledgement = acknowledgement;
            this.flags = flags;
            this.payloadLength = payloadLength;
            this.payloadOffset = payloadOffset;
        }

        boolean isSyn() {
            return (flags & 0x02) != 0 && (flags & 0x10) == 0;
        }

        boolean hasFlag(int flag) {
            return (flags & flag) != 0;
        }
    }

    static final class Query {
        final int ipVersion;
        final int protocol;
        final int sourcePort;
        final byte[] sourceAddress;
        final byte[] destinationAddress;
        final int ipId;
        final int tcpSeq;
        final int tcpAck;
        final int tcpPayloadLength;
        final byte[] dnsPayload;
        final Question question;

        Query(
                int ipVersion,
                int protocol,
                int sourcePort,
                byte[] sourceAddress,
                byte[] destinationAddress,
                int ipId,
                int tcpSeq,
                int tcpAck,
                int tcpPayloadLength,
                byte[] dnsPayload,
                Question question
        ) {
            this.ipVersion = ipVersion;
            this.protocol = protocol;
            this.sourcePort = sourcePort;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.ipId = ipId;
            this.tcpSeq = tcpSeq;
            this.tcpAck = tcpAck;
            this.tcpPayloadLength = tcpPayloadLength;
            this.dnsPayload = dnsPayload;
            this.question = question;
        }

        Query(
                int sourcePort,
                byte[] sourceAddress,
                byte[] destinationAddress,
                int ipId,
                byte[] dnsPayload,
                Question question
        ) {
            this(4, 17, sourcePort, sourceAddress, destinationAddress, ipId,
                    0, 0, 0, dnsPayload, question);
        }
    }
}
