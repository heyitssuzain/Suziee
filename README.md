# Suziee — Premium Anime Music Player

Suziee is a native Android music player crafted with Kotlin and Jetpack Compose, featuring an anime-inspired cybernetic visual identity, atmospheric motion graphics, real online music discovery via InnerTube, background playback with Media3 ExoPlayer, offline music downloads, persistent Room database storage, synchronized LRCLIB lyrics, and on-device voice commands.

## Key Features

- **Online Music Discovery**: Real search querying YouTube Music InnerTube endpoints with suggestions and metadata.
- **Audio Playback Engine**: AndroidX Media3 ExoPlayer with lockscreen controls, notification controls, and background `MediaSessionService`.
- **Synced & Plain Lyrics**: LRCLIB integration with synchronized timestamped lyrics and active line highlighting.
- **Voice Commands**: On-device speech recognition for commands like "Play Believer", "Next", "Pause", "Resume", "Open lyrics", and "Play my downloads".
- **Offline Downloads**: High-speed downloading of permitted streams into app-private storage, fully playable offline without internet.
- **Anime Aesthetic**: Original character artwork, glowing neon cyan/violet color palette, custom reactive equalizer canvas visualizer, and 5-stage opening experience.
- **Playlists & Library**: Room-backed persistent storage for custom playlists, favorites, and deduplicated playback history.
- **Privacy-First**: No signup, no mandatory accounts, no ads, no tracking.

## Architecture

- **UI Framework**: Jetpack Compose + Material 3
- **Language**: Kotlin 2.x
- **Audio Engine**: AndroidX Media3 (ExoPlayer + MediaSession)
- **Local Database**: AndroidX Room (Tracks, Playlists, Favorites, History, Downloads)
- **Settings Store**: AndroidX DataStore (Preferences)
- **Image Loading**: Coil Compose
- **Networking**: OkHttp

## Build and Run

To compile and assemble the debug APK:

```bash
gradle assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
