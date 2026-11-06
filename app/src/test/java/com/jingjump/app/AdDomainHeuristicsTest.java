package com.jingjump.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdDomainHeuristicsTest {
    @Test
    public void highConfidenceAdDomainsAreImmediate() {
        assertEquals(
                AdDomainHeuristics.SCORE_HIGH,
                AdDomainHeuristics.score("adservice.new-example.com")
        );
        assertEquals(
                AdDomainHeuristics.SCORE_HIGH,
                AdDomainHeuristics.score("x.ads.new-example.com")
        );
        assertEquals(
                AdDomainHeuristics.SCORE_HIGH,
                AdDomainHeuristics.score("pglstatp-other.example")
        );
    }

    @Test
    public void trackingLikeDomainsNeedMoreEvidence() {
        assertEquals(
                AdDomainHeuristics.SCORE_MEDIUM,
                AdDomainHeuristics.score("metrics.new-example.com")
        );
        assertEquals(
                AdDomainHeuristics.SCORE_MEDIUM,
                AdDomainHeuristics.score("beacon.new-example.com")
        );
    }

    @Test
    public void normalBusinessAndDownloadDomainsAreIgnored() {
        assertEquals(
                AdDomainHeuristics.SCORE_NONE,
                AdDomainHeuristics.score("api.qq.com")
        );
        assertEquals(
                AdDomainHeuristics.SCORE_NONE,
                AdDomainHeuristics.score("download.example.com")
        );
        assertEquals(
                AdDomainHeuristics.SCORE_NONE,
                AdDomainHeuristics.score("adobe.example.com")
        );
    }
}
