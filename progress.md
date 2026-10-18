# SUZIEE v1.2 — Playback Defect Investigation (progress log)

Date: 18 Oct 2026 · Base: `v1.2-build-19` · Scope: smallest safe changes, no feature removals.

This log covers **two** fix passes. The pass in the first half did not fix the reported
symptoms, so the second pass (below, the authoritative one) re-investigated the actual
pipeline instead of re-tuning the same knobs.

---

# PASS 2 — root causes of the persisting symptoms

## BUG 1 — Official songs still played duplicate / wrong audio

### Root causes (verified by reading the code paths, not assumed)

1. **The sniffer could accept audio belonging to a *different* video.** It captured the
   first `googlevideo` `mime=audio` request and used it immediately. When the requested
   video is unavailable/blocked, YouTube **auto-advances** to another video; that video's
   audio request was captured and handed to ExoPlayer as if it were the requested song —
   wrong audio, fully playable, no error shown.
2. **The WebView also played audio.** The headless WebView ran a real YouTube player with
   `mediaPlaybackRequiresUserGesture = false` and only muted it through a best-effort JS
   nudge. `stopLoading()` does not stop media, so the browser could emit sound at the same
   time as ExoPlayer — two overlapping sources, which is heard as "duplicate" audio.
3. **SoundCloud silently substituted a different recording.** The fallback matched
   heuristically (score + penalties) and played whatever scored highest, so a
   fan-made / slowed / cover / unrelated upload could replace an official-song request.
4. **Alternate-version penalties never fired for decorated titles.** The penalties ran on
   the *normalised* title, and `normalize()` deletes parenthesised text — exactly where
   uploads put `(Slowed + Reverb)`. Those uploads were effectively unpenalised.
5. **No duration cap exists** (checked): `MIN_TRACK_DURATION_MS` is only a lower bound and
   the SoundCloud tolerance scales with length, so `IsDownloaded`/long-track rejections are
   not the cause.
6. Ruled out with evidence: `track.streamUrl` is written to the DB but **never** used for
   playback; there is exactly **one** `ExoPlayer.Builder` and **one** `MediaSession.Builder`
   in the codebase; `setMediaSource` clears the previous playlist; `playerManager.release()`
   is never called; each track resolves once (`resolveJob` is cancelled on new selection).

### Fixes
- `YouTubeWebViewSniffer.kt`
  - The captured request is now **verified against the requested video id**
    (`movie_player.getVideoData().video_id`) before it is used. A capture belonging to a
    different video is discarded and sniffing continues within the window. The check is
    permissive by design (if the page cannot report an id, the capture is used) so it can
    only *remove* wrong audio, never break a working capture.
  - The intercepted audio request is now answered with an **empty body**. We already hold
    the signed URL, so the page downloads nothing and plays nothing: the WebView can no
    longer emit a second audio stream (and saves the bandwidth).
- `SoundCloudMatch.kt` (**new**, pure/JVM-testable) + `SoundCloudApiClient.kt`
  - New **strict verification gate** (`isStrictVerifiedMatch`): no alternate-version
    markers, title must clearly be the song, the artist must be corroborated (uploader or
    title), and the duration must be within 10 s / 6 %. Used whenever SoundCloud stands in
    for a requested YouTube-catalogue recording.
  - Marker detection now runs on the **raw** title/uploader (fixes root cause 4) and is
    **word-boundary aware**, so "De*liv*er"/"Dis*cover*" are not treated as live/cover.
  - `normalize()` keeps Unicode letters/digits instead of deleting non-ASCII, so CJK titles
    (e.g. `夜に駆ける`) are comparable at all — before, they normalised to `""` and every
    CJK match scored 0.0.
  - Non-strict behaviour (downloads, non-YouTube ids) is unchanged except that the
    penalties now actually apply to decorated titles.
- `MusicRepository.kt` / `MusicPlayerManager.kt`
  - `resolveStreamUrl(track, allowHls, strict)` threads the flag through; playback passes
    `strict = true` for YouTube-catalogue requests, downloads stay non-strict (no
    regression).
  - **No silent substitution**: if strict verification finds nothing, the player shows an
    honest error (`unplayableMessage`) instead of playing a different recording. Explicit
    requests for slowed/remix/cover versions are honoured (the marker check is skipped when
    the user asked for it).

## BUG 2 — Web-sniffer window must be 8 seconds

### Root cause / change
- The window was `SNIFF_TIMEOUT_MS = 5_000`. Set to **8_000 ms**.
- It is a **ceiling, not a wait**: `CompletableDeferred` completes the moment a verified
  audio request is captured, and the sniff is awaited to completion before the fallback, so
  SoundCloud never starts while the primary source is still resolving.
- Only `YouTubeWebViewSniffer.SNIFF_TIMEOUT_MS` changed. Network timeouts
  (`DefaultHttpDataSource` 15 s connect / 20 s read, `MusicRepository.RESOLVE_TIMEOUT_MS`,
  ExoPlayer buffering) are untouched.
- **Logging for verification**: the sniffer logs `resolved <id> in <ms>ms`, or
  `produced no stream for <id> after <ms>ms (window 8000ms) - SoundCloud fallback allowed`;
  `MusicPlayerManager` logs `PRIMARY OK: verified <mime> stream in <ms>`, or
  `PRIMARY FAILED: ... after <ms>ms (window 8000ms) - falling back to SoundCloud (strict=…)`,
  and `FALLBACK OK: SoundCloud stream (strict=…, attempt n)`.

## BUG 3 — Notification panel and lock screen

