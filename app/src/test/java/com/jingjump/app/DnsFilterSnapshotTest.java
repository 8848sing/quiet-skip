package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class DnsFilterSnapshotTest {

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static DnsFilterSnapshot snapshot() {
        return new DnsFilterSnapshot(
                set("doubleclick.net"),
                set("junk.example"),
                set("imported.test"),
                0, 0, false,
                set("mine.block"),
                set("dns.google"),
                set("free.example")
        );
    }

    @Test
    public void subtreeMatchingAcrossSources() {
        DnsFilterSnapshot current = snapshot();
        assertEquals(DnsFilterSnapshot.SOURCE_BUILTIN,
                current.blockSource("x.doubleclick.net"));
        assertEquals(DnsFilterSnapshot.SOURCE_FULL,
                current.blockSource("junk.example"));
        assertEquals(DnsFilterSnapshot.SOURCE_IMPORTED,
                current.blockSource("a.b.imported.test"));
        assertEquals(DnsFilterSnapshot.SOURCE_CUSTOM,
                current.blockSource("mine.block"));
        assertEquals(DnsFilterSnapshot.SOURCE_DOH,
                current.blockSource("dns.google"));
        assertEquals(DnsFilterSnapshot.SOURCE_NONE,
                current.blockSource("clean.example"));
    }

    @Test
    public void allowSetCoversSubdomains() {
        DnsFilterSnapshot current = snapshot();
        assertTrue(current.isAllowed("free.example"));
        assertTrue(current.isAllowed("deep.sub.free.example"));
        assertFalse(current.isAllowed("notfree.example"));
    }

    @Test
    public void customBlockWinsOverOtherSources() {
        DnsFilterSnapshot current = new DnsFilterSnapshot(
                set("a.test"), set("full.test"), set("imported.test"),
                0, 0, false,
                set("custom.test"), set("d.test"), set()
        );
        assertEquals(DnsFilterSnapshot.SOURCE_CUSTOM, current.blockSource("sub.custom.test"));
        assertEquals(DnsFilterSnapshot.SOURCE_IMPORTED, current.blockSource("imported.test"));
        assertEquals(DnsFilterSnapshot.SOURCE_FULL, current.blockSource("x.full.test"));
        assertEquals(DnsFilterSnapshot.SOURCE_BUILTIN, current.blockSource("a.test"));
    }

    @Test
    public void totalRulesSumsAllBlockSources() {
        assertEquals(5, snapshot().totalRules());
    }

    @Test
    public void pruneDropsEntriesCoveredByAncestorWithinSameSet() {
        java.util.Set<String> source = set(
                "example", "x.example", "y.x.example", "other.test");
        java.util.Set<String> kept = DnsFilterSnapshot.prune(
                source, java.util.Collections.emptyList());
        assertTrue(kept.contains("example"));
        assertTrue(kept.contains("other.test"));
        assertFalse(kept.contains("x.example"));
        assertFalse(kept.contains("y.x.example"));
    }

    @Test
    public void pruneDropsEntriesCoveredByHigherPrioritySet() {
        java.util.Set<String> covering = set("ads.doubleclick.net");
        java.util.Set<String> kept = DnsFilterSnapshot.prune(
                set("deep.ads.doubleclick.net", "unrelated.test"),
                java.util.Collections.singletonList(covering));
        assertFalse(kept.contains("deep.ads.doubleclick.net"));
        assertTrue(kept.contains("unrelated.test"));
    }

    @Test
    public void pruningKeepsBlockingDecisionsIdentical() {
        DnsFilterSnapshot before = new DnsFilterSnapshot(
                set("example"), set("x.example", "y.z.example"),
                set(), 0, 0, false, set(), set(), set());
        java.util.Set<String> prunedFull = DnsFilterSnapshot.prune(
                set("x.example", "y.z.example"),
                java.util.Collections.singletonList(set("example")));
        DnsFilterSnapshot after = new DnsFilterSnapshot(
                set("example"), prunedFull,
                set(), 0, 0, false, set(), set(), set());
        for (String probe : new String[]{
                "x.example", "a.x.example", "y.z.example", "example", "unrelated.test"}) {
            boolean blockedBefore = before.blockSource(probe) != DnsFilterSnapshot.SOURCE_NONE;
            boolean blockedAfter = after.blockSource(probe) != DnsFilterSnapshot.SOURCE_NONE;
            assertEquals(probe, blockedBefore, blockedAfter);
        }
    }
}
