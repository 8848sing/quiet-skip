package com.jingjump.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure text rules used by the accessibility service. Keeping this class free of
 * Android dependencies makes the high-risk matching rules unit-testable.
 *
 * <p>1.9.0 makes the hot path allocation-cheap: {@link #compact(String)} is a
 * single-pass scan instead of four {@code replace} calls plus
 * {@code replaceAll("\\s+")}, the countdown patterns are compiled once, and
 * every matcher has an overload that accepts an already compacted label or
 * normalized view id so a scan never recompacts the same node text six times.</p>
 */
final class ButtonMatcher {
    private static final Set<String> STRONG_SKIP_TEXTS = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "跳过",
                    "跳过广告",
                    "跳过此广告",
                    "skip",
                    "skipad",
                    "skipads"
            ))
    );

    private static final String[] AD_MARKERS = {
            "广告",
            "摇一摇",
            "扭一扭",
            "跳转详情",
            "跳转第三方",
            "第三方应用",
            "点击下载",
            "立即下载",
            "下载安装",
            "了解详情",
            "获取奖励",
            "开屏"
    };

    private static final String[] STRONG_AD_DIALOG_MARKERS = {
            "点击跳转",
            "跳转详情",
            "第三方应用",
            "摇一摇",
            "扭一扭",
            "点击下载",
            "立即下载",
            "下载安装",
            "获取奖励"
    };

    private static final Set<String> EVIDENCE_CLOSE_TEXTS = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "关闭广告",
                    "关闭此广告",
                    "关闭弹窗",
                    "关闭按钮",
                    "closead",
                    "残忍拒绝",
                    "以后再说",
                    "不再提示",
                    "不再提醒",
                    "不感兴趣"
            ))
    );

    private static final Pattern COUNTDOWN_SKIP_1 =
            Pattern.compile("(?:跳过|skip)(?:广告)?(?:\\d{1,2}(?:s|秒)?)?");
    private static final Pattern COUNTDOWN_SKIP_2 =
            Pattern.compile("\\d{1,2}(?:s|秒)?[|｜]?(?:跳过|skip)(?:广告)?");
    private static final Pattern COUNTDOWN_SKIP_3 =
            Pattern.compile("(?:跳过|skip)[|｜]\\d{1,2}(?:s|秒)?");

    private ButtonMatcher() {
    }

    /**
     * Lowercases, drops zero-width characters and removes whitespace. Labels
     * come from every node of every scan, so this stays a single pass with at
     * most one allocation.
     */
    static String compact(String value) {
        if (value == null) {
            return "";
        }
        int length = value.length();
        if (length == 0) {
            return "";
        }
        StringBuilder result = null;
        for (int i = 0; i < length; i++) {
            char raw = value.charAt(i);
            char current = raw;
            boolean drop = current <= ' '
                    || current == '\u00a0'
                    || current == '\u3000'
                    || current == '\u200b'
                    || current == '\u200c'
                    || current == '\u200d'
                    || current == '\ufeff';
            if (drop) {
                if (result == null) {
                    result = new StringBuilder(length);
                    result.append(value, 0, i);
                }
                continue;
            }
            char folded = current >= 'A' && current <= 'Z'
                    ? (char) (current + ('a' - 'A'))
                    : current;
            if (result != null) {
                result.append(folded);
            } else if (folded != raw) {
                result = new StringBuilder(length);
                result.append(value, 0, i);
                result.append(folded);
            }
        }
        return result == null ? value : result.toString();
    }

    /** Compacted view id with every non-alphanumeric character removed. */
    static String normalizeId(String viewId) {
        String compacted = compact(viewId);
        int length = compacted.length();
        if (length == 0) {
            return "";
        }
        StringBuilder result = null;
        for (int i = 0; i < length; i++) {
            char current = compacted.charAt(i);
            boolean keep = (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9');
            if (keep) {
                if (result != null) {
                    result.append(current);
                }
                continue;
            }
            if (result == null) {
                result = new StringBuilder(length);
                result.append(compacted, 0, i);
            }
        }
        return result == null ? compacted : result.toString();
    }

    static Set<String> sanitizeKeywords(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = compact(value);
            if (!normalized.isEmpty()
                    && normalized.length() <= 18
                    && !normalized.contains("取消")
                    && !normalized.contains("确认")
                    && result.size() < 24) {
                result.add(normalized);
            }
        }
        return result;
    }

    static List<String> compactMarkers(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            String normalized = compact(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    static boolean isStrongSkipLabel(String rawLabel, Collection<String> customKeywords) {
        return isStrongSkipLabelCompact(compact(rawLabel), sanitizeKeywords(customKeywords));
    }

    /** @param compactLabel label already passed through {@link #compact(String)} */
    static boolean isStrongSkipLabelCompact(String compactLabel, Collection<String> keywords) {
        if (compactLabel.isEmpty() || compactLabel.length() > 18
                || compactLabel.contains("已跳过")) {
            return false;
        }
        if (STRONG_SKIP_TEXTS.contains(compactLabel) || looksLikeCountdownSkip(compactLabel)) {
            return true;
        }
        for (String keyword : keywords) {
            if (compactLabel.equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    static boolean isEvidenceSkipLabel(String rawLabel, Collection<String> customKeywords) {
        return isEvidenceSkipLabelCompact(compact(rawLabel), sanitizeKeywords(customKeywords));
    }

    static boolean isEvidenceSkipLabelCompact(String compactLabel, Collection<String> keywords) {
        if (compactLabel.isEmpty() || compactLabel.length() > 18
                || compactLabel.contains("已跳过")) {
            return false;
        }
        for (String keyword : keywords) {
            if (compactLabel.equals(keyword)
                    && (keyword.contains("跳过") || keyword.contains("skip"))) {
                return true;
            }
        }
        return false;
    }

    static boolean isCloseLabel(String rawLabel, boolean adEvidence) {
        return isCloseLabelCompact(compact(rawLabel), adEvidence);
    }

    static boolean isCloseLabelCompact(String compactLabel, boolean adEvidence) {
        if (compactLabel.isEmpty() || compactLabel.length() > 12) {
            return false;
        }
        if (EVIDENCE_CLOSE_TEXTS.contains(compactLabel)) {
            return true;
        }
        if (!adEvidence) {
            return false;
        }
        return "关闭".equals(compactLabel)
                || "close".equals(compactLabel)
                || "×".equals(compactLabel)
                || "✕".equals(compactLabel)
                || "✖".equals(compactLabel)
                || "╳".equals(compactLabel)
                || "x".equals(compactLabel);
    }

    static boolean isAdEvidenceLabel(String rawLabel) {
        return isAdEvidenceLabelCompact(compact(rawLabel));
    }

    static boolean isAdEvidenceLabelCompact(String compactLabel) {
        if (compactLabel.isEmpty() || compactLabel.length() > 80) {
            return false;
        }
        for (String marker : AD_MARKERS) {
            if (compactLabel.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isStrongAdDialogLabel(String rawLabel) {
        return isStrongAdDialogLabelCompact(compact(rawLabel));
    }

    static boolean isStrongAdDialogLabelCompact(String compactLabel) {
        if (compactLabel.isEmpty() || compactLabel.length() > 80) {
            return false;
        }
        for (String marker : STRONG_AD_DIALOG_MARKERS) {
            if (compactLabel.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean looksLikeSplashClass(String className) {
        return looksLikeSplashClassCompact(compact(className));
    }

    static boolean looksLikeSplashClassCompact(String compactClass) {
        if (compactClass.isEmpty()) {
            return false;
        }
        return compactClass.contains("splash")
                || compactClass.contains("adsplash")
                || compactClass.contains("adactivity")
                || compactClass.contains("advert")
                || compactClass.contains("startpage")
                || compactClass.contains("welcomeactivity");
    }

    static boolean looksLikeSkipId(String rawId) {
        return looksLikeSkipIdNormalized(normalizeId(rawId));
    }

    static boolean looksLikeSkipIdNormalized(String normalizedId) {
        if (normalizedId.isEmpty()) {
            return false;
        }
        return normalizedId.contains("adskip")
                || normalizedId.contains("skipad")
                || normalizedId.contains("skipbutton")
                || normalizedId.contains("skipbtn")
                || normalizedId.contains("skipview")
                || normalizedId.contains("skiptext")
                || normalizedId.endsWith("skip");
    }

    static boolean looksLikeCloseId(String rawId) {
        return looksLikeCloseIdNormalized(normalizeId(rawId));
    }

    static boolean looksLikeCloseIdNormalized(String normalizedId) {
        if (normalizedId.isEmpty()) {
            return false;
        }
        return normalizedId.contains("adclose")
                || normalizedId.contains("closead")
                || normalizedId.contains("dismissad")
                || normalizedId.contains("addismiss")
                || normalizedId.contains("dialogclose")
                || normalizedId.contains("closedialog")
                || normalizedId.contains("closebutton")
                || normalizedId.contains("closebtn");
    }

    /**
     * 更宽松的关闭控件 ID。只在已经出现广告证据、且控件位于角落并足够小时使用，
     * 用来兼容 “iv_close / img_close / btn_dismiss” 这类常见广告 SDK 命名。
     */
    static boolean looksLikeLooseCloseId(String rawId) {
        return looksLikeLooseCloseIdNormalized(normalizeId(rawId));
    }

    static boolean looksLikeLooseCloseIdNormalized(String normalizedId) {
        if (normalizedId.isEmpty()) {
            return false;
        }
        return looksLikeCloseIdNormalized(normalizedId)
                || normalizedId.endsWith("close")
                || normalizedId.endsWith("closeicon")
                || normalizedId.endsWith("dismiss")
                || normalizedId.contains("closeicon")
                || normalizedId.contains("btnclose")
                || normalizedId.contains("ivclose")
                || normalizedId.contains("imgclose")
                || normalizedId.contains("dismissbtn");
    }

    static boolean looksLikeAdContainerId(String rawId) {
        return looksLikeAdContainerIdNormalized(normalizeId(rawId));
    }

    static boolean looksLikeAdContainerIdNormalized(String normalizedId) {
        if (normalizedId.isEmpty()) {
            return false;
        }
        return normalizedId.contains("adcontainer")
                || normalizedId.contains("adlayout")
                || normalizedId.contains("adroot")
                || normalizedId.contains("adview")
                || normalizedId.contains("adwebview")
                || normalizedId.contains("splashad")
                || normalizedId.contains("interstitial")
                || normalizedId.contains("adfullscreen");
    }

    static boolean looksLikeWebViewClass(String rawClass) {
        return compact(rawClass).contains("webview");
    }

    static boolean looksLikeAdMediaClass(String rawClass) {
        return looksLikeAdMediaClassCompact(compact(rawClass));
    }

    static boolean looksLikeAdMediaClassCompact(String compactClass) {
        return compactClass.contains("webview")
                || compactClass.contains("imageview")
                || compactClass.contains("textureview")
                || compactClass.contains("surfaceview")
                || compactClass.contains("videoview");
    }

    private static boolean looksLikeCountdownSkip(String label) {
        if (!label.contains("跳过") && !label.contains("skip")) {
            return false;
        }
        return COUNTDOWN_SKIP_1.matcher(label).matches()
                || COUNTDOWN_SKIP_2.matcher(label).matches()
                || COUNTDOWN_SKIP_3.matcher(label).matches();
    }
}