### Root causes (verified)
- **No working Next/Previous anywhere in the system UI.** The app deliberately keeps **one**
  media item loaded (queue/repeat logic lives in `MusicPlayerManager`), so the raw
  `ExoPlayer` reports `COMMAND_SEEK_TO_NEXT/PREVIOUS_MEDIA_ITEM` as **unavailable**. Media3
  only advertises transport buttons the player supports → the notification/lock screen had
  no next/previous controls (Play/Pause worked, which matches the reports).
- Still valid from pass 1: the missing `sessionActivity` (no tap target) and the one-shot
  `serviceStarted` flag (after `onTaskRemoved()` self-stopped the service, later tracks had
  no session at all → no notification). Both were fixed in pass 1 and are kept.
- Verified as correct, no change needed: manifest service declaration
  (`foregroundServiceType="mediaPlayback"`, exported, Media3 intent filter), the foreground
  / notification / wake-lock permissions, the runtime POST_NOTIFICATIONS request on every
  launch when not granted, per-track `MediaMetadata` (title/artist/artwork), exactly one
  player and one session, the session is never released by navigation (only
  `MediaPlaybackService.onDestroy`), and `onTaskRemoved` keeps the service alive while
  playing.

### Fix
- `MediaPlaybackService.kt`: the session now uses a `ForwardingPlayer` (`QueuePlayer`) that
  (a) adds the next/previous commands to `getAvailableCommands()` and (b) implements
  `seekToNext(MediaItem)/seekToPrevious(MediaItem)` by delegating to
  `MusicPlayerManager.playNext()/playPrevious()`. `onConnect` additionally advertises those
  commands to controllers, so the buttons appear even if the default player command set does
  not include them. This is the Media3-documented way to extend a session player; no second
  player, no second notification, and the UI stays in sync because it shares the same
  `ExoPlayer` and playback state.
- `MusicPlayerManager.kt`: `setSeekBackIncrementMs(10_000)` /
  `setSeekForwardIncrementMs(10_000)`, so the system's rewind/forward buttons also work.

## Preserved functionality
No databases cleared, no downloads deleted, no app identity/signing change, no feature
removed. Anime + mainstream rails, search, voice commands, SoundCloud fallback, downloads +
persistence, Room data, LRCLIB lyrics, Compose UI/onboarding and the GitHub Actions
build/signing workflow are untouched.

## Changed files (pass 2)
| File | Change |
|------|--------|
| `app/src/main/java/com/example/playback/YouTubeWebViewSniffer.kt` | video-id verification of the capture; empty response for the audio request (no second sound source); **8 s** window; elapsed-time/outcome logging |
| `app/src/main/java/com/example/data/remote/soundcloud/SoundCloudMatch.kt` | **new** — raw-title marker detection, Unicode-aware normalisation, strict verification gate |
| `app/src/main/java/com/example/data/remote/soundcloud/SoundCloudApiClient.kt` | `strict` mode; raw + word-boundary penalty detection; delegates to `SoundCloudMatch` |
| `app/src/main/java/com/example/data/repository/MusicRepository.kt` | `resolveStreamUrl(..., strict)` |
| `app/src/main/java/com/example/playback/MusicPlayerManager.kt` | strict fallback for YouTube-catalogue tracks; honest "official recording not verified" error; elapsed-time / fallback-reason logs; 10 s seek increments |
| `app/src/main/java/com/example/playback/MediaPlaybackService.kt` | `QueuePlayer` (advertise + implement Next/Previous for notification + lock screen) |
| `app/src/test/java/com/example/SoundCloudStrictMatchTest.kt` | **new** — 12 regression cases |
| `progress.md` | this file |

## Actual test results
- **Automated (CI, GitHub Actions `android-apk.yml`)**: `testDebugUnitTest` (new
  `SoundCloudStrictMatchTest` cases + existing tests), `assembleRelease` with the release
  keystore, `lintRelease`. Result for this pass: see the run linked in the release notes /
  CI annotations (the previous pass' `v1.2-build-19` run was green).
- **NOT verified (no Android device available here)**:
  - that a previously-mismatching song now plays the official recording on a real device
    (the sniffer verification + strict gate are code-verified, and the strict gate is
    unit-tested, but the live YouTube response path needs a device);
  - that the media notification and lock screen now show working Next/Previous buttons;
  - background playback across navigation and long-mashup playback.
  These are reported as **CODE FIXED BUT DEVICE UNVERIFIED**, not as fixed-and-verified.

### Suggested real-device checks
1. Play a famous official song that previously played something else (e.g. "Believer") —
   audio must be the official recording; `logcat` should show
   `PRIMARY OK: verified … stream in <ms>`.
2. Swipe the app away with a *paused* player, reopen, play again → notification and lock
   screen must appear with **working** Play/Pause **Next/Previous**.
3. Play a long track, put the phone in airplane mode mid-song, re-enable → playback should
   resume near where it stopped, or show the honest error instead of a wrong recording.

## Unresolved / honest limits
- Strict verification is metadata-level (title/artist/duration/no-decoration). It cannot
  listen to the audio; it can refuse a wrong recording, but a *decorated duplicate that
  claims the official title, artist and duration* could still pass. It can no longer
  silently substitute an openly-decorated or unrelated upload.
- Being strict removes some availability: where only an alternate version exists, the user
  now gets an honest error rather than the wrong audio (intentional, per directive).
- The notification still depends on POST_NOTIFICATIONS being granted and on OEM media
  behaviour; verified against the Media3 documentation and code, not on hardware.
- Sniffing still depends on YouTube not walling the WebView session (bot detection).
