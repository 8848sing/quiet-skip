package com.jingjump.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.StringReader;

public final class DnsRuleParserTest {

    @Test
    public void parsesAdblockStyleRules() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                "! comment line\n"
                        + "# another comment\n"
                        + "||example.com^\n"
                        + "@@||free.example.com^\n"
                        + "plain.org\n"
                        + "||kept.important^$important\n"
        ), 100);
        assertTrue(parsed.blocked.contains("example.com"));
        assertTrue(parsed.blocked.contains("plain.org"));
        assertTrue(parsed.blocked.contains("kept.important"));
        assertTrue(parsed.allowed.contains("free.example.com"));
        assertEquals(4, parsed.lines);
        assertEquals(0, parsed.skippedUnsupported);
    }

    @Test
    public void parsesHostsFileLines() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                "0.0.0.0 ads.example.net\n"
                        + "127.0.0.1 tracking.example.net\n"
                        + "::1 ipv6.example.net\n"
                        + "0.0.0.0 alias.example.net second.example.net\n"
                        + "1.2.3.4 realhost.example.net\n"
        ), 100);
        assertTrue(parsed.blocked.contains("ads.example.net"));
        assertTrue(parsed.blocked.contains("tracking.example.net"));
        assertTrue(parsed.blocked.contains("ipv6.example.net"));
        assertTrue(parsed.blocked.contains("alias.example.net"));
        assertTrue(parsed.blocked.contains("second.example.net"));
        assertTrue(parsed.blocked.contains("realhost.example.net"));
    }

    @Test
    public void skipsUnsupportedSyntax() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                "/some.regex.*/\n"
                        + "||client.rule^$client=192.168.0.1\n"
                        + "||dnstype.rule^$dnstype=AAAA\n"
                        + "||badfilter^$badfilter\n"
                        + "element.example##.ad\n"
                        + "||wild*card^\n"
                        + "@@||exception.dnstype^$dnstype=A\n"
        ), 100);
        assertTrue(parsed.blocked.isEmpty());
        assertTrue(parsed.allowed.isEmpty());
        assertEquals(6, parsed.skippedUnsupported);
    }

    @Test
    public void honoursRuleCap() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                "a.com\nb.com\nc.com\nd.com\n"
        ), 3);
        assertEquals(3, parsed.blocked.size());
        assertTrue(parsed.truncated);
    }

    @Test
    public void stripsLeadingAndTrailingDots() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                ".leading.com.\n||trailing.com^\n"
        ), 100);
        assertTrue(parsed.blocked.contains("leading.com"));
        assertTrue(parsed.blocked.contains("trailing.com"));
        assertFalse(parsed.blocked.contains(".leading.com."));
    }

    @Test
    public void hostsTabSeparatedInlineCommentIsStripped() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                "0.0.0.0 ads.example\t# tab comment\n"
                        + "127.0.0.1 localhost # space comment\n"
                        + "||glued.example^#not-a-comment\n"
        ), 100);
        assertTrue(parsed.blocked.contains("ads.example"));
        // reserved name still skipped even with a trailing comment
        assertFalse(parsed.blocked.contains("localhost"));
        // '#' glued to a token must not silently truncate into a valid domain
        assertFalse(parsed.blocked.contains("glued.example"));
        assertTrue(parsed.malformed >= 1);
    }

    @Test
    public void plainListIgnoresCommentsAndBlanks() {
        java.util.Set<String> domains = DnsRuleParser.parsePlainList(
                "# header\n\nexample.com\n  sub.example.org  \n! skip\n"
        );
        assertEquals(2, domains.size());
        assertTrue(domains.contains("example.com"));
        assertTrue(domains.contains("sub.example.org"));
    }

    @Test
    public void reservedHostnamesNeverBecomeBlockRules() throws Exception {
        DnsRuleParser.Parsed parsed = DnsRuleParser.parse(new StringReader(
                "0.0.0.0 localhost\n"
                        + "127.0.0.1 localhost localdomain\n"
                        + "::1 broadcasthost\n"
                        + "ad.example\n"
                        + "@@||localhost^\n"
        ), 100);
        // only the genuine ad entry survives; hosts-convention names are skipped
        assertEquals(1, parsed.blocked.size());
        assertTrue(parsed.blocked.contains("ad.example"));
        // whitelisting a reserved name stays harmless but legal
        assertTrue(parsed.allowed.contains("localhost"));
    }
}
