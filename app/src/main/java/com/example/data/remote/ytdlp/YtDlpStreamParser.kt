package com.example.data.remote.ytdlp

import com.example.domain.model.StreamInfo
import java.net.URI

/**
 * Pure translation layer between yt-dlp and ExoPlayer.
 *
 * Extracted from [SuzieeAudioExtractor] so the exact rules that decide "is this a
 * playable stream for the video we asked for" are testable on the JVM, without
 * Android, Python or a network.
 */
internal object YtDlpStreamParser {

    /** YouTube video ids are exactly 11 URL-safe characters. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Headers ExoPlayer must own; passing them through breaks HTTP framing. */
    private val BLOCKED_HEADERS = setOf(
        "accept-encoding",
        "connection",
        "content-length",
        "host",
        "range",
        "transfer-encoding"
    )

    fun isVideoId(videoId: String): Boolean = VIDEO_ID.matches(videoId)

    /**
     * Builds the watch URL yt-dlp is asked to resolve. The id is validated first, so a
     * title or a manual paste can never smuggle extra yt-dlp arguments or a different URL.
     */
    fun watchUrl(videoId: String): String {
        require(isVideoId(videoId)) { "Not a YouTube video id: $videoId" }
        return "https://www.youtube.com/watch?v=$videoId"
    }

    /**
     * Picks the playable URL out of yt-dlp's stdout.
     *
     * `--get-url` normally prints exactly one line, but yt-dlp is allowed to print a
     * notice or a blank line first (for example when a client logs a deprecation note to
     * stdout). Taking the first non-empty line - the old behaviour - turned such a note
     * into "no stream" and made playback fail even though a perfectly good URL was on the
     * next line. Scanning every line for the one URL that passes [isPlayableMediaUrl] is
     * strict about *what* is accepted while being tolerant about *where* it appears.
     */
    fun pickStreamUrl(out: String?): String? {
        if (out.isNullOrBlank()) return null
        return out.lineSequence()
            .map { it.trim() }
            .firstOrNull { isPlayableMediaUrl(it) }
    }

    /**
     * Converts the direct URL reported by yt-dlp into a [StreamInfo], or null when it is
     * not something we are willing to hand to ExoPlayer.
     *
     * Only HTTPS URLs on `googlevideo.com` are accepted. That rejects a stray line from
     * yt-dlp's output (a warning, a redirect to a consent page, a local path) instead of
     * sending the player somewhere unexpected. The video id itself was already fixed when
     * the request was built, so this URL belongs to the requested recording.
     */
    fun toStreamInfo(
        url: String?,
        formatId: String?,
        extension: String?,
        headers: Map<String, String>?
    ): StreamInfo? {
        val cleaned = url?.trim().orEmpty()
        if (!isPlayableMediaUrl(cleaned)) return null

        val isHls = cleaned.contains("hls_playlist", ignoreCase = true) ||
            cleaned.contains(".m3u8", ignoreCase = true)

        return StreamInfo(
            url = cleaned,
            mimeType = if (isHls) {
                HLS_MIME
            } else {
                mimeFor(formatId, extension) ?: mimeFromUrl(cleaned)
            },
            headers = sanitizeHeaders(headers)
        )
    }

    fun isPlayableMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase() ?: return false
        return host == GOOGLEVIDEO_HOST || host.endsWith(".$GOOGLEVIDEO_HOST")
    }

    fun mimeFor(formatId: String?, extension: String?): String? = when (formatId?.trim()) {
        "139", "140" -> AUDIO_MP4
        "249", "250", "251" -> AUDIO_WEBM
        else -> when (extension?.trim()?.lowercase()) {
            "webm" -> AUDIO_WEBM
            "m4a", "mp4" -> AUDIO_MP4
            "mp3" -> AUDIO_MPEG
            "ogg", "opus" -> AUDIO_OGG
            else -> null
        }
    }

    /**
     * Passes through the per-stream headers yt-dlp captured (User-Agent / Referer /
     * Accept-Language) while dropping hop-by-hop headers ExoPlayer sets itself.
     */
    fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        return headers.entries.asSequence()
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .filter { it.key.lowercase() !in BLOCKED_HEADERS }
            .take(MAX_HEADERS)
            .associate { it.key to it.value }
    }

    /**
     * `--get-url` only prints the URL, so the container is read from the googlevideo
     * query (`mime=audio%2Fwebm`, `itag=251`). Media3 can sniff the rest, but a hint on
     * an extension-less `videoplayback` URL is what keeps first-play robust.
     */
    private fun mimeFromUrl(url: String): String? {
        val lower = url.lowercase()
        return when {
            "mime=audio%2fwebm" in lower || "mime=audio/webm" in lower -> AUDIO_WEBM
            "mime=audio%2fmp4" in lower || "mime=audio/mp4" in lower -> AUDIO_MP4
            "mime=audio%2fmpeg" in lower || "mime=audio/mpeg" in lower -> AUDIO_MPEG
            "mime=audio%2fogg" in lower || "mime=audio/ogg" in lower -> AUDIO_OGG
            "itag=139" in lower || "itag=140" in lower -> AUDIO_MP4
            "itag=249" in lower || "itag=250" in lower || "itag=251" in lower -> AUDIO_WEBM
            else -> null
        }
    }

    private const val GOOGLEVIDEO_HOST = "googlevideo.com"
    private const val MAX_HEADERS = 12

    private const val AUDIO_WEBM = "audio/webm"
    private const val AUDIO_MP4 = "audio/mp4"
    private const val AUDIO_MPEG = "audio/mpeg"
    private const val AUDIO_OGG = "audio/ogg"

    /** Must match Media3's MimeTypes.APPLICATION_M3U8 exactly (case-sensitive compare). */
    private const val HLS_MIME = "application/x-mpegURL"
}
