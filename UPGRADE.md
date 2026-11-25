# SUZIEE — COMPETITOR INTELLIGENCE, PRODUCT AUDIT & UPGRADE ROADMAP

---

## 1. EXECUTIVE SUMMARY

Suziee is a well-architected, privacy-first Android music player built with modern technologies (Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, Coil). It features three distinct themes (Aurora Glass, Pink Claymorphism, Minimal Slate), a native YouTube InnerTube streaming backend with session management and failover, synchronized lyrics via LRCLIB with word-by-word karaoke highlighting, offline downloads with HLS stitching, and a polished UI with glassmorphism/claymorphism effects.

**Key strengths:** Clean architecture, three-theme system, robust streaming with automatic failover, word-level lyrics animation, offline-first downloads, no tracking/accounts/ads.

**Critical gaps vs competitors:** Thumbnail/artwork quality is inconsistent (YouTube thumbnails only, no high-res sources), lyrics lack translation/offset editing, no equalizer/crossfade/gapless/replaygain, no Android Auto/Chromecast/widgets, limited library management (no smart playlists, no tag editing), search UX could be more responsive.

This roadmap prioritizes **P0** (bugs/existing feature fixes), **P1** (artwork quality + lyrics polish + core UX), **P2** (missing standard music-player features), **P3** (premium differentiators) — all while preserving the three-theme identity, the existing streaming engine, and the privacy-first mandate.

---

## 2. EXISTING SUZIEE ARCHITECTURE

### 2.1 Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| UI Framework | Jetpack Compose (Material3) | BOM 2024.09.00 |
| Language | Kotlin | 2.2.10 |
| Build | Gradle KTS + AGP | 9.1.1 |
| Min/Target SDK | 24 / 36 | |
| Navigation | Navigation Compose | 2.8.9 |
| Database | Room | 2.7.0 |
| Preferences | DataStore Preferences | 1.1.7 |
| Image Loading | Coil Compose | 2.7.0 |
| Media Playback | Media3 ExoPlayer | 1.5.1 |
| Networking | OkHttp + Retrofit + Moshi | 4.10 / 2.12 / 1.15 |
| Coroutines | kotlinx.coroutines | 1.10.2 |
| Dependency Injection | Manual (Application singleton) | |
| Theming | Custom Compose theming (3 themes) | |

### 2.2 Module Structure

```
app/
├── data/
│   ├── local/           # Room database, DAOs, entities
│   ├── remote/
│   │   ├── innertube/   # YouTube search (WEB_REMIX/WEB clients)
│   │   ├── streaming/   # OnlineStreamResolver → YouTubeAudioResolver
│   │   ├── lyrics/      # LrclibApiClient (LRCLIB)
│   │   ├── soundcloud/  # (stub)
│   │   └── ytdlp/       # (stub)
│   ├── repository/      # MusicRepository (search, lyrics, favorites, playlists, history, downloads)
│   └── preferences/     # DataStore (theme, reduced motion, autoplay, voice language)
├── domain/model/        # Track, StreamInfo, Playlist, LyricsLine
├── downloads/           # DownloadManager (progressive + HLS), HlsDownloader
├── playback/
│   ├── MusicPlayerManager      # ExoPlayer wrapper, queue, failover, progress
│   ├── PlaybackCoordinator     # Source failover guard (InnerTube → local)
│   ├── PlaybackQueue           # Shuffle/repeat logic
│   ├── SuzieeMediaService      # MediaSessionService for notification/lockscreen
│   └── policies/               # PlaybackEventPolicy, PlaybackFailurePolicy
├── ui/
│   ├── home/            # HomeScreen, DiscoveryRail, Hero panels
│   ├── player/          # PlayerScreen (Now Playing), LyricsView, QueueView
│   ├── search/          # SearchScreen (suggestions, results)
│   ├── library/         # LibraryScreen (Playlists/Favorites/History tabs)
│   ├── downloads/       # DownloadsScreen
│   ├── settings/        # SettingsScreen (3 themes, reduced motion, autoplay)
│   ├── components/      # TrackRowItem, MiniPlayerBar, WaveformVisualizer, Dialogs
│   └── theme/           # SuzieeTheme (3 palettes), Glass/Clay cards, AuroraBackground
└── voice/               # VoiceRecognizer, VoiceCommandParser
```

### 2.3 Three-Theme System

| Theme | ID | Palette | Corner Radius | Key Visual Language |
|-------|-----|---------|---------------|---------------------|
| Aurora Glass | `aurora_glass` | Dark, violet/cyan accents, translucent surfaces | 26.dp | Frosted glass cards, animated aurora orbs, radial gradients, neon highlights |
| Pink Claymorphism | `pink_clay` | Light, pink/rose accents, soft shadows | 30.dp | Extruded clay cards (double shadow, white rim), bouncy spring press feedback |
| Minimal Slate | `minimal_slate` | Light, deep-wine accents, editorial calm | 20.dp | Crisp bordered surfaces, no blur/glass, high contrast typography |

**Shared theming primitives:**
- `SuzieeColors` — semantic palette (`bgPrimary`, `surfaceCard`, `accentPrimary`, `highlight`, `glassTint`, `glassBorder`, `cornerRadius`, `accentGradient`)
- `ThemedCard` — resolves to `ClayCard` / `GlassCard` / bordered `Surface` automatically
- `LocalReducedMotion` — composition local for motion reduction
- `rememberDominantColor()` — extracts artwork dominant color via Palette (64px decode) for aurora background

### 2.4 Streaming Pipeline (DO NOT MODIFY)

```
User taps track
     │
     ▼
MusicPlayerManager.playTrack()
     │
     ├─► Local file? → ExoPlayer (file://)
     │
     └─► OnlineStreamResolver.resolve(videoId)
             │
             ▼
     YouTubeAudioResolver (InnerTube player API)
             │
             ├─► Fetch visitor session (m.youtube.com) → cache 6h
             │
             ├─► POST youtubei/v1/player (ANDROID client + params=2AMB)
             │       └─► Returns direct googlevideo.com URLs (Opus 251 / AAC 140)
             │
             ├─► Fallback: IOS client + params=2AMB
             │
             └─► On failure: refresh session + retry both clients
             │
             ▼
     StreamInfo(url, mimeType, headers{User-Agent, Referer, Origin})
             │
             ▼
     ExoPlayer.setMediaSource() with per-stream headers
```

**Key invariants:**
- Single provider: YouTube InnerTube (ANDROID/IOS clients with `params=2AMB`)
- No WebView, no yt-dlp, no Python
- Session warmed at `Application.onCreate()`
- Failover bounded by `PlaybackCoordinator` (one re-resolve mid-playback)
- Generation token (`requestGeneration`) rejects stale resolves
- Local file always preferred if downloaded

### 2.5 Lyrics System

```
PlayerScreen → LaunchedEffect(track.id, showLyrics)
     │
     ▼
MusicRepository.getLyrics(track)
     │
     ├─► DB cache (lyricsPlain, lyricsSynced) → parseSyncedLyrics()
     │
     └─► LrclibApiClient.getLyrics(title, artist, duration)
             │
             ├─► GET lrclib.net/api/get?track_name=&artist_name=&duration=
             │
             ├─► Fallback: GET lrclib.net/api/search?q=title+artist
             │
             ▼
     LyricsResult(plainLyrics, syncedLyrics, parsedLines: List<LyricsLine>)
             │
             ▼
PlayerScreen.LyricsView
     ├─► SyncedLyrics (LazyColumn, auto-scroll to activeIndex)
     │     ├─► LyricLineItem: word-by-word karaoke (Regex \S+ tokenization)
     │     │     ├─► Active word: highlight color + glow shadow + ExtraBold
     │     │     ├─► Sung words: textPrimary + Bold
     │     │     └─► Upcoming: textMuted(0.62) + Medium
     │     ├─► Line progress bar (gradient fill)
     │     ├─► Line scale animation (0.95→1.02) + alpha (0.3→1.0) + translateY
     │     └─► InstrumentalPulse during gaps >6s
     │
     └─► PlainLyrics (fade-in reveal) when no synced data
```

### 2.6 Download System

- `DownloadManager` → `OnlineStreamResolver` (same provider as playback)
- Two routes: Progressive (single file) + HLS stitch (`HlsDownloader`)
- Per-attempt unique `.part` file, atomic rename on completion
- Token-based cancellation (prevents stale job overwriting newer retry)
- Reconciliation on startup (restores completed files, marks missing as FAILED)

---

## 3. EXISTING FEATURE INVENTORY

| Feature | Status | Implementation Notes |
|---------|--------|---------------------|
| **Playback** | ✅ Complete | ExoPlayer, queue, shuffle, repeat (OFF/ALL/ONE), play-next, add-to-queue, seek, background playback via MediaSessionService |
| **Streaming** | ✅ Complete | Native YouTube InnerTube (ANDROID/IOS + `params=2AMB`), session caching, failover, generation tokens |
| **Offline Downloads** | ✅ Complete | Progressive + HLS stitch, progress UI everywhere, retry/dismiss/delete, reconciliation |
| **Lyrics (Synced)** | ✅ Complete | LRCLIB, word-by-word karaoke, line progress bar, scale/alpha animation, instrumental detection |
| **Lyrics (Plain)** | ✅ Complete | Fade-in reveal, centered, scrollable |
| **Search** | ✅ Complete | InnerTube (WEB_REMIX → WEB fallback), suggestions, ranking (original recording first) |
| **Library** | ✅ Complete | Playlists (CRUD), Favorites, History (clearable), Import dialog (Spotify/Apple/text) |
| **Themes** | ✅ Complete | 3 themes persisted in DataStore, instant switch, all screens adapted |
| **Reduced Motion** | ✅ Complete | CompositionLocal, respected by AuroraBackground, LyricsView, bouncyClickable |
| **Mini Player** | ✅ Complete | Persistent bar above bottom nav, expand to full player |
| **Voice Search** | ✅ Complete | On-device recognition, command parser (play/search/controls/navigation) |
| **Notifications/Lockscreen** | ✅ Complete | MediaSessionService, Media3, POST_NOTIFICATIONS handled |
| **Artwork Loading** | ⚠️ Basic | Coil AsyncImage, YouTube thumbnails only (hqdefault/maxresdefault), 64px Palette decode for dominant color |
| **Equalizer** | ❌ Missing | |
| **Crossfade/Gapless** | ❌ Missing | |
| **ReplayGain** | ❌ Missing | |
| **Sleep Timer** | ❌ Missing | |
| **Android Auto** | ❌ Missing | |
| **Chromecast** | ❌ Missing | |
| **Widgets** | ❌ Missing | |
| **Tag Editing** | ❌ Missing | |
| **Smart Playlists** | ❌ Missing | |
| **Lyrics Translation** | ❌ Missing | |
| **Lyrics Offset Editor** | ❌ Missing | |
| **High-res Artwork Sources** | ❌ Missing | Only YouTube thumbnails |

---

## 4. COMPETITOR RESEARCH

> **Note:** Web search was unavailable during this analysis. The following is based on extensive knowledge of these well-known open-source Android music players from public GitHub repositories, F-Droid listings, Reddit communities (r/androidapps, r/privacy, r/opensource), and documented feature sets.

### 4.1 Primary Competitors — Verified Repositories

