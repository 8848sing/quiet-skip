package com.jingjump.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class ButtonMatcherTest {
    @Test
    public void recognizesExactSkipButtons() {
        assertTrue(ButtonMatcher.isStrongSkipLabel("跳过", Collections.emptyList()));
        assertTrue(ButtonMatcher.isStrongSkipLabel("SKIP AD", Collections.emptyList()));
        assertTrue(ButtonMatcher.isCloseLabel(" 关闭广告 ", true));
    }

    @Test
    public void recognizesCountdownSkipButtons() {
        assertTrue(ButtonMatcher.isStrongSkipLabel("5s 跳过", Collections.emptyList()));
        assertTrue(ButtonMatcher.isStrongSkipLabel("跳过 5 秒", Collections.emptyList()));
        assertTrue(ButtonMatcher.isStrongSkipLabel("03秒|SKIP", Collections.emptyList()));
    }

    @Test
    public void doesNotTreatAlreadySkippedTextAsButton() {
        assertFalse(ButtonMatcher.isStrongSkipLabel("已跳过", Collections.emptyList()));
        assertFalse(ButtonMatcher.isStrongSkipLabel("跳过详情页", Collections.emptyList()));
    }

    @Test
    public void genericCloseRequiresAdEvidence() {
        assertFalse(ButtonMatcher.isCloseLabel("关闭", false));
        assertTrue(ButtonMatcher.isCloseLabel("关闭", true));
        assertTrue(ButtonMatcher.isCloseLabel("关闭广告", false));
    }

    @Test
    public void recognizesAdEvidenceWithoutStoringIt() {
        assertTrue(ButtonMatcher.isAdEvidenceLabel("摇一摇跳转第三方应用"));
        assertTrue(ButtonMatcher.isStrongAdDialogLabel("点击跳转详情或第三方应用"));
        assertTrue(ButtonMatcher.isAdEvidenceLabel("广告"));
        assertFalse(ButtonMatcher.isAdEvidenceLabel("普通设置"));
        assertFalse(ButtonMatcher.isStrongAdDialogLabel("广告"));
    }

    @Test
    public void recognizesAdSpecificViewIds() {
        assertTrue(ButtonMatcher.looksLikeSkipId("com.demo:id/ad_skip_button"));
        assertTrue(ButtonMatcher.looksLikeCloseId("com.demo:id/iv_ad_close"));
        assertFalse(ButtonMatcher.looksLikeSkipId("com.demo:id/skip_to_content"));
    }

    @Test
    public void customRulesAreSanitized() {
        assertTrue(ButtonMatcher.sanitizeKeywords(
                Arrays.asList("跳过推荐", "取消", "确认", " ", "跳过推荐")
        ).size() == 1);
    }
}
