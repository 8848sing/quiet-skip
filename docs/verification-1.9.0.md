# Quiet Skip 1.9.0 verification report

[English](verification-1.9.0.md) | [简体中文](verification-1.9.0.zh-CN.md)

Verification date: 2026-11-06

## Build artifacts

- Package name `com.jingjump.app`; version `1.9.0`; versionCode `18`
- minSdk `26` / targetSdk `35` / compileSdk `35`
- APK size: `935,581` bytes (1.8.4 was `931,678`, so +3,903 bytes, all from the TCP relay and hot-path rewrites; test code is not packaged)
- APK SHA-256: `56CBA90371923B4B59E8FB41DD6B0C4EB26B0B4799EAA5287DCE97E9B4246DC4`
- Source package SHA-256: `481B01F39E4FDCC81533EBEDE577170080732AD04C5C28EB266570325DEA2A1A`
- Signing certificate SHA-256: `4440FC1AA0D6BA29D6BDE33233EC9A65DE4CD3457371D01F72641969DACFA3A8` (identical to every release since 1.7.0, so it installs over the top)
- Signing scheme: v2 + v3 (identical to the 1.8.4 artifact; v1 is not enabled, and minSdk 26 does not need it); single signer; zipalign 4-byte alignment passes; badging confirms `1.9.0 (18)`

## Build environment

JDK Temurin `17.0.10+7`; Gradle `8.10.2`; AGP `8.7.3`; SDK Platform `35`; Build Tools `34.0.0`; Robolectric `4.14.1` (API 28/35).

> Environment note: the local Gradle wrapper could not download its distribution (JDK trust chain plus a non-ASCII user directory), so the machine's Gradle 8.10.2 installation was used directly; dependency resolution went online once and was then cached. The build scripts themselves were not modified.

## Automated tests

```text
OK (113 tests)
```

All 87 tests from 1.8.4 still pass; this release adds 26:

