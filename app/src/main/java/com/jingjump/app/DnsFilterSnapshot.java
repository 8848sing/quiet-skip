package com.jingjump.app;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable merged view of every DNS rule source. A query is blocked when it
 * matches a rule set, unless the domain (or one of its parents) appears in the
 * allow set — exceptions and user whitelists always win, mirroring the
 * behaviour of AdGuard's exception rules.
 *
 * Source sets are taken over without copying: callers must hand over
 * freshly-built (or empty) sets and must not mutate them afterwards.
 */
final class DnsFilterSnapshot {
    static final int SOURCE_NONE = 0;
    static final int SOURCE_BUILTIN = 1;
    static final int SOURCE_FULL = 2;
    static final int SOURCE_IMPORTED = 3;
    static final int SOURCE_CUSTOM = 4;
    static final int SOURCE_DOH = 5;
    static final int SOURCE_LEARNED = 6;
    static final int SOURCE_CNAME = 7;

    private final Set<String> builtin;
    private final Set<String> full;
    private final Set<String> imported;
    private final Set<String> customBlock;
    private final Set<String> dohBlock;
    private final Set<String> allow;

    final int importedSkipped;
    final int importedMalformed;
    final boolean importedTruncated;

    DnsFilterSnapshot(
            Set<String> builtin,
            Set<String> full,
            Set<String> imported,
            int importedSkipped,
            int importedMalformed,
            boolean importedTruncated,
            Set<String> customBlock,
            Set<String> dohBlock,
            Set<String> allow
    ) {
        this.builtin = immutable(builtin);
        this.full = immutable(full);
        this.imported = immutable(imported);
        this.customBlock = immutable(customBlock);
        this.dohBlock = immutable(dohBlock);
        this.allow = immutable(allow);
        this.importedSkipped = importedSkipped;
        this.importedMalformed = importedMalformed;
        this.importedTruncated = importedTruncated;
    }

    static DnsFilterSnapshot empty() {
        return new DnsFilterSnapshot(
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                0,
                0,
                false,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet()
        );
    }

    /**
     * Drops entries whose strict ancestor is present in {@code source} itself
     * or in any set of {@code covering} (parent domains block their subtrees
     * anyway). Purely a memory/lookup optimisation: the blocking decision is
     * unchanged, only the reported source label can move to the parent.
     */
    static Set<String> prune(Set<String> source, Collection<Set<String>> covering) {
        if (source.isEmpty()) {
            return source;
        }
        HashSet<String> kept = new HashSet<>(source.size() * 2);
        entries:
        for (String entry : source) {
            int dot = entry.indexOf('.');
            while (dot >= 0) {
                String ancestor = entry.substring(dot + 1);
                if (source.contains(ancestor) || containsIn(covering, ancestor)) {
                    continue entries;
                }
                dot = entry.indexOf('.', dot + 1);
            }
            kept.add(entry);
        }
        return kept;
    }

    private static boolean containsIn(Collection<Set<String>> sets, String key) {
        for (Set<String> set : sets) {
            if (set.contains(key)) {
                return true;
            }
        }
        return false;
    }

    boolean isAllowed(String rawDomain) {
        return matches(rawDomain, allow);
    }

    int blockSource(String rawDomain) {
        if (matches(rawDomain, customBlock)) {
            return SOURCE_CUSTOM;
        }
        if (matches(rawDomain, imported)) {
            return SOURCE_IMPORTED;
        }
        if (matches(rawDomain, full)) {
            return SOURCE_FULL;
        }
        if (matches(rawDomain, builtin)) {
            return SOURCE_BUILTIN;
        }
        if (matches(rawDomain, dohBlock)) {
            return SOURCE_DOH;
        }
        return SOURCE_NONE;
    }

    int totalRules() {
        return builtin.size() + full.size() + imported.size()
                + customBlock.size() + dohBlock.size();
    }

    int builtinSize() {
        return builtin.size();
    }

    int fullSize() {
        return full.size();
    }

    int importedSize() {
        return imported.size();
    }

    int customBlockSize() {
        return customBlock.size();
    }

    int dohSize() {
        return dohBlock.size();
    }

    int allowSize() {
        return allow.size();
    }

    private static boolean matches(String rawDomain, Set<String> set) {
        if (rawDomain == null || set.isEmpty()) {
            return false;
        }
        String cursor = rawDomain;
        while (!cursor.isEmpty()) {
            if (set.contains(cursor)) {
                return true;
            }
            int dot = cursor.indexOf('.');
            if (dot < 0) {
                return false;
            }
            cursor = cursor.substring(dot + 1);
        }
        return false;
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null || source.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(source);
    }
}
