package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DnsCacheTest {

    private static byte[] response(int id) {
        byte[] dns = new byte[12 + 20];
        dns[0] = (byte) ((id >>> 8) & 0xff);
        dns[1] = (byte) (id & 0xff);
        dns[2] = (byte) 0x81;
        dns[3] = (byte) 0x80;
        return dns;
    }

    @Test
    public void storesAndRewritesQueryId() {
        DnsCache cache = new DnsCache();
        cache.put("a.test", 1, response(0x1234), 120);
        byte[] hit = cache.get("a.test", 1, 0x9999);
        assertNotNull(hit);
        assertEquals(0x99, hit[0] & 0xff);
        assertEquals(0x99, hit[1] & 0xff);
        assertEquals(0x81, hit[2] & 0xff);
        assertEquals(hit.length, 32);
        assertNull(cache.get("a.test", 28, 0x9999));
        assertNull(cache.get("b.test", 1, 0x9999));
    }

    @Test
    public void clampsTtlBounds() {
        assertEquals(DnsCache.MIN_TTL_MS, DnsCache.clampTtl(0));
        assertEquals(DnsCache.MIN_TTL_MS, DnsCache.clampTtl(1));
        assertEquals(120_000L, DnsCache.clampTtl(120));
        assertEquals(DnsCache.MAX_TTL_MS, DnsCache.clampTtl(999_999));
    }

    @Test
    public void evictsOldestBeyondCapacity() {
        DnsCache cache = new DnsCache();
        for (int i = 0; i < DnsCache.MAX_ENTRIES + 20; i++) {
            cache.put("host" + i + ".test", 1, response(i), 300);
        }
        assertTrue(cache.size() <= DnsCache.MAX_ENTRIES + 1);
        assertNull(cache.get("host0.test", 1, 1));
        assertNotNull(cache.get("host250.test", 1, 1));
    }

    @Test
    public void clearDropsEverything() {
        DnsCache cache = new DnsCache();
        cache.put("a.test", 1, response(1), 300);
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a.test", 1, 1));
    }
}
