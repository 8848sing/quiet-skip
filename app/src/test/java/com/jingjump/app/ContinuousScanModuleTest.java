package com.jingjump.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class ContinuousScanModuleTest {
    @Test
    public void keepsSchedulingUntilCallbackStopsIt() {
        AtomicInteger scans = new AtomicInteger();
        AtomicBoolean enabled = new AtomicBoolean(true);
        Handler handler = new Handler(Looper.getMainLooper());
        ContinuousScanModule module = new ContinuousScanModule(
                handler,
                100L,
                new ContinuousScanModule.Callback() {
                    @Override
                    public boolean shouldContinue() {
                        return enabled.get();
                    }

                    @Override
                    public void scan() {
                        scans.incrementAndGet();
                    }
                }
        );

        module.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550));
        assertTrue(scans.get() >= 5);

        enabled.set(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertFalse(module.isRunning());
    }

    @Test
    public void explicitStopCancelsPendingTicksAndAllowsRestart() {
        AtomicInteger scans = new AtomicInteger();
        Handler handler = new Handler(Looper.getMainLooper());
        ContinuousScanModule module = new ContinuousScanModule(
                handler,
                100L,
                new ContinuousScanModule.Callback() {
                    @Override
                    public boolean shouldContinue() {
                        return true;
                    }

                    @Override
                    public void scan() {
                        scans.incrementAndGet();
                    }
                }
        );

        module.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        module.stop();
        int stoppedAt = scans.get();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertTrue(scans.get() == stoppedAt);
        assertFalse(module.isRunning());

        module.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertTrue(scans.get() > stoppedAt);
        module.stop();
    }
}
