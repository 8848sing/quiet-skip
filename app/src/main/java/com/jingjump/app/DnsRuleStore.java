package com.jingjump.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads every DNS rule source into one immutable snapshot and manages the
 * imported subscription file. Sources: curated built-in list, anti-AD style
 * extended list, user-imported file, DoH provider list (strict mode), the
 * manual block/allow lists from the UI.
 */
final class DnsRuleStore {
    static final int MAX_BUILTIN_RULES = 20_000;
    static final int MAX_EXTENDED_RULES = 400_000;
    static final int MAX_USER_LIST_RULES = 2_000;
    static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;
    static final String IMPORT_FILE_NAME = "dns_import_rules.txt";
    static final String IMPORT_META_SEPARATOR = "|";

    private DnsRuleStore() {
    }

    /**
     * Cheap signature of every input that can change the built snapshot.
     * Lets the service skip a full re-parse (10^5 rules) when, for example,
     * only the block mode changed — while still re-reading the cheap flags.
     */
    static String ruleFingerprint(Context context) {
        StringBuilder fingerprint = new StringBuilder(80);
        fingerprint.append(SettingsStore.dnsFullList(context) ? '1' : '0');
        fingerprint.append(SettingsStore.dnsStrictMode(context) ? '1' : '0');
        fingerprint.append(':').append(SettingsStore.dnsCustomBlockText(context).hashCode());
        fingerprint.append(':').append(SettingsStore.dnsCustomAllowText(context).hashCode());
        File imported = importFile(context);
        boolean exists = imported.exists();
        fingerprint.append(':').append(exists ? imported.lastModified() : 0L);
        fingerprint.append(':').append(exists ? imported.length() : 0L);
        return fingerprint.toString();
    }

    static DnsFilterSnapshot load(Context context) {
        DnsRuleParser.Parsed builtin = parseAsset(context, "ad_domains.txt", MAX_BUILTIN_RULES);
        DnsRuleParser.Parsed full = SettingsStore.dnsFullList(context)
                ? parseAsset(context, "full_blocklist.txt", MAX_EXTENDED_RULES)
                : emptyParsed();
        DnsRuleParser.Parsed imported = importFile(context).exists()
                ? parseImportFile(context)
                : emptyParsed();
        DnsRuleParser.Parsed doh = SettingsStore.dnsStrictMode(context)
                ? parseAsset(context, "doh_providers.txt", MAX_BUILTIN_RULES)
                : emptyParsed();
        DnsRuleParser.Parsed customBlock = parseText(
                SettingsStore.dnsCustomBlockText(context), MAX_USER_LIST_RULES);
        DnsRuleParser.Parsed customAllow = parseText(
                SettingsStore.dnsCustomAllowText(context), MAX_USER_LIST_RULES);

        HashSet<String> allow = new HashSet<>();
        allow.addAll(builtin.allowed);
        allow.addAll(full.allowed);
        allow.addAll(imported.allowed);
        allow.addAll(doh.allowed);
        allow.addAll(customAllow.blocked);
        allow.addAll(customAllow.allowed);
        // @@ exceptions typed into the manual blacklist editor are exception
        // rules by AdGuard semantics, not blocks: honour them as allow entries.
        allow.addAll(customBlock.allowed);

        // Parent domains already block their whole subtree, so entries whose
        // ancestor lives in the same (or a higher-priority) source are pure
        // overhead: prune them to cut the snapshot's memory and load time.
        Set<String> customSet = DnsFilterSnapshot.prune(
                customBlock.blocked, Collections.emptyList());
        Set<String> importedSet = DnsFilterSnapshot.prune(
                imported.blocked, Collections.singletonList(customSet));
        Set<String> fullSet = DnsFilterSnapshot.prune(
                full.blocked, Arrays.asList(customSet, importedSet));
        Set<String> builtinSet = DnsFilterSnapshot.prune(
                builtin.blocked, Arrays.asList(customSet, importedSet, fullSet));
        Set<String> dohSet = DnsFilterSnapshot.prune(
                doh.blocked, Arrays.asList(customSet, importedSet, fullSet, builtinSet));

        return new DnsFilterSnapshot(
                builtinSet,
                fullSet,
                importedSet,
                imported.skippedUnsupported,
                imported.malformed,
                imported.truncated,
                customSet,
                dohSet,
                allow
        );
    }

    /**
     * Reads a user supplied subscription file, persists it and returns the
     * parse result so the UI can report effective/unsupported counts.
     */
    static DnsRuleParser.Parsed importRules(Context context, InputStream input) throws IOException {
        File target = importFile(context);
        File parent = target.getParentFile();
        File temp = new File(parent, IMPORT_FILE_NAME + ".tmp");
        long copied = 0;
        try (java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                copied += read;
                if (copied > MAX_IMPORT_BYTES) {
                    throw new IOException("IMPORT_TOO_LARGE");
                }
                buffer.write(chunk, 0, read);
            }
            byte[] content = buffer.toByteArray();
            DnsRuleParser.Parsed parsed;
            try (Reader reader = new InputStreamReader(
                    new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
                parsed = DnsRuleParser.parse(reader, MAX_EXTENDED_RULES);
            }
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(content);
                output.getFD().sync();
            }
            moveOver(temp, target);
            return parsed;
        } catch (IOException | RuntimeException error) {
            if (!temp.delete()) {
                // best effort; a stale temp file is never read back
            }
            throw error;
        }
    }

    static void clearImport(Context context) {
        File target = importFile(context);
        if (target.exists() && !target.delete()) {
            target.deleteOnExit();
        }
        SettingsStore.setDnsImportMeta(context, "");
    }

    static File importFile(Context context) {
        return new File(context.getFilesDir(), IMPORT_FILE_NAME);
    }

    static String describeImport(DnsRuleParser.Parsed parsed, String displayName) {
        return displayName + IMPORT_META_SEPARATOR + parsed.ruleCount()
                + IMPORT_META_SEPARATOR + parsed.skippedUnsupported
                + IMPORT_META_SEPARATOR + parsed.malformed
                + IMPORT_META_SEPARATOR + (parsed.truncated ? 1 : 0)
                + IMPORT_META_SEPARATOR + System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- helpers

    private static DnsRuleParser.Parsed parseAsset(
            Context context,
            String name,
            int maxRules
    ) {
        try (Reader reader = new InputStreamReader(
                context.getAssets().open(name), StandardCharsets.UTF_8)) {
            return DnsRuleParser.parse(reader, maxRules);
        } catch (Throwable ignored) {
            return emptyParsed();
        }
    }

    private static DnsRuleParser.Parsed parseImportFile(Context context) {
        try (Reader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(importFile(context)), StandardCharsets.UTF_8)) {
            return DnsRuleParser.parse(reader, MAX_EXTENDED_RULES);
        } catch (Throwable ignored) {
            return emptyParsed();
        }
    }

    private static DnsRuleParser.Parsed parseText(String text, int maxRules) {
        if (text == null || text.trim().isEmpty()) {
            return emptyParsed();
        }
        try {
            return DnsRuleParser.parse(new StringReader(text), maxRules);
        } catch (Throwable ignored) {
            return emptyParsed();
        }
    }

    private static DnsRuleParser.Parsed emptyParsed() {
        return new DnsRuleParser.Parsed(
                new HashSet<>(), new HashSet<>(), 0, 0, 0, false);
    }

    private static void moveOver(File source, File target) throws IOException {
        try {
            java.nio.file.Files.move(
                    source.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            java.nio.file.Files.move(
                    source.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
