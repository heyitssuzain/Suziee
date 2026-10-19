package com.example.domain.model

import java.util.Locale

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long = 0L,
    val artworkUrl: String = "",
    val streamUrl: String? = null,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val lyricsPlain: String? = null,
    val lyricsSynced: String? = null,
    val isFavorite: Boolean = false
) {
    val formattedDuration: String
        get() {
            if (durationSeconds <= 0L) return "--:--"
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
}

/**
 * A resolved, directly playable audio stream.
 *
 * @param url The direct media URL (googlevideo / local file).
 * @param mimeType Optional container mime type (e.g. "audio/webm", "audio/mp4", or
 * "application/x-mpegURL" for an HLS playlist), used to help the player parse
 * extension-less stream URLs reliably.
 * @param headers Per-stream HTTP headers that the CDN requires (YouTube needs a
 * Referer + the browser session Cookie or the User-Agent yt-dlp used). Merged on top
 * of the player's default media headers.
 */
data class StreamInfo(
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap()
)

data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverArtworkUrl: String = "",
    val trackCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class LyricsLine(
    val timeMs: Long,
    val text: String
)