| Test class | Count | Coverage |
| --- | --- | --- |
| `TcpDnsRelayTest` | 6 | handshake (SYN → SYN-ACK), length-prefixed queries and responses, a 3000-byte response split into 3 segments with continuous sequence numbers, retransmissions acknowledged without duplicating upstream traffic, RST for packets without a handshake, refusal of new handshakes beyond 16 connections, FIN answered with FIN-ACK and connection recycling |
| `DnsPacketHardeningTest` | 5 | correct pseudo-header checksum in `buildTcpPacket`, truncated response keeping the question section with TC=1, `ageTtls` decrementing every record TTL, cache hits returning the remaining TTL, oversized responses not entering the cache |
| `MatchingFastPathTest` | 9 | normalized fast path agreeing with the IDN slow path (case, trailing dot, over-long names, internationalized domains), heuristic `scoreNormalized` matching `score` entry by entry, safe business domains never learned, `compact` stripping spaces, ideographic spaces and zero-width characters, view ID normalization, compact and string overloads being equivalent, the safety policy pre-compacting marker words |
| `RuleStoreCacheTest` | 4 | readable immediately after saving, cache invalidated when the document changes, unknown packages returning default rules (including Bilibili's default anti-mis-tap), falling back to defaults after preferences are cleared |
| `SettingsCounterTest` | 2 | exact readings before and after a batched counter flush, reset clearing the not-yet-flushed part too |

## Defects found and fixed in this release

1. **TCP DNS was unusable (a documentation error since 1.8.0)**
   - The service never answered SYN with SYN-ACK: SYNs were silently dropped and clients retried until timeout. `DnsPacket.buildTcpReply()` also used the client's sequence number as its own, so even if it had been reached it could not build a legal packet.
   - Consequence: when an upstream response was truncated (TC=1), the system resolver's TCP retry always failed, which the user saw as "a web page spinning forever".
   - Fix: a new `TcpDnsRelay` handles the handshake, sequence numbers, in-order reinjection and RST fallback; the TCP branch in `buildReply()` was removed so no "looks supported" dead code remains.

2. **Oversized responses answered with SERVFAIL**
   - Old logic: `upstream.length > 1472` answered SERVFAIL immediately. SERVFAIL means "server failure", so clients concluded the resolver was down instead of retrying over TCP.
   - Fix: return a RFC 1035 truncated answer (question section only, TC=1); the IPv6 payload limit was also corrected from 1472 to 1452 (the old value produced an out-of-bounds 1508-byte packet for IPv6).

3. **One malformed packet terminated the whole DNS filter**
   - Old logic: `catch (Throwable)` in `readTunnel()` wrapped the entire read loop, so an exception from any packet set the error state and called `shutdown()`.
   - Fix: per-packet isolation; the bad packet is dropped and the rest continue.

4. **The "ad evidence" window renewed itself**
   - Old logic: cached evidence was merged into `adEvidence` and then also triggered `evidenceUntil = now + 5s`, which kept "ad evidence present" true for as long as the app was in the foreground and left the generic close-button logic armed indefinitely.
   - Fix: only evidence actually observed by the current scan extends the window (8 s for strong evidence, 5 s for ordinary); cached evidence is consumed without renewal.

5. **Upstream response validation too strict, causing false failures**
   - Old logic: on a reused port, the first late packet with a mismatched ID made the code `return null` (followed by SERVFAIL or switching servers).
   - Fix: with socket reuse, mismatched packets are discarded and the code keeps waiting until the timeout, while the ID and QR validation is retained.

## Hot-path measurements

Using the same real rule database (`full_blocklist.txt`, 105,236 entries) and 48,000 typical button labels, the 1.8.4 and 1.9.0 implementations were compared on JVM 17 (third round after warm-up, single threaded):

| Item | 1.8.4 | 1.9.0 | Output equivalence |
| --- | --- | --- | --- |
| Domain normalization (105,236 entries) | 90 ms | 5 ms | checksums identical |
| Button label compaction (48,000 entries) | 14 ms | 1 ms | output lengths identical |
| Ad-domain heuristics (105,236 entries) | 196 ms | 62 ms | total scores identical (8,172) |

Notes:

- Domain normalization only takes the fast path when the domain is already valid lowercase ASCII (the vast majority of DNS queries and built-in rules); internationalized domains still go through the IDN slow path, so behavior is unchanged.
- The three accessibility changes (memoized node attributes, one compaction per node, invisible nodes excluded from the candidate list) cannot be quantified without a device; only semantic equivalence can be guaranteed, and decision conditions, scoring and candidate order are unchanged.

## Artifact verification

- badging: `1.9.0 (18)`; certificate fingerprint identical to previous releases; zipalign passes; `apksigner verify` passes (v2 + v3).
- Lint (`:app:lintRelease`): `No issues found.`
- The source package contains `TcpDnsRelay`, `DnsPacketHardeningTest`, `TcpDnsRelayTest`, `MatchingFastPathTest`, `RuleStoreCacheTest`, `SettingsCounterTest` and all existing sources and tests.
- **The source package builds independently and reproduces the result**: extracted into an empty directory with only `local.properties` added, `:app:assembleRelease` and all 113 tests pass, producing 927,957 bytes — the same byte count as the unsigned APK built in the workspace.
- **dex review**: `classes.dex` contains `TcpDnsRelay`, `buildTcpPacket`, `buildTruncatedResponse`, `ageTtls`, `upstreamTcpQuery`, `looksLikeSkipIdNormalized`, `compactMarkers` and `isCommentSensitiveContextCompact`, and does not contain the replaced `buildTcpReply`.
- Rule assets are the same version as 1.8.3/1.8.4 (`full_blocklist.txt`, 2,119,318 bytes, unchanged).

## Physical-device checks

`adb devices` shows no connected device, so no physical-device verification was done. Priorities on a real phone:

1. Turn on DNS filtering and browse, watch video, and confirm there are no new resolution failures (especially the TCP relay).
2. Open an app that fires many tracking requests and confirm the home-screen "blocked" counter grows correctly while the UI stays smooth (batched counter flushing).
3. Open the "App rules" page and check how fast the app list appears (background loading) and how smoothly it scrolls (rule caching).
4. Stay on a Bilibili video detail page for a while and confirm ordinary controls are not mis-tapped because of long-lived "ad evidence" (evidence window fix).

## Known boundaries

- Same as 1.8.3/1.8.4 (HTTPDNS, fixed IPs, shared business domains, encrypted DNS only partly mitigated in strict mode, already-cached assets); no new boundaries.
- New boundary: the TCP relay allows at most 16 concurrent connections with 10-second idle recycling; beyond that it answers RST (fails faster, but fails). DNS over HTTPS/TLS still has only partial mitigation in strict mode.