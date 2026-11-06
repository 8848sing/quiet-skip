package com.jingjump.app;

import java.net.IDN;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Domain normalization and subtree matching shared by every rule source.
 * The rule loading itself moved to {@link DnsRuleStore} in 1.8.
 *
 * <p>1.9.0 turns both paths allocation-light: a domain that is already plain
 * lowercase ASCII (the overwhelming majority — every DNS question and every
 * rule line in the bundled lists) returns without touching {@link IDN} or a
 * regex engine, and the ASCII validation uses one precompiled pattern instead
 * of compiling a new one per call. Loading the 105k-entry list therefore no
 * longer compiles 105k patterns.</p>
 */
final class DnsBlockList {
    private static final Pattern ASCII_DOMAIN = Pattern.compile("[a-z0-9.-]+");
    private static final int MAX_DOMAIN_LENGTH = 253;

    private DnsBlockList() {
    }

    static boolean isDomainBlocked(String rawDomain, Set<String> blockedDomains) {
        String domain = normalizeDomain(rawDomain);
        if (domain.isEmpty() || blockedDomains == null || blockedDomains.isEmpty()) {
            return false;
        }
        String cursor = domain;
        while (!cursor.isEmpty()) {
            if (blockedDomains.contains(cursor)) {
                return true;
            }
            int dot = cursor.indexOf('.');
            if (dot < 0) {
                break;
            }
            cursor = cursor.substring(dot + 1);
        }
        return false;
    }

    static String normalizeDomain(String rawDomain) {
        if (rawDomain == null) {
            return "";
        }
        String value = rawDomain.trim();
        if (value.startsWith("#")) {
            return "#";
        }
        // Trailing root dots ("example.com.") are dropped before validation.
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        if (end == 0 || end > MAX_DOMAIN_LENGTH) {
            return "";
        }
        boolean plainAscii = true;
        for (int i = 0; i < end; i++) {
            char current = value.charAt(i);
            if ((current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '.' || current == '-') {
                continue;
            }
            plainAscii = false;
            break;
        }
        if (plainAscii) {
            return end == value.length() ? value : value.substring(0, end);
        }
        String normalized = value.substring(0, end).toLowerCase(Locale.ROOT);
        try {
            normalized = IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED)
                    .toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
        if (normalized.isEmpty() || normalized.length() > MAX_DOMAIN_LENGTH) {
            return "";
        }
        return ASCII_DOMAIN.matcher(normalized).matches() ? normalized : "";
    }

    /** True when the value is already a valid, normalized ASCII domain. */
    static boolean isNormalizedDomain(String value) {
        return value != null
                && !value.isEmpty()
                && value.length() <= MAX_DOMAIN_LENGTH
                && ASCII_DOMAIN.matcher(value).matches();
    }
}