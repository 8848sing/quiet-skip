package com.jingjump.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AutoLearnStore {
    private static final String PREFS = "jingjump_settings";
    private static final String KEY_AUTO_DOMAINS = "auto_block_domains";
    private static final String KEY_CANDIDATES = "auto_block_candidates";
    private static final int MAX_DOMAINS = 600;
    private static final int MAX_CANDIDATES = 400;

    private static volatile String cachedRaw;
    private static volatile Set<String> cachedSet;

    private AutoLearnStore() {
    }

    static synchronized boolean inspectAndLearn(Context context, String rawDomain) {
        if (!SettingsStore.autoLearn(context)) {
            return false;
        }
        String domain = DnsBlockList.normalizeDomain(rawDomain);
        if (domain.isEmpty()) {
            return false;
        }
        Set<String> learned = learnedSet(context);
        if (DnsBlockList.isDomainBlocked(domain, learned)) {
            return true;
        }
        int score = AdDomainHeuristics.score(domain);
        if (score == AdDomainHeuristics.SCORE_NONE) {
            return false;
        }

        if (score == AdDomainHeuristics.SCORE_HIGH) {
            addLearnedDomain(context, learned, domain);
            return true;
        }

        JSONObject candidates = candidates(context);
        int count = candidates.optInt(domain, 0) + 1;
        if (count >= 2) {
            candidates.remove(domain);
            addLearnedDomain(context, learned, domain);
            saveCandidates(context, candidates);
            return true;
        }
        if (candidates.length() < MAX_CANDIDATES) {
            try {
                candidates.put(domain, count);
            } catch (Throwable ignored) {
                return false;
            }
        }
        saveCandidates(context, candidates);
        return false;
    }

    static synchronized boolean isLearnedBlocked(Context context, String rawDomain) {
        return DnsBlockList.isDomainBlocked(rawDomain, learnedSet(context));
    }

    static synchronized List<String> learnedDomains(Context context) {
        List<String> domains = new ArrayList<>(learnedSet(context));
        Collections.sort(domains);
        return domains;
    }

    static synchronized int learnedCount(Context context) {
        return learnedSet(context).size();
    }

    static synchronized void clear(Context context) {
        prefs(context).edit()
                .remove(KEY_AUTO_DOMAINS)
                .remove(KEY_CANDIDATES)
                .apply();
    }

    private static void addLearnedDomain(
            Context context,
            Set<String> learned,
            String domain
    ) {
        if (learned.size() >= MAX_DOMAINS && !learned.contains(domain)) {
            return;
        }
        learned.add(domain);
        prefs(context).edit()
                .putString(KEY_AUTO_DOMAINS, String.join("\n", learned))
                .apply();
    }

    private static Set<String> learnedSet(Context context) {
        String raw = prefs(context).getString(KEY_AUTO_DOMAINS, "");
        if (raw == null) {
            raw = "";
        }
        Set<String> cached = cachedSet;
        if (cached != null && raw.equals(cachedRaw)) {
            return cached;
        }
        HashSet<String> domains = new HashSet<>();
        if (!raw.isEmpty()) {
            for (String value : raw.split("\\n")) {
                String domain = DnsBlockList.normalizeDomain(value);
                if (!domain.isEmpty()) {
                    domains.add(domain);
                }
            }
        }
        cachedSet = domains;
        cachedRaw = raw;
        return domains;
    }

    private static JSONObject candidates(Context context) {
        String raw = prefs(context).getString(KEY_CANDIDATES, "{}");
        try {
            return new JSONObject(raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static void saveCandidates(Context context, JSONObject candidates) {
        prefs(context).edit().putString(KEY_CANDIDATES, candidates.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
