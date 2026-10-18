<p align="center">
  <img src="hero.png" alt="Suziee — in-app home screen hero artwork" width="420" />
</p>

<h1 align="center">Suziee — Premium Anime Music Player</h1>

<p align="center">
  <a href="https://github.com/heyitssuzain/Suziee/releases/latest"><img alt="Download latest APK" src="https://img.shields.io/badge/Download-Suziee.apk-00E5CC?style=for-the-badge&logo=android&logoColor=white" /></a>
  <a href="https://github.com/heyitssuzain/Suziee/releases/latest"><img alt="Version" src="https://img.shields.io/badge/version-1.2-blueviolet?style=for-the-badge" /></a>
</p>

Suziee is a native Android music player crafted with **Kotlin** and **Jetpack Compose**, featuring an anime-inspired cybernetic visual identity, atmospheric motion graphics, real online music discovery, background playback with **Media3 ExoPlayer**, offline music downloads, persistent **Room** database storage, synchronized **LRCLIB** lyrics, and on-device voice commands.

## 📥 Download & Install

Grab the latest APK straight from GitHub Releases — no login, no Play Store:

**https://github.com/heyitssuzain/Suziee/releases/latest/download/Suziee.apk**

1. Open the downloaded `Suziee.apk` on your Android phone (Android 8+).
2. If Android asks, allow *Install unknown apps* for your browser or file manager.
3. Install. Updating from an earlier release? Just install on top — **no uninstall needed** and your downloads, playlists and favorites are preserved.

> The release APK is signed with a private release key. Every future update uses the same key, so all users update in place forever.

## ✨ Key Features

- **Online Music Discovery** — real search querying YouTube Music InnerTube endpoints with suggestions and metadata.
- **Block-Resistant Streaming** — dual-source architecture: a headless WebView sniffer (primary) with an automatic SoundCloud fallback and retry/backoff, so songs start fast and keep playing.
- **Notification & Lock Screen Controls** — title, artist and artwork with working Play/Pause, Next and Previous via Media3 `MediaSessionService`.
- **Offline Downloads** — save permitted streams (progressive + HLS stitch) into app-private storage with live progress, percentage and retry-on-failure; fully playable offline.
- **Trending For Everyone** — anime trending sections **plus** mainstream sections for general listeners (Bollywood, Hindi, English and international hits).
- **Synced & Plain Lyrics** — LRCLIB integration with synchronized timestamped lyrics and active line highlighting.
- **Voice Commands** — on-device speech recognition: *"Play Believer"*, *"Next"*, *"Pause"*, *"Resume"*, *"Open lyrics"*, *"Play my downloads"*.
- **Playlists & Library** — Room-backed persistent storage for custom playlists, favorites and deduplicated playback history.
- **Anime Aesthetic** — original character artwork, glowing neon cyan/violet palette, reactive equalizer canvas visualizer and a 5-stage opening experience.

## 🆕 What's New in v1.2

- Mainstream trending rails on Home: **Bollywood & Hindi Hits** and **Global Top Hits** (anime rails unchanged).
- Live download feedback right where you tap: progress ring + percentage on the track row and in the player.
- Faster song starts, automatic failover when a stream dies mid-playback, and long downloads with retry + dismiss/retry states.
- Media notification and lock-screen playback controls.

## 🏗 Architecture

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose + Material 3 |
| Language | Kotlin 2.x |
| Audio | AndroidX Media3 (ExoPlayer + MediaSession) |
| Database | AndroidX Room (Tracks, Playlists, Favorites, History, Downloads) |
| Settings | AndroidX DataStore (Preferences) |
| Images | Coil Compose |
| Networking | OkHttp |

## 🔨 Build

The release APK is built **entirely on GitHub Actions** — every push to `main` runs the [`android-apk.yml`](.github/workflows/android-apk.yml) workflow, which compiles, lints, signs with the keystore from repository secrets, and publishes `Suziee.apk` to GitHub Releases.

To compile a local debug build:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## 📚 Documentation

- [`PRD.md`](PRD.md) — product requirements
- [`TRD.md`](TRD.md) — technical design
- [`docs/BUILD.md`](docs/BUILD.md) — build details
- [`docs/TESTING.md`](docs/TESTING.md) — testing
- [`NOTICE.md`](NOTICE.md) and [`docs/THIRD_PARTY_LICENSES.md`](docs/THIRD_PARTY_LICENSES.md) — notices and third-party licenses

## 🔒 Privacy-First

No signup, no mandatory accounts, no ads, no tracking.
