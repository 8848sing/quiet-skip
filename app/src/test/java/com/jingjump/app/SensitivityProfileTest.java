package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SensitivityProfileTest {
    @Test
    public void higherLevelsScanSoonerAndPersistLonger() {
        SensitivityProfile conservative =
                SensitivityProfile.of(SensitivityProfile.LEVEL_CONSERVATIVE);
        SensitivityProfile standard =
                SensitivityProfile.of(SensitivityProfile.LEVEL_STANDARD);
        SensitivityProfile sensitive =
                SensitivityProfile.of(SensitivityProfile.LEVEL_SENSITIVE);
        SensitivityProfile aggressive =
                SensitivityProfile.of(SensitivityProfile.LEVEL_AGGRESSIVE);

        assertTrue(conservative.initialScanDelayMs > standard.initialScanDelayMs);
        assertTrue(standard.initialScanDelayMs > sensitive.initialScanDelayMs);
        assertTrue(sensitive.initialScanDelayMs > aggressive.initialScanDelayMs);

        assertTrue(conservative.minScanIntervalMs > standard.minScanIntervalMs);
        assertTrue(standard.minScanIntervalMs > sensitive.minScanIntervalMs);
        assertTrue(sensitive.minScanIntervalMs > aggressive.minScanIntervalMs);

        assertTrue(conservative.activeWindowMs < standard.activeWindowMs);
        assertTrue(standard.activeWindowMs < sensitive.activeWindowMs);
        assertTrue(sensitive.activeWindowMs < aggressive.activeWindowMs);

        assertTrue(conservative.maxWatchScans == 0);
        assertTrue(standard.maxWatchScans > conservative.maxWatchScans);
        assertTrue(sensitive.maxWatchScans > standard.maxWatchScans);
        assertTrue(aggressive.maxWatchScans > sensitive.maxWatchScans);

        assertFalse(standard.structuralAdEvidence);
        assertTrue(sensitive.structuralAdEvidence);
        assertTrue(aggressive.unsolicitedCornerClick);
    }

    @Test
    public void outOfRangeLevelsAreClamped() {
        assertEquals(
                SensitivityProfile.LEVEL_CONSERVATIVE,
                SensitivityProfile.of(-8).level
        );
        assertEquals(
                SensitivityProfile.LEVEL_STANDARD,
                SensitivityProfile.of(SensitivityProfile.LEVEL_GLOBAL).level
        );
        assertEquals(
                SensitivityProfile.LEVEL_AGGRESSIVE,
                SensitivityProfile.of(99).level
        );
        assertEquals(
                SensitivityProfile.LEVEL_AGGRESSIVE,
                SensitivityProfile.clampLevel(99)
        );
    }
}
