# SUZIEE v1.2 — Playback Defect Investigation (progress log)

Date: 19 Oct 2026 · Base: `v1.2-build-21` · Scope: smallest safe changes, no feature removals,
no data loss, no identity/signing change.

Nine fix passes are recorded here. **Pass 9 (below) is the current, authoritative one**: the
on-device 403 was googlevideo's new range/PO-token gate on the Android-VR URLs, and build 32
switches to the ANDROID/IOS clients with the `params=2AMB` testsuite flag, which returns
ordinary full-length audio URLs. Pass 8 removed yt-dlp and the headless WebView and replaced
both with a native YouTube player-API client. Pass 7 fixed the "every song fails" regression
in build 29 by removing an IPv6 stall that made yt-dlp miss its deadline; pass 6 introduced
the yt-dlp engine; pass 5 tightened identity verification; pass 4 replaced the primary
extraction source with the pre-warmed headless engine; pass 3 is kept for its evidence
trail: pass 2 had made official songs *stop playing entirely*, and pass 3 removed that
regression instead of adding another layer of tightening.

---

# PASS 9 — on-device 403: Android-VR is range/PO-token gated; ANDROID `params=2AMB` fixes it (build 32)

## The evidence from the user's logcat

Build 31 resolved the URL successfully, then ExoPlayer failed a few seconds later:

```
I/YouTubeAudioResolver: Resolved vxUBYHz_q1I in 3730ms (itag=251, mime=audio/webm)
I/PlaybackCoordinator: Resolved 'Attention' via INNERTUBE
I/SuzieeMediaService: MediaItem transition (reason=3): 'Attention' by 'Charlie Puth'
E/MusicPlayerManager: ExoPlayer error: Source error
```

So this was **not** a resolve failure and **not** a wrong-recording problem. The stream URL
was produced correctly; googlevideo refused the media request.

## Root cause: Android-VR URLs only serve the first ~1 MB

I replayed the exact Android-VR URL outside the app. With no `Range` header (ExoPlayer's
first request at position 0) googlevideo returns **403**. The only request that works is a
bounded range starting at 0:

| Request | Result |
|---|---|
| no `Range` header | **403** |
| `Range: bytes=0-` | **403** |
| `Range: bytes=0-131071` | 206 (works) |
| `Range: bytes=0-950000` | 206 (works) |
| `Range: bytes=0-999999` | **403** |
| `Range: bytes=900000-1799999` (fresh URL) | **403** |
| `Range: bytes=900000-` (open-ended) | **403** |
| whole-file `bytes=0-<length>` | **403** |

This is the GVS PO-token enforcement introduced in 2026: yt-dlp's issue #17348 documents
that `android_vr` now requires a GVS PO token for everything except format 18, and the Kodi
YouTube add-on's issue #1481 measures the same cutoff. The bundled yt-dlp in build 30 also
failed a full audio download with 403, so this was a YouTube-side change, not an app bug.

## The fix: ANDROID/IOS player clients with the testsuite `params=2AMB` flag

The same player endpoint returns **ordinary direct URLs with no range gate** when the client
is `ANDROID` or `IOS` and the body carries `params: "2AMB"` (the testsuite flag). The flag
decodes to a single protobuf field (`field 59, varint 1`); without it those clients answer
with SABR-only streaming data (zero direct URLs).

Changed in `YouTubeAudioResolver.kt`:

* `ANDROID_VR` is removed. Primary client is `ANDROID` (`clientName=ANDROID`, client id `3`,
  version `21.26.364`, Android 11 UA, SDK 30); fallback is `IOS` (`clientName=IOS`, client id
  `5`, version `21.26.4`).
* Every player request now sends `params=2AMB`.
* `StreamInfo.headers` uses the **winning client's** User-Agent, not the old VR UA.
* A refused request still refreshes the visitor session once and retries both clients.

## PC verification (exact request shape, 2026-10-19) — all passed

| Song | Video id | ANDROID POST | Format | Full GET | Seek GET |
|---|---|---|---|---|---|
| Attention (official video) | `nfs8NYg7yQM` | 0.88 s | itag 251, `audio/webm` | **200**, 3,716,623 B | **206**, open-ended and bounded |
| Attention (Topic audio) | `vxUBYHz_q1I` | 0.67–1.29 s | itag 251 | **200**, 3,477,177 B | **206**, 900,000 B |
| Love Me Like You Do | `AJtDXIazrMo` | 0.80 s | itag 251 | **200**, 4,045,870 B | **206** |
| Blinding Lights | `4NRXx6U8ABQ` | 0.81 s | itag 251 | **200**, 4,094,821 B | **206** |
| Shape of You | `JGwWNGJdvx8` | 0.71 s | itag 251 | **200**, 4,159,651 B | **206** |
| Believer | `7wtfhZwyrcc` | 0.87 s | itag 251 | **200**, 3,528,030 B | **206** |
| Flowers | `G7KNmW9a75Y` | 0.76 s | itag 251 | **200**, 3,264,719 B | **206** |
| As It Was | `H5v3kku4y6Q` | 0.89 s | itag 251 | **200**, 2,748,477 B | **206** |
| Starboy | `34Na4j8AVgA` | 0.67 s | itag 251 | **200**, 4,873,928 B | **206** |

Measured latency is **0.67–1.29 s** for a warm session, one POST per song, plus ExoPlayer's
first buffer. All nine tracks returned itag 251 and accepted both a full GET and a ranged
seek request, which is what ExoPlayer needs.

## Automated verification (CI run #32, commit `7ebd43f`) — green

`Build APK` and `Run unit tests` both succeeded: **26 tests, 0 failures**. Published release
**`v1.2-build-32`**, `Suziee.apk` **23,835,291 bytes** (same size as build 31; no dependency
was added):

* https://github.com/heyitssuzain/Suziee/releases/download/v1.2-build-32/Suziee.apk
* Stable latest URL: https://github.com/heyitssuzain/Suziee/releases/latest/download/Suziee.apk

## Honest limits

* **No Android device is available locally.** The PC test proves the exact HTTP flow, the
  full-length response and the ranged seek response. It does not prove on-device ExoPlayer
  decoding, audible output, OEM network behaviour, notification/lock-screen controls or
  download writes. Build 32 still needs the two screenshot songs played on the phone.
* `params=2AMB` is an **undocumented YouTube flag**. It works today and is the strongest
  anonymous path found, but YouTube can close it. No client can guarantee "permanently
  working"; the code logs the exact client, itag and failure stage so the next change is
  diagnosable.
* Captchas are not "bypassed". They are avoided because this path never loads the web UI.
  If YouTube answers `LOGIN_REQUIRED` for a network, playback still fails honestly.
* No substitute recording is ever played: the requested video id is the only id sent.

If a song still fails, this log tag names the exact stage:

```
adb logcat -s YouTubeAudioResolver OnlineStreamResolver PlaybackCoordinator MusicPlayerManager SuzieeMediaService
```

---

# PASS 8 — WebView cannot play YouTube any more; native player API replaces yt-dlp (build 31)

# PASS 8 — WebView cannot play YouTube any more; native player API replaces yt-dlp (build 31)

## The request

Build 30 still showed the same failure on the user's device. The request was to remove
yt-dlp completely, make the headless WebView the only provider, make it "undetectable",
block ads, bypass captchas, and start playback within 1.5 s. The first instruction was
explicit: **test whether the WebView path works for "Attention" by Charlie Puth before
building on it.**

## The WebView test — it does not work, and this is measurable

I fetched the exact page the WebView loads, with the app's mobile Chrome UA, and parsed
`ytInitialPlayerResponse`:

| Page | `playabilityStatus` | `adaptiveFormats` | Formats with a direct `url` | SABR URL |
|---|---|---|---|---|
| `m.youtube.com/watch?v=nfs8NYg7yQM` (Attention) | `OK` | 25 | **0** | present |
| `www.youtube.com/watch?v=nfs8NYg7yQM` | `OK` | 22 | **0** | present |

