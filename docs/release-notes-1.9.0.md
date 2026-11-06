# Quiet Skip 1.9.0 release notes

[English](release-notes-1.9.0.md) | [简体中文](release-notes-1.9.0.zh-CN.md)

v1.9.0 is a **performance and reliability** release: no new switches, and the UI is essentially the same as 1.8.4, but the two most battery-hungry paths (DNS filtering and accessibility scanning) were trimmed heavily, and several defects that accumulated over time or broke features outright were fixed.

## What you will most likely notice

1. **DNS filtering is much more resilient**
   - **TCP DNS actually works.** The docs used to claim TCP queries were supported, but the handshake was never completed: once an upstream response was truncated and the system retried over TCP, the query just waited until timeout (which looks like a web page spinning forever). The local side now completes the TCP handshake and really forwards the query.
   - **Oversized responses no longer turn into "server errors".** Previously a response that did not fit in one packet was answered with SERVFAIL, which clients interpret as a broken resolver; it now returns a standard "truncated" flag so the client retries over TCP and gets its answer.
   - **One bad packet no longer takes the whole filter down.** A malformed packet in the tunnel used to put DNS filtering into an error state and stop it; now only that packet is dropped.
2. **Less battery drain, fewer stalls**
   - Each DNS query no longer creates a socket, re-authorizes the VPN and re-resolves the server address; connections are reused on the worker thread.
   - The block counter accumulates in memory and flushes to disk once per second (previously every single block wrote to disk, which a flood of ad retries turned into continuous writes).
   - First-screen loading and reloading of the 105k-rule database is noticeably faster (measurements are in the verification report).
3. **Fewer mis-taps**
   - Fixed the "self-renewing ad evidence" defect: once the word "ad" appeared on screen, the app stayed in an "ad evidence present" state until you switched apps; the evidence window now expires as intended, so ordinary pages are not treated as ad pages for long.
   - The same text is no longer compacted six times per scan, and node attributes are read once, so heat and stalls under the highest sensitivity drop.
4. **The app-rules page opens faster**: app names and icons load in the background, so the first frame is no longer blocked.

## Install

1. Install `quiet-skip-v1.9.0.apk` over the top (the signature matches every release since 1.7.0); settings, rules, whitelist and counters are all preserved.
2. If DNS filtering was running, tap "Enable DNS blocking" once more after upgrading.
3. If the accessibility service shows "waiting for authorization", turn "Quiet Skip · ad skipper" off and on again in the system accessibility settings.

## Verification

- **All 113 local automated tests pass** (the 87 from 1.8.4 plus 26 new ones).
- Build, lint (0 issues), zipalign and v2/v3 signature verification pass; the certificate matches previous releases; badging confirms `1.9.0 (18)`.
- No physical-device verification was performed (no device connected to this machine).

## Cautions

- DNS filtering still handles DNS only (not ordinary web, video or download traffic) and occupies Android's single VPN tunnel.
- The new TCP DNS relay is covered by unit tests for the handshake, segmentation and failure fallback, but it **has not been tried on a real device**. If one app cannot reach the network after upgrading, turn DNS filtering off first to compare: this is the only part that could introduce new problems.
- Auto-learning, the whitelist and subscription import logic are unchanged.