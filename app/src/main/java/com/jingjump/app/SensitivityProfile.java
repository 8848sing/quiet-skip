package com.jingjump.app;

import android.content.Context;

/**
 * 每个应用可单独选择的灵敏度档位。档位只影响“扫描节奏”和“无文字广告的识别力度”，
 * 不改变明确的“跳过”文字规则，也不会关闭防误触策略。
 *
 * <p>1.9.0 起四个档位是共享的不可变实例：扫描热路径里
 * {@code profileForRule()} 每个窗口、每秒数次调用，不再每次都分配对象。</p>
 */
final class SensitivityProfile {
    static final int LEVEL_GLOBAL = -1;
    static final int LEVEL_CONSERVATIVE = 0;
    static final int LEVEL_STANDARD = 1;
    static final int LEVEL_SENSITIVE = 2;
    static final int LEVEL_AGGRESSIVE = 3;

    static final int MIN_LEVEL = LEVEL_CONSERVATIVE;
    static final int MAX_LEVEL = LEVEL_AGGRESSIVE;

    final int level;
    final String label;
    final long initialScanDelayMs;
    final long followUpScanDelayMs;
    final long lateScanDelayMs;
    final long watchScanIntervalMs;
    final int maxWatchScans;
    final long minScanIntervalMs;
    final long activeWindowMs;
    final long continuousBurstMs;
    final long continuousScanIntervalMs;
    final long persistentScanIntervalMs;
    final long clickSuppressionMs;
    final int maxActionsPerWindow;
    final int maxContinuousScans;
    final int maxNodesPerScan;
    final int maxCandidates;
    final boolean structuralAdEvidence;
    final boolean unsolicitedCornerClick;
    final boolean retryAlternativeTargets;

    private static final SensitivityProfile CONSERVATIVE = new SensitivityProfile(
            0,
            "conservative",
            150L,
            620L,
            0L,
            0L,
            0,
            320L,
            8_000L,
            8_000L,
            760L,
            1_000L,
            1_000L,
            6,
            12,
            560,
            2,
            false,
            false,
            false
    );

    private static final SensitivityProfile STANDARD = new SensitivityProfile(
            1,
            "standard",
            90L,
            420L,
            0L,
            1_500L,
            3,
            200L,
            12_000L,
            12_000L,
            500L,
            650L,
            700L,
            10,
            20,
            650,
            3,
            false,
            false,
            true
    );

    private static final SensitivityProfile SENSITIVE = new SensitivityProfile(
            2,
            "sensitive",
            55L,
            240L,
            900L,
            1_200L,
            8,
            110L,
            20_000L,
            20_000L,
            380L,
            450L,
            560L,
            16,
            30,
            900,
            4,
            true,
            true,
            true
    );

    private static final SensitivityProfile AGGRESSIVE = new SensitivityProfile(
            3,
            "aggressive",
            30L,
            140L,
            600L,
            900L,
            14,
            60L,
            35_000L,
            30_000L,
            260L,
            320L,
            450L,
            24,
            45,
            1_100,
            5,
            true,
            true,
            true
    );

    private SensitivityProfile(
            int level,
            String label,
            long initialScanDelayMs,
            long followUpScanDelayMs,
            long lateScanDelayMs,
            long watchScanIntervalMs,
            int maxWatchScans,
            long minScanIntervalMs,
            long activeWindowMs,
            long continuousBurstMs,
            long continuousScanIntervalMs,
            long persistentScanIntervalMs,
            long clickSuppressionMs,
            int maxActionsPerWindow,
            int maxContinuousScans,
            int maxNodesPerScan,
            int maxCandidates,
            boolean structuralAdEvidence,
            boolean unsolicitedCornerClick,
            boolean retryAlternativeTargets
    ) {
        this.level = level;
        this.label = label;
        this.initialScanDelayMs = initialScanDelayMs;
        this.followUpScanDelayMs = followUpScanDelayMs;
        this.lateScanDelayMs = lateScanDelayMs;
        this.watchScanIntervalMs = watchScanIntervalMs;
        this.maxWatchScans = maxWatchScans;
        this.minScanIntervalMs = minScanIntervalMs;
        this.activeWindowMs = activeWindowMs;
        this.continuousBurstMs = continuousBurstMs;
        this.continuousScanIntervalMs = continuousScanIntervalMs;
        this.persistentScanIntervalMs = persistentScanIntervalMs;
        this.clickSuppressionMs = clickSuppressionMs;
        this.maxActionsPerWindow = maxActionsPerWindow;
        this.maxContinuousScans = maxContinuousScans;
        this.maxNodesPerScan = maxNodesPerScan;
        this.maxCandidates = maxCandidates;
        this.structuralAdEvidence = structuralAdEvidence;
        this.unsolicitedCornerClick = unsolicitedCornerClick;
        this.retryAlternativeTargets = retryAlternativeTargets;
    }

    static SensitivityProfile of(int level) {
        if (level == LEVEL_GLOBAL) {
            return STANDARD;
        }
        switch (clampLevel(level)) {
            case LEVEL_CONSERVATIVE:
                return CONSERVATIVE;
            case LEVEL_SENSITIVE:
                return SENSITIVE;
            case LEVEL_AGGRESSIVE:
                return AGGRESSIVE;
            case LEVEL_STANDARD:
            default:
                return STANDARD;
        }
    }

    static int clampLevel(int level) {
        if (level < MIN_LEVEL) {
            return MIN_LEVEL;
        }
        if (level > MAX_LEVEL) {
            return MAX_LEVEL;
        }
        return level;
    }

    static int resolveLevel(Context context, String packageName) {
        RuleStore.AppRule rule = RuleStore.get(context, packageName);
        int level = rule.sensitivity;
        if (level == LEVEL_GLOBAL || level < MIN_LEVEL || level > MAX_LEVEL) {
            return SettingsStore.sensitivity(context);
        }
        return level;
    }

    static SensitivityProfile resolve(Context context, String packageName) {
        return of(resolveLevel(context, packageName));
    }
}