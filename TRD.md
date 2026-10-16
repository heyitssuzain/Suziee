# Suziee — Technical Requirements Document (TRD)

## 1. System Architecture
- **Language**: Kotlin 2.x / JVM
- **UI Toolkit**: Jetpack Compose + Material 3 Dynamic Dark Theming
- **Media Engine**: AndroidX Media3 ExoPlayer 1.5.1 + MediaSessionService
- **Persistence Layer**: Room 2.7.0 (with SQLite KSP compiler) + DataStore Preferences 1.1.7
- **Networking**: OkHttp 4.10.0
- **Asynchronous Flow**: Kotlin Coroutines & StateFlow

## 2. Audio Pipeline & Resilient Fallbacks
- Primary search and player extraction queries YouTube Music InnerTube endpoints.
- Fallback resolver interface connects distributed stream resolution instances with graceful error messaging.
- Offline playback routes directly to local file storage URI (`Uri.fromFile`) skipping remote calls entirely.

## 3. Background Services
- `MediaPlaybackService` declares `foregroundServiceType="mediaPlayback"` with MediaSession token.
