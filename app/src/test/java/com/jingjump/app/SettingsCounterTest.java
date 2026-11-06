package com.jingjump.app;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The DNS block counter is batched in memory (1.9.0) to stop writing
 * preferences on every blocked query, but the number shown to the user must
 * still be exact and survive a flush.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class SettingsCounterTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
        SettingsStore.resetStats(context);
    }

    @Test
    public void batchedCounterIsExactBeforeAndAfterFlush() {
        assertEquals(0, SettingsStore.dnsBlockCount(context));
        assertEquals(1, SettingsStore.recordDnsBlock(context));
        assertEquals(2, SettingsStore.recordDnsBlock(context));
        assertEquals(3, SettingsStore.recordDnsBlock(context));
        assertEquals(3, SettingsStore.dnsBlockCount(context));

        SettingsStore.flushDnsBlockCount(context);
        assertEquals(3, SettingsStore.dnsBlockCount(context));
        assertEquals(3, prefs().getInt("dns_block_count", 0));

        assertEquals(4, SettingsStore.recordDnsBlock(context));
        assertEquals(4, SettingsStore.dnsBlockCount(context));
    }

    @Test
    public void resetClearsPendingAndStoredCounts() {
        SettingsStore.recordDnsBlock(context);
        SettingsStore.recordDnsBlock(context);
        SettingsStore.resetStats(context);
        assertEquals(0, SettingsStore.dnsBlockCount(context));
        assertEquals(1, SettingsStore.recordDnsBlock(context));
        assertEquals(1, SettingsStore.dnsBlockCount(context));
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("jingjump_settings", Context.MODE_PRIVATE);
    }
}