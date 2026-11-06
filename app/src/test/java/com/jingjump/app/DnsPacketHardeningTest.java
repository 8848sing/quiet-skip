package com.jingjump.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * Covers the 1.9.0 DNS packet additions: real TCP segment construction,
 * truncation instead of SERVFAIL for oversized answers, and TTL ageing of
 * cached answers.
 */
public final class DnsPacketHardeningTest {

    @Test
    public void buildTcpPacketProducesValidChecksums() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] packet = DnsPacket.buildTcpPacket(
                4,
                new byte[]{(byte) 223, 5, 5, 5},
                new byte[]{10, 0, 1, 2},
                53,
                41_000,
                0x1122_3344,
                0x5566_7788,
                DnsPacket.TCP_FLAG_ACK,
                payload
        );
        assertNotNull(packet);
        assertEquals(0x45, packet[0] & 0xff);
        int tcpOffset = 20;
        assertEquals(53, u16(packet, tcpOffset));
        assertEquals(41_000, u16(packet, tcpOffset + 2));
        assertEquals(0x1122_3344, readU32(packet, tcpOffset + 4));
        assertEquals(0x5566_7788, readU32(packet, tcpOffset + 8));
        assertEquals(DnsPacket.TCP_FLAG_ACK, packet[tcpOffset + 13] & 0xff);
        assertArrayEquals(payload,
                java.util.Arrays.copyOfRange(packet, tcpOffset + 20, packet.length));
        assertEquals(0xffff, verifyV4TcpChecksum(packet));
    }

    @Test
    public void truncatedResponseKeepsQuestionAndSetsTc() {
        byte[] query = query("ads.example.com", DnsPacket.TYPE_A);
        DnsPacket.Question question = DnsPacket.parseQuestion(query, 0, query.length);
        assertNotNull(question);
        byte[] truncated = DnsPacket.buildTruncatedResponse(query, question);
        assertNotNull(truncated);
        assertEquals(question.end, truncated.length);
        assertEquals(0x83, truncated[2] & 0xff); // QR + TC + RD
        assertEquals(1, u16(truncated, 4));
        assertEquals(0, u16(truncated, 6));
        assertEquals(0, u16(truncated, 8));
        assertEquals(0, u16(truncated, 10));
        assertEquals(0x80, truncated[3] & 0xff);
    }

    @Test
    public void ageTtlsSubtractsElapsedTimeFromEveryRecord() {
        byte[] response = answerWithTwoRecords(120);
        int firstTtl = firstRecordTtlOffset(response);
        DnsPacket.ageTtls(response, 30);
        assertEquals(90, readU32(response, firstTtl));
        assertEquals(30, readU32(response, findSecondRecordTtlOffset(response)));
        DnsPacket.ageTtls(response, 1_000);
        assertEquals(0, readU32(response, firstTtl));
    }

    @Test
    public void cacheServesAnswerWithRemainingTtl() {
        DnsCache cache = new DnsCache();
        byte[] response = answerWithTwoRecords(120);
        int firstTtl = firstRecordTtlOffset(response);
        long storedAt = 1_000_000L;
        cache.put("ads.example.com", DnsPacket.TYPE_A, response, 120, storedAt);
        byte[] hit = cache.get("ads.example.com", DnsPacket.TYPE_A, 0x7777, storedAt + 30_000L);
        assertNotNull(hit);
        assertEquals(0x7777, u16(hit, 0));
        assertEquals(90, readU32(hit, firstTtl));
        assertNull(cache.get("ads.example.com", DnsPacket.TYPE_A, 0x7777, storedAt + 121_000L));
    }

    @Test
    public void cacheRejectsOversizedAnswers() {
        DnsCache cache = new DnsCache();
        cache.put("big.example", DnsPacket.TYPE_A, new byte[2_000], 60, 0L);
        assertNull(cache.get("big.example", DnsPacket.TYPE_A, 1, 0L));
    }

    // -------------------------------------------------------------- fixtures

    private static int questionEnd(byte[] dns) {
        int cursor = 12;
        while (dns[cursor] != 0) {
            cursor += (dns[cursor] & 0xff) + 1;
        }
        return cursor + 5;
    }

    private static int firstRecordTtlOffset(byte[] dns) {
        // first record: pointer(2) type(2) class(2) then the ttl
        return questionEnd(dns) + 2 + 2 + 2;
    }

    private static int findSecondRecordTtlOffset(byte[] dns) {
        // first record: pointer(2) type(2) class(2) ttl(4) rdlen(2) rdata(4)
        int firstRecordLength = 2 + 2 + 2 + 4 + 2 + 4;
        // second record: pointer(2) type(2) class(2) then the ttl
        return questionEnd(dns) + firstRecordLength + 2 + 2 + 2;
    }

    private static byte[] answerWithTwoRecords(int ttl) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 0x1234);
        writeU16(out, 0x8180);
        writeU16(out, 1);
        writeU16(out, 2);
        writeU16(out, 0);
        writeU16(out, 0);
        for (String label : "ads.example.com".split("\\.")) {
            out.write(label.length());
            for (byte value : label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                out.write(value);
            }
        }
        out.write(0);
        writeU16(out, DnsPacket.TYPE_A);
        writeU16(out, 1);
        int firstRdata = out.size();
        out.write(0xC0);
        out.write(0x0C);
        writeU16(out, DnsPacket.TYPE_A);
        writeU16(out, 1);
        writeU32(out, ttl);
        writeU16(out, 4);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        assertTrue(firstRdata > 0);
        out.write(0xC0);
        out.write(0x0C);
        writeU16(out, DnsPacket.TYPE_AAAA);
        writeU16(out, 1);
        writeU32(out, ttl / 2);
        writeU16(out, 16);
        for (int i = 0; i < 16; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] query(String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 0x1234);
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
        writeU16(out, type);
        writeU16(out, 1);
        return out.toByteArray();
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

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static int readU32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
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
}