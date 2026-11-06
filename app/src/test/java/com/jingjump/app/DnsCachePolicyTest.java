package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

public final class DnsCachePolicyTest {

    // ---------------------------------------------------------------- layout

    private static void writeName(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.")) {
            out.write(label.length());
            for (byte b : label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                out.write(b);
            }
        }
        out.write(0);
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    /** rcode + answer/authority counts + one RR in the answer or authority section. */
    private static byte[] response(int rcode, int answerCount, int answerTtl,
                                   int authorityCount, int authorityTtl, int flagsExtra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u16(out, 0x7777);
        u16(out, 0x8180 | (rcode & 0x0f) | flagsExtra);
        u16(out, 1);
        u16(out, answerCount);
        u16(out, authorityCount);
        u16(out, 0);
        writeName(out, "probe.test");
        u16(out, DnsPacket.TYPE_A);
        u16(out, 1);
        for (int i = 0; i < answerCount; i++) {
            u16(out, 0xC00C);
            u16(out, DnsPacket.TYPE_A);
            u16(out, 1);
            u32(out, answerTtl);
            u16(out, 4);
            out.write(1);
            out.write(2);
            out.write(3);
            out.write(4);
        }
        for (int i = 0; i < authorityCount; i++) {
            u16(out, 0xC00C);
            u16(out, 6); // SOA
            u16(out, 1);
            u32(out, authorityTtl);
            u16(out, 20); // SOA rdata is not parsed by the TTL reader
            for (int j = 0; j < 20; j++) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    // ----------------------------------------------------------------- tests

    @Test
    public void positiveAnswerUsesSmallerOfAnswerTtlAndCap() {
        assertEquals(45, DnsPacket.cacheTtlSeconds(response(0, 1, 45, 0, 0, 0)));
        assertEquals(600, DnsPacket.cacheTtlSeconds(response(0, 1, 30_000, 0, 0, 0)));
        assertEquals(15, DnsPacket.cacheTtlSeconds(response(0, 1, 1, 0, 0, 0)));
    }

    @Test
    public void nxDomainUsesSoaTtlAndIsCapped() {
        assertEquals(120, DnsPacket.cacheTtlSeconds(
                response(DnsPacket.RCODE_NXDOMAIN, 0, 0, 1, 120, 0)));
        assertEquals(300, DnsPacket.cacheTtlSeconds(
                response(DnsPacket.RCODE_NXDOMAIN, 0, 0, 1, 90_000, 0)));
    }

    @Test
    public void nODATAWithoutSoaGetsShortCache() {
        assertEquals(60, DnsPacket.cacheTtlSeconds(response(0, 0, 0, 0, 0, 0)));
    }

    @Test
    public void transientAndTruncatedResponsesAreNotCached() {
        assertEquals(-1, DnsPacket.cacheTtlSeconds(
                response(DnsPacket.RCODE_SERVFAIL, 1, 60, 0, 0, 0)));
        assertEquals(-1, DnsPacket.cacheTtlSeconds(
                response(0, 1, 60, 0, 0, 0x0200))); // TC bit set
        assertEquals(-1, DnsPacket.cacheTtlSeconds(new byte[]{1, 2, 3}));
    }

    @Test
    public void upstreamOrderPrefersLastSuccessfulServer() {
        List<String> normal = DnsVpnService.orderFor(0);
        assertEquals(Arrays.asList("223.5.5.5", "119.29.29.29"), normal);
        List<String> swapped = DnsVpnService.orderFor(1);
        assertEquals(Arrays.asList("119.29.29.29", "223.5.5.5"), swapped);
        // out-of-range preference falls back to the fixed order
        assertEquals(normal, DnsVpnService.orderFor(9));
        assertTrue(v6Present());
    }

    private static boolean v6Present() {
        return DnsVpnService.ROUTED_DNS_IPV6.length > 0;
    }
}