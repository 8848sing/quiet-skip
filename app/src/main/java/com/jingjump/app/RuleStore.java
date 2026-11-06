package com.jingjump.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RuleStore {
    private static final String PREFS = "jingjump_settings";
    private static final String KEY_APP_RULES = "app_rules";

    /**
     * 1.9.0 keeps the parsed rules in memory keyed by the raw preferences
     * value. Before this, every {@link #get} parsed the whole JSON document
     * again — the accessibility service calls it once per 1.2s per window and
     * the app list calls it for every row it binds, so scrolling the rule page
     * re-parsed the document hundreds of times.
     */
    private static final class Cache {
        final String raw;
        final Map<String, AppRule> rules;

        Cache(String raw, Map<String, AppRule> rules) {
            this.raw = raw;
            this.rules = rules;
        }
    }

    private static volatile Cache cache;

    private RuleStore() {
    }

    static AppRule get(Context context, String packageName) {
        String raw = prefs(context).getString(KEY_APP_RULES, "{}");
        if (raw == null) {
            raw = "{}";
        }
        Cache current = cache;
        if (current == null || !raw.equals(current.raw)) {
            current = new Cache(raw, parseAll(raw));
            cache = current;
        }
        AppRule rule = current.rules.get(packageName);
        return rule != null ? rule : defaultRule(packageName);
    }

    private static Map<String, AppRule> parseAll(String raw) {
        Map<String, AppRule> result = new HashMap<>();
        JSONObject all;
        try {
            all = new JSONObject(raw);
        } catch (JSONException ignored) {
            return result;
        }
        java.util.Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String packageName = keys.next();
            JSONObject item = all.optJSONObject(packageName);
            if (item != null) {
                result.put(packageName, parseRule(packageName, item));
            }
        }
        return result;
    }

    private static AppRule defaultRule(String packageName) {
        boolean defaultSafety = InteractionSafetyPolicy.isBilibiliPackage(packageName);
        return new AppRule(
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                defaultSafety,
                InteractionSafetyPolicy.defaultPauseMarkers(packageName),
                InteractionSafetyPolicy.defaultBlockedClickWords(packageName),
                SensitivityProfile.LEVEL_GLOBAL
        );
    }

    private static AppRule parseRule(String packageName, JSONObject item) {
        boolean defaultSafety = InteractionSafetyPolicy.isBilibiliPackage(packageName);
        boolean enabled = item.optBoolean("enabled", true);
        boolean continuousScan = item.optBoolean("continuous_scan", false);
        boolean safetyMode = item.optBoolean("safety_mode", defaultSafety);
        int sensitivity = item.optInt("sensitivity", SensitivityProfile.LEVEL_GLOBAL);
        if (sensitivity < SensitivityProfile.MIN_LEVEL
                || sensitivity > SensitivityProfile.MAX_LEVEL) {
            sensitivity = SensitivityProfile.LEVEL_GLOBAL;
        }
        JSONArray array = item.optJSONArray("keywords");
        List<String> keywords = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    keywords.add(value);
                }
            }
        }
        JSONArray selectorArray = item.optJSONArray("selectors");
        List<String> selectors = new ArrayList<>();
        if (selectorArray != null) {
            for (int i = 0; i < selectorArray.length(); i++) {
                String value = selectorArray.optString(i, "").trim();
                if (!value.isEmpty() && selectors.size() < 24) {
                    selectors.add(value);
                }
            }
        }
        List<String> pauseMarkers = item.has("pause_page_markers")
                ? stringList(item.optJSONArray("pause_page_markers"), 32)
                : new ArrayList<>(InteractionSafetyPolicy.defaultPauseMarkers(packageName));
        List<String> blockedClickWords = item.has("blocked_click_words")
                ? stringList(item.optJSONArray("blocked_click_words"), 32)
                : new ArrayList<>(InteractionSafetyPolicy.defaultBlockedClickWords(packageName));
        return new AppRule(
                enabled,
                new ArrayList<>(ButtonMatcher.sanitizeKeywords(keywords)),
                selectors,
                continuousScan,
                safetyMode,
                pauseMarkers,
                blockedClickWords,
                sensitivity
        );
    }

    static boolean isEnabled(Context context, String packageName) {
        return get(context, packageName).enabled;
    }

    static void save(
            Context context,
            String packageName,
            boolean enabled,
            List<String> keywords,
            List<String> selectors,
            boolean continuousScan,
            boolean safetyMode,
            List<String> pauseMarkers,
            List<String> blockedClickWords,
            int sensitivity
    ) {
        synchronized (RuleStore.class) {
            JSONObject all = readAll(context);
            JSONObject item = new JSONObject();
            try {
                item.put("enabled", enabled);
                item.put("continuous_scan", continuousScan && enabled);
                item.put("safety_mode", safetyMode);
                int storedSensitivity = sensitivity < SensitivityProfile.MIN_LEVEL
                        || sensitivity > SensitivityProfile.MAX_LEVEL
                        ? SensitivityProfile.LEVEL_GLOBAL
                        : sensitivity;
                item.put("sensitivity", storedSensitivity);
                JSONArray array = new JSONArray();
                for (String keyword : ButtonMatcher.sanitizeKeywords(keywords)) {
                    array.put(keyword);
                }
                item.put("keywords", array);
                JSONArray selectorArray = new JSONArray();
                for (String selector : selectors) {
                    String value = selector == null ? "" : selector.trim();
                    if (!value.isEmpty() && selectorArray.length() < 24) {
                        selectorArray.put(value);
                    }
                }
                item.put("selectors", selectorArray);
                item.put("pause_page_markers", toJsonArray(sanitizeStrings(pauseMarkers)));
                item.put("blocked_click_words", toJsonArray(
                        sanitizeStrings(blockedClickWords)
                ));
                all.put(packageName, item);
            } catch (JSONException ignored) {
                return;
            }
            prefs(context).edit().putString(KEY_APP_RULES, all.toString()).apply();
        }
    }

    private static JSONObject readAll(Context context) {
        String raw = prefs(context).getString(KEY_APP_RULES, "{}");
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> stringList(JSONArray array, int limit) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length() && values.size() < limit; i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<String> sanitizeStrings(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isEmpty() && normalized.length() <= 24 && result.size() < 32) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    static final class AppRule {
        final boolean enabled;
        final List<String> keywords;
        final List<String> selectors;
        final boolean continuousScan;
        final boolean safetyMode;
        final List<String> pausePageMarkers;
        final List<String> blockedClickWords;
        final int sensitivity;

        AppRule(
                boolean enabled,
                List<String> keywords,
                List<String> selectors,
                boolean continuousScan,
                boolean safetyMode,
                List<String> pausePageMarkers,
                List<String> blockedClickWords,
                int sensitivity
        ) {
            this.enabled = enabled;
            this.keywords = Collections.unmodifiableList(new ArrayList<>(keywords));
            this.selectors = Collections.unmodifiableList(new ArrayList<>(selectors));
            this.continuousScan = continuousScan && enabled;
            this.safetyMode = safetyMode;
            this.pausePageMarkers = Collections.unmodifiableList(
                    new ArrayList<>(pausePageMarkers)
            );
            this.blockedClickWords = Collections.unmodifiableList(
                    new ArrayList<>(blockedClickWords)
            );
            this.sensitivity = sensitivity;
        }
    }
}
