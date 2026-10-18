package com.example.data.remote.innertube

import com.example.domain.model.Track

/**
 * Reorders parsed search results so the recording the user actually asked for surfaces
 * first, ahead of remixes, covers, slowed+reverb edits and similar uploads of the same
 * song.
 *
 * ## Why this exists
 * The Music search backend (and especially the plain-YouTube fallback, which searches
 * `"$query music audio"`) returns uploads in raw relevance order. Fan edits frequently
 * outrank the official recording, and the player always plays the first listed result -
 * so the user heard a fan-made version while the UI showed the song they asked for.
 *
 * ## Design rules
 *  - **Reorder only, never filter.** Every result stays in the list, so playlists and
 *    the queue never lose entries and display metadata still matches the results the
 *    backend returned.
 *  - An **explicit** request for an alternate version ("... slowed and reverb") stops
 *    the penalty and lets alt versions rank normally, honouring the user's intent.
 *  - Kotlin's `sortedByDescending` is stable, so results with equal scores keep the
 *    original backend ordering. On its own this is a no-op re-ranking, never a
 *    correctness risk.
 *  - Pure JVM logic (no Android imports) so it is unit-testable.
 */
internal object SearchRanking {

    /** Title marker phrases for a *different recording* of the requested song. */
    private val ALTERNATE_KEYWORDS =
        listOf(
            "slowed", "reverb", "remix", "nightcore", "sped up", "8d", "16d",
            "bass boosted", "mashup", "bootleg", "cover", "karaoke", "live",
            "instrumental", "lyrical", "whistle remix", "hardstyle"
        )

    /** Channel/artwork markers of an official or auto-generated official upload. */
    private val OFFICIAL_MARKERS = listOf("- topic", "vevo", "official", "records")

    /** Words appended by the fallback query ("$query music audio") or pure noise. */
    private val STOPWORDS = setOf("music", "audio", "song", "video", "official")

    internal fun rank(query: String, tracks: List<Track>): List<Track> {
        if (tracks.size < 2) return tracks

        val loweredQuery = query.lowercase()
        // When the user explicitly asks for an alternate version, that keyword no longer
        // penalises an upload - otherwise every "X slowed" search would return nothing.
        val alternatesRequested =
            ALTERNATE_KEYWORDS.any { loweredQuery.contains(it) }
        val queryTokens = tokenize(query)

        return tracks.sortedByDescending { score(it, queryTokens, alternatesRequested) }
    }

    private fun score(track: Track, queryTokens: Set<String>, alternatesRequested: Boolean): Double {
        val title = track.title.lowercase()
        val artist = track.artist.lowercase()
        var score = 0.0

        // Relevance to the requested song text (query terms found in the title).
        if (queryTokens.isNotEmpty()) {
            val matched = queryTokens.count { title.contains(it) }
            score += matched.toDouble() / queryTokens.size
        }

        // Official / auto-generated "Artist - Topic" channels host the original recording.
        if (OFFICIAL_MARKERS.any { artist.contains(it) }) score += 0.5
        if (OFFICIAL_MARKERS.any { title.contains(it) }) score += 0.15

        // A different recording of the same song is pushed hard below the original,
        // unless the user explicitly asked for one of these versions.
        if (!alternatesRequested && ALTERNATE_KEYWORDS.any { title.contains(it) }) {
            score -= 2.5
        }

        return score
    }

    private fun tokenize(query: String): Set<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOPWORDS }
            .toSet()
}
