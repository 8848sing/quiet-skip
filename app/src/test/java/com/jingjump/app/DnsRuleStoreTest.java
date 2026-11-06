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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class DnsRuleStoreTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("jingjump_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void loadsBuiltinExtendedAndCustomSources() {
        SettingsStore.setDnsCustomBlockText(context, "mine.example\n||extra.example^\n");
        SettingsStore.setDnsCustomAllowText(context, "doubleclick.net");

        DnsFilterSnapshot snapshot = DnsRuleStore.load(context);

        assertTrue("built-in list should be loaded", snapshot.builtinSize() > 100);
        assertTrue("anti-AD extended list should be loaded", snapshot.fullSize() > 50_000);
        assertEquals(DnsFilterSnapshot.SOURCE_CUSTOM,
                snapshot.blockSource("a.mine.example"));
        assertEquals(DnsFilterSnapshot.SOURCE_CUSTOM,
                snapshot.blockSource("extra.example"));
        // allow wins over the built-in set at the decision layer (service checks isAllowed first)
        assertTrue(snapshot.isAllowed("x.doubleclick.net"));
        assertFalse(snapshot.isAllowed("x.other-ads.net") && snapshot.totalRules() == 0);
        assertEquals(0, snapshot.importedSize());
    }

    @Test
    public void strictModeAddsDohSources() {
        DnsFilterSnapshot relaxed = DnsRuleStore.load(context);
        assertEquals(DnsFilterSnapshot.SOURCE_NONE, relaxed.blockSource("dns.google"));

        SettingsStore.setDnsStrictMode(context, true);
        DnsFilterSnapshot strict = DnsRuleStore.load(context);
        assertEquals(DnsFilterSnapshot.SOURCE_DOH, strict.blockSource("dns.google"));
        assertEquals(DnsFilterSnapshot.SOURCE_DOH,
                strict.blockSource("doh.example.cloudflare-dns.com"));
    }

    @Test
    public void fullListCanBeDisabledToSaveMemory() {
        assertTrue(DnsRuleStore.load(context).fullSize() > 0);
        SettingsStore.setDnsFullList(context, false);
        assertEquals(0, DnsRuleStore.load(context).fullSize());
    }

    @Test
    public void fingerprintTracksEveryRuleInput() {
        String base = DnsRuleStore.ruleFingerprint(context);
        assertEquals("stable for identical settings", base,
                DnsRuleStore.ruleFingerprint(context));

        SettingsStore.setDnsFullList(context, false);
        String withoutFull = DnsRuleStore.ruleFingerprint(context);
        assertFalse("extended list toggle must re-load rules", base.equals(withoutFull));

        SettingsStore.setDnsStrictMode(context, true);
        String withStrict = DnsRuleStore.ruleFingerprint(context);
        assertFalse(withoutFull.equals(withStrict));

        SettingsStore.setDnsCustomBlockText(context, "mine.example");
        String withCustom = DnsRuleStore.ruleFingerprint(context);
        assertFalse(withStrict.equals(withCustom));

        SettingsStore.setDnsCustomAllowText(context, "free.example");
        String withAllow = DnsRuleStore.ruleFingerprint(context);
        assertFalse(withCustom.equals(withAllow));

        // block mode does not change the rule sources and must NOT reload
        String beforeMode = DnsRuleStore.ruleFingerprint(context);
        SettingsStore.setDnsBlockMode(context, DnsPacket.BLOCK_MODE_NXDOMAIN);
        assertEquals(beforeMode, DnsRuleStore.ruleFingerprint(context));
    }

    @Test
    public void importRoundTripPersistsAndMerges() throws Exception {
        String rules = ""
                + "! a subscription file\n"
                + "||imported.example^\n"
                + "@@||free.imported.example^\n"
                + "0.0.0.0 hosts.imported.example\n"
                + "/regex/unsupported\n";
        InputStream input = new ByteArrayInputStream(
                rules.getBytes(StandardCharsets.UTF_8));
        DnsRuleParser.Parsed parsed = DnsRuleStore.importRules(context, input);

        assertEquals(2, parsed.ruleCount());
        assertEquals(1, parsed.skippedUnsupported);
        assertTrue(parsed.allowed.contains("free.imported.example"));

        SettingsStore.setDnsImportMeta(
                context, DnsRuleStore.describeImport(parsed, "demo.txt"));

        DnsFilterSnapshot snapshot = DnsRuleStore.load(context);
        assertEquals(DnsFilterSnapshot.SOURCE_IMPORTED,
                snapshot.blockSource("x.imported.example"));
        assertEquals(DnsFilterSnapshot.SOURCE_IMPORTED,
                snapshot.blockSource("hosts.imported.example"));
        assertTrue(snapshot.isAllowed("free.imported.example"));

        DnsRuleStore.clearImport(context);
        assertEquals(0, DnsRuleStore.load(context).importedSize());
        assertEquals("", SettingsStore.dnsImportMeta(context));
    }
}
