package com.example.playback

import android.os.SystemClock
import android.util.Log
import com.example.data.remote.extraction.SecureExtractionEngine
import com.example.data.repository.MusicRepository
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/**
 * Which provider actually supplied the stream currently loaded into ExoPlayer.
 * Used to decide whether a playback failure is worth retrying against the fallback.
 */
internal enum class StreamSource {
    NONE,

    /** Captured by the pre-warmed headless extraction engine (primary). */
    YOUTUBE_EXTRACTOR,

    /** Resolved through the SoundCloud public API (fallback). */
    SOUNDCLOUD,

    /** A local, already-downloaded file. */
    LOCAL
}

/**
 * Bridges the extraction stream flow into a *play decision* - the clean-architecture
 * intermediary between "where does this song's audio come from" and the player.
 *
 * ## What it owns
 *  * the **resolution ladder**: the primary [SecureExtractionEngine] first, the hedged
 *    SoundCloud resolve in parallel once the extractor has been slow for
 *    [FALLBACK_HEDGE_DELAY_MS], and SoundCloud alone for a failover,
 *  * the **identity of the winning source** ([currentSource]), which decides whether a
 *    playback failure is worth escalating,
 *  * the **one-shot failover guards** ([claimSoundCloudFailover]/[claimTransientRetry]), so
 *    a failing track can never loop between sources,
 *  * the **substitution policy**: a YouTube catalogue request is resolved with SoundCloud
 *    in `strict` mode, which means a different recording (cover, slowed edit, same-title
 *    different song) is refused outright rather than played in place of the requested song.
 *
 * ## What it deliberately does not own
 * The player itself, the queue, repeat/shuffle, the UI state and the `MediaItem` handoff
 * stay in [MusicPlayerManager]. The coordinator never touches ExoPlayer, so it can be
 * reasoned about (and its outputs tested) without a running player.
 */
