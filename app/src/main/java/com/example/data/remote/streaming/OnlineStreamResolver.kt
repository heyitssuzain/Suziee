package com.example.data.remote.streaming

import android.content.Context
import com.example.data.remote.innertube.YouTubeAudioResolver
import com.example.domain.model.StreamInfo

/** Which source produced the stream that is currently loaded. */
enum class StreamSource {
    NONE,

    /** Resolved by the native YouTube player-API client. */
    INNERTUBE,

    /** An already-downloaded local file; no network resolution happened. */
    LOCAL
}

/** A stream plus the source that produced it. */
data class ResolvedStream(val info: StreamInfo, val source: StreamSource)

/**
 * The single place that turns a YouTube video id into playable audio.
 *
 * There is exactly one online provider: [YouTubeAudioResolver], which talks to YouTube's
 * player API directly. There is no yt-dlp/Python and no WebView on this path. The old
 * WebView capture cannot work any more because YouTube serves SABR-only streaming data to
 * its web clients (no direct URLs), which ExoPlayer cannot play.
 *
 * Resolution is cheap after startup: the visitor session is warmed in
 * `SuzieeApplication`, so a song is normally one player-API POST (~1 s) plus ExoPlayer's
 * first buffer.
 */
class OnlineStreamResolver(context: Context) {

    private val api = YouTubeAudioResolver(context)

    /** Fetches the visitor session ahead of the first play. */
    fun warmUp() = api.warmUp()

    /**
     * Resolves [videoId] to a playable stream, or null when YouTube refuses.
     *
     * The requested id is the only id sent to YouTube; no substitute recording is ever
     * searched for or played.
     */
    suspend fun resolve(videoId: String): ResolvedStream? {
        val info = api.resolve(videoId) ?: return null
        return ResolvedStream(info, StreamSource.INNERTUBE)
    }

    /** Last resolver failure, for diagnostics. */
    fun lastFailure(): String? = api.lastFailure
}
