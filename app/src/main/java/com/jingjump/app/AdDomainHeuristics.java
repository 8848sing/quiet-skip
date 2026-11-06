package com.jingjump.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Heuristic scoring for "this domain looks like an ad domain". 1.9.0 rewrote
 * the label walk so a per-query call no longer splits the domain with a regex
 * nor re-normalizes an already normalized name: this runs on every DNS query
 * that is not in any block list, i.e. on the hottest path of the filter.
 */
final class AdDomainHeuristics {
    static final int SCORE_NONE = 0;
    static final int SCORE_MEDIUM = 1;
    static final int SCORE_HIGH = 2;

    private static final Set<String> HIGH_LABELS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "ad",
                    "ads",
                    "adx",
                    "adv",
                    "adserver",
                    "adservice",
                    "advert",
                    "adsystem",
                    "mobads",
                    "doubleclick",
                    "googlesyndication",
                    "applovin",
                    "mintegral",
                    "sigmob",
                    "tradplus",
                    "mbridge",
                    "unityads",
                    "pangle",
                    "pangolin",
                    "pglstatp",
                    "ctobsnssdk",
                    "adcolony",
                    "chartboost",
                    "inmobi"
            ))
    );

    private static final String[] HIGH_FRAGMENTS = {
            "adserver",
            "adservice",
            "advert",
            "adtech",
            "adclick",
            "adimp",
            "doubleclick",
            "googlesyndication",
            "mobads",
            "gdtimg",
            "pglstatp",
            "ctobsnssdk",
            "applovin",
            "mintegral",
            "sigmob",
            "tradplus",
            "pangolin",
            "unityads"
    };

    private static final String[] MEDIUM_FRAGMENTS = {
            "tracker",
            "tracking",
            "metrics",
            "analytics",
            "beacon",
            "bidding",
            "impression",
            "promotion",
            "creative"
    };

    private static final Set<String> SAFE_BUSINESS_DOMAINS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "qq.com",
                    "baidu.com",
                    "bdstatic.com",
                    "gtimg.cn",
                    "qpic.cn",
                    "weixin.qq.com",
                    "taobao.com",
                    "tmall.com",
                    "alipay.com",
                    "alicdn.com",
                    "pinduoduo.com",
                    "yangkeduo.com",
                    "jd.com",
                    "douyin.com",
                    "byteimg.com",
                    "bytescm.com",
                    "bilibili.com",
                    "weibo.com",
                    "zhihu.com",
                    "xiaohongshu.com",
                    "kuaishou.com",
                    "migu.cn",
                    "163.com",
                    "sina.com.cn"
            ))
    );

    private AdDomainHeuristics() {
    }

    static int score(String rawDomain) {
        String domain = DnsBlockList.normalizeDomain(rawDomain);
        if (domain.isEmpty() || "https".equals(domain) || "http".equals(domain)) {
            return SCORE_NONE;
        }
        return scoreNormalized(domain);
    }

    /** Same decision as {@link #score(String)} for an already normalized name. */
    static int scoreNormalized(String domain) {
        if (domain == null || domain.isEmpty()
                || "https".equals(domain) || "http".equals(domain)) {
            return SCORE_NONE;
        }
        boolean medium = false;
        int cursor = 0;
        int length = domain.length();
        while (cursor <= length) {
            int dot = domain.indexOf('.', cursor);
            int end = dot < 0 ? length : dot;
            if (end > cursor) {
                String label = domain.substring(cursor, end);
                if (isHighConfidenceLabel(label)) {
                    return SCORE_HIGH;
                }
                if (!medium && containsAny(label, MEDIUM_FRAGMENTS)) {
                    medium = true;
                }
            }
            if (dot < 0) {
                break;
            }
            cursor = dot + 1;
        }
        if (isSafeBusinessDomain(domain)) {
            return SCORE_NONE;
        }
        return medium ? SCORE_MEDIUM : SCORE_NONE;
    }

    private static boolean isHighConfidenceLabel(String label) {
        if (HIGH_LABELS.contains(label) || containsAny(label, HIGH_FRAGMENTS)) {
            return true;
        }
        return label.startsWith("ads-")
                || label.startsWith("adx-")
                || label.startsWith("adserver-")
                || label.startsWith("adservice-")
                || label.endsWith("-adserver")
                || label.endsWith("-adservice");
    }

    private static boolean isSafeBusinessDomain(String domain) {
        int dot = domain.indexOf('.');
        if (dot < 0) {
            return SAFE_BUSINESS_DOMAINS.contains(domain);
        }
        // A safe business domain is itself safe and every subdomain of it is
        // safe, so matching the last two/three labels is enough.
        String lastTwo = domain.substring(dot + 1);
        if (SAFE_BUSINESS_DOMAINS.contains(lastTwo)) {
            return true;
        }
        int secondDot = lastTwo.indexOf('.');
        if (secondDot < 0) {
            return SAFE_BUSINESS_DOMAINS.contains(domain);
        }
        String lastThree = lastTwo.substring(secondDot + 1);
        return SAFE_BUSINESS_DOMAINS.contains(lastThree);
    }

    private static boolean containsAny(String value, String[] fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}