# Quiet Skip 1.9.0

[English](README.md) | [简体中文](README.zh-CN.md)

Quiet Skip (originally 静跳, "JingJump") is a local ad-handling tool for Android. It combines accessibility selectors, DNS domain filtering and automatic ad-domain learning to dismiss splash ads, interstitial ads and some pop-ups.

No account, no telemetry, no remote rule subscription: everything runs on the device.

## 1.9.0 update (hot paths and reliability)

This release adds no new switches. It only trims the two most battery-hungry, stall-prone hot paths (DNS queries and accessibility scanning) and fixes a few defects that used to accumulate over time.

### DNS filtering

- **TCP DNS actually works now.** Since 1.8.0 the documentation claimed "TCP port 53 support", but the service never completed a TCP handshake: when a truncated response (TC=1) forced the client onto TCP, it simply waited until timeout. The new `TcpDnsRelay` performs the local SYN/SYN-ACK/ACK, forwards length-prefixed messages in order, reinjects responses in 1200-byte segments, supports up to 16 concurrent connections with a 10-second idle reaper, and answers RST immediately when it cannot serve a connection so the client fails fast instead of hanging.
- **Oversized responses now use standard truncation.** When an upstream response exceeded the tunnel MTU (1452-byte payload for IPv4 and IPv6), the old code answered SERVFAIL — which clients interpret as a broken server. It now returns a truncated answer containing only the question section with TC=1, so the client retries over TCP and, together with the relay above, actually gets the answer.
- **Cached TTLs decrement over time.** A cache hit subtracts the elapsed seconds from every record TTL, so clients no longer reuse a nearly expired record as if it had its original TTL.
- **Upstream socket reuse.** Each worker thread keeps one `protect()`-ed UDP socket and one resolved server address instead of creating a socket, calling `protect()` and re-resolving the IP for every query. The receive loop filters out late packets by query ID (previously a single mismatched packet was treated as a failure).
- **Bounded query thread pool.** 8 threads with a 96-entry queue; on overload, overflow queries get an immediate SERVFAIL instead of letting the backlog grow without limit.
- **Batched block counters.** Every blocked query used to write SharedPreferences (a flood of disk writes under an ad-SDK retry storm). Counters now accumulate in memory, flush once per second or every 25 events, and force a flush when the service stops, while the UI reading stays exact.
- **Faster rule loading.** Domain normalization gained a fast path for "already valid lowercase ASCII" rules (no IDN plus regex per rule) and reuses one precompiled ASCII regex; first-screen loading and reloads of 105k rules are noticeably faster. Ad-domain heuristics now scan label by label instead of building arrays with `split("\\.")`.
- **Per-packet exception isolation.** A malformed packet in the tunnel read loop used to push the whole DNS filter into an error state and stop it; now only that packet is dropped and the rest keep flowing.

### Accessibility skipping

- **Labels are compacted once per scan.** The text of a node used to be run through `compact()` six or more times per scan (each call doing four string replacements plus a regex). It is now computed once per node and reused by every later match. `compact()` itself moved from four `replace` calls plus `replaceAll` to a single character scan, which also strips the ideographic spaces (U+3000 and NBSP) common in Chinese buttons.
- **Memoized node attributes.** The 13 built-in generic GKD selectors walk the whole node tree; previously each selector re-asked every node for class name, text, description, bounds and visibility (all cross-process calls). Now each node is queried once per scan.
- **Invisible nodes no longer take memory.** Only nodes that are visible with a positive size enter the candidate list, and the "simple page" test uses traversal counts instead of holding objects; semantics are unchanged but object counts drop sharply.
- **Reused rule and profile objects.** `RuleStore.get()` gained a parse cache keyed by the raw preference text (no more re-parsing the whole JSON for every row while scrolling the rules page), and the four sensitivity profiles are now shared instances instead of being rebuilt per scan.
- **Fixed the self-renewing "ad evidence" window.** Once any ad-like text appeared, the cached evidence re-extended `evidenceUntil` by 5 seconds on every scan, so the app stayed in an "ad evidence present" state for as long as it was in the foreground and the mis-tap risk grew over time. Only evidence actually observed in the current scan extends the window now; cached evidence is consumed and expires.
- **App-rules page loads in the background.** App names and icons are read on a background thread, so the first frame is no longer blocked by loading icons for 200+ apps.

