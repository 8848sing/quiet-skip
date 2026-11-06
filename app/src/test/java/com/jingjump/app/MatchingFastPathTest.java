package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Locks down the behaviour of the 1.9.0 fast paths: the normalization fast
 * path must agree with the slow (IDN) path, the heuristic rewrite must return
 * exactly what the old label-splitting version returned, and label compaction
 * must strip the same characters as before (plus ideographic spaces, which
 * Chinese ad buttons really use).
 */
public final class MatchingFastPathTest {

    @Test
    public void normalizesPlainAsciiDomainsWithoutTheIdnPath() {
        assertEquals("example.com", DnsBlockList.normalizeDomain("example.com"));
        assertEquals("example.com", DnsBlockList.normalizeDomain("  Example.COM.  "));
        assertEquals("example.com", DnsBlockList.normalizeDomain("example.com..."));
        assertEquals("", DnsBlockList.normalizeDomain(""));
        assertEquals("", DnsBlockList.normalizeDomain("   "));
        assertEquals("", DnsBlockList.normalizeDomain(null));
        assertEquals("#", DnsBlockList.normalizeDomain("# comment"));
        assertTrue(DnsBlockList.isNormalizedDomain("ads.example.com"));
        assertFalse(DnsBlockList.isNormalizedDomain("ADS.example.com"));
        assertFalse(DnsBlockList.isNormalizedDomain("ads example.com"));
    }

    @Test
    public void normalizesInternationalDomains() {
        assertEquals("xn--fsqu00a.xn--0zwm56d",
                DnsBlockList.normalizeDomain("例子.测试"));
    }

    @Test
    public void rejectsOverlongAndMalformedDomains() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longName.append("abcdefgh.");
        }
        assertEquals("", DnsBlockList.normalizeDomain(longName.toString()));
        assertEquals("", DnsBlockList.normalizeDomain("bad domain.com"));
        assertEquals("", DnsBlockList.normalizeDomain("bad_domain.com"));
    }

    @Test
    public void normalizedHeuristicMatchesRawScoring() {
        String[] domains = {
                "adservice.new-example.com",
                "x.ads.new-example.com",
                "pglstatp-other.example",
                "metrics.new-example.com",
                "beacon.new-example.com",
                "api.qq.com",
                "download.example.com",
                "adobe.example.com",
                "ads-tracking.example",
                "cdn.example.org",
                "weixin.qq.com",
                "a.weixin.qq.com",
                "notqq.com",
                "https"
        };
        for (String domain : domains) {
            assertEquals("score mismatch for " + domain,
                    AdDomainHeuristics.score(domain),
                    AdDomainHeuristics.scoreNormalized(domain));
        }
    }

    @Test
    public void safeBusinessDomainsAreNeverLearned() {
        assertEquals(AdDomainHeuristics.SCORE_NONE,
                AdDomainHeuristics.scoreNormalized("tracker.qq.com"));
        assertEquals(AdDomainHeuristics.SCORE_NONE,
                AdDomainHeuristics.scoreNormalized("x.weixin.qq.com"));
    }

    @Test
    public void compactStripsSpacesIncludingIdeographicOnes() {
        assertEquals("跳过5秒", ButtonMatcher.compact("跳 过 5 秒"));
        assertEquals("跳过5秒", ButtonMatcher.compact("跳\u3000过 5\u00a0秒"));
        assertEquals("skipad", ButtonMatcher.compact(" SKIP\u200bAD "));
        assertEquals("", ButtonMatcher.compact(null));
    }

    @Test
    public void normalizedIdsDropSeparators() {
        assertEquals("comdemoidivadclose",
                ButtonMatcher.normalizeId("com.demo:id/iv_ad_close"));
        assertTrue(ButtonMatcher.looksLikeCloseIdNormalized(
                ButtonMatcher.normalizeId("com.demo:id/iv_ad_close")));
        assertFalse(ButtonMatcher.looksLikeSkipIdNormalized(
                ButtonMatcher.normalizeId("com.demo:id/skip_to_content")));
    }

    @Test
    public void compactMatchOverloadsAgreeWithStringVersions() {
        assertTrue(ButtonMatcher.isCloseLabelCompact(
                ButtonMatcher.compact(" 关闭广告 "), true));
        assertTrue(ButtonMatcher.isStrongSkipLabelCompact(
                ButtonMatcher.compact("5s 跳过"),
                Collections.emptyList()
        ));
        assertTrue(ButtonMatcher.isAdEvidenceLabelCompact(
                ButtonMatcher.compact("摇一摇跳转第三方应用")));
        assertFalse(ButtonMatcher.isAdEvidenceLabelCompact(
                ButtonMatcher.compact("普通设置")));
        assertTrue(ButtonMatcher.isStrongSkipLabel(
                "跳过推荐",
                Arrays.asList("跳过推荐")
        ));
    }

    @Test
    public void safetyPolicyUsesPrecompactedMarkers() {
        java.util.List<String> markers = ButtonMatcher.compactMarkers(
                Arrays.asList("VideoDetailsActivity", "评论", "弹幕"));
        assertTrue(InteractionSafetyPolicy.matchesCompactText("热门评论", markers));
        assertFalse(InteractionSafetyPolicy.matchesCompactText("首页推荐", markers));
        assertTrue(InteractionSafetyPolicy.isCommentSensitiveContextCompact(
                "tv.danmaku.bili",
                ButtonMatcher.compact("com.bilibili.video.story.StoryTransparentActivity")
        ));
        assertFalse(InteractionSafetyPolicy.isCommentSensitiveContextCompact(
                "com.example.app",
                ButtonMatcher.compact("com.bilibili.video.story.StoryTransparentActivity")
        ));
    }
}