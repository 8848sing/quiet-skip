package com.jingjump.app;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * In-memory ring buffer of the most recent blocked queries. Nothing is written
 * to disk; the log disappears with the process on purpose so DNS filtering
 * stays debuggable without creating a browsing trail. Repeated hits of the
 * same domain + query type refresh one entry with a counter instead of
 * flooding the log during an ad retry storm.
 */
final class DnsBlockLog {
    static final int MAX_ENTRIES = 200;

    static final class Entry {
        final long timeMillis;
        final String domain;
        final int queryType;
        final int source;
        final int count;

        Entry(long timeMillis, String domain, int queryType, int source, int count) {
            this.timeMillis = timeMillis;
            this.domain = domain;
            this.queryType = queryType;
            this.source = source;
            this.count = count;
        }
    }

    private static final class MutableEntry {
        long timeMillis;
        final String domain;
        final int queryType;
        int source;
        int count;

        MutableEntry(long timeMillis, String domain, int queryType, int source) {
            this.timeMillis = timeMillis;
            this.domain = domain;
            this.queryType = queryType;
            this.source = source;
            this.count = 1;
        }
    }

    private static final Object LOCK = new Object();
    private static final java.util.ArrayDeque<MutableEntry> RING = new java.util.ArrayDeque<>();
    private static int version;

    private DnsBlockLog() {
    }

    /** Bumped on every change so UIs can rebuild only when something moved. */
    static int version() {
        synchronized (LOCK) {
            return version;
        }
    }

    static void record(String domain, int queryType, int source) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            MutableEntry existing = null;
            for (MutableEntry candidate : RING) {
                if (candidate.queryType == queryType && candidate.domain.equals(domain)) {
                    existing = candidate;
                    break;
                }
            }
            if (existing != null) {
                RING.remove(existing);
                existing.timeMillis = now;
                existing.source = source;
                existing.count++;
                RING.addLast(existing);
                version++;
                return;
            }
            RING.addLast(new MutableEntry(now, domain, queryType, source));
            while (RING.size() > MAX_ENTRIES) {
                RING.pollFirst();
            }
            version++;
        }
    }

    /** Newest entries first. */
    static List<Entry> snapshot() {
        synchronized (LOCK) {
            List<Entry> copy = new ArrayList<>(RING.size());
            Iterator<MutableEntry> iterator = RING.descendingIterator();
            while (iterator.hasNext()) {
                MutableEntry entry = iterator.next();
                copy.add(new Entry(
                        entry.timeMillis, entry.domain, entry.queryType,
                        entry.source, entry.count));
            }
            return copy;
        }
    }

    static void clear() {
        synchronized (LOCK) {
            RING.clear();
            version++;
        }
    }

    static int size() {
        synchronized (LOCK) {
            return RING.size();
        }
    }

    static String queryTypeLabel(int type) {
        switch (type) {
            case DnsPacket.TYPE_A:
                return "A";
            case DnsPacket.TYPE_AAAA:
                return "AAAA";
            case DnsPacket.TYPE_CNAME:
                return "CNAME";
            case 12:
                return "PTR";
            case 15:
                return "MX";
            case 16:
                return "TXT";
            case 64:
                return "SVCB";
            case 65:
                return "HTTPS";
            default:
                return "TYPE" + type;
        }
    }
}
