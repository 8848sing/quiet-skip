package com.jingjump.app;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small TTL-aware response cache in front of the upstream resolvers. Repeated
 * lookups for the same name are answered locally, which saves radio wake-ups
 * and shortens ad-heavy page loads even while the ad hosts themselves fail.
 *
 * <p>1.9.0 stores the whole answer and rewrites the record TTLs with the time
 * the entry has been cached, so a client never keeps a record longer than the
 * upstream allowed (a 60 s record previously kept its original 60 s TTL no
 * matter how long ago it had been cached).</p>
 */
final class DnsCache {
    static final int MAX_ENTRIES = 256;
    static final long MIN_TTL_MS = 15_000L;
    static final long MAX_TTL_MS = 600_000L;

    private static final class CachedAnswer {
        final byte[] response;
        final long storedAt;
        final long expiresAt;

        CachedAnswer(byte[] response, long storedAt, long expiresAt) {
            this.response = response;
            this.storedAt = storedAt;
            this.expiresAt = expiresAt;
        }
    }

    private final LinkedHashMap<String, CachedAnswer> entries =
            new LinkedHashMap<String, CachedAnswer>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedAnswer> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    synchronized void put(String domain, int queryType, byte[] dnsResponse, long ttlSeconds) {
        put(domain, queryType, dnsResponse, ttlSeconds, System.currentTimeMillis());
    }

    synchronized void put(
            String domain,
            int queryType,
            byte[] dnsResponse,
            long ttlSeconds,
            long now
    ) {
        if (domain == null || dnsResponse == null || dnsResponse.length < 12
                || dnsResponse.length > 1400) {
            return;
        }
        long ttlMs = clampTtl(ttlSeconds);
        entries.put(key(domain, queryType),
                new CachedAnswer(Arrays.copyOf(dnsResponse, dnsResponse.length),
                        now, now + ttlMs));
    }

    synchronized byte[] get(String domain, int queryType, int queryId) {
        return get(domain, queryType, queryId, System.currentTimeMillis());
    }

    synchronized byte[] get(String domain, int queryType, int queryId, long now) {
        String key = key(domain, queryType);
        CachedAnswer entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt < now) {
            entries.remove(key);
            return null;
        }
        byte[] response = Arrays.copyOf(entry.response, entry.response.length);
        response[0] = (byte) ((queryId >>> 8) & 0xff);
        response[1] = (byte) (queryId & 0xff);
        long elapsedSeconds = (now - entry.storedAt) / 1000L;
        if (elapsedSeconds > 0) {
            try {
                DnsPacket.ageTtls(response, elapsedSeconds);
            } catch (Throwable ignored) {
                // A malformed answer is still better forwarded than dropped.
            }
        }
        return response;
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    static long clampTtl(long ttlSeconds) {
        long ttlMs = ttlSeconds <= 0 ? MIN_TTL_MS : ttlSeconds * 1000L;
        if (ttlMs < MIN_TTL_MS) {
            return MIN_TTL_MS;
        }
        return Math.min(ttlMs, MAX_TTL_MS);
    }

    private static String key(String domain, int queryType) {
        return queryType + ":" + domain;
    }
}