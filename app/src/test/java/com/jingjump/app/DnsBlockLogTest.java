package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public final class DnsBlockLogTest {

    @Before
    public void reset() {
        DnsBlockLog.clear();
    }

    @Test
    public void keepsNewestFirstWithinCap() {
        for (int i = 0; i < DnsBlockLog.MAX_ENTRIES + 50; i++) {
            DnsBlockLog.record("host" + i + ".test", 1, DnsFilterSnapshot.SOURCE_BUILTIN);
        }
        assertEquals(DnsBlockLog.MAX_ENTRIES, DnsBlockLog.size());
        List<DnsBlockLog.Entry> entries = DnsBlockLog.snapshot();
        assertEquals("host" + (DnsBlockLog.MAX_ENTRIES + 49) + ".test",
                entries.get(0).domain);
        assertTrue(entries.get(entries.size() - 1).domain.startsWith("host50."));
    }

    @Test
    public void sameDomainAndTypeMergesIntoOneCountedEntry() {
        DnsBlockLog.record("a.test", 1, DnsFilterSnapshot.SOURCE_BUILTIN);
        DnsBlockLog.record("a.test", 1, DnsFilterSnapshot.SOURCE_FULL);
        DnsBlockLog.record("a.test", 28, DnsFilterSnapshot.SOURCE_BUILTIN);

        assertEquals(2, DnsBlockLog.size());
        List<DnsBlockLog.Entry> entries = DnsBlockLog.snapshot();
        assertEquals(28, entries.get(0).queryType);
        assertEquals(1, entries.get(1).queryType);
        assertEquals(2, entries.get(1).count);
        // newest source wins on the merged entry
        assertEquals(DnsFilterSnapshot.SOURCE_FULL, entries.get(1).source);
    }

    @Test
    public void versionAdvancesOnRecordAndClear() {
        int before = DnsBlockLog.version();
        DnsBlockLog.record("v.test", 1, DnsFilterSnapshot.SOURCE_BUILTIN);
        int afterRecord = DnsBlockLog.version();
        assertTrue(afterRecord > before);
        DnsBlockLog.clear();
        assertTrue(DnsBlockLog.version() > afterRecord);
    }

    @Test
    public void labelsQueryTypes() {
        assertEquals("A", DnsBlockLog.queryTypeLabel(1));
        assertEquals("AAAA", DnsBlockLog.queryTypeLabel(28));
        assertEquals("HTTPS", DnsBlockLog.queryTypeLabel(65));
        assertEquals("TYPE65535", DnsBlockLog.queryTypeLabel(65535));
    }
}