### Verification

- **All 113 local automated tests pass** (the 87 from 1.8.4 plus 26 new ones: 6 TCP relay, 4 truncation and TTL, 8 fast-path equivalence, 4 rule cache, 2 batched counters and so on).
- Version: `1.9.0 (18)`.

## 1.8.4 fixes and test coverage

- **Fixed the auto-learn switch not taking effect immediately.** The off direction happened to work because `inspectAndLearn` re-checked the preference internally, but turning it on waited for the next rule reload or filter restart. Toggling the switch now calls the reload entry point directly (`refreshSettings()` runs before the fingerprint check, so the rule database is not re-parsed).
- **Six tests for the auto-learn path** (previously zero coverage): first-time learning of a high-confidence domain, promotion after two medium-confidence hits, no learning while disabled, domain normalization (case, surrounding whitespace, trailing dot), candidate counts cleared together with the list, and ordinary domains never being learned.
- Version: `1.8.4 (17)`.

## 1.8.3 update (reload stability)

- **Merged reloads.** Rapidly toggling "extended rule database / strict mode" now serializes reloads on a single thread with at most one follow-up run, instead of parsing 105k rules concurrently (a memory-peak risk on low-end phones).
- **Reload avoidance.** A rule-input fingerprint (extended database switch, strict mode, manual black/white lists, imported file timestamps and sizes) means cheap switches such as "blocking method" no longer re-parse the whole database, while the switch itself still applies immediately.
- **Startup race.** Changing settings during startup is no longer silently ignored: the fingerprint is compared once startup finishes and a reload is scheduled if it differs.
- **hosts comment parsing.** Supports tab-separated trailing comments (`0.0.0.0 a.com<TAB># comment`); a `#` glued to a domain is no longer silently truncated but counted as a malformed line.
- Version: `1.8.3 (16)`.

## 1.8.2 update (caching and freshness)

- **Negative caching.** NODATA / NXDOMAIN TTLs are taken from the authority-section SOA per RFC 2308 (capped at 300 s) and cached, so ad SDKs polling non-existent domains stop hitting upstream — less battery, less latency.
- **Fixed the TC truncation flag mask** (`0x0002` → `0x0200`): real truncated responses were cached as complete and fed into CNAME checks, while NXDOMAIN answers with rcode=3 were misread as truncated. A regression test now covers it.
- **Upstream stickiness.** The last successful resolver is remembered and preferred, so cold queries no longer wait out the 900 ms timeout on every attempt when the primary is down.
- **Live block-log refresh.** While the rules page is open it checks a version counter every 1.2 s and rebuilds the list only when new blocks arrived.
- **Lighter UI polling.** The main status poll went from 800 ms to 1000 ms and skips repaints when the text is unchanged.
- Version: `1.8.2 (15)`.

## 1.8.1 update (performance and memory)

- **Rule pruning.** The extended database drops subdomains already covered by a parent entry both at packaging and at load time (112k → 105k entries, built-in table 145 → 131) with identical blocking decisions, lowering memory and load time.
- **Zero-copy snapshots.** Rule snapshots no longer make defensive copies of every source set; peak load memory is roughly halved.
- **Serial upstream with short timeouts.** The previous model that spawned two bare threads per query is gone; queries now run 900 ms × 2 serially with cache fallback, so DNS storms no longer cause thread churn.
- **Deduplicated block log.** Repeated blocks of the same domain and query type merge into one row with a counter (shown as ×N), so a single retry storm can no longer fill the 200-entry list.
- **Less work on the UI thread.** The system "Private DNS" check moved from an 800 ms poll to `onResume`; subscription import parsing moved off the main thread (background thread plus completion callback), so an 8 MB file no longer risks an ANR.
- **Fixed an ancestor-chain advance bug in rule pruning** (introduced by the new pruning feature; a regression test now pins behavior equivalence).
- **Imported hosts files no longer block local names.** `localhost`, `localdomain` and `broadcasthost` are format artefacts of hosts files and are exempt per RFC 6761.
- **Fixed whitelist entries broken by CNAME cloaking detection.** Whitelisted domains no longer get their response chain rewritten into a block response, and `@@` exceptions in the blacklist editor are merged into the whitelist following AdGuard semantics.
- **Fixed a crash on corrupted import data**, and upstream responses are now validated for the ID and QR bits.
- Version: `1.8.1 (14)`.

