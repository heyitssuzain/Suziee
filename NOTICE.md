# Notices & Third-Party Attributions

Suziee uses open-source libraries and public API specifications:
- **AndroidX Media3**: Apache-2.0
- **Jetpack Compose & Material 3**: Apache-2.0
- **AndroidX Room & DataStore**: Apache-2.0
- **Coil**: Apache-2.0
- **OkHttp**: Apache-2.0
- **LRCLIB**: Public open lyrics service (lrclib.net)
- **InnerTube API**: Architectural client integration for YouTube Music

The playback provider talks to YouTube's player API directly. The app no longer bundles
yt-dlp, a Python runtime or a headless WebView, so there is no GPL-3.0 component in the
APK.
