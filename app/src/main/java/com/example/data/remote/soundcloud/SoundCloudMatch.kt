package com.example.data.remote.soundcloud

import kotlin.math.abs

/**
 * Pure (JVM-testable) matching helpers for the SoundCloud fallback.
 *
 * ## Design (rewritten after the "no verified source found" regression)
 *
 * The first version of this file required *positive proof* that a candidate was the same
 * recording (title + artist + duration + score floor, all of them). That rejected
 * legitimate songs whenever a needed field was missing - and missing fields are normal:
 * the plain-YouTube fallback reports `Unknown Artist`/`YouTube Music`, the duration can be
 * 0, the title can carry decorations - so solving those songs died with
 * "No verified matching source was found".
 *
 * The gate now works the other way round: **reject only on positive evidence that the
 * candidate is a different recording.** That keeps the guarantee that matters ("never
 * silently play an unrelated/slowed/cover version") without requiring metadata that is
 * often simply absent:
 *
 *  - decorated uploads (slowed / reverb / remix / cover / live ...) are rejected unless the
 *    user explicitly asked for an alternate version,
 *  - a clearly different title is rejected,
 *  - a clearly different length is rejected when both sides know their duration,
 *  - a *weak* title match additionally needs the artist to corroborate (uploader similarity
 *    or the artist name inside the upload title),
 *  - everything else - including candidates with missing artist/duration metadata that
 *    otherwise agree - is accepted.
 *
 * Marker detection runs on the raw text (decorations live in parentheses, which
 * `normalize()` strips) and is word-boundary + Unicode aware, so "Deliver"/"Discover" are
 * not read as live/cover and CJK titles stay comparable.
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

    /** Below this the candidate is a different song, not a different version of it. */
    private const val REJECT_TITLE_SIMILARITY = 0.45

    /** At or above this the title alone is trusted (artist corroboration not required). */
    private const val TRUSTED_TITLE_SIMILARITY = 0.75

    /** Duration tolerance when both sides know their duration. */
    private const val MIN_DURATION_TOLERANCE_MS = 15_000L
    private const val DURATION_TOLERANCE_RATIO = 0.10

    /** Uploader similarity that counts as "same artist". */
    private const val ARTIST_SIMILARITY_THRESHOLD = 0.5

    /**
     * Filler words that surround an alternate-version marker ("Believer Slowed **and**
     * Reverb"). Removed together with the marker so an explicit request for a slowed
     * version still compares equal to a plain upload of the same song.
     */
    private val MARKER_CONNECTORS = listOf("and", "version", "mix", "edit", "audio", "with")

    /** True when [text] advertises a different recording of the same song. */
    fun hasAlternateVersionMarker(text: String): Boolean {
        if (text.isBlank()) return false
        return MARKER_REGEX.containsMatchIn(text.lowercase())
    }

    /**
     * Removes alternate-version markers and their filler words from [text].
     *
     * Only used when the user *asked* for an alternate version: then the marker words are
     * expected and must not count as a title mismatch, otherwise the requested slowed
     * version could never be found.
     */
    private fun stripAlternateVersionMarkers(text: String): String {
        var out = text.lowercase().replace("+", " ").replace("&", " ")
        for (marker in ALTERNATE_VERSION_MARKERS) {
            out = out.replace(Regex("\\b" + Regex.escape(marker) + "\\b"), " ")
        }
        for (connector in MARKER_CONNECTORS) {
            out = out.replace(Regex("\\b" + Regex.escape(connector) + "\\b"), " ")
        }
        return out
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

    /** True when the user's own request already asks for an alternate version. */
    fun requestsAlternateVersion(wantedTitle: String, wantedArtist: String): Boolean =
        hasAlternateVersionMarker(wantedTitle) || hasAlternateVersionMarker(wantedArtist)

    /**
     * The substitution gate used when SoundCloud stands in for a requested
     * YouTube-catalogue recording.
     *
     * @return true when there is **no positive evidence** that the candidate is a
     * different recording. Missing metadata never causes a rejection on its own.
     */
    fun isAcceptableSubstitute(
        wantedTitle: String,
        wantedArtist: String,
        wantedDurationMs: Long,
        candidateTitle: String,
        candidateUploader: String,
        candidateDurationMs: Long
    ): Boolean {
        // 1. A decorated upload is a different recording - unless that is what was asked for.
        val requestedAlternate = requestsAlternateVersion(wantedTitle, wantedArtist)
        if (!requestedAlternate) {
            if (hasAlternateVersionMarker(candidateTitle)) return false
            if (hasAlternateVersionMarker(candidateUploader)) return false
        }

        // When an alternate version was requested the marker words are expected, so they
        // are removed from both sides before comparing the song titles.
        val wantedForCompare =
            if (requestedAlternate) stripAlternateVersionMarkers(wantedTitle) else wantedTitle
        val candidateForCompare =
            if (requestedAlternate) stripAlternateVersionMarkers(candidateTitle) else candidateTitle

        val titleSimilarity =
            similarity(normalize(wantedForCompare), normalize(candidateForCompare))

        // 2. Clearly a different song.
        if (titleSimilarity < REJECT_TITLE_SIMILARITY) return false

        // 3. Clearly a different length (only judged when both sides know it).
        if (wantedDurationMs > 0L && candidateDurationMs > 0L) {
            val tolerance = maxOf(
                MIN_DURATION_TOLERANCE_MS,
                (wantedDurationMs * DURATION_TOLERANCE_RATIO).toLong()
            )
            if (abs(candidateDurationMs - wantedDurationMs) > tolerance) return false
        }

        // 4. A weak title match needs the artist to corroborate it. Missing artist
        //    metadata is not a conflict - it simply cannot contribute evidence.
        if (titleSimilarity < TRUSTED_TITLE_SIMILARITY && !isPlaceholderArtist(wantedArtist)) {
            if (!artistCorroborates(wantedArtist, candidateTitle, candidateUploader)) return false
        }

        return true
    }

    /**
     * True when [wantedArtist] shows up as the uploader (fuzzy) or inside the upload title.
     * Callers only invoke this when the artist is a real, comparable name.
     */
    fun artistCorroborates(
        wantedArtist: String,
        candidateTitle: String,
        candidateUploader: String
    ): Boolean {
        val uploaderSimilarity = similarity(normalize(wantedArtist), normalize(candidateUploader))
        if (uploaderSimilarity >= ARTIST_SIMILARITY_THRESHOLD) return true
        if (candidateUploader.isBlank() && candidateTitle.isBlank()) return true

        val artistTokens = normalize(wantedArtist).split(' ').filter { it.length > 2 }
        return artistTokens.isNotEmpty() &&
            artistTokens.any { normalize(candidateTitle).contains(it) }
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