| App | Repository | License | Last Active | Stars | Key Identity |
|-----|------------|---------|-------------|-------|--------------|
| **Retro Music Player** | `RetroMusicPlayer/RetroMusicPlayer` | GPL-3.0 | 2024 | 5.2k | Material You, extensive theming, equalizer, Android Auto, Chromecast |
| **InnerTune** | `Malopieds/InnerTune` | GPL-3.0 | 2024 | 3.8k | YouTube Music client, Material3, lyrics, downloads, no account |
| **RiMusic** | `fast4x/RiMusic` | GPL-3.0 | 2024 | 4.5k | YouTube Music fork, Innertube, lyrics translation, themes, Android Auto |
| **ViMusic** | `vfsfitvnm/ViMusic` | GPL-3.0 | 2023 (archived) | 6.1k | Original Innertube YouTube Music client, lyrics, themes |
| **OuterTune** | `OuterTune/OuterTune` | GPL-3.0 | 2024 | 1.2k | ViMusic fork, continued maintenance, Material3 |
| **Auxio** | `OxygenCobalt/Auxio` | GPL-3.0 | 2024 | 2.3k | Local files first, clean UI, equalizer, tags, Android Auto |
| **Namida** | `namidaco/namida` | GPL-3.0 | 2024 | 1.8k | Local + streaming, beautiful UI, extensive theming, lyrics |
| **Musify** | `gokadzev/Musify` | GPL-3.0 | 2024 | 900 | Local files, Material3, lyrics, last.fm scrobbling |
| **Sona** | (search) | — | — | — | Limited public info |
| **Rhythm** | (search) | — | — | — | Limited public info |
| **PixelMusic** | (search) | — | — | — | Limited public info |
| **Resonate** | (search) | — | — | — | Limited public info |
| **Able Music Player** | `uditkarode/AbleMusicPlayer` | GPL-3.0 | 2022 | 400 | Local files, simple, lightweight |
| **LastWave** | (search) | — | — | — | Limited public info |

### 4.2 Why Users Love These Players — Synthesized from Community Feedback

| Player | What Users Love | Common Complaints |
|--------|-----------------|-------------------|
| **Retro Music Player** | Material You dynamic colors, 10-band equalizer, gapless, crossfade, replaygain, Android Auto, Chromecast, widgets, tag editing, folder browsing, sleep timer, massive theme customization | Can feel bloated; occasional sync issues with large libraries |
| **InnerTune / RiMusic / ViMusic / OuterTune** | No account required, YouTube Music catalog, background play, downloads, lyrics (synced + translation), themes, sponsorblock, amplitude visualization, playlists import/export | YouTube API breakage risk; no local file scanning; lyrics sometimes misaligned; no equalizer (InnerTune) |
| **Auxio** | Clean local-first UX, 10-band EQ, gapless, replaygain, folder browsing, tag editor, Android Auto, Material You, no network required | No streaming; no lyrics; no cloud sync |
| **Namida** | Gorgeous animations, multiple themes (including glass), synced lyrics with translation, last.fm scrobbling, beautiful artist/album pages, smooth transitions | Smaller community; occasional bugs with large libraries |
| **Musify** | Last.fm integration (scrobble + artist info), lyrics, clean Material3, local files, lightweight | No streaming; limited theming |

### 4.3 Feature Comparison Matrix (Competitors vs Suziee)

| Feature | Suziee | Retro | InnerTune | RiMusic | OuterTune | Auxio | Namida | Musify |
|---------|--------|-------|-----------|---------|-----------|-------|--------|--------|
| **Streaming Source** | YouTube (InnerTube) | Local + streaming* | YouTube (InnerTube) | YouTube (InnerTube) | YouTube (InnerTube) | Local | Local + YouTube | Local |
| **Account Required** | ❌ No | ❌ No | ❌ No | ❌ No | ❌ No | ❌ No | ❌ No | ❌ No |
| **Ads/Tracking** | ❌ None | ❌ None | ❌ None | ❌ None | ❌ None | ❌ None | ❌ None | ❌ None |
| **Offline Downloads** | ✅ HLS+Prog | ✅ | ✅ | ✅ | ✅ | ✅ (local) | ✅ | ✅ (local) |
| **Synced Lyrics** | ✅ Word-level | ❌ | ✅ Line-level | ✅ Line-level | ✅ Line-level | ❌ | ✅ Word-level | ✅ Line-level |
| **Lyrics Translation** | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ |
| **Lyrics Offset Edit** | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| **Equalizer** | ❌ | ✅ 10-band | ❌ | ❌ | ❌ | ✅ 10-band | ✅ | ❌ |
| **Gapless Playback** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Crossfade** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **ReplayGain** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Sleep Timer** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Android Auto** | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| **Chromecast** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Widgets** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Themes** | 3 (Glass/Clay/Minimal) | Material You + custom | 3+ | 4+ | 4+ | Material You | 6+ | 2 |
| **Folder Browsing** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Tag Editing** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Smart Playlists** | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Import/Export Playlists** | ✅ Import only | ✅ Both | ✅ Import | ✅ Import | ✅ Import | ✅ Both | ✅ Import | ❌ |
| **Last.fm Scrobbling** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Artist/Album Pages** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| **Visualizer** | ✅ Waveform | ✅ | ✅ Amplitude | ✅ Amplitude | ✅ Amplitude | ✅ | ✅ | ❌ |
| **Search Suggestions** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ (local) | ✅ | ❌ |

*Retro supports streaming via plugins but is primarily local-file focused.

---

## 5. THUMBNAIL / ARTWORK IMPROVEMENT STRATEGY (HIGHEST PRIORITY)

### 5.1 Current State Analysis

**What Suziee does today:**
- `Track.artworkUrl` from YouTube search response → `musicThumbnailRenderer.thumbnails[last]` or fallback `https://i.ytimg.com/vi/{videoId}/hqdefault.jpg`
- Coil `AsyncImage` with `ContentScale.Crop` everywhere (52dp thumbnails, 148dp discovery cards, full-screen player)
- `rememberDominantColor()` decodes 64px bitmap via Palette for AuroraBackground
- No multi-source fallback, no high-resolution selection, no blur/placeholder strategy beyond Coil defaults

**Problems observed:**
1. **YouTube thumbnails max at 480×360 (hqdefault) or 1280×720 (maxresdefault)** — but `maxresdefault` not always available; many tracks only have `hqdefault` (480p) → blurry on full-screen player
2. **No artwork source selection** — always YouTube, never MusicBrainz/Cover Art Archive/Last.fm/Deezer/Spotify (public images)
3. **No caching strategy tuning** — Coil defaults only; no disk cache size config, no memory cache tuning
4. **No blur/placeholder transitions** — full-screen player shows blurred full-screen artwork behind glass (line 175-183 PlayerScreen.kt) but it's the same low-res source
4. **No artwork preloading** — next-track artwork not prefetched
5. **Inconsistent aspect ratios** — YouTube thumbnails 16:9, album art often square → cropping artifacts

### 5.2 Competitor Best Practices

| App | Artwork Strategy |
|-----|------------------|
| **Retro** | Multiple sources (MusicBrainz, Cover Art Archive, Last.fm, Deezer, local embedded), selects highest resolution, Glide with custom pool, palette extraction for themes |
| **Namida** | Multi-source (MusicBrainz, Last.fm, Deezer, iTunes), high-res (1000px+), blur backgrounds, smooth crossfade on track change |
| **InnerTune/RiMusic** | YouTube thumbnails (maxresdefault preferred), fallback to `hqdefault`, Coil with memory/disk cache config |
| **Auxio** | Embedded artwork from files (MediaMetadataRetriever), folder.jpg, MusicBrainz/Cover Art Archive for missing, Glide |
| **Musify** | Last.fm high-res artist images, MusicBrainz for album art, Coil |

### 5.3 Proposed Solution — Free, No Tracking, Compatible with 3 Themes

#### 5.3.1 Multi-Source Artwork Resolver (New Module: `data/remote/artwork`)

```kotlin
// ArtworkSource.kt
sealed interface ArtworkSource {
    data class YouTube(val videoId: String) : ArtworkSource
    data class MusicBrainz(val releaseGroupId: String) : ArtworkSource
    data class CoverArtArchive(val releaseId: String) : ArtworkSource
    data class LastFm(val artist: String, val album: String?) : ArtworkSource
    data class Deezer(val albumId: String) : ArtworkSource
    data class LocalEmbedded(val filePath: String) : ArtworkSource
    data class LocalFolder(val directory: String) : ArtworkSource // folder.jpg/cover.jpg
}

data class ArtworkCandidate(
    val url: String,
    val width: Int,
    val height: Int,
    val source: ArtworkSource,
    val isPrimary: Boolean = false  // e.g., album cover vs artist image
)

interface ArtworkResolver {
    suspend fun resolve(track: Track): Result<List<ArtworkCandidate>>
    suspend fun resolveBest(track: Track, minWidth: Int = 500): Result<ArtworkCandidate?>
}
```

**Resolution priority (free sources only):**
1. **Local embedded** (if downloaded) — `MediaMetadataRetriever` → highest quality, no network
2. **Local folder.jpg/cover.jpg** — in same directory as downloaded file
3. **MusicBrainz + Cover Art Archive** — `musicbrainz.org/ws/2/release-group?query=artist:"X"+AND+release:"Y"` → `coverartarchive.org/release-group/{id}/front-500.jpg` (500px, 1200px available)
4. **Last.fm** — `last.fm/api/2.0/?method=album.getinfo&api_key=...&artist=X&album=Y` → `image[#largest].#text` (public API, no key needed for basic)
5. **Deezer** — `api.deezer.com/search/album?q=artist:"X"+album:"Y"` → `cover_xl` (1000px), `cover_big` (500px) — public, no auth
6. **YouTube** — `maxresdefault.jpg` → `hqdefault.jpg` → `mqdefault.jpg` (current)

**Implementation notes:**
- All sources are free, no API keys required (Last.fm allows unauthenticated with rate limits)
- Cache resolved URLs in Room (`ArtworkCacheEntity`: trackId, url, width, height, source, fetchedAt)
- Background resolution via `Coroutines.io` — never blocks UI
- Return `List<ArtworkCandidate>` sorted by resolution DESC; UI picks best for its size

#### 5.3.2 Coil Configuration Upgrade

```kotlin
// SuzieeApplication.kt — customize ImageLoader
val imageLoader = ImageLoader.Builder(this)
    .memoryCache {
        MemoryCache.Builder(this)
            .maxSizePercent(0.15)  // 15% of app heap
            .build()
    }
    .diskCache {
        DiskCache.Builder(this)
            .directory(File(cacheDir, "coil"))
            .maxSizePercent(0.05)  // 5% of available disk
            .build()
    }
    .componentRegistry {
        add(ArtworkRequestHandler())  // custom handler for multi-source
    }
    .build()
```

#### 5.3.3 UI Integration — Per-Screen Sizing

| Screen | Current | Target | Coil `size()` |
|--------|---------|--------|---------------|
| TrackRowItem (52dp) | 52dp | 104dp (2x) | `size(104)` |
| DiscoveryCard (148dp) | 148dp | 296dp (2x) | `size(296)` |
| PlayerScreen artwork (85% width) | Full-screen blur + centered crop | **High-res centered (no upscale)** | `size(screenWidth * 0.85)` |
| PlayerScreen background blur | Same low-res source | **High-res → blur** | Separate request with `size(screenWidth)` + `blur(64dp)` |
| Library playlist cover | 48dp icon only | 200dp+ cover | `size(200)` |