## 1.8.0 update (stronger DNS blocking)

- **Larger rule database.** Added the anti-AD extended list (about 112k domains, MIT licensed, bundled in the APK) covering advertising, tracking, scam and gambling sites; it has its own toggle on the home screen and is on by default.
- **AdGuard syntax.** Manual lists and subscriptions accept `||domain^`, `@@||domain^` exceptions, `/etc/hosts` lines and bare domains (the usable subset of the AdGuard DNS rule syntax; regex and modifiers such as `$client` or `$dnstype` that cannot work locally are ignored per the spec).
- **Whitelist.** A new highest-priority whitelist: once a domain matches, no rule (extended list, imported subscriptions or auto-learning) blocks it or its subdomains.
- **Subscription import.** Third-party lists can be imported from a local text file (AdGuard / hosts / bare domains, up to 8 MB or 400k entries) with accepted/ignored counts; nothing is downloaded remotely.
- **Sinkhole blocking.** The default blocking mode answers A queries with `0.0.0.0`, AAAA with `::1` and other types with an empty NOERROR (the Pi-hole / AdGuard Home approach), failing ad SDKs faster than NXDOMAIN; the old NXDOMAIN behavior is still selectable.
- **Full IPv6.** The TUN interface gained a ULA v6 address and can resolve and answer IPv6 DNS queries (including RFC 1071 UDP checksums).
- **Broader coverage.** Routes for common public resolvers are taken over (AliDNS 223.5.5.5/223.6.6.6, Tencent DNSPod, Google 8.8.8.8/8.8.4.4, Cloudflare 1.1.1.1/1.0.0.1, 114, Baidu and others — 23 routes as /32 and /128), so apps with hard-coded DNS still get filtered. Only DNS server addresses are routed; ordinary traffic is not.
- **TCP DNS.** TCP queries on port 53 are resolved and answered directly, so large or truncated-retry responses no longer bypass filtering.
- **Strict mode (optional, off by default).** Blocks known DoH/DoT provider domains (dns.google, cloudflare-dns.com, dns.alidns.com, doh.pub, dns.adguard.com and 21 in total) and answers TCP RST to port 853 handshakes on hijacked resolvers, forcing encrypted-DNS clients back to filterable plain DNS; the home screen warns when the system Private DNS is enabled.
- **CNAME cloaking detection.** Upstream responses are scanned for answer names and CNAME targets that match the rules and replaced with a block response, catching cloaked tracking that points normal domains at ad domains.
- **Concurrent upstream plus local cache.** Ali and Tencent resolvers race concurrently (1.4 s total timeout) and responses are cached locally by TTL (256 entries), so repeated queries cost no traffic.
- **Block log.** The most recent 200 blocks (time, domain, query type, matched source) are kept in memory only, and tapping one white-lists that domain.
- **Hot rule reload.** Editing the black/white lists or toggling the extended list or strict mode needs no DNS filter restart.
- The auto-learn cap went from 300 to 600 domains and the learning set lives in an in-memory cache, so the query path no longer re-parses it.
- Version: `1.8.0 (13)`.

## Sensitivity profiles

| Profile | First scan | Follow-up scan | Active window | Continuous sweep | Resident scan | Delayed follow-up | Structural matching |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Conservative | 150 ms | 620 ms | 8 s | 8 s / 760 ms | 1000 ms | none | no |
| Standard | 90 ms | 420 ms | 12 s | 12 s / 500 ms | 650 ms | 3× / 1.5 s | no |
| Sensitive | 55 ms | 240 ms | 20 s | 20 s / 380 ms | 450 ms | 8× / 1.2 s | yes |
| Instant | 30 ms | 140 ms | 35 s | 30 s / 260 ms | 320 ms | 14× / 0.9 s | yes + text-free corners |

Guidance:

- Most apps work fine on "Standard".
- Apps that delay their ads, or whose pop-ups are icon-only, do better on "Sensitive".
- Apps with especially many splash ads, where occasional mis-taps are acceptable, can use "Instant".
- Keep anti-mis-tap protection on for apps with comments, input fields or payment flows.

## Install

1. Install `quiet-skip-v1.9.0.apk`.
2. Open Quiet Skip, read the notice and tap "Enable accessibility service", then enable "Quiet Skip · ad skipper" under the system's installed services.
3. For network-level blocking, tap "Enable DNS blocking" and grant the VPN permission.
4. Adding the app to the battery-optimization allowlist is recommended to reduce the chance of the system killing its services.

On some Android 13+ phones that block accessibility services for side-loaded apps, open "Settings → Apps → Quiet Skip → overflow menu" and choose "Allow restricted settings".

## The two filtering layers

### Accessibility skipping

- Recognizes short buttons such as "Skip", "Skip ad", "跳过" and common ad control IDs.
- Ships GKD-style selector rules that match nodes by class name, `text/vid/id/desc`, comparison operators and parent, ancestor or sibling relations.
- "App rules" lets you append several custom selector lines per app, for example `TextView[text*="跳过"][visibleToUser=true]`.
- Every app can pick its own sensitivity; the home screen sets the default.
- Each app also has its own "continuous scan" switch; when on, that app is scanned repeatedly at the resident interval of its sensitivity while it is in the foreground.
- Before tapping a "close"-like button it checks ad text, control ID, visibility and size; anti-mis-tap mode only allows an unambiguous skip button at the top or the bottom-right corner.
- Consecutive pop-ups: after one successful close it keeps sweeping per profile, handling 10 to 45 pop-ups in a row depending on the profile.
- When strong ad evidence appears (a tap that jumps to a third-party app, "shake", "download now"), small text-free close icons in the top-left, top-right and bottom-right corners are recognized.
- "Try text-free buttons" is off by default; the Sensitive and Instant profiles attempt corner close icons automatically when structural evidence holds.

### DNS ad blocking

- The VPN notification, in-app status, starting, running and failed states all share one persisted state.
- Ships a conservative ad-domain list plus the anti-AD extended database (about 112k entries, MIT licensed, see `assets/LICENSE-anti-ad.txt`), toggleable on the home screen and on by default.
- Supports AdGuard syntax (`||domain^`, `@@||domain^` exceptions), `/etc/hosts` lines and bare domain lists; regex and other `$` modifiers are ignored per the AdGuard DNS spec.
- The whitelist has the highest priority: once a domain matches, no rule (extended list, imported subscriptions, auto-learning) blocks it or its subdomains.
- The "Rules and block log" page edits manual black/white lists, imports subscriptions from local text files (8 MB / 400k entries), shows the last 200 blocks and white-lists a domain with one tap.
- The default blocking mode is a fake address: A answers `0.0.0.0`, AAAA answers `::1`, other types get an empty NOERROR; the old NXDOMAIN behavior remains selectable.
- Auto-learning of ad domains: high-risk markers such as `adservice`, `adserver`, `adx`, `mobads` and ad SDK names are learned and blocked on first sight; weaker markers such as `tracking`, `metrics` and `beacon` need a second sighting; the cap is 600 entries.
- Resolves IPv4 and IPv6 UDP and TCP port 53 queries; takes over `/32` and `/128` routes for common public resolvers (Ali, Tencent, Google, Cloudflare, 114, Baidu, Quad9 and others) without touching ordinary web, video or download traffic.
- Strict mode (off by default): blocks known DoH/DoT provider domains and refuses port 853 handshakes on hijacked resolvers to mitigate encrypted-DNS bypass; the home screen warns when the system Private DNS is on.
- CNAME cloaking detection: a response whose answer names or CNAME targets match the rules is replaced with a block response.
- Two upstream servers (`223.5.5.5`, `119.29.29.29`) race concurrently, responses are cached locally by TTL (256 entries), and rule edits hot-reload without restarting the filter.
- Enabling it occupies the single Android VPN slot, so it cannot run alongside other VPNs or network accelerators.
- Ad assets already downloaded locally may still display once; DNS only stops later configuration and asset requests.
- Apps with private HTTPDNS (direct IP), fixed IPs or domains shared with their real business traffic still slip through; strict mode only partly mitigates DoH/DoT.

