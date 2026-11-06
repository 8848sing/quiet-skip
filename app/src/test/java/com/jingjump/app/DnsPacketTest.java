package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class DnsPacketTest {
    @Test
    public void parsesUdpDnsQuery() {
        byte[] packet = buildQuery();
        DnsPacket.Query query = DnsPacket.parseQuery(packet, 0, packet.length);

        assertNotNull(query);
        assertEquals("ad.toutiao.com", query.question.name);
        assertEquals(40000, query.sourcePort);
        assertEquals(40000, ((packet[20] & 0xff) << 8) | (packet[21] & 0xff));
    }

    @Test
    public void buildsNxDomainResponse() {
        byte[] packet = buildQuery();
        DnsPacket.Query query = DnsPacket.parseQuery(packet, 0, packet.length);
        byte[] response = DnsPacket.buildError(
                query.dnsPayload,
                0,
                query.question,
                DnsPacket.RCODE_NXDOMAIN
        );

        assertNotNull(response);
        assertTrue((response[2] & 0x80) != 0);
        assertEquals(3, response[3] & 0x0f);
        assertEquals(1, ((response[4] & 0xff) << 8) | (response[5] & 0xff));
        assertEquals(0, ((response[6] & 0xff) << 8) | (response[7] & 0xff));
    }

    @Test
    public void buildsReplyWithSwappedAddressesAndPorts() {
        byte[] packet = buildQuery();
        DnsPacket.Query query = DnsPacket.parseQuery(packet, 0, packet.length);
        byte[] reply = DnsPacket.buildUdpReply(query, new byte[]{0, 0, 0, 0});

        assertNotNull(reply);
        assertEquals(32, reply.length);
        assertEquals(223, reply[12] & 0xff);
        assertEquals(5, reply[13] & 0xff);
        assertEquals(5, reply[14] & 0xff);
        assertEquals(5, reply[15] & 0xff);
        assertEquals(10, reply[16] & 0xff);
        assertEquals(111, reply[17] & 0xff);
        assertEquals(222, reply[18] & 0xff);
        assertEquals(1, reply[19] & 0xff);
        assertEquals(53, ((reply[20] & 0xff) << 8) | (reply[21] & 0xff));
        assertEquals(40000, ((reply[22] & 0xff) << 8) | (reply[23] & 0xff));
    }

    @Test
    public void blockListMatchesDomainAndSubdomainsOnly() {
        Set<String> blocked = new HashSet<>(Arrays.asList("ad.toutiao.com", "gdt.qq.com"));

        assertTrue(DnsBlockList.isDomainBlocked("ad.toutiao.com", blocked));
        assertTrue(DnsBlockList.isDomainBlocked("x.y.ad.toutiao.com", blocked));
        assertTrue(DnsBlockList.isDomainBlocked("gdt.qq.com.", blocked));
        assertTrue(!DnsBlockList.isDomainBlocked("notad.toutiao.com", blocked));
        assertTrue(!DnsBlockList.isDomainBlocked("example.com", blocked));
    }

    private static byte[] buildQuery() {
        ByteArrayOutputStream dns = new ByteArrayOutputStream();
        dns.write(0x12);
        dns.write(0x34);
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
        writeU16(dns, 1);
        writeU16(dns, 1);
        byte[] payload = dns.toByteArray();

        byte[] packet = new byte[28 + payload.length];
        packet[0] = 0x45;
        writeU16(packet, 2, packet.length);
        writeU16(packet, 4, 0x77);
        writeU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 17;
        packet[12] = 10;
        packet[13] = 111;
        packet[14] = (byte) 222;
        packet[15] = 1;
        packet[16] = (byte) 223;
        packet[17] = 5;
        packet[18] = 5;
        packet[19] = 5;
        writeU16(packet, 20, 40000);
        writeU16(packet, 22, 53);
        writeU16(packet, 24, payload.length + 8);
        System.arraycopy(payload, 0, packet, 28, payload.length);
        return packet;
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeU16(byte[] output, int offset, int value) {
        output[offset] = (byte) ((value >>> 8) & 0xff);
        output[offset + 1] = (byte) (value & 0xff);
    }
}
