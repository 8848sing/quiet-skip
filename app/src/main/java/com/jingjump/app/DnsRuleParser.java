package com.jingjump.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses DNS blocklists in the three formats AdGuard DNS products accept:
 * Adblock-style ({@code ||domain^}, {@code @@||domain^} exceptions), {@code
 * /etc/hosts} lines and plain domain lists. Regex rules and rules with
 * modifiers we cannot honour (dnstype/client/ctag/denyallow/badfilter/...)
 * are skipped, following the AdGuard DNS rule-syntax specification.
 */
final class DnsRuleParser {
    static final class Parsed {
        final Set<String> blocked;
        final Set<String> allowed;
        final int lines;
        final int skippedUnsupported;
        final int malformed;
        final boolean truncated;

        Parsed(
                Set<String> blocked,
                Set<String> allowed,
                int lines,
                int skippedUnsupported,
                int malformed,
                boolean truncated
        ) {
            this.blocked = Collections.unmodifiableSet(blocked);
            this.allowed = Collections.unmodifiableSet(allowed);
            this.lines = lines;
            this.skippedUnsupported = skippedUnsupported;
            this.malformed = malformed;
            this.truncated = truncated;
        }

        int ruleCount() {
            return blocked.size();
        }
    }

    private DnsRuleParser() {
    }

    /**
     * RFC 6761 / hosts conventions. Standard hosts files map these names to
     * loopback as part of their format ("127.0.0.1 localhost"); importing one
     * must not turn the reserved names themselves into blocking rules.
     */
    private static final Set<String> RESERVED_HOSTNAMES;
    static {
        RESERVED_HOSTNAMES = new HashSet<>(Arrays.asList(
                "localhost", "localdomain", "broadcasthost"));
    }

    static Parsed parse(Reader source, int maxRules) throws IOException {
        BufferedReader reader = source instanceof BufferedReader
                ? (BufferedReader) source
                : new BufferedReader(source);
        HashSet<String> blocked = new HashSet<>();
        HashSet<String> allowed = new HashSet<>();
        int lines = 0;
        int unsupported = 0;
        int malformed = 0;
        boolean truncated = false;

        String raw;
        while ((raw = reader.readLine()) != null) {
            String line = stripInlineComment(raw).trim();
            if (line.isEmpty() || isCommentHeader(line)) {
                continue;
            }
            lines++;

            String pattern = line;
            String modifiers = "";
            int dollar = pattern.indexOf('$');
            if (dollar >= 0) {
                modifiers = pattern.substring(dollar + 1).trim().toLowerCase(java.util.Locale.ROOT);
                pattern = pattern.substring(0, dollar);
            }
            if (!modifiers.isEmpty() && !isSupportedModifierSet(modifiers)) {
                unsupported++;
                continue;
            }
            if (pattern.startsWith("##") || pattern.startsWith("#@#")) {
                // Cosmetic element-hiding rules: meaningless for DNS.
                unsupported++;
                continue;
            }

            boolean exception = pattern.startsWith("@@");
            if (exception) {
                pattern = pattern.substring(2);
            }
            if (isHostsLine(pattern)) {
                String[] parts = pattern.split("\\s+");
                boolean matched = false;
                for (int i = 1; i < parts.length; i++) {
                    String domain = normalizeHostsDomain(parts[i]);
                    if (domain.isEmpty()) {
                        continue;
                    }
                    matched = true;
                    if (blocked.size() + allowed.size() >= maxRules) {
                        truncated = true;
                        break;
                    }
                    if (exception) {
                        allowed.add(domain);
                    } else if (!RESERVED_HOSTNAMES.contains(domain)) {
                        blocked.add(domain);
                    }
                }
                if (!matched) {
                    malformed++;
                }
                if (truncated) {
                    break;
                }
                continue;
            }

            if (pattern.startsWith("/")) {
                // Regex rule: intentionally unsupported to keep lookups cheap.
                unsupported++;
                continue;
            }
            if (pattern.startsWith("||")) {
                pattern = pattern.substring(2);
            } else if (pattern.startsWith("|")) {
                unsupported++;
                continue;
            }
            while (pattern.endsWith("^")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.startsWith("/") || pattern.contains("*")
                    || pattern.contains("|") || pattern.contains("/")) {
                unsupported++;
                continue;
            }
            String domain = normalizeHostsDomain(pattern);
            if (domain.isEmpty()) {
                malformed++;
                continue;
            }
            if (blocked.size() + allowed.size() >= maxRules) {
                truncated = true;
                break;
            }
            if (exception) {
                allowed.add(domain);
            } else if (!RESERVED_HOSTNAMES.contains(domain)) {
                blocked.add(domain);
            }
        }
        if (truncated && blocked.size() + allowed.size() >= maxRules) {
            // keep the truncated marker so callers can warn about the limit
            truncated = true;
        }
        return new Parsed(blocked, allowed, lines, unsupported, malformed, truncated);
    }

    /** Convenience for simple newline-separated user lists (one domain per line). */
    static Set<String> parsePlainList(String text) {
        HashSet<String> domains = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return Collections.unmodifiableSet(domains);
        }
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#") || value.startsWith("!")) {
                continue;
            }
            String domain = normalizeHostsDomain(value);
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }
        return Collections.unmodifiableSet(domains);
    }

    private static boolean isCommentHeader(String line) {
        return line.startsWith("!") || line.startsWith("#") || line.startsWith("[")
                || line.startsWith(";");
    }

    private static String stripInlineComment(String line) {
        // Hosts files allow "domain # comment" or "domain<TAB># comment".
        // A '#' glued to a token is left in place so the normalizer rejects
        // the line instead of silently truncating a domain.
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '#') {
                continue;
            }
            if (i == 0) {
                return "";
            }
            char previous = line.charAt(i - 1);
            return previous == ' ' || previous == '\t'
                    ? line.substring(0, i)
                    : line;
        }
        return line;
    }

    private static boolean isSupportedModifierSet(String modifiers) {
        for (String part : modifiers.split(",")) {
            String value = part.trim();
            if (value.isEmpty() || "important".equals(value) || "strict-dname".equals(value)) {
                continue;
            }
            // Everything else (dnstype, client, ctag, denyallow, badfilter,
            // dnsrewrite, browser scope, cosmetic…) changes semantics we do
            // not implement, so the rule must be ignored as a whole.
            return false;
        }
        return true;
    }

    private static boolean isHostsLine(String pattern) {
        int space = pattern.indexOf(' ');
        if (space <= 0) {
            space = pattern.indexOf('\t');
        }
        if (space <= 0) {
            return false;
        }
        String head = pattern.substring(0, space).trim();
        return isIpLiteral(head);
    }

    private static boolean isIpLiteral(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return value.matches("[0-9a-fA-F:]+");
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.matches("\\d+")) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHostsDomain(String raw) {
        String value = raw.trim();
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return DnsBlockList.normalizeDomain(value);
    }
}
