package com.jingjump.app;

import android.content.Context;
import android.content.SharedPreferences;

final class SettingsStore {
    static final int DNS_STATE_IDLE = 0;
    static final int DNS_STATE_STARTING = 1;
    static final int DNS_STATE_RUNNING = 2;
    static final int DNS_STATE_ERROR = 3;

    private static final String PREFS = "jingjump_settings";
    private static final String KEY_AUTO_CLOSE = "auto_close";
    private static final String KEY_CORNER_FALLBACK = "corner_fallback";
    private static final String KEY_SENSITIVITY = "default_sensitivity";
    private static final String KEY_SKIP_COUNT = "skip_count";
    private static final String KEY_LAST_ACTION = "last_action";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_DNS_BLOCK_COUNT = "dns_block_count";
    private static final String KEY_AUTO_LEARN = "auto_learn_domains";
    private static final String KEY_DNS_STATE = "dns_runtime_state";
    private static final String KEY_DNS_STATE_TIME = "dns_runtime_state_time";
    private static final String KEY_DNS_BLOCK_MODE = "dns_block_mode";
    private static final String KEY_DNS_STRICT = "dns_strict_mode";
    private static final String KEY_DNS_FULL_LIST = "dns_full_list";
    private static final String KEY_DNS_CNAME_GUARD = "dns_cname_guard";
    private static final String KEY_DNS_CUSTOM_BLOCK = "dns_custom_block";
    private static final String KEY_DNS_CUSTOM_ALLOW = "dns_custom_allow";
    private static final String KEY_DNS_IMPORT_META = "dns_import_meta";

    private static final Object COUNTER_LOCK = new Object();
    private static int unsavedDnsBlocks;
    private static long lastDnsBlockFlushAt;

    private SettingsStore() {
    }

