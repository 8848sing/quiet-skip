package com.jingjump.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InteractionSafetyPolicyTest {
    @Test
    public void protectsBilibiliVideoAndCommentPages() {
        assertTrue(InteractionSafetyPolicy.isCommentSensitiveContext(
                "tv.danmaku.bili",
                "tv.danmaku.bili.ui.video.VideoDetailsActivity"
        ));
        assertTrue(InteractionSafetyPolicy.isCommentSensitiveContext(
                "tv.danmaku.bili",
                "tv.danmaku.bili.ui.webview.BrowserActivity"
        ));
        assertTrue(InteractionSafetyPolicy.isCommentSensitiveContext(
                "tv.danmaku.bili",
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
        ));
        assertTrue(InteractionSafetyPolicy.isCommentSensitiveContext(
                "tv.danmaku.bili",
                "com.bilibili.video.story.StoryTransparentActivity"
        ));
        assertTrue(InteractionSafetyPolicy.looksLikeCommentUi("发一条友善的评论"));
        assertTrue(InteractionSafetyPolicy.looksLikeCommentUi("评论详情"));
        assertTrue(InteractionSafetyPolicy.looksLikeCommentUi("热门评论"));
        assertTrue(InteractionSafetyPolicy.looksLikeCommentUi("共617条回复"));
        assertTrue(InteractionSafetyPolicy.matchesText(
                "tv.danmaku.bili.ui.video.VideoDetailsActivity",
                InteractionSafetyPolicy.defaultPauseMarkers("tv.danmaku.bili")
        ));
        assertTrue(InteractionSafetyPolicy.matchesText(
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
                InteractionSafetyPolicy.defaultPauseMarkers("tv.danmaku.bili")
        ));
        assertTrue(InteractionSafetyPolicy.matchesText(
                "com.bilibili.video.story.StoryTransparentActivity",
                InteractionSafetyPolicy.defaultPauseMarkers("tv.danmaku.bili")
        ));
        assertTrue(InteractionSafetyPolicy.matchesText(
                "回复 @用户",
                InteractionSafetyPolicy.defaultBlockedClickWords("tv.danmaku.bili")
        ));
        assertTrue(InteractionSafetyPolicy.matchesText(
                "关闭弹幕",
                InteractionSafetyPolicy.defaultBlockedClickWords("tv.danmaku.bili")
        ));
    }

    @Test
    public void keepsBilibiliSplashAndOtherAppsUnrestricted() {
        assertFalse(InteractionSafetyPolicy.isCommentSensitiveContext(
                "tv.danmaku.bili",
                "tv.danmaku.bili.MainActivityV2"
        ));
        assertFalse(InteractionSafetyPolicy.isCommentSensitiveContext(
                "com.example.video",
                "com.example.video.VideoDetailsActivity"
        ));
    }
}