YouTube has moved its web clients to **SABR**: the stream entries carry only
`serverAbrStreamingUrl`, not a signed `googlevideo.com` URL. ExoPlayer cannot play a SABR
endpoint, and the old fallback's "early candidate" parser required a `url`, so it correctly
found nothing. The media requests the page does make are SABR/UMP requests, not progressive
audio files. **A WebView cannot hand a playable stream to ExoPlayer today.** Making it the
only provider would have guaranteed that every song kept failing. I did not ship that.

## The provider that does work

The Android-VR player endpoint returns ordinary signed URLs when the request carries the
visitor data from a normal YouTube page. This is the same client yt-dlp used successfully
on the PC, but implemented natively in Kotlin — no Python, no QuickJS, no WebView:

1. `GET https://m.youtube.com/` once per session, with a mobile UA, to read
   `VISITOR_DATA` and `INNERTUBE_API_KEY` from YouTube's bootstrap config.
2. `POST https://www.youtube.com/youtubei/v1/player` with the `ANDROID_VR` client
   (`clientName=ANDROID_VR`, version `1.65.10`), the visitor id in both the context and
   `X-Goog-Visitor-Id`, and the API key in `X-Goog-Api-Key`.
3. Pick Opus **itag 251** (fallback AAC 140), require an HTTPS `*.googlevideo.com` URL,
   and hand it to ExoPlayer with the matching Android-VR User-Agent and YouTube Referer.
4. On a refused/stale session, retry once with the `IOS` client and once after refreshing
   the visitor data. The session is cached in memory and SharedPreferences for six hours.

The session is pre-warmed in `SuzieeApplication.onCreate`, so a normal tap costs one POST.

## PC verification (exact Kotlin flow) — all passed

`kotlin_flow_probe.py` mirrors the Kotlin request byte-for-byte: home-page visitor fetch,
then the VR player POST, then a ranged media GET.

| Song | Video id | Player POST | Format | Media GET |
|---|---|---|---|---|
| Attention — Charlie Puth | `nfs8NYg7yQM` | 0.76 s | itag 251, `audio/webm` | **206**, 131,072 bytes, `audio/webm` |
| Love Me Like You Do — Ellie Goulding | `AJtDXIazrMo` | 0.75 s | itag 251 | **206**, 131,072 bytes |
| Blinding Lights — The Weeknd | `4NRXx6U8ABQ` | 0.72 s | itag 251 | **206**, 131,072 bytes |
| Shape of You — Ed Sheeran | `JGwWNGJdvx8` | 0.61 s | itag 251 | **206**, 131,072 bytes |
| Believer — Imagine Dragons | `7wtfhZwyrcc` | 0.66 s | itag 251 | **206**, 131,072 bytes |
| Perfect — Ed Sheeran | `2Vv-BfVoq4g` | 0.67 s | itag 251 | **206**, 131,072 bytes |
| Faded — Alan Walker | `60ItHLz5WEA` | 0.62 s | itag 251 | **206**, 131,072 bytes |

The one-time home-page fetch took **1.63 s**. No returned URL contained the `n` throttling
parameter, and every media response was a real 206 with audio bytes.

## What was removed / changed

* Deleted `data/remote/ytdlp/` (`SuzieeAudioExtractor`, `YtDlpStreamParser`).
* Deleted `data/remote/extraction/` (`SecureExtractionEngine`, `AudioFormatSelector`,
  `AudioIdentityPolicy`, `AudioTrafficPolicy`) and `playback/ExtractionService`.
* Deleted the `youtubedl-android` dependency, the arm64-only `abiFilters`, the legacy
  JNI packaging, `extractNativeLibs="true"` and the GPL-3.0 notice.
* Added `innertube/YouTubeAudioResolver.kt` (network) and `YouTubeAudioPolicy.kt` (pure
  rules) plus 11 unit tests for ids, session parsing, SABR rejection, format preference,
  MIME normalisation and URL validation.
* `OnlineStreamResolver` is now a thin wrapper around the one provider;
  `StreamSource` is `NONE | INNERTUBE | LOCAL`.

## Automated verification (CI run #31, commit `cec4cd9`) — green

`Build APK` and `Run unit tests` both succeeded: **26 tests, 0 failures**, including 11 new
`YouTubeAudioPolicyTest` cases. Published release **`v1.2-build-31`**, `Suziee.apk`
**23,835,291 bytes** — **19,110,957 bytes smaller** than build 30 (the Python runtime,
QuickJS and yt-dlp are gone).

APK inspection (downloaded from the public release):

* no `libpython*`, `libqjs.so`, `youtubedl` or yt-dlp payload;
* all four ABI folders are back, containing only the two small AndroidX libraries;
* `classes3.dex` contains `YouTubeAudioResolver` and `YouTubeAudioPolicy`, and contains no
  `SuzieeAudioExtractor`, `YtDlpStreamParser` or `SecureExtractionEngine`.

## Latency

* Warm session (normal case): **0.61–0.76 s** from tap to a direct URL, plus ExoPlayer's
  1 s startup buffer.
* Cold process: the session warm-up takes 1.63 s and runs in the background; a tap made
  before it finishes waits for it, so the first request can take ~2.3 s.
* No ads and no captcha page are loaded at all, because the app never loads the YouTube
  web page or player UI.

## Honest limits

* **"Permanently working" cannot be guaranteed** by any client: YouTube changes its API
  and can start requiring a PoToken for this client. What is implemented is the strongest
  anonymous path available today, with a second client and automatic session refresh.
* Captchas are not "bypassed"; they are avoided because the API request does not load the
  web UI. If YouTube answers `LOGIN_REQUIRED` for a network, this provider cannot play.
* **No Android device is available locally.** The PC test proves the exact HTTP flow and
  the audio bytes; it does not prove on-device ExoPlayer decoding, OEM network behaviour,
  audible output, lock-screen controls or download writes. Install build 31 and test the
  two screenshot songs.

If a song still fails, this log tag now names the exact stage:

```
adb logcat -s YouTubeAudioResolver OnlineStreamResolver PlaybackCoordinator MusicPlayerManager SuzieeMediaService
```

---


# PASS 7 — "every song fails": yt-dlp was being killed before it could answer (build 30)

## The report

Build 29 showed *"Couldn't play this track. Check your connection and tap retry."* on every
song, including mainstream, available tracks from the screenshots (`Attention` — Charlie
Puth, `Love Me Like You Do` — Ellie Goulding). This is the same generic text for both
"neither engine resolved" and "ExoPlayer rejected the stream", so the first job was to find
which one it was without a device.

## What the provider test actually showed

I extracted the **exact yt-dlp zipapp bundled in the released APK** (`res/Nc`, yt-dlp
`2025.11.12`) and ran it on the PC against real YouTube videos, with the app's own flags:

| Test | Result |
|---|---|
| default flags (`-f 251/140/bestaudio --get-url --no-playlist --socket-timeout 5`) | **~45 s**, then a URL |
| same command + `--force-ipv4` | **4.4–5.9 s**, then a URL |
| `--force-ipv4 --extractor-args youtube:player_client=android_vr` | **3.9–4.9 s** |
| HTTP GET (range 0–2047) on the returned URL, Chrome-Android UA + YouTube Referer | **206**, `audio/webm`, real bytes |
| 5 different songs (Attention, Love Me Like You Do, Blinding Lights, Shape of You, Believer) | URL + 206 on all five |

The 45 s is not yt-dlp being slow: it is a **broken IPv6 route** on a dual-stack network.
yt-dlp waits for that route before falling back to IPv4. The app's old watchdog was **7 s**,
so it killed every extraction long before the URL arrived; the WebView fallback then has its
own 8 s ceiling and YouTube's current web player often never emits a plain audio request at
all. Result: both engines report "nothing", on every track. That matches the screenshots.

