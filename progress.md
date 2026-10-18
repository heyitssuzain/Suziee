# SUZIEE v1.2 — Playback Defect Investigation (progress log)

Date: 18 Oct 2026 · Base: `v1.2-build-18` · Scope: smallest safe changes, no feature removals.

## 1. Official song playback plays the wrong recording

### Root cause (verified in code)
- **Primary path ranking**: playback plays the *first* search result. The plain-YouTube
  fallback (`InnerTubeApiClient.searchFallbackYouTube`, query `"$query music audio")`
  returns uploads in raw relevance order, where remix / slowed+reverb / cover uploads
  frequently outrank the official recording.
- **Fallback path matching**: `SoundCloudApiClient.matchScore` already penalises
  alternate versions (`DECORATION_PENALTIES`, lines ~463–479) — no change needed there.
- **Metadata sync**: the player plays exactly the videoId that was displayed, and
  `startPlayback` builds `MediaMetadata` from that same Track, so display metadata
  already matched the *selected* result. The defect was *result selection*, not metadata.

### Fix
- **New** `app/src/main/java/com/example/data/remote/innertube/SearchRanking.kt`:
  reorders (never filters) parsed results — original recordings and
  official/"Artist - Topic" uploads rank first; remix/slowed/reverb/nightcore/8D/cover/
  karaoke/live-style titles drop only when the user did **not** explicitly ask for them.
  Applied to both search paths in `InnerTubeApiClient.kt`.
- Explicit requests ("… slowed reverb") still return alternate versions; no result is
  removed from the queue, only demoted.

## 2. Web-sniffer timeout and SoundCloud fallback

### Verified (no code change needed)
- `YouTubeWebViewSniffer.SNIFF_TIMEOUT_MS = 5_000` caps only the *sniff* phase, and the
  `CompletableDeferred` resolves **the moment an audio request is captured** — the 5 s
  is the worst-case ceiling, not a fixed wait.
- Timeouts are already separate: search (~15 s connect / 20 s read per request),
  remote resolve (`MusicRepository.RESOLVE_TIMEOUT_MS = 30 s`, with
  `SoundCloud` retries + 900 ms backoff in `MusicPlayerManager`), playback prep
  (ExoPlayer `bufferForPlaybackMs = 1 s`).
- Fallback starting early: `resolveRemoteStream` runs strictly sequentially
  (sniffer → SoundCloud); no duplicate requests; each `SniffSession` WebView is torn
  down in a `finally` block, one per track.
- Fallback match quality: SoundCloud candidates must pass hard filters
  (streamable, non-SNIP policy, duration ballpark ±35 %) *and* a title/artist/duration
  score with alternate-version penalties before becoming the audio source.

## 3. Slow playback and interrupted songs

### Root cause (verified)
- **`serviceStarted` one-shot flag (real bug)** — see §4; also made playback start
  calls idempotent.
- **Failover restarted songs from 0:00** — a stream error at, e.g., 12 min sent the
  user back to the beginning (perceived as "song broken/restarted").
- Duration limits: verified there is **no upper duration limit**; `MIN_TRACK_DURATION_MS
  = 45 s` is only a lower bound and the SoundCloud duration tolerance scales with track
  length, so long tracks/mashups are not rejected by an arbitrary cap. Official
  recordings of any length play through the sniffer regardless.

### Fix
- `MusicPlayerManager.kt`: mid-playback errors now capture `exoPlayer.currentPosition`
  and the SoundCloud failover **resumes at that position** (seek applied before
  `prepare()`), instead of restarting at 0.
- Buffering/startup: the existing 1 s start threshold + 15 s/60 s buffers, retry ladder
  and bounded retries (3 SoundCloud attempts + 1 mid-play failover per track) were
  verified correct and are unchanged. No infinite loops, no extra player sessions.

## 4. Notification panel and lock-screen controls

### Findings (code audit against the Media3 background-playback docs)
- `MediaPlaybackService` (`MediaSessionService`) is declared with
  `foregroundServiceType="mediaPlayback"`, exported, with the
  `androidx.media3.session.MediaSessionService` intent filter; `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `POST_NOTIFICATIONS` are declared
  and the runtime permission is requested in `MainActivity`. ✓
- The service builds `MediaSession` **directly on `app.playerManager.exoPlayer`** — the
  Compose UI drives that same player instance, so there is exactly **one** player and
  Play/Pause/Next/Prev commands reach it; no separate player exists. ✓
- Per-track metadata (title, artist, artwork) is published through
  `MediaMetadata` on every `startPlayback`, so notification/lock-screen data follows the
  current song. ✓
- **Defect 1 (real bug, fixed)**: the session had no `sessionActivity`. Without a
  launch `PendingIntent`, the system media notification has no tap target — on several
  OEM implementations that degrades/suppresses the media notification entirely.
- **Defect 2 (real bug, fixed)**: `MusicPlayerManager.serviceStarted` was set once and
  never reset. After `MediaPlaybackService.onTaskRemoved()` called `stopSelf()` (user
  swipes the app away with a paused player), every later playback session ran with
  **no live session service at all → no notification, no lock-screen controls** —
  exactly the reported symptom, permanently after the first task-removal.

### Fix
- `MediaPlaybackService.onCreate` now sets `setSessionActivity(launch PendingIntent)`.
- `ensurePlaybackServiceStarted()` now calls `startService` on every playback start
  (idempotent; the stale flag is deleted). If the service was killed or self-stopped,
  it is revived before the next track.

## 5. Preserved functionality
No databases cleared, no downloads deleted, no app identity/signing change, no feature
removed. Anime + mainstream trending rails, search, voice commands, SoundCloud fallback,
downloads + persistence, Room data, LRCLIB lyrics, Compose UI/onboarding and the GitHub
Actions build/signing workflow are untouched.

## 6. Changed files
| File | Change |
|------|--------|
| `app/src/main/java/com/example/data/remote/innertube/SearchRanking.kt` | **new** — result ranking (original recording first) |
| `app/src/main/java/com/example/data/remote/innertube/InnerTubeApiClient.kt` | apply ranking in both search paths |
| `app/src/main/java/com/example/playback/MusicPlayerManager.kt` | position-preserving failover; idempotent service start (flag removed) |
| `app/src/main/java/com/example/playback/MediaPlaybackService.kt` | `setSessionActivity` launch intent |
| `app/src/test/java/com/example/SearchRankingTest.kt` | **new** — 6 regression tests |
| `progress.md` | this file |

## Actual test results
- **Automated (CI, GitHub Actions `android-apk.yml`)**:
  - `gradle assembleRelease` / `assembleDebug` — build verification of the changed code.
  - `gradle testDebugUnitTest — includes the 6 new `SearchRankingTest` cases plus the
    existing unit tests.
  - `lintRelease` gate — unchanged lint profile.
- **NOT verified (no Android device available):** real-device official-song matching
  against live YouTube responses, notification/lock-screen appearance on a physical
  phone, background playback across app navigation, and long mashup playback. These
  require the real-device test below.

### Suggested real-device checks (user)
1. Search a famous official song (e.g. "Believer") → listed result & playing audio
   should be the official version.
2. Play a song → swipe the app away → reopen and play again → media notification and
   lock-screen controls must appear; Play/Pause/Next/Prev must respond.
3. Start a long track, force airplane-mode off/on mid-song → playback should resume
   near where it failed rather than restart or error out once the stream recovered.

## Unresolved / honest limits
- Correct-recording ranking is heuristic; it cannot *verify* audio content, only
  metadata signals (titles, channel markers, durations). If SoundCloud must be used and
  only an alternate version exists, it may still play the alternative (audibly flagged
  in release notes as "best effort close-match").
- The notification path depends on the device granting POST_NOTIFICATIONS and OEM media
  behaviour; verified against the Media3 documentation, not on hardware.
- Sniffer success ultimately depends on YouTube's bot-detection state of the WebView
  session; unchanged by this work.
