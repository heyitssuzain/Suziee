package com.example.data.remote.soundcloud

import kotlin.math.abs

/**
 * Pure (JVM-testable) matching helpers for the SoundCloud fallback.
 *
 * ## Why this exists
 * SoundCloud is a *substitute* source: the audio it returns is a different upload than
 * the official recording the user asked for. The old heuristic only *penalised*
 * alternate versions (remix / slowed / cover ...) and accepted whatever scored highest,
 * so an official-song request could silently play a fan-made recording. Three things are
 * fixed here:
 *
 *  1. Alternate-version markers are detected on the **raw** title. The old check ran on
 *     the normalised title, which strips parenthesised text - exactly where uploads put
 *     "(Slowed + Reverb)". Those uploads were therefore never penalised at all.
 *  2. [isStrictVerifiedMatch] provides a real verification gate: title, artist identity
 *     and duration must all corroborate, otherwise the caller must show an honest error
 *     instead of playing a different recording.
 *  3. Marker detection and title comparison are word-boundary aware and Unicode aware,
 *     so "De**live**r" is not treated as a live version, and Japanese/CJK titles are
 *     actually comparable (the old normaliser deleted every non-ASCII character, which
 *     made every CJK match score 0.0).
 *
 * Nothing here filters results in non-strict (download / non-YouTube) flows.
 */
internal object SoundCloudMatch {

    /** Uploads that are a *different recording* of the same song. */
    val ALTERNATE_VERSION_MARKERS =
        listOf(
            "slowed", "reverb", "remix", "sped up", "nightcore", "8d", "16d",
            "bass boosted", "mashup", "bootleg", "cover", "karaoke", "instrumental",
            "tribute", "live", "acoustic", "reaction"
        )

    /** Word-boundary matcher so "deliver"/"discover" are not read as live/cover. */
    private val MARKER_REGEX = Regex(
        "\\b(" + ALTERNATE_VERSION_MARKERS.joinToString("|") { Regex.escape(it) } + ")\\b"
    )

    /** Artist values the search backend emits when it could not parse a real artist. */
    private val PLACEHOLDER_ARTISTS =
        listOf("unknown artist", "youtube music", "various artists", "topic", "unknown")

    /** A title-only match must be near-identical when there is no artist to corroborate. */
    private const val STRICT_MIN_TITLE_SIMILARITY = 0.62
    private const val STRICT_MIN_TITLE_SIMILARITY_WITHOUT_ARTIST = 0.85
    private const val STRICT_MIN_ARTIST_SIMILARITY = 0.5
    private const val STRICT_MIN_MATCH_SCORE = 0.65
    private const val STRICT_MIN_DURATION_TOLERANCE_MS = 10_000L
    private const val STRICT_DURATION_TOLERANCE_RATIO = 0.06

    /** True when [text] advertises a different recording of the same song. */
    fun hasAlternateVersionMarker(text: String): Boolean {
        if (text.isBlank()) return false
        return MARKER_REGEX.containsMatchIn(text.lowercase())
    }

    /** Word-boundary check for a single marker (used for the weighted score penalties). */
    fun containsMarker(text: String, marker: String): Boolean {
        if (text.isBlank() || marker.isBlank()) return false
        return Regex("\\b" + Regex.escape(marker.lowercase()) + "\\b")
            .containsMatchIn(text.lowercase())
    }

    /** True when [artist] carries no usable identity for verification. */
    fun isPlaceholderArtist(artist: String): Boolean {
        val normalised = normalize(artist)
        return normalised.isBlank() || PLACEHOLDER_ARTISTS.any { it == normalised }
    }

    /**
     * The strict gate used when SoundCloud is standing in for a requested official
     * recording. Returns true only when the candidate is demonstrably the same song by
     * the same artist with a matching length.
     */
    fun isStrictVerifiedMatch(
        wantedTitle: String,
        wantedArtist: String,
        wantedDurationMs: Long,
        candidateTitle: String,
        candidateUploader: String,
        candidateDurationMs: Long,
        overallScore: Double
    ): Boolean {
        val requestedAlternate =
            hasAlternateVersionMarker(wantedTitle) || hasAlternateVersionMarker(wantedArtist)

        // 1. A decorated upload may never stand in for a plain official request.
        if (!requestedAlternate) {
            if (hasAlternateVersionMarker(candidateTitle)) return false
            if (hasAlternateVersionMarker(candidateUploader)) return false
        }

        // 2. The title must clearly be the requested song.
        val artistUsable = !isPlaceholderArtist(wantedArtist)
        val titleThreshold =
            if (artistUsable) STRICT_MIN_TITLE_SIMILARITY
            else STRICT_MIN_TITLE_SIMILARITY_WITHOUT_ARTIST
        val titleSimilarity = similarity(normalize(wantedTitle), normalize(candidateTitle))
        if (titleSimilarity < titleThreshold) return false

        // 3. The artist must be corroborated - by the uploader name, or by appearing in
        //    the upload title (how official/label uploads are usually named).
        if (artistUsable) {
            val artistSimilarity = similarity(normalize(wantedArtist), normalize(candidateUploader))
            val artistTokens = normalize(wantedArtist).split(' ').filter { it.length > 2 }
            val artistInTitle =
                artistTokens.isNotEmpty() &&
                    artistTokens.any { normalize(candidateTitle).contains(it) }
            if (artistSimilarity < STRICT_MIN_ARTIST_SIMILARITY && !artistInTitle) return false
        }

        // 4. Length must line up closely (both sides known and in the same ballpark).
        if (wantedDurationMs > 0L && candidateDurationMs > 0L) {
            val tolerance = maxOf(
                STRICT_MIN_DURATION_TOLERANCE_MS,
                (wantedDurationMs * STRICT_DURATION_TOLERANCE_RATIO).toLong()
            )
            if (abs(candidateDurationMs - wantedDurationMs) > tolerance) return false
        }

        // 5. Overall confidence.
        return overallScore >= STRICT_MIN_MATCH_SCORE
    }

    /**
     * Lowercases and strips decorations like "(Official Video)", "[HD]" and punctuation.
     *
     * Keeps Unicode letters/digits (so Japanese, Korean, Cyrillic and accented titles stay
     * comparable) rather than deleting everything outside a-z0-9.
     */
    fun normalize(value: String): String {
        var text = value.lowercase()
        text = text.replace(Regex("\\([^)]*\\)"), " ")
        text = text.replace(Regex("\\[[^]]*]"), " ")
        text = text
            .replace("official video", " ")
            .replace("official audio", " ")
            .replace("lyric video", " ")
            .replace("lyrics", " ")
            .replace("audio", " ")
            .replace("topic", " ")
            .replace("remastered", " ")
            .replace("feat.", " ")
            .replace("ft.", " ")
        text = text.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Token-overlap (Jaccard) similarity with a containment bonus. 0.0 .. 1.0 */
    fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        // Single-character tokens are dropped for Latin text (they carry no signal) but
        // kept for non-ASCII scripts, where a single character can be a whole word.
        val tokensA = a.split(' ').filter { it.length > 1 || it.any { c -> c.code > 127 } }.toSet()
        val tokensB = b.split(' ').filter { it.length > 1 || it.any { c -> c.code > 127 } }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val common = tokensA.count { tokensB.contains(it) }.toDouble()
        val jaccard = common / (tokensA.size + tokensB.size - common).toDouble()
        return if (b.contains(a) || a.contains(b)) maxOf(jaccard, 0.8) else jaccard
    }
}
