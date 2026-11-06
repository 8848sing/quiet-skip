package com.jingjump.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class InteractionSafetyPolicy {
    private static final Set<String> COMMENT_SENSITIVE_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "tv.danmaku.bili",
                    "tv.danmaku.bilibilihd",
                    "com.bilibili.app.in"
            ))
    );

    private static final String[] COMMENT_SENSITIVE_CLASS_MARKERS = {
            "videodetail",
            "video_detail",
            "comment",
            "detailactivity",
            "detailsactivity",
            "unitedbiz",
            "storytransparentactivity",
            "browseractivity",
            "webview",
            "liveactivity",
            "searchactivity"
    };

    /**
     * Comment-UI markers already passed through {@link ButtonMatcher#compact}.
     * 1.9.0 precomputes them so a scan does not recompact nine markers for
     * every node of every window.
     */
    private static final String[] COMPACT_COMMENT_UI_MARKERS = {
            "发一条友善的评论",
            "评论区",
            "评论详情",
            "热门评论",
            "条回复",
            "弹幕设置",
            "回复@",
            "按热度",
            "按时间"
    };

    private InteractionSafetyPolicy() {
    }

    static List<String> defaultPauseMarkers(String packageName) {
        if (!isBilibiliPackage(packageName)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(
                "VideoDetailsActivity",
                "UnitedBizDetailsActivity",
                "StoryTransparentActivity",
                "评论",
                "热门评论",
                "弹幕",
                "搜索",
                "直播"
        )));
    }

    static List<String> defaultBlockedClickWords(String packageName) {
        if (!isBilibiliPackage(packageName)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(
                "评论",
                "回复",
                "弹幕",
                "取消",
                "发送"
        )));
    }

    static boolean matchesText(String value, List<String> markers) {
        if (value == null || markers == null || markers.isEmpty()) {
            return false;
        }
        return matchesCompactText(
                ButtonMatcher.compact(value),
                ButtonMatcher.compactMarkers(markers)
        );
    }

    /** Both arguments must already be compacted (see {@link ButtonMatcher#compactMarkers}). */
    static boolean matchesCompactText(String compactValue, List<String> compactMarkers) {
        if (compactValue.isEmpty() || compactMarkers.isEmpty()) {
            return false;
        }
        for (String marker : compactMarkers) {
            if (compactValue.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isCommentSensitiveContext(String packageName, String className) {
        return isCommentSensitiveContextCompact(
                packageName,
                ButtonMatcher.compact(className)
        );
    }

    static boolean isCommentSensitiveContextCompact(String packageName, String compactClass) {
        if (packageName == null || !COMMENT_SENSITIVE_PACKAGES.contains(packageName)) {
            return false;
        }
        if (compactClass.isEmpty()) {
            return false;
        }
        for (String marker : COMMENT_SENSITIVE_CLASS_MARKERS) {
            if (compactClass.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBilibiliPackage(String packageName) {
        return COMMENT_SENSITIVE_PACKAGES.contains(packageName);
    }

    static boolean looksLikeCommentUi(String label) {
        if (label == null) {
            return false;
        }
        return looksLikeCommentUiCompact(ButtonMatcher.compact(label));
    }

    static boolean looksLikeCommentUiCompact(String compactLabel) {
        if (compactLabel.isEmpty()) {
            return false;
        }
        for (String marker : COMPACT_COMMENT_UI_MARKERS) {
            if (compactLabel.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}