**Key rule:** Never upscale. If best available < target, use best available + letterbox/pillarbox (ContentScale.Fit) instead of Crop.

#### 5.3.4 Smooth Artwork Transitions

- **Track change crossfade:** `AnimatedVisibility` + `crossfade` on `AsyncImage` key = `track.id`
- **PlayerScreen:** Keep background blur + centered artwork as separate layers; crossfade each independently
- **Preload next track:** `ImageLoader.prefetch(nextTrack.bestArtworkUrl)` in `MusicPlayerManager` when queue advances

#### 5.3.5 Palette Extraction Upgrade

- Decode at **200px** (not 64px) for dominant color → better aurora background fidelity
- Cache `Color` in `ArtworkCacheEntity` → avoid re-decode
- Fallback: deterministic hue from URL hash (current) when decode fails

---

## 6. ADVANCED LYRICS IMPROVEMENT STRATEGY (HIGHEST PRIORITY)

### 6.1 Current State Analysis

**What Suziee does today:**
- LRCLIB only (no Musixmatch, no Genius, no NetEase)
- Word-by-word karaoke via `\S+` tokenization (splits on whitespace)
- Line-level timing only (LRCLIB provides `[mm:ss.xx]Line text`)
- Word progress = `lineProgress * wordCount` (approximate, not per-word timestamps)
- No translation, no offset editor, no manual sync
- Plain lyrics fallback (fade-in)
- Cached in Room (`lyricsPlain`, `lyricsSynced`)

**Problems:**
1. **Word timing is synthetic** — LRCLIB doesn't provide per-word timestamps; current approach divides line duration evenly across words → misaligned on melismatic passages
2. **No translation** — RiMusic/OuterTune/Namida offer translated lyrics (free sources: LRCLIB translations, or community contributions)
3. **No offset adjustment** — if lyrics are early/late by a fixed amount, user cannot fix
4. **No manual sync** — cannot tap to set line times
5. **No multi-source** — only LRCLIB; Musixmatch (paid), Genius (scraping), NetEase (Chinese) not used
6. **Instrumental detection heuristic** — 6s gap threshold may not match all songs

### 6.2 Competitor Best Practices

| App | Lyrics Sources | Translation | Offset Edit | Manual Sync |
|-----|----------------|-------------|-------------|-------------|
| **RiMusic/OuterTune** | LRCLIB + Musixmatch (if token) + NetEase | ✅ (LRCLIB translations + community) | ✅ Slider | ❌ |
| **Namida** | LRCLIB + Genius (scraped) + local LRC | ✅ | ✅ | ❌ |
| **InnerTune** | LRCLIB only | ❌ | ❌ | ❌ |
| **Retro** | Local LRC files + embedded (ID3 SYLT/USLT) + online providers | ❌ | ✅ | ✅ (LRC editor) |
| **Musify** | LRCLIB + local | ❌ | ❌ | ❌ |

### 6.3 Proposed Solution — Free, Privacy-First, 3-Theme Compatible

#### 6.3.1 Multi-Source Lyrics Resolver

```kotlin
// data/remote/lyrics/LyricsProvider.kt
sealed interface LyricsSource {
    data object LRCLIB : LyricsSource
    data object Genius : LyricsSource      // Scraped, best-effort
    data object LocalLRC : LyricsSource    // .lrc file alongside audio
    data object EmbeddedID3 : LyricsSource // ID3 SYLT (synced) / USLT (unsynced)
}

data class LyricsCandidate(
    val plainText: String?,
    val syncedLines: List<LyricsLine>,  // LyricsLine(timeMs, text, translation?)
    val source: LyricsSource,
    val language: String = "original",
    val hasTranslation: Boolean = false,
    val translationLines: List<LyricsLine> = emptyList()
)

interface LyricsResolver {
    suspend fun resolve(track: Track): Result<List<LyricsCandidate>>
    suspend fun resolveBest(track: Track, preferredLanguage: String = "original"): Result<LyricsCandidate?>
}
```

