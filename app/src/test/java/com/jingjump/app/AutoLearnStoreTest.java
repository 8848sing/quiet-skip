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

/**
 * Covers the auto-learn chain end to end: heuristic scoring, the two-hit rule
 * for medium-confidence candidates, disabling, normalization and clearing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class AutoLearnStoreTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("jingjump_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void highConfidenceDomainIsLearnedOnFirstHit() {
        assertTrue(AutoLearnStore.inspectAndLearn(context, "adserver.example"));
        assertTrue(AutoLearnStore.isLearnedBlocked(context, "adserver.example"));
        // parent-entry semantics: subdomains are covered too
        assertTrue(AutoLearnStore.isLearnedBlocked(context, "x.adserver.example"));
        assertEquals(1, AutoLearnStore.learnedCount(context));
    }

    @Test
    public void mediumConfidenceDomainNeedsTwoHits() {
        assertFalse("first hit only records a candidate",
                AutoLearnStore.inspectAndLearn(context, "tracker.example"));
        assertFalse(AutoLearnStore.isLearnedBlocked(context, "tracker.example"));
        assertTrue("second hit promotes the candidate",
                AutoLearnStore.inspectAndLearn(context, "tracker.example"));
        assertTrue(AutoLearnStore.isLearnedBlocked(context, "tracker.example"));
        assertEquals(1, AutoLearnStore.learnedCount(context));
    }

    @Test
    public void learningCanBeDisabled() {
        SettingsStore.setAutoLearn(context, false);
        assertFalse(AutoLearnStore.inspectAndLearn(context, "adserver.example"));
        assertEquals(0, AutoLearnStore.learnedCount(context));
    }

    @Test
    public void learnedDomainsAreNormalized() {
        assertTrue(AutoLearnStore.inspectAndLearn(context, "  ADServer.Example.  "));
        assertEquals(1, AutoLearnStore.learnedCount(context));
        assertTrue(AutoLearnStore.isLearnedBlocked(context, "adserver.example"));
    }

    @Test
    public void clearDropsLearnedDomainsAndCandidates() {
        assertTrue(AutoLearnStore.inspectAndLearn(context, "adserver.example"));
        AutoLearnStore.inspectAndLearn(context, "tracker.example");
        assertTrue(AutoLearnStore.learnedCount(context) > 0);

        AutoLearnStore.clear(context);

        assertEquals(0, AutoLearnStore.learnedCount(context));
        assertFalse(AutoLearnStore.isLearnedBlocked(context, "adserver.example"));
        // candidates were wiped too: one more medium hit must not block yet
        assertFalse(AutoLearnStore.inspectAndLearn(context, "tracker.example"));
    }

    @Test
    public void unknownDomainsAreNeverLearned() {
        assertFalse(AutoLearnStore.inspectAndLearn(context, "www.example.org"));
        assertFalse(AutoLearnStore.inspectAndLearn(context, "cdn.example.org"));
        assertEquals(0, AutoLearnStore.learnedCount(context));
    }
}