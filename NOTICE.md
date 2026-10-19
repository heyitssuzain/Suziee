# Notices & Third-Party Attributions

Suziee uses open-source libraries and public API specifications:
- **AndroidX Media3**: Apache-2.0
- **Jetpack Compose & Material 3**: Apache-2.0
- **AndroidX Room & DataStore**: Apache-2.0
- **Coil**: Apache-2.0
- **OkHttp**: Apache-2.0
- **LRCLIB**: Public open lyrics service (lrclib.net)
- **InnerTube API**: Architectural client integration for YouTube Music

## GPL-3.0 component

- **youtubedl-android** (`io.github.junkfood02.youtubedl-android:library:0.18.1`)
  - License: **GNU General Public License v3.0** (GPL-3.0)
  - Source: https://github.com/yausername/youtubedl-android
  - Bundled in the APK: yes (the Kotlin library, a Python runtime and the yt-dlp executable).
  - License text: https://www.gnu.org/licenses/gpl-3.0.txt

Because this GPL-3.0 library is linked into the app, **APK builds that include it are
distributed as a GPL-3.0 combined work** (the original Suziee source remains available
under the MIT license in `LICENSE`). If a build must stay MIT-only, remove the
`youtubedl-library` dependency and the yt-dlp source (`data/remote/ytdlp/`); the
headless-WebView engine continues to work on its own.