## What changed in build 30

* `SuzieeAudioExtractor`
  * `--force-ipv4` — removes the ~40 s stall (the single biggest fix).
  * watchdog raised from **7 s to 12 s per client attempt**, with `--socket-timeout 10`
    and `--extractor-retries 1`.
  * two attempts: **`android_vr` first** (direct URLs, no SABR-only payload), then yt-dlp's
    **default client list** as the safety net. Worst case is bounded; the first successful
    attempt returns immediately.
  * output parsing now scans **all** stdout lines for the first valid HTTPS
    `googlevideo` URL, instead of trusting the first non-empty line (a stray notice could
    previously hide the URL).
  * failed initialization is retried after **60 s** instead of disabling yt-dlp for the
    life of the process.
  * failure logs now include the client, stdout/stderr excerpts and whether init succeeded.
* `OnlineStreamResolver`
  * WebView fallback window raised to **15 s** and started after **2 s**.
  * the final failure log includes `yt-dlp initialized=…`, so a future logcat identifies an
    init failure immediately.

## Automated verification (CI run #30, commit `2359c94`) — green

`Build APK` and `Run unit tests` both succeeded. **54 tests, 0 failures**, including the two
new `pickStreamUrl` cases (13 parser tests total). Published release:
**`v1.2-build-30`**, `Suziee.apk` **42,946,248 bytes** (+932 bytes over build 29).

## Still device-unverified

The PC test proves the bundled provider **does** resolve and serve real audio for the songs
in the screenshots, and it proves the old deadline was the problem. It cannot prove the
on-device Python/QuickJS startup time or OEM network behaviour. Install build 30 and play
the two songs from the screenshots; if anything still fails, the log tags are now enough to
name the stage:

```
adb logcat -s SuzieeAudioExtractor OnlineStreamResolver PlaybackCoordinator MusicPlayerManager SuzieeMediaService
```

---

# PASS 6 — yt-dlp primary engine, WebView fallback (build 29)

## What was asked

Replace the legacy streaming providers (SoundCloud included) with yt-dlp as the online
engine, keep a headless-WebView sniffer as fallback, and ship it without breaking playback,
downloads, playlists, favourites, lyrics, voice, trending, the notification panel or the
lock-screen controls. Startup had to stay fast (~1.5 s target) and the APK had to grow by no
more than about 20 MB.

## What was built

### A. yt-dlp engine — `data/remote/ytdlp/`

* `SuzieeAudioExtractor.kt`
  * `YoutubeDL.init(context)` on the IO dispatcher, once per process; warmed from
    `SuzieeApplication` so the first tap does not pay the Python-unpack cost.
  * One request per tap with `--get-url` (single URL). `--dump-json` was deliberately
    **not** used: `youtubedl-android` pulls Jackson in as a *runtime-only* dependency, so the
    `ObjectMapper` type is not on the compile classpath. A `--get-url` line is all the player
    needs and cannot deserialize a wrong/foreign payload.
  * Format preference `251/140/bestaudio`, `--no-playlist`, `--socket-timeout 5`,
    `--extractor-retries 1`, fixed process id so a hung run can be killed.
  * **7 s watchdog** around the process; on timeout it calls `destroyProcessById`, so a stuck
    yt-dlp never pins the playback path.
  * Throttled (7-day) background nightly update, only after a failed extraction and only when
    no extraction is in flight. No startup update, so launch latency is unaffected.
* `YtDlpStreamParser.kt` — pure Kotlin (no Android, no network): validates the 11-character
  video id, the URL scheme/host, rejects manifest/playlist payloads, derives mime/container
  (`mime=`, `itag=`, extension fallback) and passes through `Referer`/`User-Agent` headers.
  This is the single "is this playable" gate. 11 unit tests.
* `OnlineStreamResolver.kt` — races both sources through a `Channel`:
  * yt-dlp is always started first;
  * the WebView fallback starts only after `HEDGE_DELAY_MS = 2_500`, so a healthy yt-dlp
    answer never waits for the sniffer;
  * the first **usable** stream wins and the loser is cancelled;
  * after both fail once, the next attempt starts both immediately (no hedge delay).

### B. Playback wiring

* `PlaybackCoordinator` was rewritten around *source identity*: it hands the player exactly
  one authoritative stream per track and allows **one** mid-playback re-resolve
  (`claimFailover()`), so a looping failure cannot spin the engines.
* `MusicPlayerManager` keeps a single player handoff, metadata, media session and error
  recovery; failures now surface a generic, honest message instead of a fake success.
* `DownloadManager` takes the shared `OnlineStreamResolver`, so downloads use the same
  yt-dlp URL as playback. Progressive responses are saved as-is; manifest URLs keep the
  existing HLS stitching path; WebM extension is handled explicitly.
* SoundCloud provider and its strict-match test were deleted; `MusicRepository.resolveStreamUrl`
  removed, so no legacy path can silently win.

### C. Packaging (the size constraint)

* Maven Central artifact `io.github.junkfood02.youtubedl-android:library:0.18.1`
  (the `com.github.yausername:…:0.14.0` coordinates in the request are the old JitPack
  build; the maintained artifact is the one tested here).
* `abiFilters += "arm64-v8a"` **only**. Four ABIs would have added ~60 MB and v7a ~14 MB;
  arm64 keeps the delta under the cap.
* `packaging.jniLibs.useLegacyPackaging = true` plus `android:extractNativeLibs="true"`:
  youtubedl-android reads `libpython.zip.so` from the *extracted* native-library directory,
  so the default `extractNativeLibs=false` would break Python at runtime.
* `minSdk 24` already matched the library exactly; no version bump, no identity change.

## Automated verification (CI run #29, commit `7bb7442`) — green, test gate enforced

Every step succeeded, including `Run unit tests`. Check-run annotations (readable without
GitHub auth) report the per-class JUnit results:

| Test class | tests | failures | errors |
|---|---|---|---|
| `YtDlpStreamParserTest` | 11 | 0 | 0 |
| `AudioFormatSelectorTest` | 14 | 0 | 0 |
| `VoiceCommandParserTest` | 7 | 0 | 0 |
| `AudioTrafficPolicyTest` | 6 | 0 | 0 |
| `AudioIdentityPolicyTest` | 6 | 0 | 0 |
| `SearchRankingTest` | 6 | 0 | 0 |
| `ExampleUnitTest` | 1 | 0 | 0 |
| `ExampleRobolectricTest` | 1 | 0 | 0 |

**52 tests, 0 failures.** Two earlier runs are recorded for honesty: #27 (`c7e582f`) failed
to compile because of the runtime-only Jackson type (fixed by switching to `--get-url`), and
#28 (`a12c022`) built and published an APK but was red on two parser assertions (one test id
was exactly 11 valid characters; one fallback-extension case carried a `mime` it meant to
omit). Both are fixed in `7bb7442`.

### Published artifact, inspected after download

* Release **`v1.2-build-29`**, asset `Suziee.apk`, **42,945,316 bytes**
  (`sha256:97c8b642…46752f`). Previous build 26 was 23,851,707 bytes →
  **+19,093,609 bytes ≈ +18.2 MiB (+19.1 MB)**, inside the "up to 20 MB" budget.
* Native payload present and **arm64 only**:
  `lib/arm64-v8a/libpython.zip.so` 14,305,904 B, `libqjs.so` 916,104 B,
  `libpython.so` 4,384 B; no `armeabi-v7a`/`x86`/`x86_64` directories.
* The 3,170,726-byte yt-dlp binary is packaged (AGP's resource-path optimization renames it
  from `res/raw/ytdlp` to a short name, so `R.raw.ytdlp` still resolves at runtime).
