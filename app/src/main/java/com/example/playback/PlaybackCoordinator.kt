package com.example.playback

import android.util.Log
import com.example.data.remote.streaming.OnlineStreamResolver
import com.example.data.remote.streaming.ResolvedStream
import com.example.data.remote.streaming.StreamSource
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException

/**
 * Turns "the user asked for this track" into "a stream is loaded, and here is where it
 * came from".
 *
 * The actual source ladder lives in [OnlineStreamResolver] (yt-dlp first, headless
 * WebView fallback). This class owns the decisions that must survive between resolutions:
 *
 *  * the **identity of the winning source** ([currentSource]), used to decide whether a
 *    playback error is worth a second attempt,
 *  * the **one-shot failover guard** ([claimFailover]), so a track whose stream dies
 *    mid-playback is re-resolved exactly once instead of looping,
 *  * the **partial-resolve hedge**: on a normal play yt-dlp gets
 *    [OnlineStreamResolver.HEDGE_DELAY_MS] before the WebView fallback starts, while a
 *    failover races both engines immediately.
 *
 * It never touches ExoPlayer, the queue, or the UI.
 */
internal class PlaybackCoordinator(
    private val resolver: OnlineStreamResolver
) {
    /** Where the currently loaded stream came from (drives the error failover). */
    var currentSource: StreamSource = StreamSource.NONE
        private set

    /** Guards the one automatic re-resolve after a mid-playback error. */
    private var failoverUsed = false

    /** Resets every per-track guard. Called when a new track (or a manual retry) starts. */
    fun resetForNewTrack() {
        currentSource = StreamSource.NONE
        failoverUsed = false
    }

    /** The stream came from an already-downloaded local file; no remote resolution happened. */
    fun noteLocalSource() {
        currentSource = StreamSource.LOCAL
    }

    /**
     * Claims the single "this remote stream died, re-resolve it once" attempt.
     * Returns false once it has already been used for this track.
     */
    fun claimFailover(): Boolean {
        if (failoverUsed) return false
        failoverUsed = true
        return true
    }

    /**
     * Resolves [track] through the online ladder.
     *
     * @param usePrimary true for a new song (yt-dlp gets the hedge delay first); false
     *   for a failover, where both engines start at once because the previous stream is
     *   already dead.
     * @return the stream, or null when neither engine could produce one. The caller shows
     *   an honest error in that case.
     */
    suspend fun resolve(track: Track, usePrimary: Boolean): StreamInfo? {
        val resolved: ResolvedStream? = try {
            resolver.resolve(
                videoId = track.id,
                hedgeDelayMs = if (usePrimary) OnlineStreamResolver.HEDGE_DELAY_MS else 0L
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Resolution ladder failed for ${track.id}: ${e.message}")
            null
        }

        currentSource = resolved?.source ?: StreamSource.NONE
        if (resolved != null) {
            Log.i(TAG, "Resolved '${track.title}' via ${resolved.source}")
        } else {
            Log.w(TAG, "No stream for '${track.title}' (${track.id})")
        }
        return resolved?.info
    }

    companion object {
        private const val TAG = "PlaybackCoordinator"
    }
}
