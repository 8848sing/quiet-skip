package com.jingjump.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AdSkipAccessibilityService extends AccessibilityService {
    private static final long RULE_CACHE_MS = 1_200L;
    private static final long DUPLICATE_CLICK_WINDOW_MS = 1_600L;
    private static final float DUPLICATE_CLICK_RADIUS_PX = 28f;
    private static final List<String> NO_KEYWORDS = Collections.emptyList();

    private static final Set<String> BLOCKED_SYSTEM_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "android",
                    "com.android.systemui",
                    "com.android.settings",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller"
            ))
    );

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ContinuousScanModule persistentScanModule = new ContinuousScanModule(
            mainHandler,
            SensitivityProfile.of(SensitivityProfile.LEVEL_STANDARD).persistentScanIntervalMs,
            new ContinuousScanModule.Callback() {
                @Override
                public boolean shouldContinue() {
                    return isPersistentScanAllowed();
                }

                @Override
                public void scan() {
                    if (!isScreenInteractive()) {
                        return;
                    }
                    activeUntil = Math.max(
                            activeUntil,
                            SystemClock.uptimeMillis() + profile.activeWindowMs
                    );
                    scanNow(false);
                }
            }
    );
    private final Runnable initialScan = new Runnable() {
        @Override
        public void run() {
            initialScanScheduled = false;
            scanNow(false);
        }
    };
    private final Runnable followUpScan = new Runnable() {
        @Override
        public void run() {
            followUpScheduled = false;
            scanNow(false);
        }
    };
    private final Runnable lateScan = new Runnable() {
        @Override
        public void run() {
            lateScanScheduled = false;
            scanNow(false);
        }
    };
    private final Runnable continuousScan = new Runnable() {
        @Override
        public void run() {
            continuousScanScheduled = false;
            long now = SystemClock.uptimeMillis();
            if (now > continuousBurstUntil || continuousScansRemaining <= 0) {
                return;
            }
            scanNow(false);
            continuousScansRemaining--;
            if (!continuousScanScheduled
                    && SystemClock.uptimeMillis() <= continuousBurstUntil
                    && continuousScansRemaining > 0) {
                continuousScanScheduled = true;
                mainHandler.postDelayed(
                        continuousScan,
                        Math.max(120L, profile.continuousScanIntervalMs)
                );
            }
        }
    };
    private final Runnable watchScan = new Runnable() {
        @Override
        public void run() {
            watchScanScheduled = false;
            if (watchScansRemaining <= 0 || !isPackageEligible(currentPackage)) {
                watchScansRemaining = 0;
                return;
            }
            watchScansRemaining--;
            if (!isScreenInteractive()) {
                return;
            }
            activeUntil = Math.max(
                    activeUntil,
                    SystemClock.uptimeMillis() + profile.activeWindowMs
            );
            scanNow(false);
            if (watchScansRemaining > 0 && profile.watchScanIntervalMs > 0L) {
                watchScanScheduled = true;
                mainHandler.postDelayed(watchScan, profile.watchScanIntervalMs);
            }
        }
    };

    private String currentPackage = "";
    private String currentClass = "";
    private long activeUntil;
    private long suppressUntil;
    private long lastScanAt;
    private final ScanActionBudget actionBudget = new ScanActionBudget(
            SensitivityProfile.of(SensitivityProfile.LEVEL_STANDARD).activeWindowMs,
            SensitivityProfile.of(SensitivityProfile.LEVEL_STANDARD).maxActionsPerWindow
    );
    private SensitivityProfile profile =
            SensitivityProfile.of(SensitivityProfile.LEVEL_STANDARD);
    private RuleStore.AppRule cachedRule;
    private String cachedRulePackage = "";
    private long cachedRuleAt;
    private long lastScrollTriggerAt;

    private boolean initialScanScheduled;
    private boolean followUpScheduled;
    private boolean lateScanScheduled;
    private boolean continuousScanScheduled;
    private long continuousBurstUntil;
    private int continuousScansRemaining;
    private boolean watchScanScheduled;
    private int watchScansRemaining;

    private String evidencePackage = "";
    private long evidenceUntil;
    private boolean strongEvidenceCached;

    private float lastClickX = Float.NaN;
    private float lastClickY = Float.NaN;
    private long lastClickAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
            info.notificationTimeout = 60L;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }
        CharSequence packageValue = event.getPackageName();
        if (packageValue == null) {
            return;
        }
        String packageName = packageValue.toString();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !isPackageEligible(packageName)) {
            resetScanSession("");
            return;
        }
        if (!isPackageEligible(packageName)) {
            return;
        }

        boolean packageChanged = !packageName.equals(currentPackage);
        if (packageChanged) {
            resetScanSession(packageName);
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            currentClass = className == null ? "" : className.toString();
            actionBudget.startNewWindow(SystemClock.uptimeMillis());
            suppressUntil = 0L;
            refreshProfile(packageName, true);
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (profile.level < SensitivityProfile.LEVEL_SENSITIVE) {
                // 低档位不跟随滚动事件，避免频繁遍历界面。
                return;
            }
            long scrollNow = SystemClock.uptimeMillis();
            if (scrollNow - lastScrollTriggerAt < 350L) {
                return;
            }
            lastScrollTriggerAt = scrollNow;
        }
        activeUntil = SystemClock.uptimeMillis() + profile.activeWindowMs;
        queueScans();
        syncPersistentScan();
    }

    private void resetScanSession(String packageName) {
        persistentScanModule.stop();
        mainHandler.removeCallbacks(initialScan);
        mainHandler.removeCallbacks(followUpScan);
        mainHandler.removeCallbacks(lateScan);
        mainHandler.removeCallbacks(watchScan);
        mainHandler.removeCallbacks(continuousScan);
        currentPackage = packageName;
        currentClass = "";
        activeUntil = 0L;
        suppressUntil = 0L;
        lastScanAt = 0L;
        actionBudget.reset();
        initialScanScheduled = false;
        followUpScheduled = false;
        lateScanScheduled = false;
        continuousScanScheduled = false;
        continuousScansRemaining = 0;
        watchScanScheduled = false;
        watchScansRemaining = 0;
        evidencePackage = "";
        evidenceUntil = 0L;
        strongEvidenceCached = false;
        lastClickX = Float.NaN;
        lastClickY = Float.NaN;
        lastClickAt = 0L;
        cachedRule = null;
        cachedRulePackage = "";
        cachedRuleAt = 0L;
        refreshProfile(packageName, true);
    }

    private void refreshProfile(String packageName, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force
                && packageName.equals(cachedRulePackage)
                && cachedRule != null
                && now - cachedRuleAt < RULE_CACHE_MS) {
            return;
        }
        RuleStore.AppRule rule = loadRule(packageName, now);
        int level = rule.sensitivity;
        if (level == SensitivityProfile.LEVEL_GLOBAL) {
            level = SettingsStore.sensitivity(this);
        }
        profile = SensitivityProfile.of(level);
        actionBudget.configure(profile.activeWindowMs, profile.maxActionsPerWindow);
        persistentScanModule.setIntervalMs(profile.persistentScanIntervalMs);
    }

    private RuleStore.AppRule loadRule(String packageName, long now) {
        if (packageName.equals(cachedRulePackage)
                && cachedRule != null
                && now - cachedRuleAt < RULE_CACHE_MS) {
            return cachedRule;
        }
        cachedRule = RuleStore.get(this, packageName);
        cachedRulePackage = packageName;
        cachedRuleAt = now;
        return cachedRule;
    }

    private RuleStore.AppRule ruleFor(String packageName) {
        return loadRule(packageName, SystemClock.uptimeMillis());
    }

    private boolean isPersistentScanAllowed() {
        if (!isPackageEligible(currentPackage)) {
            return false;
        }
        RuleStore.AppRule rule = ruleFor(currentPackage);
        return rule.enabled && rule.continuousScan;
    }

    private void syncPersistentScan() {
        if (isPersistentScanAllowed()) {
            persistentScanModule.setIntervalMs(profile.persistentScanIntervalMs);
            persistentScanModule.start();
        } else {
            persistentScanModule.stop();
        }
    }

    private void queueScans() {
        if (!initialScanScheduled) {
            initialScanScheduled = true;
            mainHandler.postDelayed(initialScan, Math.max(20L, profile.initialScanDelayMs));
        }
        if (!followUpScheduled) {
            followUpScheduled = true;
            mainHandler.postDelayed(followUpScan, Math.max(80L, profile.followUpScanDelayMs));
        }
        if (profile.lateScanDelayMs > 0L && !lateScanScheduled) {
            lateScanScheduled = true;
            mainHandler.postDelayed(lateScan, profile.lateScanDelayMs);
        }
        if (profile.watchScanIntervalMs > 0L
                && profile.maxWatchScans > 0
                && !watchScanScheduled
                && watchScansRemaining <= 0) {
            watchScansRemaining = profile.maxWatchScans;
            watchScanScheduled = true;
            mainHandler.postDelayed(watchScan, profile.watchScanIntervalMs);
        }
    }

    private void scanNow(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force) {
            if (now > activeUntil
                    || now < suppressUntil
                    || !actionBudget.hasCapacity(now)) {
                return;
            }
            if (lastScanAt != 0L && now - lastScanAt < profile.minScanIntervalMs) {
                return;
            }
        }
        if (!isScreenInteractive()
                || !isPackageEligible(currentPackage)
                || !ruleFor(currentPackage).enabled) {
            return;
        }
        lastScanAt = now;
        refreshProfile(currentPackage, false);

        List<AccessibilityNodeInfo> roots = collectRoots();
        for (AccessibilityNodeInfo root : roots) {
            String rootPackage = packageOf(root);
            if (rootPackage.isEmpty()) {
                rootPackage = currentPackage;
            }
            if (!isPackageEligible(rootPackage)
                    || !rootPackage.equals(currentPackage)) {
                continue;
            }
            RuleStore.AppRule rule = ruleFor(rootPackage);
            if (!rule.enabled) {
                continue;
            }
            SensitivityProfile rootProfile = profileForRule(rule);
            // Each root is inspected independently: a vendor WebView that throws from
            // the accessibility API must never abort the whole scan.
            ScanResult result;
            try {
                result = inspectRoot(root, rootPackage, rule, rootProfile);
            } catch (Throwable ignored) {
                continue;
            }
            if (result.candidates.isEmpty()) {
                continue;
            }
            if (tryCandidates(result.candidates, rootProfile)) {
                long actionTime = SystemClock.uptimeMillis();
                actionBudget.recordAction(actionTime);
                suppressUntil = actionTime + rootProfile.clickSuppressionMs;
                activeUntil = Math.max(activeUntil, actionTime + rootProfile.activeWindowMs);
                startContinuousBurst(actionTime);
                SettingsStore.recordSkip(this, rootPackage);
                return;
            }
            suppressUntil = Math.max(
                    suppressUntil,
                    SystemClock.uptimeMillis() + Math.min(900L, rootProfile.clickSuppressionMs)
            );
        }
    }

    private SensitivityProfile profileForRule(RuleStore.AppRule rule) {
        int level = rule.sensitivity;
        if (level == SensitivityProfile.LEVEL_GLOBAL) {
            level = SettingsStore.sensitivity(this);
        }
        if (level == profile.level) {
            return profile;
        }
        return SensitivityProfile.of(level);
    }

    private void startContinuousBurst(long now) {
        continuousBurstUntil = now + profile.continuousBurstMs;
        continuousScansRemaining = profile.maxContinuousScans;
        if (!continuousScanScheduled) {
            continuousScanScheduled = true;
            mainHandler.postDelayed(
                    continuousScan,
                    Math.max(120L, profile.continuousScanIntervalMs)
            );
        }
    }

    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            roots.add(activeRoot);
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) {
                        continue;
                    }
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && !roots.contains(root)) {
                        roots.add(root);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Some vendor builds restrict window enumeration; the active root still works.
        }
        return roots;
    }

    private ScanResult inspectRoot(
            AccessibilityNodeInfo root,
            String packageName,
            RuleStore.AppRule rule,
            SensitivityProfile activeProfile
    ) {
        Set<String> customKeywords = ButtonMatcher.sanitizeKeywords(rule.keywords);
        List<String> customSelectors = rule.selectors;
        List<String> pauseMarkers = ButtonMatcher.compactMarkers(rule.pausePageMarkers);
        List<String> blockedClickWords = ButtonMatcher.compactMarkers(rule.blockedClickWords);
        String compactClass = ButtonMatcher.compact(currentClass);
        boolean splashClass = ButtonMatcher.looksLikeSplashClassCompact(compactClass);
        List<NodeEntry> entries = new ArrayList<>();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        long screenArea = Math.max(1L, (long) screenWidth * screenHeight);

        boolean adEvidence = splashClass;
        boolean strongDialogEvidence = false;
        boolean safetyMode = rule.safetyMode;
        boolean overlayWindow = isOverlayWindow(root);
        boolean protectedPage = safetyMode
                && (InteractionSafetyPolicy.isCommentSensitiveContextCompact(
                packageName,
                compactClass
        ) || InteractionSafetyPolicy.matchesCompactText(
                compactClass,
                pauseMarkers
        ));
        boolean adContainerPresent = false;
        boolean largeMediaPresent = false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < activeProfile.maxNodesPerScan) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null) {
                continue;
            }
            visited++;
            String rawLabel = labelOf(node);
            String compactLabel = ButtonMatcher.compact(rawLabel);
            String normalizedId = ButtonMatcher.normalizeId(viewIdOf(node));
            Rect bounds = boundsOf(node);
            boolean visible = isUsable(node);
            String nodeClass = classOf(node);
            String compactNodeClass = ButtonMatcher.compact(nodeClass);
            // Only visible, positively sized nodes can ever become a candidate or
            // corroborate a corner icon, so invisible wrappers are no longer kept
            // alive for the whole scan.
            if (visible && bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                entries.add(new NodeEntry(
                        node,
                        rawLabel,
                        compactLabel,
                        normalizedId,
                        bounds,
                        true,
                        nodeClass,
                        compactNodeClass
                ));
            }
            if (!compactLabel.isEmpty()
                    && ButtonMatcher.isAdEvidenceLabelCompact(compactLabel)) {
                adEvidence = true;
            }
            if (!compactLabel.isEmpty()
                    && ButtonMatcher.isStrongAdDialogLabelCompact(compactLabel)) {
                strongDialogEvidence = true;
            }
            if (ButtonMatcher.looksLikeAdContainerIdNormalized(normalizedId)) {
                adContainerPresent = true;
            }
            if (visible
                    && bounds != null
                    && ButtonMatcher.looksLikeAdMediaClassCompact(compactNodeClass)
                    && (long) bounds.width() * bounds.height() >= screenArea * 25L / 100L) {
                largeMediaPresent = true;
            }
            if (!compactLabel.isEmpty()
                    && safetyMode
                    && (InteractionSafetyPolicy.looksLikeCommentUiCompact(compactLabel)
                    || InteractionSafetyPolicy.matchesCompactText(
                    compactLabel,
                    pauseMarkers
            ))) {
                protectedPage = true;
            }
            try {
                int childCount = node.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                }
            } catch (Throwable ignored) {
                // Continue with the nodes that are available.
            }
        }

        long evidenceNow = SystemClock.uptimeMillis();
        if (!packageName.equals(evidencePackage)) {
            strongEvidenceCached = false;
        }
        boolean freshStrongEvidence = strongDialogEvidence;
        boolean freshEvidence = adEvidence || (activeProfile.structuralAdEvidence
                && (adContainerPresent
                || (largeMediaPresent
                && visited <= 160
                && hasCornerIconOnly(entries, screenWidth, screenHeight)
                && (overlayWindow || splashClass))));
        boolean cachedEvidence = packageName.equals(evidencePackage) && evidenceNow < evidenceUntil;
        adEvidence = freshEvidence || cachedEvidence;
        strongDialogEvidence = strongDialogEvidence || (cachedEvidence && strongEvidenceCached);
        // Only genuinely observed evidence may extend the window. 1.9.0 fixed
        // the old code path where cached evidence re-armed itself every scan, so
        // one stray "广告" label kept the clicker armed for as long as the app
        // stayed in the foreground.
        if (freshStrongEvidence) {
            evidencePackage = packageName;
            evidenceUntil = evidenceNow + 8_000L;
            strongEvidenceCached = true;
        } else if (freshEvidence && !(cachedEvidence && strongEvidenceCached)) {
            evidencePackage = packageName;
            evidenceUntil = evidenceNow + 5_000L;
            strongEvidenceCached = false;
        }

        if (protectedPage) {
            return new ScanResult();
        }

        ScanResult result = new ScanResult();
        result.adEvidence = adEvidence;
        boolean closeEnabled = SettingsStore.autoClose(this) && !safetyMode;
        boolean cornerEnabled = SettingsStore.cornerFallback(this) && !safetyMode;
        int maxCandidates = activeProfile.maxCandidates;

        AccessibilityNodeInfo selectorHit = GkdRuleSet.findFirst(
                this,
                root,
                packageName,
                customSelectors
        );
        if (selectorHit != null && isUsable(selectorHit)) {
            Rect selectorBounds = boundsOf(selectorHit);
            String selectorLabel = labelOf(selectorHit);
            String selectorCompact = ButtonMatcher.compact(selectorLabel);
            String selectorId = ButtonMatcher.normalizeId(viewIdOf(selectorHit));
            boolean closeStyle = ButtonMatcher.isCloseLabelCompact(selectorCompact, true)
                    || ButtonMatcher.looksLikeCloseIdNormalized(selectorId)
                    || selectorCompact.contains("关闭");
            boolean iconOnly = selectorCompact.isEmpty()
                    && !ButtonMatcher.looksLikeSkipIdNormalized(selectorId)
                    && !ButtonMatcher.looksLikeCloseIdNormalized(selectorId);
            boolean selectorSkipStyle = ButtonMatcher.isStrongSkipLabelCompact(
                    selectorCompact,
                    customKeywords
            ) || ButtonMatcher.looksLikeSkipIdNormalized(selectorId);
            boolean selectorBlocked = safetyMode
                    && InteractionSafetyPolicy.matchesCompactText(
                    selectorCompact,
                    blockedClickWords
            );
            boolean safeRestrictedSkip = !safetyMode
                    || (selectorSkipStyle
                    && isTopOrBottomCorner(selectorBounds, screenWidth, screenHeight)
                    && isClickableInAncestors(selectorHit)
                    && !selectorBlocked);
            if ((!closeStyle || closeEnabled)
                    && (!iconOnly || !strongDialogEvidence
                    || isAdCloseIconControl(selectorBounds, screenWidth, screenHeight))
                    && safeRestrictedSkip
                    && isReasonableControl(selectorBounds, screenWidth, screenHeight)) {
                String selectorClass = classOf(selectorHit);
                consider(
                        result,
                        new NodeEntry(
                                selectorHit,
                                selectorLabel,
                                selectorCompact,
                                selectorId,
                                selectorBounds,
                                true,
                                selectorClass,
                                ButtonMatcher.compact(selectorClass)
                        ),
                        96,
                        "gkd",
                        maxCandidates
                );
            }
        }

        for (NodeEntry entry : entries) {
            if (!entry.visible || !isReasonableControl(entry.bounds, screenWidth, screenHeight)) {
                continue;
            }
            if (safetyMode && InteractionSafetyPolicy.matchesCompactText(
                    entry.compactLabel,
                    blockedClickWords
            )) {
                continue;
            }

            boolean restrictedSkipPositionSafe = !safetyMode
                    || (isTopOrBottomCorner(entry.bounds, screenWidth, screenHeight)
                    && isClickableInAncestors(entry.node));
            if (ButtonMatcher.isStrongSkipLabelCompact(entry.compactLabel, NO_KEYWORDS)
                    && restrictedSkipPositionSafe) {
                consider(result, entry, 100, "text", maxCandidates);
            } else if (ButtonMatcher.looksLikeSkipIdNormalized(entry.normalizedId)
                    && restrictedSkipPositionSafe) {
                consider(result, entry, 92, "id", maxCandidates);
            } else if ((adEvidence || splashClass)
                    && ButtonMatcher.isEvidenceSkipLabelCompact(entry.compactLabel, customKeywords)
                    && restrictedSkipPositionSafe) {
                consider(result, entry, 88, "custom", maxCandidates);
            } else if (closeEnabled
                    && (adEvidence || splashClass)
                    && isCustomCloseWord(entry.compactLabel, customKeywords)) {
                consider(result, entry, 82, "custom-close", maxCandidates);
            }

            if (closeEnabled && strongDialogEvidence) {
                if (ButtonMatcher.isCloseLabelCompact(entry.compactLabel, true)) {
                    consider(result, entry, 76, "close", maxCandidates);
                } else if (ButtonMatcher.looksLikeCloseIdNormalized(entry.normalizedId)
                        && isTopOrBottomCorner(entry.bounds, screenWidth, screenHeight)) {
                    consider(result, entry, 70, "close-id", maxCandidates);
                } else if (ButtonMatcher.looksLikeLooseCloseIdNormalized(entry.normalizedId)
                        && isSmallCornerIcon(entry, screenWidth, screenHeight)
                        && isClickableInAncestors(entry.node)) {
                    consider(result, entry, 66, "close-id-loose", maxCandidates);
                } else if (isAdCloseIconControl(entry.bounds, screenWidth, screenHeight)
                        && isClickableInAncestors(entry.node)) {
                    consider(result, entry, 58, "ad-close-icon", maxCandidates);
                }
            } else if (closeEnabled && adEvidence) {
                if (ButtonMatcher.isCloseLabelCompact(entry.compactLabel, true)) {
                    consider(result, entry, 76, "close", maxCandidates);
                } else if (ButtonMatcher.looksLikeCloseIdNormalized(entry.normalizedId)
                        && isTopOrBottomCorner(entry.bounds, screenWidth, screenHeight)) {
                    consider(result, entry, 70, "close-id", maxCandidates);
                } else if (ButtonMatcher.looksLikeLooseCloseIdNormalized(entry.normalizedId)
                        && isSmallCornerIcon(entry, screenWidth, screenHeight)
                        && isClickableInAncestors(entry.node)) {
                    consider(result, entry, 64, "close-id-loose", maxCandidates);
                } else if (isAdCloseIconControl(entry.bounds, screenWidth, screenHeight)
                        && isClickableInAncestors(entry.node)) {
                    consider(result, entry, 62, "ad-close-icon", maxCandidates);
                }
            }

            if (!safetyMode
                    && (cornerEnabled || activeProfile.unsolicitedCornerClick)
                    && adEvidence
                    && isCornerControl(entry, screenWidth, screenHeight)) {
                consider(result, entry, 40, "corner", maxCandidates);
            }
        }

        if (result.candidates.isEmpty() && adEvidence && closeEnabled) {
            // 最后一轮保守补漏：只接受角落、小尺寸、可点击的明确关闭 ID。
            for (NodeEntry entry : entries) {
                if (entry.visible
                        && isReasonableControl(entry.bounds, screenWidth, screenHeight)
                        && ButtonMatcher.looksLikeLooseCloseIdNormalized(entry.normalizedId)
                        && isSmallCornerIcon(entry, screenWidth, screenHeight)) {
                    consider(result, entry, 60, "close-id-fallback", maxCandidates);
                }
            }
        }
        return result;
    }

    private static boolean isCustomCloseWord(String compactLabel, Collection<String> sanitizedKeywords) {
        if (compactLabel.isEmpty()) {
            return false;
        }
        for (String keyword : sanitizedKeywords) {
            if (compactLabel.equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void consider(
            ScanResult result,
            NodeEntry entry,
            int score,
            String source,
            int maxCandidates
    ) {
        if (result.candidates.size() >= maxCandidates) {
            Candidate weakest = result.candidates.get(result.candidates.size() - 1);
            if (score <= weakest.score) {
                return;
            }
        }
        Candidate candidate = new Candidate(entry.node, entry.bounds, score, source);
        int index = 0;
        while (index < result.candidates.size()
                && result.candidates.get(index).score >= score) {
            index++;
        }
        result.candidates.add(index, candidate);
        while (result.candidates.size() > maxCandidates) {
            result.candidates.remove(result.candidates.size() - 1);
        }
    }

    private boolean tryCandidates(List<Candidate> candidates, SensitivityProfile activeProfile) {
        long now = SystemClock.uptimeMillis();
        int limit = activeProfile.retryAlternativeTargets
                ? candidates.size()
                : Math.min(1, candidates.size());
        for (int index = 0; index < limit; index++) {
            Candidate candidate = candidates.get(index);
            if (candidate.bounds == null) {
                continue;
            }
            if (isRecentlyClicked(candidate.bounds, now)) {
                continue;
            }
            if (clickCandidate(candidate)) {
                lastClickX = candidate.bounds.exactCenterX();
                lastClickY = candidate.bounds.exactCenterY();
                lastClickAt = now;
                return true;
            }
        }
        return false;
    }

    private boolean isRecentlyClicked(Rect bounds, long now) {
        if (lastClickAt == 0L || now - lastClickAt > DUPLICATE_CLICK_WINDOW_MS) {
            return false;
        }
        float dx = bounds.exactCenterX() - lastClickX;
        float dy = bounds.exactCenterY() - lastClickY;
        return dx * dx + dy * dy <= DUPLICATE_CLICK_RADIUS_PX * DUPLICATE_CLICK_RADIUS_PX;
    }

    private boolean isReasonableControl(Rect bounds, int screenWidth, int screenHeight) {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0
                || bounds.left >= screenWidth || bounds.top >= screenHeight
                || bounds.right <= 0 || bounds.bottom <= 0) {
            return false;
        }
        long area = (long) bounds.width() * bounds.height();
        long screenArea = (long) screenWidth * screenHeight;
        return bounds.width() <= screenWidth * 0.62f
                && bounds.height() <= screenHeight * 0.22f
                && area <= screenArea * 0.11f;
    }

    private static boolean isTopOrBottomCorner(Rect bounds, int screenWidth, int screenHeight) {
        if (bounds == null) {
            return false;
        }
        float centerX = bounds.exactCenterX();
        float centerY = bounds.exactCenterY();
        return centerX >= screenWidth * 0.55f
                && (centerY <= screenHeight * 0.32f || centerY >= screenHeight * 0.68f);
    }

    private boolean isCornerControl(NodeEntry entry, int screenWidth, int screenHeight) {
        if (!isTopOrBottomCorner(entry.bounds, screenWidth, screenHeight)) {
            return false;
        }
        float centerX = entry.bounds.exactCenterX();
        float centerY = entry.bounds.exactCenterY();
        boolean topRight = centerX >= screenWidth * 0.72f && centerY <= screenHeight * 0.16f;
        boolean bottomRight = centerX >= screenWidth * 0.70f && centerY >= screenHeight * 0.76f;
        int maxSide = dp(88);
        return (topRight || bottomRight)
                && entry.bounds.width() <= maxSide
                && entry.bounds.height() <= maxSide
                && isClickableInAncestors(entry.node);
    }

    private boolean isSmallCornerIcon(NodeEntry entry, int screenWidth, int screenHeight) {
        return entry.bounds != null
                && entry.bounds.width() <= dp(88)
                && entry.bounds.height() <= dp(88)
                && entry.compactLabel.isEmpty()
                && isAdCloseIconControl(entry.bounds, screenWidth, screenHeight)
                && isClickableInAncestors(entry.node);
    }

    private boolean hasCornerIconOnly(
            List<NodeEntry> entries,
            int screenWidth,
            int screenHeight
    ) {
        for (NodeEntry entry : entries) {
            if (entry.visible && isLikelyCornerClose(entry, screenWidth, screenHeight)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyCornerClose(
            NodeEntry entry,
            int screenWidth,
            int screenHeight
    ) {
        if (entry.bounds == null
                || entry.bounds.width() > dp(88)
                || entry.bounds.height() > dp(88)
                || !isAdCloseIconControl(entry.bounds, screenWidth, screenHeight)
                || !isClickableInAncestors(entry.node)) {
            return false;
        }
        String label = entry.compactLabel;
        return label.isEmpty()
                || ButtonMatcher.isCloseLabelCompact(label, true)
                || "×".equals(label)
                || "✕".equals(label)
                || "x".equals(label);
    }

    private boolean isAdCloseIconControl(Rect bounds, int screenWidth, int screenHeight) {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        float centerX = bounds.exactCenterX();
        float centerY = bounds.exactCenterY();
        boolean small = bounds.width() <= dp(82) && bounds.height() <= dp(82);
        boolean topLeft = centerX >= screenWidth * 0.03f
                && centerX <= screenWidth * 0.32f
                && centerY >= screenHeight * 0.03f
                && centerY <= screenHeight * 0.34f;
        boolean topRight = centerX >= screenWidth * 0.68f
                && centerX <= screenWidth * 0.97f
                && centerY >= screenHeight * 0.03f
                && centerY <= screenHeight * 0.30f;
        boolean bottomRight = centerX >= screenWidth * 0.68f
                && centerX <= screenWidth * 0.99f
                && centerY >= screenHeight * 0.62f
                && centerY <= screenHeight * 0.98f;
        return small && (topLeft || topRight || bottomRight);
    }

    private boolean clickCandidate(Candidate candidate) {
        AccessibilityNodeInfo node = candidate.node;
        if (node == null) {
            return false;
        }
        if (clickSafeAncestor(node, candidate.bounds)) {
            return true;
        }
        return gestureTap(candidate.bounds.exactCenterX(), candidate.bounds.exactCenterY());
    }

    private boolean clickSafeAncestor(AccessibilityNodeInfo source, Rect sourceBounds) {
        AccessibilityNodeInfo node = source;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        long sourceArea = Math.max(1L, (long) sourceBounds.width() * sourceBounds.height());
        long screenArea = (long) screenWidth * screenHeight;

        for (int depth = 0; node != null && depth < 7; depth++) {
            try {
                Rect bounds = boundsOf(node);
                if (node.isClickable()
                        && node.isEnabled()
                        && node.isVisibleToUser()
                        && bounds != null
                        && bounds.contains(sourceBounds.centerX(), sourceBounds.centerY())
                        && (long) bounds.width() * bounds.height() <= sourceArea * 8L
                        && (long) bounds.width() * bounds.height() <= screenArea * 22L / 100L
                        && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                node = node.getParent();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean gestureTap(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, 45L))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isClickableInAncestors(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 7; depth++) {
            try {
                if (node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
                    return true;
                }
                node = node.getParent();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private String labelOf(AccessibilityNodeInfo node) {
        try {
            if (node.isEditable()) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            CharSequence text = node.getText();
            if (text != null) {
                result.append(text);
            }
            CharSequence description = node.getContentDescription();
            if (description != null) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(description);
            }
            return result.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String viewIdOf(AccessibilityNodeInfo node) {
        try {
            CharSequence id = node.getViewIdResourceName();
            return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String classOf(AccessibilityNodeInfo node) {
        try {
            CharSequence value = node.getClassName();
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Rect boundsOf(AccessibilityNodeInfo node) {
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUsable(AccessibilityNodeInfo node) {
        try {
            return node.isVisibleToUser();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String packageOf(AccessibilityNodeInfo node) {
        try {
            CharSequence value = node.getPackageName();
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isOverlayWindow(AccessibilityNodeInfo root) {
        try {
            AccessibilityWindowInfo window = root.getWindow();
            return window != null && window.getLayer() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isPackageEligible(String packageName) {
        return packageName != null
                && !packageName.isEmpty()
                && !packageName.equals(getPackageName())
                && !BLOCKED_SYSTEM_PACKAGES.contains(packageName);
    }

    private boolean isScreenInteractive() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager == null || powerManager.isInteractive();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
        mainHandler.removeCallbacks(initialScan);
        mainHandler.removeCallbacks(followUpScan);
        mainHandler.removeCallbacks(lateScan);
        mainHandler.removeCallbacks(watchScan);
        mainHandler.removeCallbacks(continuousScan);
        persistentScanModule.stop();
        initialScanScheduled = false;
        followUpScheduled = false;
        lateScanScheduled = false;
        continuousScanScheduled = false;
        continuousScansRemaining = 0;
        watchScanScheduled = false;
        watchScansRemaining = 0;
    }

    @Override
    public void onDestroy() {
        persistentScanModule.stop();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final class NodeEntry {
        final AccessibilityNodeInfo node;
        final String label;
        /** {@code label} after {@link ButtonMatcher#compact}: computed once per scan. */
        final String compactLabel;
        /** view id with non-alphanumerics removed: computed once per scan. */
        final String normalizedId;
        final Rect bounds;
        final boolean visible;
        final String className;
        final String compactClass;

        NodeEntry(
                AccessibilityNodeInfo node,
                String label,
                String compactLabel,
                String normalizedId,
                Rect bounds,
                boolean visible,
                String className,
                String compactClass
        ) {
            this.node = node;
            this.label = label;
            this.compactLabel = compactLabel;
            this.normalizedId = normalizedId;
            this.bounds = bounds;
            this.visible = visible;
            this.className = className;
            this.compactClass = compactClass;
        }
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final int score;
        final String source;

        Candidate(AccessibilityNodeInfo node, Rect bounds, int score, String source) {
            this.node = node;
            this.bounds = bounds;
            this.score = score;
            this.source = source;
        }
    }

    private static final class ScanResult {
        final List<Candidate> candidates = new ArrayList<>();
        boolean adEvidence;
    }
}