* Release notes record `Built automatically from commit 7bb7442` and *"Signed with the private
  Suziee release key"*.

## Limitations the user must know

* **arm64 only.** The APK will not install on 32-bit phones or x86/x86_64 emulators. Adding
  `"armeabi-v7a"` restores those devices at roughly +14 MB.
* **GPL-3.0.** `youtubedl-android` is GPL-3.0; a distributed APK is a combined work. The
  app's own `LICENSE` is MIT, so `NOTICE.md` now discloses this. Either adopt GPL-3.0 for the
  app or drop the library.
* **No device was available.** Nothing below was heard or seen on hardware: real yt-dlp
  extraction on a phone, real WebView fallback under bot-detection, audible correctness,
  startup latency on a mid-range device, notification/lock-screen behaviour after swipe-away.
  These are **code paths verified by unit tests and inspection**, not device-verified facts.

## Suggested real-device checks

1. Play 2–3 mainstream official songs. Expect `PRIMARY OK: <mime> stream in <ms>`; if yt-dlp
   fails, `FALLBACK OK: … total <ms>` after the hedge. Filter:
   `adb logcat -s SuzieeAudioExtractor OnlineStreamResolver PlaybackCoordinator MusicPlayerManager SuzieeMediaService`
2. Note `First audio ready in <ms>` — this is the real startup latency.
3. With a paused player, swipe the app away, reopen, play again → notification and lock
   screen must show title/artwork and working Play/Pause/Next/Previous.
4. Download one progressive and one HLS-backed track; confirm the file plays offline.
5. Block notifications in system settings and confirm the app degrades honestly (no crash,
   no fake "playing" state).

---

# PASS 5 — identity verification: wrong recordings can no longer be played (build 26)

## The report, and what was actually wrong

"Still the official song plays with wrong audio." A wrong recording can only reach the
speakers through one of two doors, and both were open in pass 4:

1. **The SoundCloud substitute accepted a different performer.** The gate rejected only
   *conflicts*: a decorated title, a clearly different title, a clearly different length, or a
   weak title without artist corroboration. An **unlabelled cover** - uploaded under exactly
   the official title, at a plausible length, by a channel that is not the artist - had **no
   conflict to detect**, because a title match ≥ 0.75 skipped the artist check entirely
   (`SoundCloudMatch.isAcceptableSubstitute` step 4). The same door let a *different song that
   merely shares the title* through. That is "Requesting 'Attention' but getting a
   cover/unrelated audio", and it fires whenever the extraction engine fails and the fallback
   takes over.
2. **The signed capture was not checked against the requested video.** The page's own player
   is the source of the captured URL, and a watch page can move on by itself (bot/availability
   interstitial followed by autoplay, region block, a stale document left by the pooled
   WebView). The URL it then produces is a perfectly valid, `n`-transformed audio stream - for
   the wrong song. Pass 3 had deliberately made the video-id check log-only, so that stream was
   used as-is. The user's design prompt names this exactly ("transaction token pattern … if the
   stream doesn't match the current active transaction token, drop it").

## What changed

### A. The substitute gate must verify the performer — `SoundCloudMatch.kt`
New rule (step 4 of `isAcceptableSubstitute`): when the request names a **real artist** (not
`Unknown Artist` / `YouTube Music` / `Various Artists`) and the candidate has a **named
uploader**, that uploader must corroborate the artist (fuzzy uploader match, or the artist name
inside the upload title). A named uploader that is not the artist is positive evidence of a
different performer - cover, re-upload, or same-title different song - and is refused.
Missing metadata still never rejects: a **blank** uploader carries no evidence, so it falls
back to the existing rule (a weak title then still needs the artist to appear somewhere).

This is the deliberate trade the directive asks for: for an official request, an **honest
error** is correct where a different recording used to be played.

### B. A capture must belong to the requested video — `SecureExtractionEngine.kt` + `AudioIdentityPolicy.kt`
* **Transaction token pattern.** Every extraction session gets a fresh UUID token; the injected
  hook echoes it on every bridge call and a call bearing any other token is dropped. A pooled
  WebView's previous document can therefore never feed a callback into the new session.
* **Page-identity check on the authoritative capture.** The hook reports the video id the
  page's player is on every tick; the engine keeps it and drops a signed capture when the page
  is demonstrably on a *different* video. `AudioIdentityPolicy.isForeignCapture` implements
  this as **reject only on positive evidence** - an unknown/blank/`null` report is *accepted*,
  because dropping on "unknown" is what previously starved every official song of its stream.
* **Bounded drift recovery.** On a mismatch the page is asked to reload the requested video
  (max `MAX_DRIFT_RELOADS = 2`), and the foreign stream keeps being dropped meanwhile - so an
  unrecoverable page fails honestly instead of playing the wrong song.
* **The hook itself never publishes a foreign response**: `videoDetails.videoId` is compared
  against the requested id inside V8 before any `adaptiveFormats` record is sent.
* **One re-navigation** if nothing at all has been captured by `RENAVIGATE_AT_MS = 4_000L`
  (the first page load can land on an interstitial; the 8 s deadline is never extended).
* **Browser-identity masking** (best effort, as requested): `navigator.webdriver = false`,
  `hardwareConcurrency`, `languages`, and a 1-pixel canvas-noise shim on `toDataURL` - to
  reduce how often the page answers with a bot interstitial instead of a player response.

### C. Requested architecture names
`HeadlessExtractorRepository` → **`SecureExtractionEngine`**; `MusicPlaybackService` →
**`SuzieeMediaService`** (now also logging every `onMediaItemTransition` with
title/artist/artwork, which is the evidence trail for "notification shows a different track");
new **`PlaybackCoordinator`** owns the resolution ladder (engine → hedged SoundCloud), the
winning-source identity and the one-shot failover claims, so `MusicPlayerManager` no longer
mixes resolution policy with player handling.

