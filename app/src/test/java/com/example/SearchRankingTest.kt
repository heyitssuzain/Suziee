package com.example

import com.example.data.remote.innertube.SearchRanking
import com.example.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the search-result ranking that keeps the *official* recording
 * of a song above remixes, covers, slowed+reverb edits and similar alternate uploads,
 * while still honouring explicit requests for such versions and never filtering any
 * result out of the list.
 */
class SearchRankingTest {

    private fun track(id: String, title: String, artist: String, duration: Long = 204L) =
        Track(
            id = id,
            title = title,
            artist = artist,
            durationSeconds = duration,
            artworkUrl = "https://example.org/$id.jpg"
        )

    @Test
    fun `official upload outranks slowed reverb edit for a plain song query`() {
        val slowed = track("slow1", "Believer (Slowed + Reverb)", "LoFi Edits Channel")
        val original = track("orig1", "Believer", "Imagine Dragons")

        val ranked = SearchRanking.rank("Believer", listOf(slowed, original))

        assertEquals("orig1", ranked.first().id)
        // Nothing may be filtered out of the list.
        assertEquals(2, ranked.size)
    }

    @Test
    fun `remix and nightcore variants also rank below the original`() {
        val original = track("orig2", "Believer", "Imagine Dragons")
        val remix = track("rem1", "Believer Remix", "Remixer Guy")
        val nightcore = track("nc1", "Believer Nightcore", "NC Edits")
        val cover = track("cov1", "Believer Cover", "Some Band")

        val ranked = SearchRanking.rank("Believer", listOf(remix, cover, nightcore, original))

        assertEquals("orig2", ranked.first().id)
    }

    @Test
    fun `explicit alternate-version request keeps the alternate first`() {
        val slowed = track("slow2", "Believer (Slowed + Reverb)", "LoFi Edits Channel")
        val original = track("orig3", "Believer", "Imagine Dragons")

        val ranked = SearchRanking.rank(
            "Believer slowed and reverb",
            listOf(original, slowed)
        )

        assertEquals("slow2", ranked.first().id)
    }

    @Test
    fun `official topic channel is preferred between equally-matching uploads`() {
        val topic = track("topic1", "Shape of You", "Ed Sheeran - Topic")
        val random = track("rnd1", "Shape of You", "Edits4Fun")

        val ranked = SearchRanking.rank("Shape of You", listOf(random, topic))

        assertEquals("topic1", ranked.first().id)
    }

    @Test
    fun `original ordering is preserved when scores are equal`() {
        val a = track("a", "Track Alpha", "Artist One")
        val b = track("b", "Track Bravo", "Artist Two")

        val ranked = SearchRanking.rank("cool summer playlist", listOf(a, b))

        assertEquals(listOf("a", "b"), ranked.map { it.id })
    }

    @Test
    fun `singleton and empty inputs pass through untouched`() {
        val single = listOf(track("solo", "Only One", "Artist"))
        assertEquals(single, SearchRanking.rank("Only One", single))
        assertEquals(emptyList<Track>(), SearchRanking.rank("query", emptyList()))
    }
}