    static boolean autoClose(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CLOSE, true);
    }

    static void setAutoClose(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CLOSE, enabled).apply();
    }

    static boolean cornerFallback(Context context) {
        return prefs(context).getBoolean(KEY_CORNER_FALLBACK, false);
    }

    static void setCornerFallback(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CORNER_FALLBACK, enabled).apply();
    }

    static int sensitivity(Context context) {
        int value = prefs(context).getInt(
                KEY_SENSITIVITY,
                SensitivityProfile.LEVEL_STANDARD
        );
        return SensitivityProfile.clampLevel(value);
    }

    static void setSensitivity(Context context, int level) {
        prefs(context).edit()
                .putInt(KEY_SENSITIVITY, SensitivityProfile.clampLevel(level))
                .apply();
    }

    static int skipCount(Context context) {
        return prefs(context).getInt(KEY_SKIP_COUNT, 0);
    }

    static String lastAction(Context context) {
        return prefs(context).getString(KEY_LAST_ACTION, "");
    }

    static long lastTime(Context context) {
        return prefs(context).getLong(KEY_LAST_TIME, 0L);
    }

    static int dnsBlockCount(Context context) {
        synchronized (COUNTER_LOCK) {
            return prefs(context).getInt(KEY_DNS_BLOCK_COUNT, 0) + unsavedDnsBlocks;
        }
    }

    static boolean autoLearn(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_LEARN, true);
    }

    static void setAutoLearn(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_LEARN, enabled).apply();
    }

    static int dnsState(Context context) {
        return prefs(context).getInt(KEY_DNS_STATE, DNS_STATE_IDLE);
    }

    static void setDnsState(Context context, int state) {
        prefs(context).edit()
                .putInt(KEY_DNS_STATE, state)
                .putLong(KEY_DNS_STATE_TIME, System.currentTimeMillis())
                .apply();
    }

    static long dnsStateChangedAt(Context context) {
        return prefs(context).getLong(KEY_DNS_STATE_TIME, 0L);
    }

    /** 0 = NXDOMAIN, 1 = sinkhole address. Sinkhole is the v1.8 default. */
    static int dnsBlockMode(Context context) {
        int value = prefs(context).getInt(KEY_DNS_BLOCK_MODE, DnsPacket.BLOCK_MODE_SINKHOLE);
        return Math.max(0, Math.min(1, value));
    }

    static void setDnsBlockMode(Context context, int mode) {
        prefs(context).edit()
                .putInt(KEY_DNS_BLOCK_MODE, Math.max(0, Math.min(1, mode)))
                .apply();
    }

    static boolean dnsStrictMode(Context context) {
        return prefs(context).getBoolean(KEY_DNS_STRICT, false);
    }

    static void setDnsStrictMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DNS_STRICT, enabled).apply();
    }

    static boolean dnsFullList(Context context) {
        return prefs(context).getBoolean(KEY_DNS_FULL_LIST, true);
    }

    static void setDnsFullList(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DNS_FULL_LIST, enabled).apply();
    }

    static boolean dnsCnameGuard(Context context) {
        return prefs(context).getBoolean(KEY_DNS_CNAME_GUARD, true);
    }

    static void setDnsCnameGuard(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DNS_CNAME_GUARD, enabled).apply();
    }

    static String dnsCustomBlockText(Context context) {
        return prefs(context).getString(KEY_DNS_CUSTOM_BLOCK, "");
    }

    static void setDnsCustomBlockText(Context context, String value) {
        prefs(context).edit().putString(KEY_DNS_CUSTOM_BLOCK, clampListText(value)).apply();
    }

    static String dnsCustomAllowText(Context context) {
        return prefs(context).getString(KEY_DNS_CUSTOM_ALLOW, "");
    }

    static void setDnsCustomAllowText(Context context, String value) {
        prefs(context).edit().putString(KEY_DNS_CUSTOM_ALLOW, clampListText(value)).apply();
    }

    static String dnsImportMeta(Context context) {
        return prefs(context).getString(KEY_DNS_IMPORT_META, "");
    }

    static void setDnsImportMeta(Context context, String value) {
        prefs(context).edit().putString(KEY_DNS_IMPORT_META, clampListText(value)).apply();
    }

    private static String clampListText(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 64_000 ? value.substring(0, 64_000) : value;
    }

    static synchronized void recordSkip(Context context, String packageName) {
        SharedPreferences preferences = prefs(context);
        int count = preferences.getInt(KEY_SKIP_COUNT, 0) + 1;
        preferences.edit()
                .putInt(KEY_SKIP_COUNT, count)
                .putString(KEY_LAST_ACTION, context.getString(R.string.stats_last_action_value, packageName))
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .apply();
    }

    /**
     * 1.9.0 batches this counter in memory: it used to do a preferences read
     * plus a full-file write for every single blocked query, which is exactly
     * the situation an ad SDK retry storm creates. The counter is flushed at
     * most once per second (or every 25 blocks) and on service shutdown.
     */
    static int recordDnsBlock(Context context) {
        synchronized (COUNTER_LOCK) {
            unsavedDnsBlocks++;
            long now = System.currentTimeMillis();
            int total = prefs(context).getInt(KEY_DNS_BLOCK_COUNT, 0) + unsavedDnsBlocks;
            if (unsavedDnsBlocks >= 25 || now - lastDnsBlockFlushAt >= 1_000L) {
                flushDnsBlocksLocked(context);
            }
            return total;
        }
    }

    /** Writes any pending block count to disk (service shutdown, app pause). */
    static void flushDnsBlockCount(Context context) {
        synchronized (COUNTER_LOCK) {
            flushDnsBlocksLocked(context);
        }
    }

    private static void flushDnsBlocksLocked(Context context) {
        if (unsavedDnsBlocks == 0) {
            return;
        }
        SharedPreferences preferences = prefs(context);
        int total = preferences.getInt(KEY_DNS_BLOCK_COUNT, 0) + unsavedDnsBlocks;
        unsavedDnsBlocks = 0;
        lastDnsBlockFlushAt = System.currentTimeMillis();
        preferences.edit().putInt(KEY_DNS_BLOCK_COUNT, total).apply();
    }

    static void resetStats(Context context) {
        synchronized (COUNTER_LOCK) {
            unsavedDnsBlocks = 0;
        }
        prefs(context).edit()
                .remove(KEY_SKIP_COUNT)
                .remove(KEY_LAST_ACTION)
                .remove(KEY_LAST_TIME)
                .remove(KEY_DNS_BLOCK_COUNT)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
