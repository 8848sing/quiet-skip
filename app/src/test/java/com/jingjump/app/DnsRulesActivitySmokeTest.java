package com.jingjump.app;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public final class DnsRulesActivitySmokeTest {
    @Test
    public void dnsRulesActivityReachesResumedState() {
        ActivityController<DnsRulesActivity> controller =
                Robolectric.buildActivity(DnsRulesActivity.class);
        DnsRulesActivity activity = controller.create().start().resume().visible().get();

        assertNotNull(activity);
        assertNotNull(activity.findViewById(android.R.id.content));

        controller.pause().stop().destroy();
    }
}
