package com.example.data.remote.innertube

import java.net.URI

/**
 * Pure rules for the native YouTube player client.
 *
 * Kept free of Android, OkHttp and JSON objects so the parts that decide *what* is played
 * can be unit-tested on the JVM:
 *
 *  * which strings are real video ids,
 *  * where the visitor data / InnerTube key live in the watch page,
 *  * which `adaptiveFormats` entry is a playable audio stream,
 *  * which URLs may be handed to ExoPlayer.
 */
/**
 * Public so the quality tier can cross the resolver boundary: `OnlineStreamResolver` and
 * `YouTubeAudioResolver` accept it as a parameter and `DownloadManager` reads it out of
 * preferences, and an `internal` object would not be visible to those public signatures.
 */
object YouTubeAudioPolicy {

    /** YouTube video ids are exactly 11 URL-safe characters. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val VISITOR_DATA = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"")
    private val API_KEY = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]{10,})\"")

    private const val GOOGLEVIDEO_HOST = "googlevideo.com"

    /** Session values scraped from YouTube's own page. */
    data class Session(val visitorData: String, val apiKey: String)

    /** One `adaptiveFormats` entry, before the playability rules are applied. */
    data class RawAudioFormat(
        val itag: Int,
        val mimeType: String?,
        val url: String?,
        val bitrate: Int
    )

    /** A validated, directly playable audio stream. */
    data class AudioFormat(val url: String, val mimeType: String, val itag: Int)

    fun isVideoId(videoId: String): Boolean = VIDEO_ID.matches(videoId)

    /**
     * Extracts the visitor data and API key from a YouTube page.
     *
     * Both values are part of YouTube's own bootstrap config and are required by the
     * `youtubei/v1/player` request; without the visitor data the API answers
     * `LOGIN_REQUIRED` for the anonymous player clients.
     */
    fun parseSession(html: String?): Session? {
        if (html.isNullOrBlank()) return null
        val visitor = VISITOR_DATA.find(html)?.groupValues?.getOrNull(1) ?: return null
        val key = API_KEY.find(html)?.groupValues?.getOrNull(1) ?: return null
        return Session(visitor, key)
    }

    /**
     * User-selectable download/stream quality tier (P2-10).
     *
     * The itag lists are preferences, not requirements: when the requested itag is absent
     * the policy falls back to the best remaining audio entry, so a video that only offers
     * AAC still plays.
     */
    enum class AudioQuality(val preferredItags: List<Int>) {
        HIGH(listOf(251, 140)),
        MEDIUM(listOf(140, 251)),
        LOW(listOf(250, 139, 251, 140))
    }

    fun qualityFromPreference(value: String?): AudioQuality = when (value?.lowercase()) {
        "low", "data_saver" -> AudioQuality.LOW
        "medium", "standard" -> AudioQuality.MEDIUM
        else -> AudioQuality.HIGH
    }

    /**
     * Picks the best audio-only format for [quality].
     *
     * Preference: the tier's itag list **in priority order** (default: Opus 251 160 kbps →
     * AAC 140 128 kbps) → the highest-bitrate remaining audio entry. The tier is walked in
     * order rather than scanning `formats` for a match, so a caller listing AAC before Opus
     * still gets the Opus stream. Formats without a direct `url` are skipped: YouTube's web
     * clients now report SABR-only entries (`serverAbrStreamingUrl`) that ExoPlayer cannot
     * play, which is exactly why the old WebView capture stopped working.
     */
    fun pickAudioFormat(
        formats: List<RawAudioFormat>,
        quality: AudioQuality = AudioQuality.HIGH
    ): AudioFormat? {
        val playable = formats.mapNotNull { format ->
            val url = format.url?.trim().orEmpty()
            if (!isPlayableMediaUrl(url)) return@mapNotNull null
            val mime = normalizeMime(format.mimeType) ?: mimeForItag(format.itag) ?: return@mapNotNull null
            format to mime
        }
        if (playable.isEmpty()) return null

        // Walk the tier in priority order so an API listing 140 before 251 still yields 251.
        val preferred = quality.preferredItags.firstNotNullOfOrNull { itag ->
            playable.firstOrNull { (format, _) -> format.itag == itag }
        }
        val (chosen, mime) = preferred ?: playable.maxByOrNull { it.first.bitrate } ?: return null

        return AudioFormat(chosen.url!!.trim(), mime, chosen.itag)
    }

    /** Only HTTPS URLs on `googlevideo.com` (or a subdomain) are playable media. */
    fun isPlayableMediaUrl(url: String?): Boolean {
        val cleaned = url?.trim().orEmpty()
        if (cleaned.isEmpty()) return false
        val parsed = runCatching { URI(cleaned) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase() ?: return false
        return host == GOOGLEVIDEO_HOST || host.endsWith(".$GOOGLEVIDEO_HOST")
    }

    /** `audio/webm; codecs="opus"` → `audio/webm`; non-audio mime types are rejected. */
    fun normalizeMime(mimeType: String?): String? {
        val base = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            base.startsWith("audio/webm") -> "audio/webm"
            base.startsWith("audio/mp4") -> "audio/mp4"
            base.startsWith("audio/mpeg") -> "audio/mpeg"
            base.startsWith("audio/ogg") -> "audio/ogg"
            base == "audio/opus" -> "audio/ogg"
            else -> null
        }
    }

    private fun mimeForItag(itag: Int): String? = when (itag) {
        139, 140 -> "audio/mp4"
        249, 250, 251 -> "audio/webm"
        else -> null
    }
}
