package com.example.data.remote.extraction

/**
 * One **audio-only** entry of YouTube's `streamingData.adaptiveFormats`.
 *
 * The page's own player response is parsed inside the WebView's V8 engine (see
 * [SecureExtractionEngine]) and only the fields we need are handed to Kotlin as a
 * compact, line-delimited record - parsing the whole (multi-hundred-kilobyte) JSON in
 * Kotlin would waste both time and memory on the critical path.
 *
 * @param itag YouTube's numeric format id (251 = Opus 160 kbps WebM, 140 = AAC 128 kbps MP4).
 * @param mimeType Container only - any `; codecs="..."` suffix is stripped by [AudioFormatSelector.normalizeMimeType].
 * @param bitrate Advertised average bitrate in bits per second (0 when unknown).
 * @param contentLength Advertised size in bytes (0 when unknown).
 * @param url The raw `googlevideo` media URL.
 */
data class AudioFormatCandidate(
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long,
    val url: String
) {
    /** True when the format carries audio (and therefore is a candidate for playback). */
    val isAudioOnly: Boolean
        get() = mimeType.startsWith("audio/", ignoreCase = true)

    /** True when this entry can actually be played: audio-only and carrying a URL. */
    val isUsable: Boolean
        get() = isAudioOnly && url.isNotBlank()
}

/**
 * Format selection + URL normalisation for the headless extraction engine.
 *
 * Everything in here is deliberately **pure** (no Android, no WebView, no JSON library):
 * it is the part of the new extraction path that can be verified by CI unit tests, which
 * matters because the WebView half can only be exercised on a real device.
 */
object AudioFormatSelector {

    /** itag 251 - Opus 160 kbps in WebM: the best audio-only format YouTube offers. */
    const val ITAG_OPUS = 251

    /** itag 140 - AAC 128 kbps in MP4: universally supported, the safe second choice. */
    const val ITAG_AAC = 140

    private const val RECORD_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "|"
    private const val FIELD_COUNT = 5

    /** Field order used by the injected JavaScript hook. */
    private const val FIELD_ITAG = 0
    private const val FIELD_MIME = 1
    private const val FIELD_BITRATE = 2
    private const val FIELD_LENGTH = 3
    private const val FIELD_URL = 4

    /**
     * Parses the line-delimited payload the in-page V8 hook sends over the JavaScript
     * bridge: one `itag|mimeType|bitrate|contentLength|url` record per line.
     *
     * Malformed lines are skipped instead of failing the whole batch: a single odd format
     * must never cost us the stream.
     */
    fun parse(payload: String?): List<AudioFormatCandidate> {
        if (payload.isNullOrBlank()) return emptyList()
        val candidates = ArrayList<AudioFormatCandidate>()
        for (line in payload.split(RECORD_SEPARATOR)) {
            if (line.isBlank()) continue
            // `limit` keeps any '|' inside the URL part of the URL instead of splitting the record.
            val fields = line.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
            if (fields.size < FIELD_COUNT) continue

            val itag = fields[FIELD_ITAG].trim().toIntOrNull() ?: continue
            val mimeType = normalizeMimeType(fields[FIELD_MIME]) ?: continue
            val url = fields[FIELD_URL].trim()
            if (url.isBlank()) continue

            candidates.add(
                AudioFormatCandidate(
                    itag = itag,
                    mimeType = mimeType,
                    bitrate = fields[FIELD_BITRATE].trim().toIntOrNull() ?: 0,
                    contentLength = fields[FIELD_LENGTH].trim().toLongOrNull() ?: 0L,
                    url = url
                )
            )
        }
        return candidates
    }

    /**
     * Picks the format to play: itag 251 first, then itag 140, then any other audio-only
     * format, and within an itag rank the highest advertised bitrate. Returns null when no
     * usable audio-only candidate exists (e.g. only `signatureCipher` entries, which would
     * need YouTube's obfuscated deciphering JS and are therefore skipped by the hook).
     */
    fun pickBest(candidates: List<AudioFormatCandidate>): AudioFormatCandidate? =
        candidates.asSequence()
            .filter { it.isUsable }
            .sortedWith(compareBy({ rank(it.itag) }, { -it.bitrate }))
            .firstOrNull()

    private fun rank(itag: Int): Int = when (itag) {
        ITAG_OPUS -> 0
        ITAG_AAC -> 1
        else -> 2
    }

    /** `audio/webm; codecs="opus"` -> `audio/webm`. Returns null for unusable values. */
    fun normalizeMimeType(raw: String?): String? {
        val mime = raw?.substringBefore(';')?.trim().orEmpty()
        return mime.takeIf { it.isNotEmpty() && it.contains('/') }
    }

    /**
     * What ExoPlayer should be told about a sniffed CDN request, which has no file
     * extension - the `mime=audio%2Fmp4` query parameter is the only hint.
     */
    fun mimeTypeFromUrl(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("mime=audio%2fmp4") || lower.contains("mime=audio/mp4") -> "audio/mp4"
            lower.contains("mime=audio%2fwebm") || lower.contains("mime=audio/webm") -> "audio/webm"
            else -> null
        }
    }

    /**
     * Removes the byte-range/chunking query parameters the browser adds
     * (`range`, `rn`, `rbuf`, `sq`, `sabr`).
     *
     * The browser asks for the audio in chunks, but none of those parameters is covered by
     * the URL signature, so dropping them turns a chunk request back into a full-file
     * request. Every other parameter (notably the transformed `n` and the `sig`) is left
     * byte-for-byte untouched - a `Uri`-style rebuild would re-encode them and break the
     * signature.
     */
    fun stripChunkParameters(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url

        val base = url.substring(0, queryStart)
        val kept = url.substring(queryStart + 1)
            .split('&')
            .filter { parameter ->
                val key = parameter.substringBefore('=')
                key.lowercase() !in DROPPED_QUERY_PARAMETERS
            }

        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    /**
     * Chunk/range parameters the browser adds to media requests. None of them are covered
     * by the URL signature, so dropping them is safe.
     */
    private val DROPPED_QUERY_PARAMETERS = setOf("range", "rn", "rbuf", "sq", "sabr")
}
