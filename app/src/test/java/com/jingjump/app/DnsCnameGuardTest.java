package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public final class DnsCnameGuardTest {

    private static byte[] responseWithCname(String qname, String target, int ttl) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 0x4242);       // id
        writeU16(out, 0x8180);       // QR|RD|RA, NOERROR
        writeU16(out, 1);            // QDCOUNT
        writeU16(out, 1);            // ANCOUNT
        writeU16(out, 0);
        writeU16(out, 0);
        writeName(out, qname);
        writeU16(out, DnsPacket.TYPE_A);
        writeU16(out, 1);
        writeU16(out, 0xC00C);       // answer name -> question
        writeU16(out, DnsPacket.TYPE_CNAME);
        writeU16(out, 1);
        writeU32(out, ttl);
        byte[] rdata = encodedName(target);
        writeU16(out, rdata.length);
        out.write(rdata, 0, rdata.length);
        return out.toByteArray();
    }

    private static byte[] encodedName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeName(out, name);
        return out.toByteArray();
    }

    private static void writeName(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.")) {
            out.write(label.length());
            for (byte b : label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                out.write(b);
            }
        }
        out.write(0);
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

    private static DnsFilterSnapshot snapshot(java.util.Set<String> full,
                                              java.util.Set<String> allow) {
        return new DnsFilterSnapshot(
                java.util.Collections.emptySet(),
                full,
                java.util.Collections.emptySet(),
                0, 0, false,
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                allow
        );
    }

    @Test
    public void blocksCnameCloakedTracking() {
        byte[] upstream = responseWithCname("news.example", "ads.tracker.test", 60);
        String hit = DnsVpnService.cnameBlockTarget(
                snapshot(java.util.Collections.singleton("tracker.test"),
                        java.util.Collections.emptySet()),
                true, "news.example", upstream);
        assertEquals("ads.tracker.test", hit);
    }

    @Test
    public void whitelistedRequestIsNeverBrokenByItsAnswerChain() {
        byte[] upstream = responseWithCname("news.example", "ads.tracker.test", 60);
        // user whitelisted the *queried* domain: the answer chain must pass,
        // otherwise "added to whitelist but still broken" happens
        assertNull(DnsVpnService.cnameBlockTarget(
                snapshot(java.util.Collections.singleton("tracker.test"),
                        java.util.Collections.singleton("news.example")),
                true, "news.example", upstream));
    }

    @Test
    public void whitelistedTargetIsNotBlocked() {
        byte[] upstream = responseWithCname("news.example", "ads.tracker.test", 60);
        assertNull(DnsVpnService.cnameBlockTarget(
                snapshot(java.util.Collections.singleton("tracker.test"),
                        java.util.Collections.singleton("tracker.test")),
                true, "news.example", upstream));
    }

    @Test
    public void guardDisabledSkipsScan() {
        byte[] upstream = responseWithCname("news.example", "ads.tracker.test", 60);
        assertNull(DnsVpnService.cnameBlockTarget(
                snapshot(java.util.Collections.singleton("tracker.test"),
                        java.util.Collections.emptySet()),
                false, "news.example", upstream));
    }

    @Test
    public void unrelatedAnswersAreNotBlocked() {
        byte[] upstream = responseWithCname("news.example", "cdn.example", 60);
        assertNull(DnsVpnService.cnameBlockTarget(
                snapshot(java.util.Collections.singleton("tracker.test"),
                        java.util.Collections.emptySet()),
                true, "news.example", upstream));
    }
}