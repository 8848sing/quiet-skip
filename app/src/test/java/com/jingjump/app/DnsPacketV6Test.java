package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class DnsPacketV6Test {

    private static final byte[] CLIENT_V6 = hex("fd1111112222333300000000000000aa");
    private static final byte[] TUNNEL_V6 = hex("fd111223344000000000000000000001");

    @Test
    public void parsesIpv6UdpQuery() {
        byte[] packet = buildV6UdpQuery();
        DnsPacket.Query query = DnsPacket.parseQuery(packet, 0, packet.length);
        assertNotNull(query);
        assertEquals(6, query.ipVersion);
        assertEquals(17, query.protocol);
        assertEquals("ad.toutiao.com", query.question.name);
        assertEquals(1, query.question.type);
        assertEquals(40001, query.sourcePort);
        assertTrue(Arrays.equals(CLIENT_V6, query.sourceAddress));
    }

    @Test
    public void buildsIpv6ReplyWithValidUdpChecksum() {
        byte[] packet = buildV6UdpQuery();
        DnsPacket.Query query = DnsPacket.parseQuery(packet, 0, packet.length);
        byte[] dns = query.dnsPayload;
        dns = setResponseFlag(dns);
        byte[] reply = DnsPacket.buildUdpReply(query, dns);
        assertNotNull(reply);
        assertEquals(0x60, reply[0] & 0xff);
        assertEquals(17, reply[6] & 0xff);
        // source of the reply is the tunnel address
        assertTrue(Arrays.equals(TUNNEL_V6, Arrays.copyOfRange(reply, 8, 24)));
        assertTrue(Arrays.equals(CLIENT_V6, Arrays.copyOfRange(reply, 24, 40)));
        int transportLength = ((reply[44] & 0xff) << 8) | (reply[45] & 0xff);
        assertEquals(reply.length - 40, transportLength);
        int stored = ((reply[46] & 0xff) << 8) | (reply[47] & 0xff);
        assertTrue(stored != 0);
        // re-summing pseudo-header + datagram must yield all-ones
        assertEquals(0xffff, verifyV6Transport(reply));
    }

    @Test
    public void sinkholeAnswersPerQueryType() {
        byte[] dns = queryBytes(DnsPacket.TYPE_A);
        DnsPacket.Question question = DnsPacket.parseQuestion(dns, 0, dns.length);
        assertNotNull(question);

        byte[] a = DnsPacket.buildBlockedResponse(dns, question, DnsPacket.BLOCK_MODE_SINKHOLE);
        assertNotNull(a);
        assertEquals(0x81, a[2] & 0xff);
        assertEquals(0x80, a[3] & 0xff);
        assertEquals(1, u16(a, 6));
        int cursor = question.end;
        assertEquals(0xC0, a[cursor] & 0xff);
        assertEquals(DnsPacket.TYPE_A, u16(a, cursor + 2));
        assertEquals(4, u16(a, cursor + 10));
        assertTrue(Arrays.equals(DnsPacket.SINKHOLE_A,
                Arrays.copyOfRange(a, cursor + 12, cursor + 16)));

        byte[] dns6 = queryBytes(DnsPacket.TYPE_AAAA);
        DnsPacket.Question q6 = DnsPacket.parseQuestion(dns6, 0, dns6.length);
        byte[] v6 = DnsPacket.buildBlockedResponse(dns6, q6, DnsPacket.BLOCK_MODE_SINKHOLE);
        assertNotNull(v6);
        int cursor6 = q6.end;
        assertEquals(DnsPacket.TYPE_AAAA, u16(v6, cursor6 + 2));
        assertEquals(16, u16(v6, cursor6 + 10));
        assertTrue(Arrays.equals(DnsPacket.SINKHOLE_AAAA,
                Arrays.copyOfRange(v6, cursor6 + 12, cursor6 + 28)));

        byte[] dnsHttps = queryBytes(65);
        DnsPacket.Question qh = DnsPacket.parseQuestion(dnsHttps, 0, dnsHttps.length);
        byte[] empty = DnsPacket.buildBlockedResponse(dnsHttps, qh, DnsPacket.BLOCK_MODE_SINKHOLE);
        assertNotNull(empty);
        assertEquals(0, u16(empty, 6));
        assertEquals(empty.length, qh.end);
    }

    @Test
    public void nxdomainModeKeepsLegacyBehaviour() {
        byte[] dns = queryBytes(DnsPacket.TYPE_A);
        DnsPacket.Question question = DnsPacket.parseQuestion(dns, 0, dns.length);
        byte[] response = DnsPacket.buildBlockedResponse(
                dns, question, DnsPacket.BLOCK_MODE_NXDOMAIN);
        assertNotNull(response);
        assertEquals(3, response[3] & 0x0f);
    }

    @Test
    public void parsesFramedTcpQueryAndBuildsRst() {
        byte[] packet = buildV4TcpQuery();
        DnsPacket.Query query = DnsPacket.parseQuery(packet, 0, packet.length);
        assertNotNull(query);
        assertEquals(6, query.protocol);
        assertEquals("ad.toutiao.com", query.question.name);
        assertEquals(0x1122, query.tcpSeq);
        assertEquals(0x3344, query.tcpAck);

        byte[] syn = buildV4TcpSyn(853);
        DnsPacket.Segment segment = DnsPacket.parseTcpSegment(syn, 0, syn.length);
        assertNotNull(segment);
        assertEquals(853, segment.destinationPort);
        assertTrue(segment.isSyn());

        byte[] rst = DnsPacket.buildTcpRst(segment);
        assertNotNull(rst);
        assertEquals(0x04, rst[33] & 0x0f);
        // TCP checksum must verify over the pseudo header
        assertEquals(0xffff, verifyV4TcpChecksum(rst));
    }

    @Test
    public void scanAnswersFindsCnameTargetsAndTtl() {
        byte[] dns = buildResponseWithCname();
        java.util.List<String> names = new java.util.ArrayList<>();
        long ttl = DnsPacket.scanAnswers(dns, (name, type) -> names.add(name), Long.MAX_VALUE);
        assertEquals(60, ttl);
        assertTrue(names.contains("ad.toutiao.com"));
        assertTrue(names.contains("ads.blocked.test"));

        String hit = DnsPacket.findBlockedTarget(dns, new DnsFilterSnapshot(
                java.util.Collections.singleton("ads.blocked.test"),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                0, 0, false,
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet()
        ));
        assertEquals("ads.blocked.test", hit);

        String miss = DnsPacket.findBlockedTarget(dns, new DnsFilterSnapshot(
                java.util.Collections.singleton("something.else"),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                0, 0, false,
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet()
        ));
        assertNull(miss);
    }

    // ------------------------------------------------------------- builders

    private static byte[] queryBytes(int queryType) {
        ByteArrayOutputStream dns = new ByteArrayOutputStream();
        writeU16(dns, 0x1234);
        writeU16(dns, 0x0100);
        writeU16(dns, 1);
        writeU16(dns, 0);
        writeU16(dns, 0);
        writeU16(dns, 0);
        dns.write(2);
        dns.write('a');
        dns.write('d');
        dns.write(7);
        dns.writeBytes("toutiao".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        dns.write(3);
        dns.writeBytes("com".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        dns.write(0);
        writeU16(dns, queryType);
        writeU16(dns, 1);
        return dns.toByteArray();
    }

    private static byte[] buildV6UdpQuery() {
        byte[] dns = queryBytes(DnsPacket.TYPE_A);
        int transportLength = dns.length + 8;
        byte[] packet = new byte[40 + transportLength];
        packet[0] = 0x60;
        writeU16(packet, 4, transportLength);
        packet[6] = 17;
        packet[7] = 64;
        System.arraycopy(CLIENT_V6, 0, packet, 8, 16);
        System.arraycopy(TUNNEL_V6, 0, packet, 24, 16);
        writeU16(packet, 40, 40001);
        writeU16(packet, 42, 53);
        writeU16(packet, 44, transportLength);
        System.arraycopy(dns, 0, packet, 48, dns.length);
        return packet;
    }

    private static byte[] buildV4TcpQuery() {
        byte[] dns = queryBytes(DnsPacket.TYPE_A);
        byte[] packet = new byte[20 + 20 + 2 + dns.length];
        packet[0] = 0x45;
        writeU16(packet, 2, packet.length);
        writeU16(packet, 4, 0x55);
        writeU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 6;
        packet[12] = 10; packet[13] = 0; packet[14] = 1; packet[15] = 2;
        packet[16] = 10; packet[17] = 111; packet[18] = (byte) 222; packet[19] = 1;
        writeU16(packet, 20, 40002);
        writeU16(packet, 22, 53);
        writeU32(packet, 24, 0x1122);
        writeU32(packet, 28, 0x3344);
        packet[32] = (byte) (5 << 4);
        packet[33] = 0x18;
        writeU16(packet, 34, 65_535);
        writeU16(packet, 40, dns.length);
        System.arraycopy(dns, 0, packet, 42, dns.length);
        // fix the ip checksum
        writeU16(packet, 10, ipChecksum(packet, 0, 20));
        return packet;
    }

    private static byte[] buildV4TcpSyn(int destinationPort) {
        byte[] packet = new byte[20 + 20];
        packet[0] = 0x45;
        writeU16(packet, 2, packet.length);
        writeU16(packet, 4, 0x77);
        writeU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 6;
        packet[12] = 10; packet[13] = 0; packet[14] = 1; packet[15] = 2;
        packet[16] = 8; packet[17] = 8; packet[18] = 8; packet[19] = 8;
        writeU16(packet, 20, 40003);
        writeU16(packet, 22, destinationPort);
        writeU32(packet, 24, 0x1234);
        writeU32(packet, 28, 0);
        packet[32] = (byte) (5 << 4);
        packet[33] = 0x02;
        writeU16(packet, 34, 65_535);
        writeU16(packet, 10, ipChecksum(packet, 0, 20));
        writeU16(packet, 36, tcpChecksum(packet, 20, 20, 0, 0));
        return packet;
    }

    private static byte[] buildResponseWithCname() {
        byte[] query = queryBytes(DnsPacket.TYPE_A); // header 12 + name 16 + qinfo 4
        String target = "ads.blocked.test";
        int rdataLength = 1 + 3 + 1 + 7 + 1 + 4 + 1; // 3 labels + root
        byte[] message = new byte[query.length + 12 + rdataLength];
        System.arraycopy(query, 0, message, 0, query.length);
        message[2] = (byte) (message[2] | 0x80); // QR
        message[7] = 1;                          // ANCOUNT = 1

        int cursor = query.length;
        message[cursor++] = (byte) 0xC0;         // name pointer to offset 12
        message[cursor++] = 0x0C;
        message[cursor++] = 0;                   // type CNAME
        message[cursor++] = 5;
        message[cursor++] = 0;                   // class IN
        message[cursor++] = 1;
        message[cursor++] = 0;                   // ttl 60
        message[cursor++] = 0;
        message[cursor++] = 0;
        message[cursor++] = 60;
        message[cursor++] = (byte) ((rdataLength >>> 8) & 0xff);
        message[cursor++] = (byte) (rdataLength & 0xff);
        for (String label : target.split("\\.")) {
            message[cursor++] = (byte) label.length();
            for (byte b : label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                message[cursor++] = b;
            }
        }
        message[cursor] = 0;
        return message;
    }

    // -------------------------------------------------------------- helpers

    private static byte[] setResponseFlag(byte[] dns) {
        byte[] copy = dns.clone();
        copy[2] = (byte) (copy[2] | 0x80);
        return copy;
    }

    private static int verifyV6Transport(byte[] packet) {
        // pseudo header: src(16) dst(16) len(4) next(4=3zero+proto)
        int sum = 0;
        for (int off : new int[]{8, 24}) {
            for (int i = 0; i < 16; i += 2) {
                sum += ((packet[off + i] & 0xff) << 8) | (packet[off + i + 1] & 0xff);
            }
        }
        int transportLength = ((packet[4] & 0xff) << 8) | (packet[5] & 0xff);
        sum += transportLength;
        sum += packet[6] & 0xff;
        for (int i = 40; i < packet.length; i += 2) {
            int high = packet[i] & 0xff;
            int low = i + 1 < packet.length ? packet[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return sum & 0xffff;
    }

    private static int verifyV4TcpChecksum(byte[] packet) {
        int headerLength = (packet[0] & 0x0f) * 4;
        int tcpLength = packet.length - headerLength;
        int sum = 0;
        for (int i = 0; i < 4; i += 2) {
            sum += ((packet[12 + i] & 0xff) << 8) | (packet[13 + i] & 0xff);
            sum += ((packet[16 + i] & 0xff) << 8) | (packet[17 + i] & 0xff);
        }
        sum += 6;
        sum += tcpLength;
        for (int i = headerLength; i < packet.length; i += 2) {
            int high = packet[i] & 0xff;
            int low = i + 1 < packet.length ? packet[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return sum & 0xffff;
    }

    private static int tcpChecksum(byte[] packet, int tcpOffset, int tcpLength,
                                   int seq, int ack) {
        int sum = 0;
        for (int i = 0; i < 4; i += 2) {
            sum += ((packet[12 + i] & 0xff) << 8) | (packet[13 + i] & 0xff);
            sum += ((packet[16 + i] & 0xff) << 8) | (packet[17 + i] & 0xff);
        }
        sum += 6;
        sum += tcpLength;
        for (int i = tcpOffset; i < tcpOffset + tcpLength; i += 2) {
            int high = packet[i] & 0xff;
            int low = i + 1 < tcpOffset + tcpLength ? packet[i + 1] & 0xff : 0;
            sum += (high << 8) | low;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        int result = (~sum) & 0xffff;
        return result == 0 ? 0xffff : result;
    }

    private static int ipChecksum(byte[] packet, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) {
            sum += ((packet[offset + i] & 0xff) << 8);
            if (i + 1 < length) {
                sum += packet[offset + i + 1] & 0xff;
            }
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (~sum) & 0xffff;
    }

    private static int DnsPacketU32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeU16(byte[] out, int offset, int value) {
        out[offset] = (byte) ((value >>> 8) & 0xff);
        out[offset + 1] = (byte) (value & 0xff);
    }

    private static void writeU32(byte[] out, int offset, int value) {
        out[offset] = (byte) ((value >>> 24) & 0xff);
        out[offset + 1] = (byte) ((value >>> 16) & 0xff);
        out[offset + 2] = (byte) ((value >>> 8) & 0xff);
        out[offset + 3] = (byte) (value & 0xff);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(
                    value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
