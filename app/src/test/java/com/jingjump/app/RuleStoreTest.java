package com.jingjump.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class RuleStoreTest {
    @Test
    public void continuousScanPersistsOnlyWhileAppRuleIsEnabled() {
        Context context = RuntimeEnvironment.getApplication();
        String packageName = "com.example.continuous";

        RuleStore.save(
                context,
                packageName,
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                true,
                java.util.Arrays.asList("评论", "VideoDetailsActivity"),
                java.util.Arrays.asList("取消", "回复"),
                SensitivityProfile.LEVEL_SENSITIVE
        );
        assertTrue(RuleStore.get(context, packageName).continuousScan);
        assertTrue(RuleStore.get(context, packageName).safetyMode);
        assertTrue(RuleStore.get(context, packageName).pausePageMarkers.contains("评论"));
        assertTrue(RuleStore.get(context, packageName).sensitivity
                == SensitivityProfile.LEVEL_SENSITIVE);

        RuleStore.save(
                context,
                packageName,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                SensitivityProfile.LEVEL_GLOBAL
        );
        assertFalse(RuleStore.get(context, packageName).continuousScan);
        assertTrue(RuleStore.get(context, packageName).sensitivity
                == SensitivityProfile.LEVEL_GLOBAL);
    }
}
