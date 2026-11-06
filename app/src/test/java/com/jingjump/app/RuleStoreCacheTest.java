package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * The 1.9.0 rule cache must stay invisible to callers: a saved change is
 * visible on the very next read, changed values invalidate the cache, and an
 * untouched package still gets the built-in defaults.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class RuleStoreCacheTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
    }

    @Test
    public void savedRuleIsVisibleImmediately() {
        RuleStore.save(
                context,
                "com.demo.app",
                true,
                Collections.singletonList("跳过推荐"),
                Collections.singletonList("TextView[text=\"跳过\"]"),
                true,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                SensitivityProfile.LEVEL_SENSITIVE
        );
        RuleStore.AppRule first = RuleStore.get(context, "com.demo.app");
        assertTrue(first.enabled);
        assertTrue(first.continuousScan);
        assertEquals(SensitivityProfile.LEVEL_SENSITIVE, first.sensitivity);
        assertEquals(1, first.keywords.size());
        assertEquals(1, first.selectors.size());
    }

    @Test
    public void cacheIsInvalidatedWhenTheStoredDocumentChanges() {
        RuleStore.save(context, "com.demo.app", true, Collections.emptyList(),
                Collections.emptyList(), false, false, Collections.emptyList(),
                Collections.emptyList(), SensitivityProfile.LEVEL_AGGRESSIVE);
        assertEquals(SensitivityProfile.LEVEL_AGGRESSIVE,
                RuleStore.get(context, "com.demo.app").sensitivity);

        RuleStore.save(context, "com.demo.app", false, Collections.emptyList(),
                Collections.emptyList(), false, false, Collections.emptyList(),
                Collections.emptyList(), SensitivityProfile.LEVEL_GLOBAL);
        RuleStore.AppRule updated = RuleStore.get(context, "com.demo.app");
        assertFalse(updated.enabled);
        assertEquals(SensitivityProfile.LEVEL_GLOBAL, updated.sensitivity);
    }

    @Test
    public void unknownPackagesGetDefaults() {
        RuleStore.AppRule plain = RuleStore.get(context, "com.example.plain");
        assertTrue(plain.enabled);
        assertFalse(plain.safetyMode);
        assertEquals(SensitivityProfile.LEVEL_GLOBAL, plain.sensitivity);
        assertTrue(plain.pausePageMarkers.isEmpty());

        RuleStore.AppRule bilibili = RuleStore.get(context, "tv.danmaku.bili");
        assertTrue(bilibili.safetyMode);
        assertFalse(bilibili.pausePageMarkers.isEmpty());
    }

    @Test
    public void clearedDocumentFallsBackToDefaults() {
        RuleStore.save(context, "com.demo.app", false, Collections.emptyList(),
                Collections.emptyList(), false, false, Collections.emptyList(),
                Collections.emptyList(), SensitivityProfile.LEVEL_GLOBAL);
        assertFalse(RuleStore.get(context, "com.demo.app").enabled);

        prefs().edit().clear().commit();
        assertTrue(RuleStore.get(context, "com.demo.app").enabled);
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("jingjump_settings", Context.MODE_PRIVATE);
    }
}