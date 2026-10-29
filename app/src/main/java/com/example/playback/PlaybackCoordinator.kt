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
 * The actual source lives in [OnlineStreamResolver] (the native YouTube player-API
 * client). This class owns the decisions that must survive between resolutions:
 *
 *  * the **identity of the winning source** ([currentSource]), used to decide whether a
 *    playback error is worth a second attempt,
 *  * the **one-shot failover guard** ([claimFailover]), so a track whose stream dies
 *    mid-playback is re-resolved exactly once instead of looping.
 *
 * It never touches ExoPlayer, the queue, or the UI.
 */
internal class PlaybackCoordinator(
    private val resolver: OnlineStreamResolver
) {
    /** Where the currently loaded stream came from (drives the error failover). */
    var currentSource: StreamSource = StreamSource.NONE
        private set

    /** Counts automatic re-resolves after mid-playback errors (bounded by policy). */
    private var failoverAttempts = 0

    /** Resets every per-track guard. Called when a new track (or a manual retry) starts. */
    fun resetForNewTrack() {
        currentSource = StreamSource.NONE
        failoverAttempts = 0
    }

    /** The stream came from an already-downloaded local file; no remote resolution happened. */
    fun noteLocalSource() {
        currentSource = StreamSource.LOCAL
    }

    /**
     * Claims one "this remote stream died, re-resolve it" attempt.
     * Returns false once [PlaybackFailurePolicy.MAX_AUTOMATIC_RECOVERIES] is exhausted for
     * this track, or immediately while already recovering (checked by the caller).
     */
    fun claimFailover(): Boolean {
        if (!PlaybackFailurePolicy.canRecover(failoverAttempts)) return false
        failoverAttempts++
        return true
    }

    /**
     * Resolves [track] through the online provider.
     *
     * @return the stream, or null when YouTube could not provide it. The caller shows an
     *   honest error in that case; no substitute recording is played.
     */
    suspend fun resolve(track: Track): StreamInfo? {
        val resolved: ResolvedStream? = try {
            resolver.resolve(track.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Resolution failed for ${track.id}: ${e.message}")
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