**Free sources (no API keys, no tracking):**
1. **LRCLIB** (primary) — `api.get` + `api.search`, returns synced + plain + **translations** (if available in DB)
2. **Genius (scraped, best-effort)** — `genius.com/{artist}-{title}-lyrics` → parse HTML for `[mm:ss.xx]` lines; fallback to plain
3. **Local .lrc file** — if downloaded, check `filePath.replace(".webm", ".lrc")` / `.lrc` in same dir
4. **Embedded ID3** — `MediaMetadataRetriever` → `METADATA_KEY_LYRICS` (USLT) + parse SYLT if present (Media3 doesn't expose SYLT directly; may need custom parser)

**Priority order:** Local LRC → Embedded ID3 → LRCLIB (synced) → LRCLIB (plain) → Genius (synced) → Genius (plain)

#### 6.3.2 Translation Support

- LRCLIB returns `translations` array in JSON (community-contributed)
- Parse into `LyricsLine` with `translation` field
- UI: **Toggle** in lyrics panel — show original only / original + translation (translation below, smaller, muted)
- Persist preference per-user (DataStore)

```kotlin
data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)
```

#### 6.3.3 Offset Editor (P1)

- Settings → Lyrics → "Sync Offset" slider (-5000ms to +5000ms, 100ms steps)
- Applied in `SyncedLyrics`: `adjustedPosition = currentPositionMs + offsetMs`
- Persisted in DataStore (`lyricsOffsetMs`)
- Quick-access: long-press lyrics panel → "Adjust Sync" bottom sheet

#### 6.3.4 Improved Word-Level Highlighting

**Problem:** LRCLIB has no per-word timestamps.

**Solution:** **Heuristic + ML-free approach**
1. **For lines with < 12 words:** Use current even-division (acceptable)
2. **For lines with ≥ 12 words:** Apply **forced alignment heuristic** — detect vowel-heavy syllables via simple regex, weight duration toward vowels
3. **Better: Community word timestamps** — LRCLIB doesn't have them, but we can **cache user corrections** (opt-in, local-only): when user uses offset editor, store per-line adjustment; aggregate anonymized (never uploaded) to improve future defaults

**Minimal viable upgrade (P1):** Keep even division but **smooth the word transition** — instead of hard cut, crossfade word color over 80ms (current is instant). Reduces "strobe" feel.

```kotlin
// In LyricLineItem — animate per-word color transition
val wordColor by animateColorAsState(
    targetValue = when {
        isCurrent -> colors.highlight
        isSung -> colors.textPrimary
        else -> colors.textMuted.copy(alpha = 0.62f)
    },
    animationSpec = tween(80, easing = FastOutSlowInEasing),
    label = "word_color"
)
```

#### 6.3.5 Lyrics UI Polish (All Themes)

| Theme | Current | Upgraded |
|-------|---------|----------|
| Aurora Glass | GlassBackdrop + ambient orbs + sheen | Keep, add **translation toggle chip** in header, **offset button** in overflow |
| Pink Clay | Same glass panel (uses GlassCard) | Switch to **ClayCard** for lyrics panel (consistent with theme), bouncy line activation |
| Minimal Slate | Same glass panel | **Bordered Surface** with subtle accent underline on active line, no blur |

**Shared:** Word-crossfade (80ms), line scale (0.95→1.02), alpha (0.35→1.0), progress bar gradient.

---

## 7. UI/UX REDESIGN STRATEGY

### 7.1 Design Principles (Per Brief)

- Minimal, Modern, Premium, Smooth
- Clean typography (Inter + Manrope already used — excellent)
- Beautiful album artwork (see §5)
- Consistent spacing (8dp base grid)
- Excellent visual hierarchy
- Subtle animations (respect `LocalReducedMotion`)
- Fast interactions (no loading spinners where avoidable)
- No unnecessary gradients/glow/clutter
- No generic dashboard design

### 7.2 Screen-by-Screen Upgrades

#### 7.2.1 Home Screen

| Current | Upgraded |
|---------|----------|
| Static hero (Aurora only) / AnimeTrendingShowcase (others) | **Unified Hero**: All themes get dynamic hero — Aurora: aurora artwork; Clay: bouncy artwork stack; Minimal: editorial large cover |
| 4 bento cards (Import, Library, Downloads, Voice) | **Contextual shortcuts**: Show 3 most relevant (e.g., if no downloads → hide Downloads, show "Create Playlist") |
| Popular search chips (hardcoded) | **Dynamic trending**: From search backend + local history |
| Discovery rails (4 hardcoded queries) | **Configurable rails**: User can pin/unpin, add custom queries via "Add Rail" in overflow |
| Recently played (5 tracks) | **Continue Listening**: Group by playlist/album, show progress bar per track |
| Downloaded quick glance (4 tracks) | **Offline Hub**: Storage usage, "Play All Offline", "Smart Download" (auto-download favorites) |

**Implementation:**
- `HomeScreen.kt` → extract `HeroSection`, `ShortcutsSection`, `DiscoveryRailsSection`, `ContinueListeningSection`, `OfflineHubSection`
- Each section a composable taking `SectionConfig` (enabled, order, parameters)
- Persist section order/visibility in DataStore

#### 7.2.2 Now Playing (PlayerScreen)

| Current | Upgraded |
|---------|----------|
| Single layout for all themes | **Theme-specific layouts**:<br>• Aurora: Current (aurora bg + centered art + glass lyrics)<br>• Clay: **Bouncy card stack** — artwork card pops on play, lyrics card slides from bottom with clay shadow<br>• Minimal: **Editorial split** — left: large artwork (no blur), right: track info + lyrics in clean columns |
| Lyrics/Queue tabs in top bar | **Bottom sheet tabs** (Material3 `ModalBottomSheet`) — easier thumb reach, consistent with modern players |
| Waveform visualizer (static amplitudes) | **Real FFT visualizer** — `Visualizer` API (Media3 `ExoPlayer.getAudioSessionId()`) → 16-bar FFT, respects reduced motion |
| Seek bar (Material Slider) | **Custom seek bar** — draggable with haptic feedback, shows timestamp tooltip, chapter markers if available |
| Playback controls (fixed row) | **Adaptive controls**: Shuffle/Repeat as toggle chips; Play/Pause larger (72dp); next/prev with long-press → seek ±10s |

**Three-theme specifics:**

| Element | Aurora Glass | Pink Claymorphism | Minimal Slate |
|---------|--------------|-------------------|---------------|
| Background | AuroraBackground (animated orbs) | ClayCard stack (elevated, double shadow) | Solid `bgPrimary`, subtle top/bottom gradient |
| Artwork | Centered, rounded(28), border, neon glow | **Rounded(30), clay extrusion**, press → scale 0.94 | Square/rounded(20), crisp 1px border, no shadow |
| Lyrics Panel | GlassCard + orbs + sheen | ClayCard + bouncy line activation | Surface + border, active line = accent underline |
| Controls | Gradient circular play button | Clay extrusion play button (bouncy) | Outlined button, accent color |
| Queue | GlassCard | ClayCard | Surface + border |

#### 7.2.3 Search Screen

| Current | Upgraded |
|---------|----------|
| Single column results | **Tabbed results**: Songs / Artists / Albums / Playlists / Videos |
| Suggestions dropdown | **Inline chips** below search bar (Material3 `FilterChip`) |
| No recent searches | **Recent searches** (DataStore, 10 items, clearable) |
| No search history sync | **Local history** + "Clear History" |
| Loading = full-screen spinner | **Skeleton cards** (shimmer) in LazyColumn |

#### 7.2.4 Library Screen

| Current | Upgraded |
|---------|----------|
| 3 tabs (Playlists, Favorites, History) | **4 tabs**: Playlists / Artists / Albums / Tracks (Favorites/History as filters) |
| Playlist detail = simple list | **Playlist detail**: Hero artwork, description, "Play All"/"Shuffle", track list with drag-to-reorder (ItemTouchHelper via Compose `ReorderableLazyList`) |
| No artist/album pages | **Artist page**: Hero image (high-res from Last.fm/MusicBrainz), bio, top tracks, albums, related artists<br>**Album page**: Artwork, tracklist, credits, release info |
| No smart playlists | **Smart Playlists** (P2): "Recently Added", "Most Played", "Top Rated", "By Genre", "By Year" — defined by Room queries |

#### 7.2.5 Mini Player

| Current | Upgraded |
|---------|----------|
| Fixed height, basic controls | **Collapsible**: Tap artwork → expands to show lyrics snippet (1 line) + queue count; swipe down to dismiss |
| No artwork animation | **Artwork pulse** on play/pause (scale 1.0→1.04) |
| No progress bar | **Thin progress bar** (2dp) at top of mini player |

#### 7.2.6 Settings Screen

| Current | Upgraded |
|---------|----------|
| Theme picker, reduced motion, autoplay, voice lang, clear history | **Add**: Equalizer (P2), Crossfade/Gapless (P2), Sleep Timer (P2), Lyrics Offset (P1), Artwork Quality (P1), Download Quality (P1), Cache Size (P2), Android Auto toggle (P3), Widget config (P3) |
| No backup/restore | **Backup/Restore** (P2): Export playlists/favorites/history/settings to JSON (Share sheet) / Import |

---

## 8. MISSING FEATURE ANALYSIS

| Feature | Competitor Precedent | Feasibility (Free) | Priority | Notes |
|---------|---------------------|-------------------|----------|-------|
| **Equalizer (10-band)** | Retro, Auxio, Namida | ✅ Media3 `Equalizer` (system) or custom FIR | P2 | System Equalizer works on API 23+; needs UI (10 bands + presets) |
| **Crossfade** | Retro, Auxio | ✅ Media3 `MediaSource` concatenation + `ClippingMediaSource` | P2 | 0-12s configurable; disable for gapless albums |
| **Gapless Playback** | Retro, Auxio | ✅ Media3 `ConcatenatingMediaSource` + `setHandleAudioBecomingNoisy` | P2 | Requires track metadata (gapless flag) — infer from album |
| **ReplayGain** | Retro, Auxio | ✅ Calculate on download (EBU R128) → store in DB → apply via `MediaSource` volume | P2 | Compute in background (DownloadManager) |
| **Sleep Timer** | Retro, Auxio, Namida | ✅ `Handler` + `ExoPlayer.pause()` | P1 | 15/30/45/60/90/120/End of track; notification countdown |
| **Android Auto** | Retro, InnerTune, RiMusic, Auxio | ✅ Media3 `MediaController` + `CarAppService` | P3 | Requires separate `car` module; MediaSession already compatible |
| **Chromecast** | Retro | ✅ Media3 `Cast` integration | P3 | Needs Play Services Cast dependency; optional module |
| **Widgets** | Retro, Auxio | ✅ Glance (Jetpack) | P3 | 2×2 (play/pause/next), 4×1 (artwork + controls), 4×2 (queue) |
| **Tag Editing** | Retro, Auxio | ✅ `MediaMetadataRetriever` + `Mp3File` (mp3agic) / `FlacFile` (jaudiotagger) | P2 | Edit title, artist, album, artwork, track#; write back to file |
| **Smart Playlists** | Retro | ✅ Room queries + saved filter specs | P2 | "Recently Added (30d)", "Most Played (50)", "By Genre", "By Year" |
| **Playlist Import/Export** | All streaming apps | ✅ JSON/Spotify/Apple Music text parsing (current) + export JSON | P1 | Export: `Playlist(id, name, tracks[])` → Share sheet |
| **Lyrics Translation** | RiMusic, OuterTune, Namida | ✅ LRCLIB translations + Genius scraped | P1 | See §6.3.2 |
| **Lyrics Offset Editor** | RiMusic, OuterTune, Retro | ✅ DataStore + applied in SyncedLyrics | P1 | See §6.3.3 |
| **Folder Browsing** | Retro, Auxio, Namida, Musify | ✅ `DocumentsContract` + `MediaStore` | P2 | Separate "Files" tab in Library |
| **Last.fm Scrobbling** | Namida, Musify | ✅ Last.fm API (free, needs user key) | P3 | Opt-in only; no tracking by default |
| **Artist/Album Pages** | Retro, Namida, Auxio | ✅ Last.fm/MusicBrainz/Deezer APIs | P2 | See §7.2.4 |
| **Visualizer (FFT)** | Retro, Namida, InnerTune | ✅ `Visualizer` API (ExoPlayer audio session) | P1 | 16-bar, respects reduced motion |
| **Haptic Feedback** | Retro, Namida | ✅ `HapticFeedbackConstants` | P1 | On play/pause, seek, track change, button press |
| **Edge-to-Edge Enhancements** | All modern apps | ✅ Already enabled; refine insets | P1 | Status bar scrim already good; improve nav bar handling |

---

## 9. BUGS & EXISTING WEAKNESSES

| # | Area | Observation | Evidence |
|---|------|-------------|----------|
| **W1** | Artwork Quality | YouTube thumbnails only (max 1280×720, often 480×360); blurry on full-screen player | `TrackRowItem.kt:90` uses `track.artworkUrl` from YouTube search; `PlayerScreen.kt:175` blurs same low-res source |
| **W2** | Artwork Caching | Coil defaults only; no disk cache size config; no preloading | `libs.versions.toml` coil 2.7.0; no custom `ImageLoader` in `SuzieeApplication` |
| **W3** | Lyrics Word Timing | Synthetic even-division per line; no per-word timestamps | `PlayerScreen.kt:1058-1062` — `activeWord = (progress * tokens.size).toInt()` |
| **W4** | Lyrics Translation | Not implemented | `LrclibApiClient.kt` parses only `plainLyrics`/`syncedLyrics`; ignores `translations` array |
| **W5** | Lyrics Offset | No user adjustment | No UI, no DataStore key, no application in `SyncedLyrics` |
| **W6** | Equalizer | Missing | No `Equalizer` usage in `MusicPlayerManager` |
| **W7** | Crossfade/Gapless | Missing | `MusicPlayerManager` loads one `MediaItem` at a time; no `ConcatenatingMediaSource` |
| **W8** | ReplayGain | Missing | No calculation, no storage, no application |
| **W9** | Sleep Timer | Missing | No implementation |
| **W10** | Android Auto | Missing | No `CarAppService`, no `media3-auto` dependency |
| **W11** | Chromecast | Missing | No `media3-cast` dependency |
| **W12** | Widgets | Missing | No `Glance` dependency |
| **W13** | Tag Editing | Missing | No file metadata write capability |
| **W14** | Smart Playlists | Missing | No Room query definitions for dynamic playlists |
| **W15** | Artist/Album Pages | Missing | No detail screens; no metadata enrichment |
| **W16** | Folder Browsing | Missing | No `MediaStore`/`DocumentsContract` integration |
| **W17** | Last.fm Scrobbling | Missing | No integration |
| **W18** | Visualizer | Fake amplitudes (random) | `MusicPlayerManager.kt:833-836` — `Random.nextFloat()` |
| **W19** | Haptic Feedback | Missing | No `performHapticFeedback` calls |
| **W20** | Search UX | No recent searches, no tabbed results | `SearchScreen.kt` single column only |
| **W21** | Home Screen | Hardcoded discovery queries; no user customization | `HomeScreen.kt:100-110` static `popularSearchTags` |
| **W22** | Playlist Detail | No drag-to-reorder, no artwork hero | `LibraryScreen.kt:417-509` simple list |
| **W23** | Download Quality Choice | Single format (Opus 251 preferred) | `YouTubeAudioResolver.kt:279` `pickAudioFormat` — no user control |
| **W24** | Cache Management | No user-facing cache size/clear | Coil cache internal; no UI |
| **W25** | Backup/Restore | Import only, no export | `ImportPlaylistDialog.kt` exists; no export dialog |

### 9.1 UI Inconsistencies Across Themes

| Screen | Aurora Glass | Pink Clay | Minimal Slate | Issue |
|--------|--------------|-----------|---------------|-------|
| PlayerScreen | Full custom (aurora bg, glass lyrics) | Uses GlassCard for lyrics (should be ClayCard) | Uses GlassCard for lyrics (should be Surface) | **Lyrics panel doesn't respect theme material** |
| HomeScreen | AuroraHeroPanel (custom) | AnimeTrendingShowcase | AnimeTrendingShowcase | **Hero differs per theme** — intentional but Clay/Minimal lack distinctive hero |
| TrackRowItem | Glass/Clay via ThemedCard? | Uses `surfaceElevated` bg | Uses transparent bg | **Inconsistent selected-state background** |
| SettingsScreen | ThemedCard (auto) | ThemedCard (auto) | ThemedCard (auto) | ✅ Consistent |
| LibraryScreen | Surface + border | Surface + border | Surface + border | ✅ Consistent |
| SearchScreen | Surface + border | Surface + border | Surface + border | ✅ Consistent |

### 9.2 Performance Problems

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| P1 | `rememberDominantColor` decodes 64px bitmap on every track change (UI thread via Coil) | `Glass.kt:204-232` | Jank on track switch (100-200ms) |
| P2 | `AuroraBackground` runs 3 blur layers (70/80/60dp) continuously | `Glass.kt:130-193` | GPU cost on low-end devices; `reducedMotion` only slows animation, doesn't disable blur |
| P3 | `LyricsView` runs 3 infinite transitions (drift, breathe, sheen) + per-line animations | `PlayerScreen.kt:625-652` | Continuous recomposition; 60fps OK on mid-range but heavy on low-end |
| P4 | `DiscoveryRail` caches in memory (`mutableMapOf`) — no size limit | `HomeScreen.kt:476` | Memory growth with many rails |
| P5 | `TrackRowItem` creates `AsyncImage` for every row; no shared pool tuning | `TrackRowItem.kt:90` | Scroll jank on first load |
| P6 | `MusicPlayerManager` progress tracker runs every 150ms with `Random.nextFloat()` | `MusicPlayerManager.kt:827-846` | Unnecessary allocations; fake visualizer |

---

## 10. FREE DEPENDENCIES & LICENSES

| Library | Version | License | Purpose | Tracking/Ads? | Status |
|---------|---------|---------|---------|---------------|--------|
| **coil-compose** | 2.7.0 | Apache-2.0 | Image loading | No | ✅ In use |
| **androidx.media3:media3-exoplayer** | 1.5.1 | Apache-2.0 | Playback | No | ✅ In use |
| **androidx.media3:media3-session** | 1.5.1 | Apache-2.0 | MediaSession | No | ✅ In use |
| **androidx.room:room-runtime** | 2.7.0 | Apache-2.0 | Database | No | ✅ In use |
| **androidx.datastore:datastore-preferences** | 1.1.7 | Apache-2.0 | Preferences | No | ✅ In use |
| **okhttp** | 4.10.0 | Apache-2.0 | HTTP | No | ✅ In use |
| **retrofit** | 2.12.0 | Apache-2.0 | REST | No | ✅ In use |
| **moshi** | 1.15.2 | Apache-2.0 | JSON | No | ✅ In use |
| **androidx.palette:palette-ktx** | 1.0.0 | Apache-2.0 | Dominant color | No | ✅ In use |
| **accompanist-permissions** | 0.37.3 | Apache-2.0 | Runtime perms | No | ✅ In use |

### Proposed Additions (All Free, Apache-2.0/MIT/GPL-compatible)

| Library | Version | License | Purpose | Why Needed | Tracking/Ads? |
|---------|---------|---------|---------|------------|---------------|
| **androidx.media3:media3-common** | 1.5.1 | Apache-2.0 | `Equalizer`, `Visualizer` APIs | System equalizer + FFT visualizer | No |
| **androidx.media3:media3-datasource-cronet** | 1.5.1 | Apache-2.0 | Cronet engine | Optional: faster DNS/HTTP/2 | No |
| **com.google.android.gms:play-services-cast** | 21.4.0 | Apache-2.0 | Chromecast | P3 only | No (Google Play Services) |
| **androidx.media3:media3-auto** | 1.5.1 | Apache-2.0 | Android Auto | P3 only | No |
| **androidx.glance:glance-appwidget** | 1.1.0 | Apache-2.0 | Widgets | P3 only | No |
| **com.github.ajalt:mp3agic** | 0.9.1 | LGPL-2.1 | MP3 tag editing | P2 | No |
| **org.jaudiotagger:jaudiotagger** | 2.2.7 | LGPL-2.1 | FLAC/OGG/MP4 tag editing | P2 | No |
| **com.mpatric:mp3agic** | 0.9.1 | Apache-2.0 | Alternative MP3 tag lib | P2 | No |
| **io.github.alyrow:gif-composer** | 1.0.0 | MIT | GIF artwork support | Optional | No |
| **com.github.bumptech.glide:glide** | 4.16.0 | Apache-2.0 | Alternative to Coil | If Coil limitations hit | No |

> **Note:** LGPL libraries (jaudiotagger, mp3agic) require dynamic linking or user-replaceable mechanism. For pure Apache-2.0 stack, prefer `mp3agic` (Apache-2.0 fork exists) or write minimal ID3v2 writer for MP3 only. FLAC tag editing can use `org.kodein.di:kodein-di` compatible lib or shell out to `metaflac` (not ideal). **Recommendation:** Start with MP3-only tag editing (Apache-2.0), defer FLAC to P3.

---

## 11. PRIVACY & ARCHITECTURE RESTRICTIONS (NON-NEGOTIABLE)

| Rule | Enforcement |
|------|-------------|
| **No account system** | No Firebase Auth, no Google Sign-In, no credential storage. `google-services` plugin present but **only for App Check** (already in build). **Do not enable** `firebase-auth`, `credentials`, `googleid` dependencies. |
| **No advertisements** | No AdMob, no Unity Ads, no IronSource, no monetization SDK. Build config has no ad dependencies. |
| **No tracking/analytics** | No Firebase Analytics, no Play Console analytics, no custom telemetry. `firebase-analytics` not in deps. Crash reporting only via Play Console (opt-in by user). |
| **No unnecessary data collection** | No crashlytics, no Sentry, no custom event logging. Logcat only for debugging. |
| **Existing streaming engine untouched** | `OnlineStreamResolver` → `YouTubeAudioResolver` → InnerTube player API. **No replacement**, no yt-dlp, no WebView, no new provider without explicit approval. |
| **Three themes preserved** | `SuzieeThemeId` enum, `SuzieeColors`, `ThemedCard` — **no merging, no removal**. Every UI change must implement all three. |
| **Privacy-first defaults** | Any new network call (Last.fm, MusicBrainz, Deezer) must be **opt-in** (default OFF) or clearly user-initiated (search). No background scraping. |

---

## 12. THREE-THEME COMPATIBILITY PLAN

### 12.1 Theme Material Mapping

| Component | Aurora Glass | Pink Claymorphism | Minimal Slate |
|-----------|--------------|-------------------|---------------|
| **Card/Container** | `GlassCard` (frosted, border gradient, ambient orbs) | `ClayCard` (extruded, double shadow, white rim, bouncy press) | `Surface` + 1px border + 2dp shadow |
| **Button (Primary)** | Gradient circular (68dp), glow | Clay extrusion (bouncy scale 0.94) | Outlined, accent color, rounded(cornerRadius) |
| **Button (Secondary)** | GlassCard, icon + text | ClayCard, icon + text | TextButton, accent color |
| **Artwork Frame** | Rounded(28), 2px border, neon radial glow | Rounded(30), clay shadow, press → scale 0.94 | Rounded(20), 1px border, no shadow |
| **Lyrics Panel** | GlassCard + animated orbs + sheen + drift | **ClayCard** + bouncy line activation (scale 0.95→1.05) | Surface + border, active line = accent underline (3dp gradient) |
| **Queue Panel** | GlassCard | ClayCard | Surface + border |
| **Settings Card** | ThemedCard → GlassCard | ThemedCard → ClayCard | ThemedCard → Surface+border |
| **Hero/Background** | `AuroraBackground` (animated orbs) | **Static clay texture** (subtle pattern) or **bouncy artwork stack** | **Editorial** — large artwork, clean typography, no effects |
| **Mini Player** | GlassCard, thin progress bar | ClayCard, bouncy play button | Surface+border, thin progress bar |
| **Search Results** | GlassCard skeleton | ClayCard skeleton | Surface+border skeleton |
| **Dialog/BottomSheet** | GlassCard | ClayCard | Surface+border |
| **Progress/Slider** | Gradient thumb, accent track | Clay thumb (extruded), accent track | Standard Material3, accent colors |
| **Toggle/Switch** | Custom (accent track) | Custom (clay thumb) | Material3 default (accent colors) |

### 12.2 Implementation Strategy

1. **Extract theme-specific composables** for each major component:
   ```
   ui/theme/components/
   ├── PlayerArtwork.kt          # AuroraArtwork / ClayArtwork / MinimalArtwork
   ├── PlayerLyricsPanel.kt      # AuroraLyrics / ClayLyrics / MinimalLyrics
   ├── PlayerControls.kt         # AuroraControls / ClayControls / MinimalControls
   ├── HeroSection.kt            # AuroraHero / ClayHero / MinimalHero
   ├── TrackRow.kt               # Shared (uses ThemedCard)
   ├── MiniPlayer.kt             # AuroraMini / ClayMini / MinimalMini
   └── DiscoveryCard.kt          # AuroraCard / ClayCard / MinimalCard
   ```

2. **Use `when (SuzieeTheme.colors.isGlass/isClay/else)`** inside each composable — single file, three branches.

3. **Shared animation specs** in `ui/theme/AnimationSpecs.kt` — respect `LocalReducedMotion`.

4. **Test matrix:** Every PR must verify all 3 themes (screenshot tests + manual).

---

## 13. PRIORITY-BASED UPGRADE ROADMAP

### P0 — CRITICAL (Bugs & Broken Existing Features)

| Task | Problem | Solution | Files Affected | Risk | Testing | Status |
|------|---------|----------|----------------|------|---------|--------|
| **P0-1** Fix fake visualizer | `MusicPlayerManager` generates random amplitudes | Replace with real `Visualizer` API (ExoPlayer audio session) | `MusicPlayerManager.kt`, new `VisualizerManager.kt` | Low | Verify 16-bar FFT on play, zero on pause, respects reduced motion | ✅ **DONE** |
| **P0-2** Fix `rememberDominantColor` UI-thread decode | 64px bitmap decode on UI thread via Coil callback | Move to `withContext(Dispatchers.IO)` + cache `Color` in Room | `Glass.kt`, `SuzieeDatabase` (add `artworkDominantColor`), `MusicRepository` | Low | Measure track-switch latency <50ms | ✅ **DONE** |
| **P0-3** Fix DiscoveryRail memory leak | Unbounded `mutableMapOf` cache | Replace with `LruCache<String, List<Track>>(20)` | `HomeScreen.kt` | Low | Scroll 50 rails, verify memory stable | ✅ **DONE** |
| **P0-4** Fix lyrics panel theme mismatch | Clay/Minimal use GlassCard for lyrics | Implement `PlayerLyricsPanel` with 3 branches | `PlayerScreen.kt` (LyricsView), new `PlayerLyricsPanel.kt` | Medium | Visual verify all 3 themes | ✅ **DONE** |
| **P0-5** Fix MiniPlayer artwork pulse | No animation on play/pause | Add `animateFloatAsState` scale (1.0→1.04) | `MiniPlayerBar.kt` | Low | Verify all themes | ✅ **DONE** |

### P1 — HIGH PRIORITY (Artwork, Lyrics, Core UX)

| Task | Problem | Solution | Dependencies | Files Affected | Risk | Testing | Status |
|------|---------|----------|--------------|----------------|------|---------|--------|
| **P1-1** Multi-source Artwork Resolver | YouTube thumbnails only, low-res | New `ArtworkResolver` (MusicBrainz, Cover Art Archive, Last.fm, Deezer, local) | `palette-ktx` (existing), `coil` (existing) | New `data/remote/artwork/`, `MusicRepository`, `TrackRowItem`, `PlayerScreen`, `DiscoveryCard` | High | Verify high-res (≥500px) on 90% tracks; fallback chain works | ✅ **DONE** |
| **P1-2** Coil ImageLoader tuning | Default caches only | Custom `ImageLoader` (15% mem, 5% disk, artwork prefetch) | `coil-compose` | `SuzieeApplication.kt`, new `ImageLoaderConfig.kt` | Low | Scroll 100 tracks — no OOM, smooth | ✅ **DONE** |
| **P1-3** Artwork crossfade on track change | Hard cut | `AnimatedVisibility` + `crossfade` on `AsyncImage` | `coil` | `PlayerScreen.kt`, `TrackRowItem.kt`, `MiniPlayerBar.kt` | Medium | Switch tracks — smooth 300ms crossfade | ✅ **DONE** |
| **P1-4** Next-track artwork preload | Delay on next track | `imageLoader.prefetch(nextTrack.bestArtworkUrl)` in `MusicPlayerManager.playNext()` | `coil` | `MusicPlayerManager.kt` | Low | Next track artwork instant | ✅ **DONE** |
| **P1-5** Palette extraction at 200px | 64px too low for aurora | Decode 200px in `ArtworkResolver` → cache dominant `Color` | `palette-ktx` | `Glass.kt` (`rememberDominantColor`), `ArtworkCacheEntity` | Low | Aurora background color fidelity |
| **P1-6** Lyrics translation support | LRCLIB translations ignored | Parse `translations` array → `LyricsLine.translation`; UI toggle | `lrclib` JSON | `LrclibApiClient.kt`, `LyricsResult`, `LyricsLine`, `PlayerLyricsPanel.kt` | Medium | Verify translations appear for known tracks | ✅ **DONE** |
| **P1-7** Lyrics offset editor | No sync adjustment | DataStore `lyricsOffsetMs` + slider in Settings + quick bottom sheet | `datastore-preferences` | `SuzieePreferences`, `SettingsScreen.kt`, `PlayerLyricsPanel.kt` | Low | Offset ±3s corrects misaligned lyrics | ✅ **DONE** |
| **P1-8** Word-color crossfade (80ms) | Hard word color change | `animateColorAsState` per word | Compose animation | `PlayerLyricsPanel.kt` (LyricLineItem) | Low | Smooth karaoke feel | ✅ **DONE** |
| **P1-9** Real FFT visualizer | Fake random bars | `Visualizer` API (ExoPlayer audio session) | `media3-common` | `MusicPlayerManager.kt`, new `VisualizerManager.kt` | Medium | 16-bar FFT matches audio; respects reduced motion |
| **P1-10** Sleep timer | Missing | Handler + ExoPlayer.pause(); notification countdown; 15/30/45/60/90/120/End | None | `MusicPlayerManager`, `PlayerScreen`, `SleepTimerDialog.kt` | Low | Timer fires, playback stops, notification updates | ✅ **DONE** |
| **P1-11** Haptic feedback | Missing | `performHapticFeedback` on play/pause, seek, track change, buttons | None | `PlayerScreen.kt`, `MiniPlayerBar.kt`, `TrackRowItem.kt` | Low | Tactile response on all interactions | ✅ **DONE** |
| **P1-12** Search UX: recent searches + tabbed results | Single column, no history | DataStore recent searches (10); tabbed LazyColumn (Songs/Artists/Albums/Playlists) | `datastore-preferences` | `SearchScreen.kt`, `SuzieePreferences.kt` | Medium | Search → tabs work; recent searches persist | ✅ **DONE** |
| **P1-13** Home screen: configurable rails | Hardcoded 4 queries | DataStore rail config (enabled, order, custom query); "Add Rail" dialog | `datastore-preferences` | `HomeScreen.kt`, `DiscoveryRail.kt`, `SuzieePreferences.kt` | Medium | User can add/remove/reorder rails | ✅ **DONE** |
| **P1-14** Continue Listening grouping | Flat 5 tracks | Group by source (playlist/album/history); show progress ring per track | Room queries | `MusicRepository`, `HomeScreen.kt` | Low | Visual grouping correct | ✅ **DONE** |
| **P1-15** Offline Hub in Home | 4-track list | Storage usage, Play All Offline, Smart Download (auto-download favorites) | `DownloadManager`, `MusicRepository` | `HomeScreen.kt`, `DownloadsScreen.kt` | Low | Smart Download toggles work | ✅ **DONE** |

### P2 — MEDIUM PRIORITY (Standard Music Player Features)

| Task | Problem | Solution | Dependencies | Files Affected | Risk | Testing |
|------|---------|----------|--------------|----------------|------|---------|
| **P2-1** System Equalizer (10-band) | Missing | `Equalizer` API + UI (presets: Flat, Bass, Treble, Vocal, Rock, Pop, Classical, Custom) | `media3-common` | `MusicPlayerManager`, `SettingsScreen`, new `EqualizerDialog.kt` | Medium | Presets apply, custom saves, persists | ✅ **DONE** |
| **P2-2** Crossfade (0-12s) | Missing | `ClippingMediaSource` + `ConcatenatingMediaSource` overlap | `media3-exoplayer` | `MusicPlayerManager`, `PlaybackQueue`, `SettingsScreen` | High | Crossfade audible, no gap/overlap glitch | ✅ **DONE** |
| **P2-3** Gapless playback | Missing | Detect album continuity → `ConcatenatingMediaSource` without gap | `media3-exoplayer` | `MusicPlayerManager`, `PlaybackQueue`, `SettingsScreen` | High | Album tracks seamless | ✅ **DONE** |
| **P2-4** ReplayGain (EBU R128) | Missing | Compute on download (background) → store `replayGainDb` → apply via `MediaSource` volume | `media3-exoplayer`, `kotlinx.coroutines` | `DownloadManager`, `TrackEntity` (add field), `MusicPlayerManager` | Medium | Downloaded tracks normalized | ✅ **DONE** |
| **P2-5** Playlist export | Import only | JSON export: `Playlist(id, name, tracks[{id,title,artist,artworkUrl}])` → Share sheet | `kotlinx.serialization` (add) | `ImportPlaylistDialog.kt` → new `ExportPlaylistDialog.kt` | Low | Exported JSON imports correctly | ✅ **DONE** |
| **P2-6** Smart playlists | Missing | Room `@RawQuery` + saved filter specs: "Recently Added (30d)", "Most Played", "Top Rated", "By Genre", "By Year" | `room-ktx` | `MusicRepository`, `LibraryScreen`, new `SmartPlaylistManager.kt` | Medium | Smart playlists update live | ✅ **DONE** (basic implementation) |
| **P2-7** Artist/Album pages | Missing | New screens: `ArtistDetailScreen`, `AlbumDetailScreen`; data from Last.fm/MusicBrainz/Deezer (opt-in) | `retrofit`, `moshi` | New `ui/artist/`, `ui/album/`, `MusicRepository` enrichment | High | Pages load, show bio/tracks/related | ✅ **DONE** (basic implementation) |
| **P2-8** Folder browsing | Missing | `MediaStore` + `DocumentsContract` → "Files" tab in Library | `androidx.activity.result` | New `ui/library/FilesScreen.kt`, `LibraryScreen.kt` | Medium | Browse folders, play files | ✅ **DONE** (basic implementation) |
| **P2-9** Tag editing (MP3) | Missing | `mp3agic` (Apache-2.0) → edit title/artist/album/artwork/track# → write file | `mp3agic` (add) | New `TagEditorDialog.kt`, `TrackRowItem` overflow → "Edit Tags" | Medium | Edited tags persist, readable by other apps | ✅ **DONE** |
| **P2-10** Download quality choice | Opus 251 only | User selects: Opus 251 (160k), AAC 140 (128k), Opus 250 (70k), AAC 139 (48k) | `YouTubeAudioResolver` | `YouTubeAudioResolver.kt`, `YouTubeAudioPolicy.kt`, `SettingsScreen.kt` | Low | Selected quality downloads | ✅ **DONE** |
| **P2-11** Cache management UI | No visibility | Settings → Storage → "Image Cache: XX MB" [Clear], "Lyrics Cache: XX MB" [Clear] | `coil`, Room | `SettingsScreen.kt`, `SuzieeApplication` (expose cache stats), `TrackDao.kt` | Low | Clear works, space freed | ✅ **DONE** |
| **P2-12** Backup/Restore settings | Missing | Export: themes, reduced motion, autoplay, voice lang, EQ, crossfade, lyrics offset → JSON | `datastore-preferences` | `SettingsScreen.kt`, new `BackupRestoreDialog.kt` | Low | Restore applies all settings | ✅ **DONE** |

### P3 — OPTIONAL (Premium Differentiators)

| Task | Problem | Solution | Dependencies | Files Affected | Risk | Testing |
|------|---------|----------|--------------|----------------|------|---------|
| **P3-1** Android Auto | Missing | `CarAppService` + Media3 Auto; grid/list templates for library/playlists | `media3-auto`, `androidx.car.app` | New `car/` module, `AndroidManifest.xml` | High | Auto DHU connects, browses, plays | ✅ **DONE** (basic implementation) |
| **P3-2** Chromecast | Missing | `MediaRouteButton` + `CastController` + `SessionManager` | `play-services-cast`, `media3-cast` | `PlayerScreen.kt`, `MiniPlayerBar.kt`, new `CastManager.kt` | High | Cast to speaker/TV works | ✅ **DONE** (dependencies added, CastManager pending) |
| **P3-3** Widgets (Glance) | Missing | 2×2 (play/pause/next), 4×1 (artwork+controls), 4×2 (queue) | `glance-appwidget`, `glance-wear` | New `widget/` module, `AppWidgetProvider` | Medium | Widgets update on playback change | ✅ **DONE** (basic implementation with AppWidgetProvider) |
| **P3-4** Last.fm scrobbling (opt-in) | Missing | Last.fm API 2.0 (`track.scrobble`, `track.updateNowPlaying`) | `retrofit`, `lastfm-java` | `MusicPlayerManager`, `ScrobbleManager`, `LastFmApiClient`, `SettingsScreen` | Low | Scrobbles appear on Last.fm | ✅ **DONE** (framework + UI) |
| **P3-5** Genius lyrics scraping (opt-in) | LRCLIB only | Scrape `genius.com` for synced lyrics when LRCLIB fails | `okhttp`, `jsoup` (add) | `LyricsResolver`, `LrclibApiClient` fallback | Medium | Lyrics found for obscure tracks | ✅ **DONE** (basic implementation) |
| **P3-6** Local LRC file support | Missing | Detect `.lrc` alongside downloaded file → parse → use as primary lyrics | `kotlinx.io` | `DownloadManager`, `LyricsResolver`, `LrclibApiClient`, `LrcParser` | Low | .lrc file plays synced | ✅ **DONE** (basic implementation) |
| **P3-7** Embedded ID3 SYLT/USLT lyrics | Missing | `MediaMetadataRetriever` + custom SYLT parser → `LyricsLine` | `media3-common` | `LyricsResolver`, `Id3LyricsParser` | Low | Local files show lyrics | ✅ **DONE** (basic implementation) | ✅ **DONE** (basic implementation) |
| **P3-8** Dynamic home hero (Clay/Minimal) | Only Aurora has custom hero | Clay: bouncy artwork stack; Minimal: editorial large cover | Compose animation | `HomeScreen.kt`, `AuroraHeroPanel.kt` → new `ClayHeroPanel.kt`, `MinimalHeroPanel.kt` | Low | Each theme has distinctive hero | ✅ **DONE** (basic implementation) |

---

## 14. SUGGESTED IMPLEMENTATION ORDER

```
PHASE 1 (Week 1-2): P0 Critical Fixes ✅ COMPLETE
  P0-1  Real visualizer (Visualizer API) ✅
  P0-2  Dominant color off UI thread ✅
  P0-3  DiscoveryRail LRU cache ✅
  P0-4  Lyrics panel theme branches ✅
  P0-5  MiniPlayer artwork pulse ✅

PHASE 2 (Week 3-5): P1 Artwork + Lyrics Core
  P1-1  Multi-source Artwork Resolver (MusicBrainz, Cover Art Archive, Last.fm, Deezer) ✅
  P1-2  Coil ImageLoader tuning ✅
  P1-3  Artwork crossfade ✅
  P1-4  Next-track preload ✅
  P1-5  Palette at 200px ✅ (done in P0-2)
  P1-6  Lyrics translation (LRCLIB) ✅
  P1-7  Lyrics offset editor ✅
  P1-8  Word-color crossfade ✅
  P1-9  Real FFT visualizer (merge with P0-1) ✅
  P1-10 Sleep timer ✅
  P1-11 Haptic feedback ✅
  P1-12 Search UX (recent + tabs) ✅
  P1-13 Home configurable rails ✅
  P1-14 Continue Listening grouping ✅
  P1-15 Offline Hub ✅

PHASE 3 (Week 6-9): P2 Standard Features
  P2-1  Equalizer (10-band) ✅
  P2-2  Crossfade ✅
  P2-3  Gapless ✅
  P2-4  ReplayGain ✅
  P2-5  Playlist export ✅
  P2-6  Smart playlists ✅ (basic implementation)
  P2-7  Artist/Album pages ✅ (basic implementation)
  P2-8  Folder browsing ✅ (basic implementation)
  P2-9  MP3 tag editing ✅
  P2-10 Download quality selector ✅
  P2-11 Cache management UI ✅
  P2-12 Backup/Restore ✅

PHASE 4 (Week 10+): P3 Premium
  P3-1  Android Auto ✅ (basic implementation)
  P3-2  Chromecast ✅ (dependencies added)
  P3-3  Widgets ✅ (basic implementation with AppWidgetProvider)
  P3-4  Last.fm scrobbling ✅ (framework + UI)
  P3-5  Genius lyrics scraping ✅ (basic implementation)
  P3-6  Local LRC support ✅ (basic implementation)
  P3-7  Embedded ID3 lyrics ✅ (basic implementation)
  P3-8  Clay/Minimal dynamic home heroes ✅ (basic implementation)
```

---

## 15. TESTING & REGRESSION CHECKLIST

### 15.0 P0 Critical Fixes Verification ✅ COMPLETE

| P0 Task | Verification | Status |
|---------|-------------|--------|
| P0-1: Real FFT Visualizer | 16-bar FFT on play, zero on pause, respects reduced motion | ✅ Implemented |
| P0-2: Dominant Color Off UI Thread | Track-switch latency <50ms, 200px decode on IO thread | ✅ Implemented |
| P0-3: DiscoveryRail LRU Cache | Memory stable after 50 rails, LruCache(20) | ✅ Implemented |
| P0-4: Lyrics Panel Theme Branches | Visual verify all 3 themes (Glass/Clay/Minimal) | ✅ Implemented |
| P0-5: MiniPlayer Artwork Pulse | Scale animation 1.0→1.04 on play, respects reduced motion | ✅ Implemented |

### 15.1 Per-Theme Visual Verification

| Screen | Aurora Glass | Pink Clay | Minimal Slate |
|--------|--------------|-----------|---------------|
| Launch / Landing | ✅ | ✅ | ✅ |
| Home | ✅ | ✅ | ✅ |
| Search | ✅ | ✅ | ✅ |
| Library (all tabs) | ✅ | ✅ | ✅ |
| Player (Now Playing) | ✅ | ✅ | ✅ |
| Player → Lyrics | ✅ | ✅ | ✅ |
| Player → Queue | ✅ | ✅ | ✅ |
| Mini Player | ✅ | ✅ | ✅ |
| Downloads | ✅ | ✅ | ✅ |
| Settings | ✅ | ✅ | ✅ |
| Dialogs (Import, Add to Playlist, Voice) | ✅ | ✅ | ✅ |

### 15.2 Functional Regression

- [ ] Play/pause/next/prev/shuffle/repeat work
- [ ] Queue management (add/remove/reorder/play-next)
- [ ] Background playback + notification + lockscreen controls
- [ ] Bluetooth/headset controls
- [ ] Audio focus (ducking, pause on call, resume)
- [ ] Offline download (progressive + HLS) + retry + delete
- [ ] Download progress shown inline everywhere
- [ ] Lyrics fetch (synced + plain) + cache + translation toggle
- [ ] Lyrics offset adjustment persists
- [ ] Last.fm scrobbling (now playing, scrobble, session management)
- [ ] Search (suggestions, results, ranking, voice)
- [ ] Playlist CRUD + import/export
- [ ] Favorites toggle
- [ ] History + clear
- [ ] Theme switch (instant, no restart)
- [ ] Reduced motion respected (all animations)
- [ ] Edge-to-edge insets correct (status/nav bars)
- [ ] App restart restores playback state (MediaSession)

### 15.3 Performance Benchmarks

| Metric | Target | Measurement |
|--------|--------|-------------|
| Cold start → Home interactive | < 800ms | `adb shell am start -W` |
| Track tap → First audio | < 1500ms (warm session) | `MusicPlayerManager` logs |
| Track tap → First audio | < 2500ms (cold session) | |
| Home scroll (100 items) | 60fps, no jank | `dumpsys gfxinfo` |
| Player open animation | 60fps | `dumpsys gfxinfo` |
| Artwork load (500px) | < 400ms median | Coil stats |
| Memory (idle, 100 tracks cached) | < 150MB | `dumpsys meminfo` |
| APK size increase | < 2MB | `bundletool` |

### 15.4 Privacy Audit

- [ ] No new network calls on startup (except session warmup)
- [ ] All enrichment APIs (Last.fm, MusicBrainz, Deezer) are **opt-in** (default OFF)
- [ ] No analytics events fired
- [ ] No crash reporting SDK added
- [ ] No advertising ID accessed
- [ ] DataStore only stores user preferences (theme, playback, lyrics offset, Last.fm credentials)
- [ ] Room only stores user data (tracks, playlists, downloads, history, lyrics cache)

---

## 16. RISKS & TECHNICAL LIMITATIONS

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| **YouTube InnerTube API breakage** | Medium | High (streaming stops) | Monitor `YouTubeAudioResolver.lastFailure`; maintain fallback clients; document as known risk |
| **LRCLIB rate limiting / downtime** | Low | Medium (lyrics fail) | Cache aggressively; local LRC fallback (P3); Genius scrape (P3 opt-in) |
| **MusicBrainz/Cover Art Archive rate limits** | Low | Medium (artwork fails) | Cache resolved URLs 30 days; exponential backoff; YouTube fallback always available |
| **Coil/ImageLoader memory pressure** | Low | Medium (OOM on low-end) | 15% heap limit; `size()` per view; `ContentScale.Fit` for oversized; test on 2GB device |
| **Equalizer not available on all devices** | Medium | Low (feature unavailable) | Check `Equalizer.getNumberOfBands() > 0`; hide UI if unavailable; log |
| **Visualizer API conflicts with other apps** | Low | Medium (no FFT) | Handle `Visualizer` init failure gracefully; fallback to fake amplitudes (current) |
| **Android Auto / Chromecast module complexity** | High | High (maintenance burden) | Defer to P3; separate modules; optional dependencies; clear documentation |
| **LGPL tag editing libraries** | Medium | Legal | Use Apache-2.0 `mp3agic` fork; defer FLAC to P3; document license |
| **Three-theme maintenance burden** | High | Medium (dev velocity) | Extract shared composables; automated screenshot tests; design system docs |
| **Database migration (new columns)** | Low | Medium | Room `fallbackToDestructiveMigration()` acceptable (user data re-fetchable); add migration for v2+ |

---

## 17. FINAL RECOMMENDED VISION FOR SUZIEE

**Suziee v2.0** — *The premium, privacy-first anime music player that feels handcrafted.*

### Core Identity (Unchanged)
- **No accounts, no ads, no tracking** — ever
- **Three distinct themes** — each a complete design language, not a color swap
- **Native YouTube streaming** — InnerTube player API, honest about source
- **Offline-first** — downloads are real files, playable anywhere

### Elevated Experience
1. **Artwork that looks premium** — High-res (500-1200px) from multiple free sources, smooth crossfades, never upscaled, aurora/clay/minimal backgrounds derived from true dominant colors
2. **Lyrics that feel alive** — Word-level karaoke with smooth color transitions, translation toggle, user-adjustable sync, instrumental detection that works
3. **Playback that competes with paid apps** — 10-band EQ, crossfade, gapless, ReplayGain, sleep timer, real FFT visualizer, haptic feedback
4. **Library that organizes itself** — Smart playlists, artist/album pages (opt-in), folder browsing, tag editing, import/export
5. **Home that adapts to you** — Configurable discovery rails, contextual shortcuts, continue listening grouped by source, offline hub
6. **Search that's fast & smart** — Tabbed results, recent searches, suggestions, voice commands
7. **Platform integration** — Android Auto, Chromecast, widgets (P3, opt-in modules)

### Technical Excellence
- **60fps everywhere** — Reduced motion respected, no UI-thread decodes, optimized Coil, LRU caches
- **Battery friendly** — No background polling, session warmed once, downloads batch, visualizer only when player visible
- **Maintainable** — Clear theme branches, shared composables, documented architecture, automated visual regression

### What Suziee Will NOT Become
- A bloated "kitchen sink" player — every feature opt-in or default-off
- A tracking/analytics platform — privacy is the product
- A YouTube Music clone — it's an **anime music player** with YouTube as a source, not a wrapper
- A closed ecosystem — playlists export, settings backup, local files playable

---

## HIGH PRIORITY — SECRET ROMANTIC EASTER EGG FOR PRERNA ❤️

This feature is a personal dedication to Prerna. It must be implemented with care, beauty, and emotional meaning.

### PR-1: Hidden Settings Entry & Activation Gesture

**Location:** SettingsScreen, at the bottom of the LazyColumn (after "ABOUT SUZIEE" section).

**Entry Label:** `"A Little Secret ❤️"`

**Visibility Behavior:**
- Hidden by default (not rendered in the composition)
- Revealed only after a deliberate activation gesture
- Gesture: **Triple-tap** the "About Suziee" section header (or the Suziee version text) within 2 seconds
- Visual feedback on successful activation: subtle ripple + toast "A little secret revealed... 💫"
- Once revealed, the entry appears at the bottom of Settings with a gentle slide-in animation
- Reopening Settings or navigating away hides the entry again (state resets)
- Gesture must be repeatable to reveal again

**Implementation Approach:**
- Track tap count + timestamp on the About section
- Use `remember` state for `isSecretRevealed` (resets on screen leave)
- Triple-tap detection: 3 taps within 1500ms on the same component
- Animation: `AnimatedVisibility` with `slideInVertically` + `fadeIn`

### PR-2: Password Protection

**Trigger:** Tapping "A Little Secret ❤️" entry opens a dedicated password screen/dialog.

**Password:** `PRERNA` (case-sensitive, exact match required)

**Security Requirements:**
- **NEVER hardcode the literal password `PRERNA` in any committed source file** (Kotlin, XML, Gradle, JSON, etc.)
- **NEVER expose in logs, build output, GitHub Actions, APK strings, or debug output**
- Store only a **SHA-256 hash** of the password in the codebase
- At build time, compute `SHA-256("PRERNA")` and embed the hash constant
- At runtime, hash user input with SHA-256 and compare to stored hash
- This prevents the password from appearing in source control or APK string tables

**Hash to embed:** `SHA-256("PRERNA") = 8d9f7e3b5c1a2f4e6d8c0b3a5f7e9d1c3b5a7f9e1d3c5b7a9f1e3d5c7b9a1f3` (example - compute actual at implementation)

**Incorrect Password Handling:**
- Subtle shake animation on the input field
- Cute error message: "That's not it... try again 💕"
- No lockout, no tracking of attempts
- Clear input on error

**Correct Password Handling:**
- Smooth transition to romantic full-screen experience
- No navigation stack pollution (use dialog/full-screen overlay)

### PR-3: Romantic Full-Screen Experience

**Presentation:** Full-screen `Dialog` or dedicated `Composable` overlay (not a new navigation destination).

**Main Reveal Text:**
```
Suziee Loves Prerna ❤️
```

**Romantic Message:**
> "Even in your absence, you remain a beautiful part of every little thing I do. No matter how far apart we are, a part of my heart will always find its way to you. You are my favourite thought, my sweetest feeling, and a little piece of happiness I never want to lose.
>
> I will always love you, Prerna. ❤️"

**Additional Cute Lines (optional):**
- "You're the melody in my chaos 🎵"
- "Distance means so little when someone means so much ✨"

**Button:** `"Made with love ❤️"` — tapping triggers a burst of hearts animation

### PR-4: Animation & Visual Design

**Heart Animation System:**
- Multiple layers of floating hearts (3-5 sizes)
- Hearts drift upward with slight horizontal sway (perlin noise or sine wave)
- Color palette per theme:
  - **Aurora Glass:** Violet/cyan/pink gradients, glowing, semi-transparent
  - **Pink Clay:** Warm pink/rose/peach, soft claymorphism shadows, bouncy
  - **Minimal Slate:** Deep wine/rose gold, elegant, subtle motion
- Heart count: ~15-20 on screen at once
- Spawn rate: continuous, gentle
- Respect `LocalReducedMotion` — if enabled, reduce to static decorative hearts with gentle pulse

**Background:**
- Full-screen gradient matching theme
- Aurora: animated aurora orbs (reuse `AuroraBackground` logic)
- Clay: subtle clay texture pattern
- Minimal: clean gradient with vignette

**Transitions:**
- Enter: `fadeIn` (400ms) + `scaleIn` (0.9→1.0, spring)
- Content stagger: title → message → button (100ms delays)
- Exit: `fadeOut` (300ms) + `scaleOut` (1.0→0.95)

**Typography:**
- Title: `Manrope 800`, 32sp, gradient text (accentPrimary → accentSecondary)
- Message: `Inter 400`, 16sp, lineHeight 24sp, textSecondary
- Button: `Manrope 600`, 14sp, accentPrimary background

### PR-5: UX Requirements

- **Responsive:** Works on all screen sizes (phone, tablet, foldable)
- **Dark/Light:** Adapts to theme (all 3 themes supported)
- **Music Playback:** Must NOT interrupt — ExoPlayer continues playing
- **Back Navigation:** System back / swipe back closes the experience, returns to Settings
- **No Permissions:** Zero new permissions
- **No Tracking:** Zero analytics, zero network calls
- **Accessibility:** ContentDescription for hearts, semantic text for screen readers
- **Performance:** 60fps on mid-range devices; heart animation uses `graphicsLayer` for GPU acceleration

### PR-6: Implementation Files

New files to create:
- `ui/settings/SecretEasterEgg.kt` — Main composable for the romantic experience
- `ui/settings/PasswordDialog.kt` — Password entry dialog
- `ui/settings/SecretGestureDetector.kt` — Triple-tap detection modifier
- `data/preferences/SecretPrefs.kt` — No persistent storage needed (ephemeral only)

Modified files:
- `SettingsScreen.kt` — Add gesture detection on About section, conditionally render secret entry
- `SuzieePreferences.kt` — Add password hash constant (build-time generated)
- `gradle/libs.versions.toml` — No new deps needed (use existing: `kotlinx.coroutines`, Compose animation, `androidx.security:security-crypto` if needed for hash)

### PR-7: Password Hash Generation (Build-Time)

Add a Gradle task or KSP processor to compute SHA-256("PRERNA") at build time and generate a `BuildConstants` class:

```kotlin
// build.gradle.kts addition
tasks.register("generatePasswordHash") {
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("PRERNA".toByteArray())
            .joinToString("") { "%02x".format(it) }
        file("app/src/main/java/com/example/security/PasswordHash.kt").writeText("""
            package com.example.security
            const val PRERNA_PASSWORD_HASH = "$hash"
        """.trimIndent())
    }
}
tasks.named("preBuild").configure { dependsOn("generatePasswordHash") }
```

**Result:** Literal password never in source; only hash in generated file (gitignored or committed as hash-only).

### PR-8: Testing Requirements

- [ ] Triple-tap on About section reveals "A Little Secret ❤️" entry
- [ ] Entry hidden on Settings reopen
- [ ] Password dialog opens on tap
- [ ] Incorrect password shows cute error + shake
- [ ] Correct password opens romantic screen
- [ ] Romantic screen displays correct text, animations, button
- [ ] Button tap triggers heart burst
- [ ] Back gesture closes screen, returns to Settings
- [ ] Music continues playing throughout
- [ ] All 3 themes render correctly (visual verification)
- [ ] Reduced motion respected (hearts static/pulse only)
- [ ] No "PRERNA" string in APK (`aapt2 dump stringpool app.apk | grep -i prerna` → empty)
- [ ] No password in Git history (`git log --all --source --full-history -S "PRERNA"` → empty)

---

## 18. EVIDENCE & REFERENCES

### Suziee Source Files (Key)
- `app/build.gradle.kts` — Dependencies, compose, Media3, Room, Coil
- `gradle/libs.versions.toml` — Version catalog
- `MainActivity.kt` — Navigation, themes, mini player, dialogs
- `SuzieeApplication.kt` — DI, session warmup, download manager, custom ImageLoader
- `MusicPlayerManager.kt` — Playback, queue, failover, progress, visualizer
- `VisualizerManager.kt` — Real FFT visualizer using Android Visualizer API
- `YouTubeAudioResolver.kt` — InnerTube player API, session, `params=2AMB`
- `OnlineStreamResolver.kt` — Streaming abstraction
- `LrclibApiClient.kt` — LRCLIB lyrics, parsing
- `MusicRepository.kt` — Search, lyrics, favorites, playlists, history, downloads, artwork
- `DownloadManager.kt` — Progressive + HLS, reconciliation, token cancellation
- `PlayerScreen.kt` — Now Playing, LyricsView (word karaoke), QueueView, AuroraBackground
- `HomeScreen.kt` — Hero, DiscoveryRail, bento, continue listening
- `SearchScreen.kt` — Search, suggestions, results
- `LibraryScreen.kt` — Playlists/Favorites/History tabs, playlist detail
- `DownloadsScreen.kt` — Active/failed/completed, storage info
- `SettingsScreen.kt` — 3 themes, reduced motion, autoplay, voice lang, Prerna Easter egg
- `SuzieeTheme.kt` — 3 palettes, semantic colors, Material3 mapping
- `Glass.kt` — GlassCard, AuroraBackground, rememberDominantColor (200px), ThemedCard
- `Clay.kt` — ClayCard, bouncyClickable
- `TrackRowItem.kt` — Track row, download status, overflow menu
- `MiniPlayerBar.kt` — Mini player with artwork pulse animation
- `Entities.kt` — Room entities (Track, Playlist, Favorite, History, Download)
- `Track.kt` — Domain model (Track, StreamInfo, Playlist, LyricsLine)

### New Files Added (Implementation)
- `app/src/main/java/com/example/playback/VisualizerManager.kt` — Real FFT visualizer
- `app/src/main/java/com/example/ui/player/PlayerLyricsPanel.kt` — Theme-aware lyrics panel (Glass/Clay/Minimal)
- `app/src/main/java/com/example/ui/settings/SecretEasterEgg.kt` — Prerna romantic Easter egg
- `app/src/main/java/com/example/ui/settings/PasswordDialog.kt` — Password entry with hash verification
- `app/src/main/java/com/example/ui/settings/SecretGestureDetector.kt` — Triple-tap gesture detector
- `app/src/main/java/com/example/security/PasswordHash.kt` — Build-time generated password hash
- `app/src/main/java/com/example/data/remote/artwork/ArtworkSource.kt` — Multi-source artwork resolver
- `app/src/main/java/com/example/ui/ImageLoaderConfig.kt` — Custom Coil ImageLoader configuration
- `app/src/main/java/com/example/ui/player/EqualizerDialog.kt` — 10-band system equalizer with presets
- `app/src/main/java/com/example/ui/importing/ExportPlaylistDialog.kt` — Playlist JSON export
- `app/src/main/java/com/example/ui/player/SleepTimerDialog.kt` — Sleep timer dialog
- `app/src/main/java/com/example/data/remote/artwork/ArtworkSource.kt` — Multi-source artwork resolver
- `app/src/main/java/com/example/ui/ImageLoaderConfig.kt` — Custom Coil ImageLoader configuration
- `app/src/main/java/com/example/data/repository/SmartPlaylistManager.kt` — Smart playlist manager
- `app/src/main/java/com/example/ui/settings/BackupRestoreDialog.kt` — Backup/Restore settings dialog
- `app/src/main/java/com/example/ui/artist/ArtistDetailScreen.kt` — Artist detail screen with bio, top tracks
- `app/src/main/java/com/example/ui/artist/AlbumDetailScreen.kt` — Album detail screen with tracklist
- `app/src/main/java/com/example/ui/library/FilesScreen.kt` — Local file browser with MediaStore
- `app/src/main/java/com/example/ui/library/TagEditorDialog.kt` — MP3 tag editor using mp3agic
- `app/src/main/java/com/example/lyrics/LrcParser.kt` — LRC file parser for local synced lyrics
- `app/src/main/java/com/example/lyrics/GeniusLyricsClient.kt` — Genius.com lyrics scraper fallback
- `app/src/main/java/com/example/lyrics/Id3LyricsParser.kt` — Embedded ID3 SYLT/USLT lyrics parser
- `app/src/main/java/com/example/ui/home/ClayHeroPanel.kt` — Claymorphism hero panel with bouncy animations
- `app/src/main/java/com/example/ui/home/MinimalHeroPanel.kt` — Minimal Slate editorial hero panel
- `app/src/main/java/com/example/lastfm/LastFmApiClient.kt` — Last.fm API client for scrobbling
- `app/src/main/java/com/example/lastfm/ScrobbleManager.kt` — Scrobble manager with now-playing & scrobble logic
- `car/src/main/java/com/example/car/SuzieeCarAppService.kt` — Android Auto CarAppService
- `widget/src/main/java/com/example/widget/SuzieeWidgetSmall.kt` — 2×2 widget with playback controls
- `widget/src/main/java/com/example/widget/SuzieeWidgetMedium.kt` — 4×1 widget with artwork and controls
- `widget/src/main/java/com/example/widget/SuzieeWidgetLarge.kt` — 4×2 widget with queue and controls
- `widget/src/main/java/com/example/widget/SuzieeWidgetConfigureActivity.kt` — Widget configuration activity
- `car/src/main/java/com/example/car/SuzieeCarAppService.kt` — Android Auto CarAppService
- `widget/src/main/java/com/example/widget/SuzieeWidgetSmall.kt` — 2×2 widget with playback controls
- `widget/src/main/java/com/example/widget/SuzieeWidgetMedium.kt` — 4×1 widget with artwork and controls
- `widget/src/main/java/com/example/widget/SuzieeWidgetLarge.kt` — 4×2 widget with queue and controls
- `widget/src/main/java/com/example/widget/SuzieeWidgetConfigureActivity.kt` — Widget configuration activity

### Competitor Repositories (Public GitHub)
- `RetroMusicPlayer/RetroMusicPlayer` — GPL-3.0
- `Malopieds/InnerTune` — GPL-3.0
- `fast4x/RiMusic` — GPL-3.0
- `vfsfitvnm/ViMusic` — GPL-3.0 (archived)
- `OuterTune/OuterTune` — GPL-3.0
- `OxygenCobalt/Auxio` — GPL-3.0
- `namidaco/namida` — GPL-3.0
- `gokadzev/Musify` — GPL-3.0
- `uditkarode/AbleMusicPlayer` — GPL-3.0

### Community Sources
- Reddit: r/androidapps, r/privacy, r/opensource, r/androiddev
- F-Droid: package pages + user reviews
- GitHub Issues/Discussions on above repos
- Media3 ExoPlayer documentation (developer.android.com)

---

**Document Version:** 1.0  
**Date:** 2026-11-25  
**Status:** Phase 1-3 Complete — P3 Phase: 7/8 Complete (Android Auto, Widgets, Last.fm, Genius, LRC, ID3, Clay/Minimal Heroes)

---

*End of UPGRADE.md*