## Shake-to-jump ads

Shake-triggered ads are driven by the motion sensor. Quiet Skip closes the page as soon as it appears; to stop the trigger itself, search for "Motion and orientation" or "Body sensors" in the system settings and revoke that permission from the offending app.

## Staying alive in the background

- The main activity is set to `excludeFromRecents`, so it does not show in the recent-tasks list; swiping the task away does not stop the accessibility service or the DNS foreground service.
- The "Background survival (Redmi)" section opens the battery-optimization allowlist, MIUI autostart management and battery saver settings directly.
- On Redmi phones, allow Quiet Skip to autostart and set the battery saver policy to "No restrictions".
- With DNS filtering on, the VPN foreground service raises process survival; on Android 13+ allowing notifications is recommended.

## Privacy and permissions

- `INTERNET` is used only to forward DNS queries to the two public resolvers above; there are no web requests, no remote rule subscriptions, no accounts and no self-hosted service, and subscriptions come only from local files.
- There is no server, no account, no analytics SDK and no telemetry.
- Input-field nodes are not read, button text is not recorded, and app lists or screen contents are never uploaded.
- The installed-app list is used only to pick per-app rules locally.
- DNS rules, switches and counters live in the local `SharedPreferences`; imported subscriptions live in the local `files` directory.
- Auto-learning stores only candidate ad domains and a few pending counts; the block log is memory-only (last 200) and vanishes when the process ends.

## Implementation notes

- `minSdk 26`, `targetSdk 35`, `compileSdk 35`.
- `AccessibilityService` judges buttons by text, control ID, ad markers, window type, window hierarchy, node structure, size and position.
- `SensitivityProfile` resolves each app to its own scan policy; `RuleStore` persists the `sensitivity` field (`-1` means follow the global default).
- Before tapping it checks visibility, size, area and clickable ancestors, and it throttles tap rate and actions per window.
- No repeat taps at the same coordinates within 1.6 s; the Sensitive and Instant profiles move on to the next candidate.
- `VpnService` parses IPv4/IPv6 UDP DNS queries and opens real relayed TCP 53 connections (handshake, ordering, segmented reinjection); rule hits produce a local block answer (NXDOMAIN or fake address) while other queries go through the serial upstream with TTL caching (remembering the last successful resolver); in strict mode, port 853 TCP handshakes are refused.
- The VPN configuration explicitly excludes Quiet Skip itself so upstream queries never re-enter the local TUN.
- The GKD selector support is an independently implemented subset of the common syntax, not a copy of GPL-3.0 GKD service code. It supports common attribute comparisons, the `@` target marker, `>`, `>n`, `<`, `<n`, `<<n`, `+n` and `-n`; computed expressions and complex range syntax are not supported.
- The DNS rule syntax and blocking answers follow the AdGuard DNS rule specification and Pi-hole's defaults; the extended database comes from anti-AD (MIT). No project code was copied.

## Boundaries

- Ads drawn by game engines, video frames or pure Canvas without accessibility nodes cannot be detected.
- Pages completely hidden from the accessibility tree cannot be handled.
- When ads share a container with normal UI, automatic tapping cannot be 100% accurate; higher profiles mean a higher chance of mis-taps.
- Some apps use private HTTPDNS (direct IP), fixed IPs or persistent connections for ads, which DNS filtering cannot cover; DoH/DoT is only partly mitigated by strict mode.
- Player picture-in-picture, system-level pop-ups and a system-frozen accessibility service can let ads through.
- This release was not tested on a physical device; it passed Robolectric API 28/35 activity startup tests and all 113 local tests, but real-device behavior still needs your verification.

## Build

Requires JDK 17, Android SDK 35 and Gradle 8.10.2:

```powershell
.\gradlew.bat :app:lintDebug :app:assembleRelease
```

Signing can keep using `apk-sign.bat` at the project root (which reads `tools/mykey.keystore`).

This project is an independent implementation. It draws on the publicly visible ideas of "ad evidence + control detection + safe tapping" and "local DNS filtering" from an APK supplied by the user, without reusing its service code or assets.