internal class PlaybackCoordinator(
    private val repository: MusicRepository,
    private val engine: SecureExtractionEngine
) {
    /** Where the currently loaded stream came from (drives the error failover). */
    var currentSource: StreamSource = StreamSource.NONE
        private set

    /** Guards the SoundCloud failover so we only escalate once per track. */
    private var soundCloudFailoverUsed = false

    /** Guards the "one automatic re-resolve after a mid-playback error" behaviour. */
    private var transientRetryUsed = false

    /** Resets every per-track guard. Called when a new track (or a manual retry) starts. */
    fun resetForNewTrack() {
        currentSource = StreamSource.NONE
        soundCloudFailoverUsed = false
        transientRetryUsed = false
    }

    /** The stream came from an already-downloaded local file; no remote resolution happened. */
    fun noteLocalSource() {
        currentSource = StreamSource.LOCAL
    }

    /** Claims the single "YouTube stream died, escalate to SoundCloud" escalation. */
    fun claimSoundCloudFailover(): Boolean {
        if (soundCloudFailoverUsed) return false
        soundCloudFailoverUsed = true
        return true
    }

    /** Claims the single "the SoundCloud stream dropped, re-resolve it once" retry. */
    fun claimTransientRetry(): Boolean {
        if (transientRetryUsed) return false
        transientRetryUsed = true
        return true
    }

    /** True when the track is a YouTube catalogue recording (a specific official request). */
    fun isOfficialRequest(trackId: String): Boolean = looksLikeYouTubeVideoId(trackId)

    /**
     * Resolves [track] to a playable stream, using the block-resistant dual-source
     * architecture:
     *
     *  1. **PRIMARY - secure extraction engine**: a pre-warmed, invisible Chromium session
     *     running YouTube's own player. It is the only thing that can produce a working
     *     (n-transformed) googlevideo URL, because YouTube's `n` challenge must be solved by
     *     their own JavaScript. The engine prefers the fully signed media request the page
     *     makes and falls back to the audio-only format from the page's player response.
     *  2. **FALLBACK - SoundCloud public API**: used automatically when the engine does not
     *     capture a stream inside [SecureExtractionEngine.SNIFF_TIMEOUT_MS] (8s). It is
     *     resolved in parallel (hedged) once the engine has been running for
     *     [FALLBACK_HEDGE_DELAY_MS], so an engine failure does not add a second wait.
     *
     * @param usePrimary false to skip straight to SoundCloud (used by the failover).
     * @return the resolved stream, or null when no acceptable source exists. The caller
     * shows an honest error in that case instead of playing a different recording.
     */
    suspend fun resolve(track: Track, usePrimary: Boolean): StreamInfo? {
        // A YouTube id means the user asked for a specific catalogue recording. If the
        // extractor cannot produce it, SoundCloud may only be used as an acceptable
        // substitute - never an unrelated/slowed/cover recording.
        val officialRequest = isOfficialRequest(track.id)

        if (usePrimary && officialRequest) {
            Log.i(TAG, "PRIMARY: extracting YouTube stream via secure extraction engine (${track.id})")
            val startedAt = SystemClock.elapsedRealtime()

            // Hedged request: the fallback resolve is started in the background while the
            // extractor is still working, but only after HEDGE_DELAY_MS - so a normal, fast
            // extraction (usually well under 2s) costs no extra network call at all, while a
            // slow/failing extractor no longer pays for the fallback *after* its 8s window.
            // Only resolution is parallel; playback stays strictly single-source.
            // Scoped to the current resolve job so selecting another track cancels it too.
            val hedgedFallback = CoroutineScope(currentCoroutineContext()).async {
                try {
                    delay(FALLBACK_HEDGE_DELAY_MS)
                    Log.i(
                        TAG,
                        "HEDGE: extractor still unresolved after ${FALLBACK_HEDGE_DELAY_MS}ms - " +
                            "resolving SoundCloud in parallel"
                    )
                    resolveSoundCloudWithRetry(track, strict = officialRequest)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let the hedge fail the resolve job: the extractor may still win.
                    Log.w(TAG, "Hedged fallback failed: ${e.message}")
                    null
                }
            }

            // CRITICAL for cross-device reliability: a WebView that is missing, disabled or
            // outdated (very common on a friend's phone) throws while being constructed. If
            // that exception escaped, the SoundCloud fallback below would never run and the
            // user would see "Could not play this track" even though a stream was available.
            val extracted = try {
                engine.resolve(track.id)
            } catch (e: CancellationException) {
                hedgedFallback.cancel()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Extractor unavailable on this device (${e.message}) - using fallback")
                null
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            if (extracted != null) {
                currentSource = StreamSource.YOUTUBE_EXTRACTOR
                // The primary won: drop the hedged fallback (it is usually still waiting on
                // its delay, so this normally also avoids the extra request entirely).
                hedgedFallback.cancel()
                Log.i(
                    TAG,
                    "PRIMARY OK: ${extracted.mimeType ?: "media"} stream in ${elapsed}ms"
                )
                return extracted
            }

            Log.w(
                TAG,
                "PRIMARY FAILED: extractor gave no stream after ${elapsed}ms " +
                    "(window ${SecureExtractionEngine.SNIFF_TIMEOUT_MS}ms) - using hedged " +
                    "SoundCloud fallback (strict=$officialRequest)"
            )

            val fallback = try {
                hedgedFallback.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Hedged fallback failed: ${e.message}")
                null
            }
            if (fallback != null) {
                currentSource = StreamSource.SOUNDCLOUD
                Log.i(
                    TAG,
                    "FALLBACK OK: SoundCloud stream - total "
                        + "${SystemClock.elapsedRealtime() - startedAt}ms after tap"
                )
            }
            return fallback
        }

        val fallback = resolveSoundCloudWithRetry(track, strict = officialRequest)
        if (fallback != null) currentSource = StreamSource.SOUNDCLOUD
        return fallback
    }

    /**
     * SoundCloud fallback with a bounded retry.
     *
     * Deliberately short: the previous revision retried three times with a linear backoff,
     * which added up to ~2.7s of pure waiting *after* the 8s extraction window had already
     * elapsed. Two attempts with a 400ms pause keeps one retry for a flaky connection
     * without turning a failure into a multi-second stall. It also does **not** touch
     * [currentSource] - the caller owns that, because during a hedged resolve this method
     * can complete while the extractor is still the source we are about to play.
     *
     * @param strict applies the substitution gate (see
     * [com.example.data.repository.MusicRepository.resolveStreamUrl]). When on and nothing
     * acceptable is found, it returns null on purpose: the caller shows an honest error
     * instead of playing a different recording.
     */
    private suspend fun resolveSoundCloudWithRetry(track: Track, strict: Boolean): StreamInfo? {
        var attempt = 0
        while (attempt < SOUNDCLOUD_ATTEMPTS) {
            attempt++
            val stream = try {
                repository.resolveStreamUrl(track, strict = strict).getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud resolve attempt $attempt failed: ${e.message}")
                null
            }

            if (stream != null && stream.url.isNotBlank()) {
                Log.i(TAG, "SoundCloud stream resolved (strict=$strict, attempt $attempt)")
                return stream
            }

            if (attempt < SOUNDCLOUD_ATTEMPTS) {
                delay(SOUNDCLOUD_RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "PlaybackCoordinator"

        /** How many times the cheap SoundCloud fallback is tried before giving up. */
        private const val SOUNDCLOUD_ATTEMPTS = 2

        /** Pause between SoundCloud attempts. Kept short - it is pure user-visible delay. */
        private const val SOUNDCLOUD_RETRY_BACKOFF_MS = 400L

        /**
         * How long the extractor gets before the fallback resolve is started in parallel.
         *
         * A normal extraction resolves well inside this, so the hedge usually never fires and
         * costs nothing at all. It only matters when the extractor is slow or failing, where
         * it removes the second full wait from the user's startup time.
         */
        private const val FALLBACK_HEDGE_DELAY_MS = 3_000L

        /** YouTube video ids are 11 URL-safe characters; anything else skips the extractor. */
        internal fun looksLikeYouTubeVideoId(id: String): Boolean =
            id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
