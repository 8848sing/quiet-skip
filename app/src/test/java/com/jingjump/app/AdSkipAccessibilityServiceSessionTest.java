package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class AdSkipAccessibilityServiceSessionTest {
    @Test
    public void resettingSessionStopsPersistentScanAndClearsPackage() throws Exception {
        ServiceController<AdSkipAccessibilityService> controller =
                Robolectric.buildService(AdSkipAccessibilityService.class).create();
        AdSkipAccessibilityService service = controller.get();

        Field packageField = AdSkipAccessibilityService.class
                .getDeclaredField("currentPackage");
        packageField.setAccessible(true);
        packageField.set(service, "tv.danmaku.bili");

        Field moduleField = AdSkipAccessibilityService.class
                .getDeclaredField("persistentScanModule");
        moduleField.setAccessible(true);
        ContinuousScanModule module = (ContinuousScanModule) moduleField.get(service);
        module.start();
        assertTrue(module.isRunning());

        Method reset = AdSkipAccessibilityService.class
                .getDeclaredMethod("resetScanSession", String.class);
        reset.setAccessible(true);
        reset.invoke(service, "");

        assertFalse(module.isRunning());
        assertEquals("", packageField.get(service));
        controller.destroy();
    }
}