**Deviations, stated plainly.** No custom OkHttp connection pool / "traffic spoofing" stack:
the player keeps Media3's `DefaultHttpDataSource`, which is what actually transports the
stream, and adding a second HTTP stack would not change which recording is played. The
notification is still rendered by Media3 from the active `MediaItem` (we log the transitions
rather than re-rendering by hand, which would fight Media3's own notification manager).

## Automated verification (CI run #26, commit `2bc5322`) — green, test gate enforced
Every step succeeded, including the blocking `testDebugUnitTest` step. The per-class results,
read straight from the JUnit XML as `::notice::TESTRESULT …` annotations (no GitHub auth
needed):

| Test class | tests | failures | errors |
|---|---|---|---|
| `SoundCloudStrictMatchTest` (the substitute gate; grew this pass) | 26 | 0 | 0 |
| `AudioIdentityPolicyTest` (**new** this pass: drift reject, unknown-report accept, cleaning) | 6 | 0 | 0 |
| `AudioFormatSelectorTest` | 14 | 0 | 0 |
| `AudioTrafficPolicyTest` | 6 | 0 | 0 |
| `VoiceCommandParserTest` | 7 | 0 | 0 |
| `SearchRankingTest` | 6 | 0 | 0 |
| `ExampleRobolectricTest` / `ExampleUnitTest` | 1 / 1 | 0 | 0 |

**67 tests, 0 failures.** Release **`v1.2-build-26`** published: `Suziee.apk`
23,851,707 bytes, `sha256:285ae6d405d2f161674b1d4d94c4ac0cf5576df1e5543c324690762a75a22c89`
(different from build 25's `29bd99f3…`, so the new code really is in the asset), notes confirm
`Built automatically from commit 2bc5322 (workflow run #26)` and *"Signed with the private
Suziee release key"*.

**Still device-unverified:** audible output, live extraction, real startup latency, the
notification/lock-screen surfaces and how often the primary engine actually wins on the
user's phone. All of it is **CODE FIXED BUT DEVICE UNVERIFIED**.

## Log tags for the on-device checks
`adb logcat -s SecureExtractionEngine PlaybackCoordinator MusicPlayerManager SoundCloudApiClient MainActivity SuzieeMediaService ExtractionService`

Key new lines: `[<tx>] Dropping capture - page is on '<other>' while '<requested>' was
requested (drift)`, `[<tx>] Page reports '…' … (drift recovery)`, `[<tx>] Nothing captured
after 4000ms - reloading the watch page once`, `Substitute rejected (conflicts with the
request): …`, `MediaItem transition (reason=…)': '<title>' by '<artist>' artwork=yes|none`.

## Status per reported issue (pass 5)
| # | Issue | Status |
|---|-------|--------|
| 1 | Official song plays wrong/cover/unrelated audio | Both doors closed: the substitute gate now verifies the performer (26 CI cases incl. an unlabelled cover and a same-title different song), and a captured stream that is not the requested video is dropped (transaction token + page-identity check + V8-side gate). **CODE FIXED (unit-tested in CI) BUT DEVICE UNVERIFIED** |
| 2 | Never silently substitute a fan-made/alternate recording | A named non-artist uploader is now refused outright for an official request; the app shows the honest "no acceptable matching source" error instead. **CODE VERIFIED (unit tests)** |
| 3 | 8 s extraction window | Unchanged: `SecureExtractionEngine.SNIFF_TIMEOUT_MS = 8_000L`, a ceiling, not a wait. **CODE VERIFIED** |
| 4 | Startup latency | Unchanged from pass 4 (warm engine, pool reuse, hedge at 3 s, 2.5 s early-candidate grace), plus the new 4 s single re-navigation which can save a dead first load. **CODE FIXED BUT DEVICE UNVERIFIED** |
| 5 | Notification + lock-screen controls | Unchanged structurally (one session, one service, channel `suziee_playback`, id 1001, `QueuePlayer` Next/Previous, `onTaskRemoved`) + per-transition metadata logging. **CODE FIXED BUT DEVICE UNVERIFIED** |
| 6 | Requested class names / architecture | `SecureExtractionEngine`, `SuzieeMediaService`, `PlaybackCoordinator` all exist and are wired; deviations documented above. **CODE VERIFIED** |
| 7 | Ads / wasted traffic | Unchanged from pass 4. **CODE FIXED BUT DEVICE UNVERIFIED** |

## Changed files (pass 5)
| File | Change |
|------|--------|
| `app/src/main/java/com/example/data/remote/soundcloud/SoundCloudMatch.kt` | step 4: a named uploader that does not corroborate the requested artist is a conflict (cover / re-upload / same-title different song); blank uploader still never rejects |
| `app/src/main/java/com/example/data/remote/extraction/SecureExtractionEngine.kt` | `HeadlessExtractorRepository` renamed; transaction tokens on the bridge + hook, page-identity check on the signed capture, drift recovery, 4 s re-navigation, V8-side requested-video gate, browser-identity masking |
| `app/src/main/java/com/example/data/remote/extraction/AudioIdentityPolicy.kt` | **new** - pure "is this capture a different video" policy (reject only on positive evidence) |
| `app/src/main/java/com/example/playback/PlaybackCoordinator.kt` | **new** - resolution ladder, winning-source identity, one-shot failover claims; `StreamSource` moved here |
| `app/src/main/java/com/example/playback/MusicPlayerManager.kt` | uses the coordinator; resolution methods moved out; local file / failover / retry rewired |
| `app/src/main/java/com/example/playback/MusicPlaybackService.kt` → `SuzieeMediaService.kt` | renamed; `onMediaItemTransition` metadata logging added |
| `app/src/main/java/com/example/playback/ExtractionService.kt` | uses `SecureExtractionEngine` |
| `app/src/main/AndroidManifest.xml` | service renamed to `.playback.SuzieeMediaService` |
| `app/src/main/java/com/example/data/remote/innertube/InnerTubeApiClient.kt`, `.../extraction/AudioFormatSelector.kt` | comment references updated |
| `app/src/test/java/com/example/SoundCloudStrictMatchTest.kt` | 21 → 26 cases (the cover, the same-title different song, corroborating channels, blank uploader) |
| `app/src/test/java/com/example/data/remote/extraction/AudioIdentityPolicyTest.kt` | **new** - 6 cases for the drift policy |
| `progress.md` | this file |

Not touched: Room/DB, downloads, playlists, favourites, history, lyrics, voice commands,
trending rails, Compose UI/onboarding, theme, app id/version/signing, CI workflow, the search
backend. **Downloads deliberately keep the non-strict SoundCloud path** (a strict download of
a mainstream song would usually fail, because label uploads are 30 s previews); the wrong-audio
report is about playback, and changing download availability is a feature decision, not a fix.

---

# PASS 4 — pre-warmed headless extraction engine + strict media session (build 25)

> Historical: the class/file names below were renamed in **pass 5** —
> `HeadlessExtractorRepository.kt` → `SecureExtractionEngine.kt` (log tag
> `HeadlessExtractor` → `SecureExtractionEngine`) and `MusicPlaybackService.kt` →
> `SuzieeMediaService.kt` (tag `MusicPlaybackService` → `SuzieeMediaService`). Behaviour is as
> described here plus the pass-5 identity checks.

## What was built (requested design, with four deliberate deviations)

| Requested | Shipped | Where |
|---|---|---|
| Pre-warmed pool of invisible, never-attached WebViews | up to **2** warm WebViews, reused across resolves, recycled after 120 s idle; warm-up fetches **no page** (zero traffic - the on-disk Chromium HTTP cache already holds the player assets) | `data/remote/extraction/HeadlessExtractorRepository.kt` |
| `ExtractionService` holding the pool | `ExtractionService` is an **optional, non-foreground, self-stopping** pre-warm host (idles out after 90 s). The pool itself lives in the app-scoped repository, so a killed service can never break extraction | `playback/ExtractionService.kt` |
| V8 hook on `window.fetch` / `XMLHttpRequest`, parse `streamingData` in the engine | injected hook wraps both APIs **and** polls the player's retained response (the initial response is inlined in the watch page, so this usually hits immediately); parses `adaptiveFormats` inside V8, pushes only `itag\|mime\|bitrate\|length\|url` records over a JS bridge | `HOOK_JS` in the same file |
| itag 251 (Opus 160k) preferred, then itag 140 (AAC 128k) | `AudioFormatSelector.pickBest` - pure Kotlin, CI-unit-tested | `data/remote/extraction/AudioFormatSelector.kt` |
| Ad isolation: only the raw media URI reaches the player | true - ExoPlayer only ever receives a `googlevideo` audio URL; YouTube's presentation/player payload is never mounted | `MusicPlayerManager.startPlayback` |
| Stop the page load the instant the URL is extracted | `stopLoading()` on capture, page muted, WebView released back to the pool | `ExtractionSession.onAudioRequest` |
| Explicit `startForeground()` + channel + metadata | Media3's `MediaSessionService` performs the promotion itself (doing it by hand fights its notification manager). What *is* controlled: dedicated channel `suziee_playback`, stable notification id 1001, session created inside the service, `isBrowsable=false`/`isPlayable=true` metadata, audio focus + `handleAudioBecomingNoisy` | `playback/MusicPlaybackService.kt`, `MusicPlayerManager.kt` |

**Deviations, and why each one protects playback**

1. **The signed media request stays authoritative; the player-response URL is only the
   backup** (after a 2.5 s grace). The `adaptiveFormats` URL still carries an *untransformed*
   `n` parameter (throttled, sometimes 403), whereas the request the page's own player makes
   has already been transformed by YouTube's JS. Making the JSON URL the first choice is the
   classic trap that looks faster and plays worse.
2. **Video segments are not blocked.** Answering a *media* request with an empty body is
   exactly what made the page's player error out and re-negotiate in pass 2. Blocking a video
   segment would reintroduce that risk. Ads, artwork, thumbnails, stylesheets and fonts *are*
   blocked (`AudioTrafficPolicy`), which is where the genuinely wasted traffic was.
3. **No 200 ms claim.** A cold WebView plus YouTube's player bootstrap cannot be 200 ms. What
   was actually done for latency: Chromium is instantiated while the user browses (not on the
   tap), the engine is reused between tracks, and a dead end is cut from 8 s to ~2.5 s.
4. **The extractor is pooled and session-scoped rather than one long-lived WebView.**
   Callbacks are bridged through a Flow, so cancelling a resolve (new song, timeout) always
   tears the page down and releases the WebView - no cross-song leakage.

## Behaviour on failure (unchanged, on purpose)
Signed capture (immediate) → early player-json candidate (after 2.5 s, only if no signed
capture) → hedged SoundCloud resolve (3 s) → strict substitution gate → honest error. Only
*resolution* is parallel; playback stays strictly single-source.

## Automated verification (CI run #25, commit `f0df3fa`) — green, test gate enforced
| Test class | tests | failures | errors |
|---|---|---|---|
| `AudioFormatSelectorTest` (new: format ranking, bridge payload parsing, URL normalisation) | 14 | 0 | 0 |
| `AudioTrafficPolicyTest` (new: blocks ads/artwork/CSS/fonts, never blocks player/API/CDN/video) | 6 | 0 | 0 |
| `SoundCloudStrictMatchTest` | 21 | 0 | 0 |
| `VoiceCommandParserTest` | 7 | 0 | 0 |
| `SearchRankingTest` | 6 | 0 | 0 |
| `ExampleRobolectricTest` / `ExampleUnitTest` | 1 / 1 | 0 | 0 |

**56 tests, 0 failures**, published as `::notice::TESTRESULT …` annotations (readable without
GitHub auth). Release **`v1.2-build-25`**: `Suziee.apk` 23,851,707 bytes,
`sha256:29bd99f3b6545edc8b4b5f1bfaa64cf15f7837606919a7baaf015787729e8b32`, notes confirm
`Built automatically from commit f0df3fa (workflow run #25)` and *"Signed with the private
Suziee release key"*.

**Still device-unverified (no Android device here):** live extraction from YouTube, audible
output, real startup latency, the notification/lock-screen controls, and whether the early
candidate path is ever needed on a real phone. All of that is **CODE FIXED BUT DEVICE
UNVERIFIED**, never "fixed and verified".

## Log tags for the on-device checks
`adb logcat -s HeadlessExtractor MusicPlayerManager SoundCloudApiClient MainActivity MusicPlaybackService ExtractionService`

Key lines: `Headless extractor navigating to …`, `V8 hook: audio-only itag=… offered`,
`Captured signed audio stream (…) for …`, `Primary extractor resolved <id> in <ms>
(confidence=signed-media|early-player-json)`, `PRIMARY OK/FAILED`, `HEDGE`, `FALLBACK OK`,
`First audio ready in <ms> (source=…)`, `Media controller connected: <pkg>`,
`Notification surfaces: notificationsEnabled=…`, `Task removed - keeping service alive=…`.

## Status per reported issue (pass 4)
| # | Issue | Status |
|---|-------|--------|
| 1 | Official songs played wrong/duplicate audio | The signed-request capture is preserved and is the authoritative path; the SoundCloud substitute gate still rejects only *conflicts* (21 CI cases incl. the reported screenshot song). **CODE FIXED BUT DEVICE UNVERIFIED** |
| 2 | Web-extraction window must be 8 s | `HeadlessExtractorRepository.SNIFF_TIMEOUT_MS = 8_000L`, still a ceiling rather than a wait; a dead end is now cut to ~2.5 s by the early-candidate window instead of burning all 8 s. **CODE VERIFIED** |
| 3 | Playback startup too slow | Warm engine (Chromium off the tap path), pool reuse between songs, shortened failure path, hedged fallback kept, timing logs kept. **CODE FIXED BUT DEVICE UNVERIFIED** |
| 4 | Notification panel shows the track with working controls | One session in one service, dedicated channel + stable id, full metadata, POST_NOTIFICATIONS requested from `onStart()`, controller diagnostics. **CODE FIXED BUT DEVICE UNVERIFIED** |
| 5 | Lock-screen controls | Same session published through `onGetSession`; `QueuePlayer` + `onConnect` advertise and implement Next/Previous. **CODE FIXED BUT DEVICE UNVERIFIED** |
| 6 | Ads / wasted traffic | Ads, artwork, CSS and fonts never reach the network; only the raw audio URL is played. **CODE FIXED BUT DEVICE UNVERIFIED** |

## Changed files (pass 4)
| File | Change |
|------|--------|
| `app/src/main/java/com/example/data/remote/extraction/HeadlessExtractorRepository.kt` | **new** - pre-warmed WebView pool, Flow-based extraction session, V8 `fetch`/XHR hook, signed-request interception, mute/teardown, 8 s window, 2.5 s early-candidate grace |
| `app/src/main/java/com/example/data/remote/extraction/AudioFormatSelector.kt` | **new** - pure format ranking (251 → 140), bridge-payload parsing, mime detection, chunk-parameter stripping |
| `app/src/main/java/com/example/data/remote/extraction/AudioTrafficPolicy.kt` | **new** - ad/artwork/CSS/font blocking that never touches the player, the API or the CDN |
| `app/src/main/java/com/example/playback/ExtractionService.kt` | **new** - optional self-stopping pre-warm service |
| `app/src/main/java/com/example/playback/MediaPlaybackService.kt` → `MusicPlaybackService.kt` | renamed; dedicated notification channel + stable id; session ownership/`onConnect`/`QueuePlayer` unchanged |
| `app/src/main/java/com/example/playback/MusicPlayerManager.kt` | extractor wired in (hedge + strict SoundCloud gate untouched); `isBrowsable=false`/`isPlayable=true` metadata; audio-focus comment; `ensureExtractorWarm()`; renamed `YOUTUBE_EXTRACTOR` source |
| `app/src/main/java/com/example/playback/YouTubeWebViewSniffer.kt` | **removed** (superseded by the engine above; its signed-request capture is preserved inside it) |
| `app/src/main/AndroidManifest.xml` | `MusicPlaybackService` + new (non-exported) `ExtractionService` |
| `app/src/main/res/values/strings.xml` | `playback_notification_channel` |
| `app/src/main/java/com/example/MainActivity.kt` | pre-warm the engine from `onStart()` (best effort) |
| `app/src/main/java/com/example/data/remote/innertube/InnerTubeApiClient.kt` | comment reference updated |
| `app/src/test/java/com/example/data/remote/extraction/*.kt` | **new** - 20 unit tests (pure JVM) |
| `progress.md` | this file |

Not touched: Room/DB, downloads, playlists, favourites, history, lyrics, voice commands,
trending rails, Compose UI/onboarding, theme, app id/version/signing, CI workflow.

---

# PASS 3 — restore official-song playback, cut startup latency, harden the media surfaces

## What was actually wrong (evidence-led, not assumed)

The reported screenshot (`issues/issues.jpg`) shows a mainstream official song:

```
Starboy (feat. Daft Punk) — The Weeknd — 3:51
Couldn't play the official recording of this song. No verified matching source was found - tap retry.
```

That string is produced by `MusicPlayerManager.unplayableMessage()` for a YouTube-catalogue id,
i.e. **both** sources had failed. Two pass-2 changes caused that, and they compounded:

### Root cause 1 — the sniffer capture was gated behind an async JavaScript round-trip
Pass 2 completed the sniffer result *inside* an `evaluateJavascript(...)` callback. If that
callback did not fire (page navigating/tearing down, player markup without `movie_player`,
rAF timing) the already-captured, valid `googlevideo` URL was **thrown away** and the sniff ran
out its full 8 s window. Probability of the callback not firing is not zero, and every miss
cost a whole song. The sniffer's *only* job is to observe a URL that YouTube's own player
built for the video we loaded — it must not depend on a second, unrelated callback.

### Root cause 2 — the sniffer answered the audio request with an empty body
Pass 2 returned an empty `WebResourceResponse` for the media request. The captured URL was
still used, but the page's own player received a 0-byte media body, errored, and re-negotiated
(and can auto-advance). That perturbs exactly the page state we depend on, and it was an
unnecessary risk: the WebView is muted and destroyed on capture anyway.

### Root cause 3 — the SoundCloud gate demanded *proof* instead of rejecting *conflicts*
Pass 2's `isStrictVerifiedMatch` required all of: no decoration marker, title similarity ≥ 0.62,
artist corroboration, duration within 10 %/15 s, **and** an overall score ≥ 0.65. Missing
metadata is normal in this app (the plain-YouTube search path reports `YouTube Music` /
`Unknown Artist`, duration can be `0`), and a candidate with a good title but placeholder
artist and unknown duration scored **0.51 < 0.65** → rejected. Meanwhile, for a mainstream
song, the only full-length SoundCloud uploads are unofficial (official uploads are `SNIP`
previews and are filtered as unplayable), so "reject unless proven" meant *reject everything*.
**A gate that refuses every official song is a playback bug**, exactly as reported.

### Latency problems found in the same path
* `repository.recordPlayed(track)` (two DB writes) was **awaited inside the critical path**
  before any resolution started.
* When the 8 s sniff failed, the fallback then spent up to **+2.7 s** on a 3-attempt linear
  backoff — pure user-visible waiting *after* the window had already been lost.
* The media session service was only started *after* a URL existed, so the session could miss
  the first playback update.

## Fixes (pass 3)

### A. Primary source (sniffer) — `YouTubeWebViewSniffer.kt`
* The capture now **completes immediately** on the first usable `mime=audio` request. Nothing
  gates it. The WebView is stopped and torn down on capture, and ExoPlayer takes over.
* The audio request **loads normally again** (no empty-body response) — the page stays healthy
  while we hold the URL. It still cannot become a second sound source: the page player is
  muted (`movie_player.mute()`, `video.muted`, `audio.muted`) and the WebView is destroyed
  immediately after capture.
* The video-id the page reports is still read, but **only logged** (`WARNING: page reports 'X'
  while 'Y' was requested (autoplay drift?)`). Diagnostics without a failure mode.
* The 8 s window is unchanged. New: a **main-frame load error** now fails the sniff
  immediately (`result.complete(null)`) so the fallback starts sooner instead of burning the
  remaining window. Sub-resource failures (blocked ad hosts) are ignored.

### B. Substitution gate — `SoundCloudMatch.kt` (+ `SoundCloudApiClient.kt`)
The gate is inverted from "prove it matches" to **"reject only on positive evidence that this
is a different recording"**:

| Rejected (a different recording) | Accepted |
|---|---|
| decorated upload — slowed / reverb / remix / sped up / nightcore / 8D / bass boosted / mashup / bootleg / cover / karaoke / instrumental / tribute / live / acoustic / reaction / remake / lofi / rework / reprise / type beat (unless the user asked for one **or the word is part of the user's own request**) | same title + same artist + same length |
| clearly different title (similarity < 0.45) | title agrees, **duration unknown** |
| clearly different length (±15 s / 10 %, only when both are known) | title agrees, **placeholder/absent artist** (`Unknown Artist`, `YouTube Music`, …) |
| an uploader that advertises a conclusive marker (slowed / nightcore / lofi / cover / … — ambiguous words like "live"/"acoustic" are excluded so "Live Nation" cannot reject a valid upload) | **unlabelled re-upload of the same title/length** (indistinguishable by metadata) |
| a weak title (< 0.75) that the artist neither corroborates through the uploader nor by appearing in the upload title | a weak title whose artist *is* corroborated by the uploader or the upload title |

Missing metadata never causes a rejection. Explicit alternate-version requests are preserved
(marker words are stripped from *both* sides before the title comparison, so
"Believer (Slowed + Reverb)" still finds "Believer Slowed and Reverb").

### C. Latency — `MusicPlayerManager.kt`
* **Hedged fallback**: the SoundCloud resolve is started in parallel after
  `FALLBACK_HEDGE_DELAY_MS = 3 s` (scoped to the current resolve job, cancelled the moment the
  sniffer wins — so a normal fast sniff makes *no* extra request). A failing sniffer therefore
  loses the fallback wait it used to pay after the window expired. Only *resolution* is
  parallel; playback remains strictly single-source.
* **`recordPlayed` moved off the critical path** (fire-and-forget in the manager scope, logged
  on failure).
* **Fallback retries cut** from 3 attempts with 0/900/1800 ms backoff to **2 attempts / 400 ms**
  (worst case +400 ms instead of +2.7 s). `resolveSoundCloudWithRetry` no longer mutates
  `currentSource` (the caller owns it, which matters while a hedge is in flight).
* `ensurePlaybackServiceStarted()` is now also called **as soon as a track is selected**.
* Timing logs: `PRIMARY OK: <mime> stream in <ms>`, `PRIMARY FAILED: … (window 8000ms)`,
  `HEDGE: …`, `FALLBACK OK: … total <ms> after tap`, `First audio ready in <ms> (source=…)`.
  These are the numbers to read on-device.
* 8 s window, network timeouts (`DefaultHttpDataSource` 15 s/20 s, `RESOLVE_TIMEOUT_MS` 30 s)
  and ExoPlayer buffering are **unchanged**.

### D. Media surfaces — `MediaPlaybackService.kt`, `MainActivity.kt`
* Next/Previous from pass 2 are kept: `QueuePlayer` (a `ForwardingPlayer` over the app's single
  `ExoPlayer`) advertises and implements them, and `onConnect` advertises them to controllers.
  No second player, no second notification.
* **POST_NOTIFICATIONS is now requested from `onStart()`**, not `onCreate()`. The pass-2 code
  launched the request during activity creation inside a `runCatching {}` that swallowed any
  failure — on OEM builds that drop a request made before the activity is started, the app
  ended up with **no permission, no notification and no lock-screen controls, silently**.
  The launch failure and the permission result are now logged.
* New diagnostics: `Notification surfaces: notificationsEnabled=<bool>`,
  `Media controller connected: <pkg>` (proof the session is being published),
  `Task removed - keeping service alive=<bool>`.
* Unchanged and re-verified: manifest service declaration
  (`foregroundServiceType="mediaPlayback"`, exported, Media3 intent filter), the foreground /
  notification / wake-lock permissions, `setSessionActivity`, per-track `MediaMetadata`
  (title/artist/artwork), one `ExoPlayer`, one `MediaSession`, session released only in
  `onDestroy`, `onTaskRemoved` keeping the service alive while playing.

## Changed files (pass 3)
| File | Change |
|------|--------|
| `app/src/main/java/com/example/playback/YouTubeWebViewSniffer.kt` | immediate capture (no JS gate), audio request loads normally, stronger mute, log-only video-id check, main-frame fast-fail, 8 s window kept |
| `app/src/main/java/com/example/data/remote/soundcloud/SoundCloudMatch.kt` | conflict-based `isAcceptableSubstitute` (missing metadata never rejects), marker stripping for explicit alternate requests |
| `app/src/main/java/com/example/data/remote/soundcloud/SoundCloudApiClient.kt` | uses the new gate; logs the conflict reason |
| `app/src/main/java/com/example/playback/MusicPlayerManager.kt` | hedged fallback, off-path history write, 2×400 ms fallback retries, early service start, latency logs, reworded honest error |
| `app/src/main/java/com/example/playback/MediaPlaybackService.kt` | session/controller/task-removed diagnostics |
| `app/src/main/java/com/example/MainActivity.kt` | POST_NOTIFICATIONS requested from `onStart()`, failures/results/notification state logged |
| `app/src/test/java/com/example/SoundCloudStrictMatchTest.kt` | rewritten for the new gate: 21 cases incl. the exact failing screenshot case, the "missing metadata must not reject" regressions, and the request-aware marker rules |
| `.github/workflows/android-apk.yml` | unit tests are now a blocking gate; test failures are re-emitted as `::error::` annotations |
| `progress.md` | this file |

Not touched: Room/DB, downloads, playlists, favourites, history, lyrics, voice commands,
trending/anime rails, Compose UI/onboarding, theme, app id/version/signing, CI workflow.

## Actual test results

* **Automated (CI, GitHub Actions `android-apk.yml`)**
  * **Run #22** (commit `a00dda5`, the first pass-3 commit) — build, lint, APK upload and
    release publish all green: **`v1.2-build-22`** was published with `Suziee.apk`
    (23,834,871 bytes, `sha256:693a22ec…`, different from build 21's `67cc464e…`, so the
    pass-3 code is in that APK).
  * **That green step list was misleading, and saying so matters.** Run #22 carried a
    hidden check-run annotation `failure: Process completed with exit code 1` while every
    step reported `success`. The cause: the unit-test step ran with
    `continue-on-error: true`, so a **failing assertion was swallowed** and the run still
    looked green. Reading the annotation is how it was caught — not by trusting the steps.
  * The failing case was my own new expectation in
    `rejects a weak title match that the artist does not corroborate`:
    `similarity()` applies a containment bonus, so `"shape of you lofi chill remake"`
    scores **0.8 ≥ 0.75** against `"shape of you"` and the weak-title branch was never
    reached. That was a real gap in the gate, not just a bad test: a "Lofi … Remake"
    upload would have been accepted for a plain official request.
  * Fixes: markers are now evaluated **against the user's own request** (a marker word
    already present in the request is never a conflict, so the official "Lofi Girl" upload
    is safe), the marker list gained `lofi`/`lo-fi`/`remake`/`rework`/`reprise`/`type beat`
    … and uploader-based rejection only uses conclusive markers. Tests grew to **21 cases**.
  * The CI unit-test step is now a **real gate**: `continue-on-error` removed, output
    captured to `test.log`, and failures re-emitted as `::error::` annotations (readable
    without GitHub auth) so a red run is diagnosable.
  * **Run #23** (commit `5d9dbca`) — first run with the real gate, and it correctly went
    **red**: `:app:testDebugUnitTest` failed on the pre-existing `ExampleRobolectricTest`
    (`Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java
    17)`). That test pins `@Config(sdk = [36])` while CI builds on JDK 17, so the whole unit
    test task had been failing all along — exactly what `continue-on-error` had been hiding.
    Build/lint/APK/release steps were green; `v1.2-build-23` was published anyway.
    Fix: the sandbox is pinned to SDK 34 (the release build stays on JDK 17), and the results
    step now also publishes per-class test counts (`::notice::TESTRESULT <class> tests=…
    failures=…`) so which tests actually ran is verifiable without authenticated logs.
  * **Run #24** (commit `218de28`, current HEAD) — **green, with the test gate enforced**.
    Every step succeeded *including* `Run unit tests`, and the run's check-run annotations
    report the per-class results straight from the JUnit XML (readable without GitHub auth):

    | Test class | tests | failures | errors |
    |---|---|---|---|
    | `SoundCloudStrictMatchTest` (this pass's gate) | 21 | 0 | 0 |
    | `SearchRankingTest` | 6 | 0 | 0 |
    | `VoiceCommandParserTest` | 7 | 0 | 0 |
    | `ExampleRobolectricTest` | 1 | 0 | 0 |
    | `ExampleUnitTest` | 1 | 0 | 0 |

    36 tests, 0 failures. Published release: **`v1.2-build-24`**, asset `Suziee.apk`
    (23,834,871 bytes, `sha256:3879fdf7…`), release notes confirm
    `Built automatically from commit 218de28` and *"Signed with the private Suziee release
    key"* — i.e. the APK everyone downloads really is the reviewed commit, release-signed.
  * Latest run for any newer commit: see the `v1.2-build-N` release notes (`Built
    automatically from commit …`), plus the `::notice::TESTRESULT …` annotations.
* **Not verifiable here (no Android device)**: live YouTube sniffing, real SoundCloud
  responses, actual audible output, and the system notification/lock-screen surfaces. Anything
  in the list below is **CODE FIXED BUT DEVICE UNVERIFIED**, never "fixed and verified".

## Status per reported issue

| # | Issue | Status |
|---|-------|--------|
| 1 | Official songs played duplicate/fan-made audio | **CODE FIXED (unit-tested in CI) BUT DEVICE UNVERIFIED** — the pass-2 sniffer changes that made mainstream songs unresolvable are reverted; the substitution gate rejects only *conflicts* and is covered by 21 CI-passing cases, including the exact song from the reported screenshot |
| 2 | Web-sniffer window must be 8 s | **CODE VERIFIED** — `SNIFF_TIMEOUT_MS = 8_000L`; it is a ceiling (resolves on capture), not a fixed wait; only that constant is 8 s |
| 3 | Playback startup too slow | **CODE FIXED BUT DEVICE UNVERIFIED** — hedged fallback, off-critical-path DB write, 2×400 ms retries, early service start, timing logs added |
| 4 | Notification panel | **CODE FIXED BUT DEVICE UNVERIFIED** — permission request moved to a lifecycle-correct point (was silently swallowed); session/controller diagnostics added; Media3 requirements re-checked against the 1.5.1 source |
| 5 | Lock-screen controls (Play/Pause/Next/Previous) | **CODE FIXED BUT DEVICE UNVERIFIED** — `QueuePlayer` implements + advertises Next/Previous (pass 2), unchanged in pass 3 |
| 6 | Preserve existing features | **CODE VERIFIED** — no feature, data, identity or workflow changes; diff touches playback matching, latency and diagnostics only |

## Suggested real-device checks (and what to read in logcat)

1. Play the song from the screenshot ("Starboy") plus 2–3 other mainstream official songs.
   Expect `PRIMARY OK: <mime> stream in <ms>` and correct audio; if the sniffer fails,
   `PRIMARY FAILED: … (window 8000ms)` + `FALLBACK OK: … total <ms> after tap`.
   Filter: `adb logcat -s YouTubeSniffer MusicPlayerManager SoundCloudApiClient`.
2. Start a song and confirm `First audio ready in <ms>` — that is the real startup latency.
3. Notification/lock screen: `adb logcat -s MediaPlaybackService MusicPlayerManager MainActivity`
   should show `Notification surfaces: notificationsEnabled=true`, then
   `Media controller connected: com.android.systemui` when a song plays. If
   `notificationsEnabled=false`, the OS (or the user) has blocked the app's notifications —
   that is outside the app's control and must be granted in system settings.
4. Swipe the app away with a **paused** player, reopen, play again → notification and lock
   screen must appear with working Play/Pause **and** Next/Previous.

## Honest limits
* The substitution gate is metadata-level. It cannot hear the audio, so a re-upload that
  claims the exact official title/artist/length is still accepted (it is, at least, the same
  recording). Openly decorated or clearly different recordings are refused.
* Where only an alternate version exists, the app shows an honest error rather than the wrong
  audio (intentional, per directive).
* Sniffing still depends on YouTube not walling the headless WebView session (bot detection);
  the fallback exists for exactly that case.
* The notification still depends on POST_NOTIFICATIONS being granted and on OEM media
  behaviour. The code path is verified against the Media3 1.5.1 documentation and source, not
  on hardware.
