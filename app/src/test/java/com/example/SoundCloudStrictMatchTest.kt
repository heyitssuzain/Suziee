package com.example

import com.example.data.remote.soundcloud.SoundCloudMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the SoundCloud *strict* verification gate.
 *
 * These cover the defect where an official-song request was silently served by a
 * different recording (slowed/reverb, cover, unrelated upload) because matching only
 * re-ranked candidates instead of verifying them.
 */
class SoundCloudStrictMatchTest {

    private val goodScore = 0.9

    @Test
    fun `accepts the same song by the same artist with matching duration`() {
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 204_000L,
            overallScore = goodScore
        )
        assertTrue(verified)
    }

    @Test
    fun `rejects a slowed plus reverb upload for a plain official request`() {
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer (Slowed + Reverb)",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 204_000L,
            overallScore = goodScore
        )
        assertFalse(verified)
    }

    @Test
    fun `rejects a cover by an unrelated uploader`() {
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "RandomGuy",
            candidateDurationMs = 204_000L,
            overallScore = goodScore
        )
        assertFalse(verified)
    }

    @Test
    fun `rejects a candidate whose length does not line up`() {
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 264_000L, // +60s
            overallScore = goodScore
        )
        assertFalse(verified)
    }

    @Test
    fun `accepts when the requested artist appears in the upload title`() {
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Shape of You",
            wantedArtist = "Ed Sheeran",
            wantedDurationMs = 233_000L,
            candidateTitle = "Ed Sheeran - Shape of You",
            candidateUploader = "SomeChannel",
            candidateDurationMs = 233_000L,
            overallScore = goodScore
        )
        assertTrue(verified)
    }

    @Test
    fun `honours an explicit request for an alternate version`() {
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Believer (Slowed + Reverb)",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer Slowed and Reverb",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 204_000L,
            overallScore = goodScore
        )
        assertTrue(verified)
    }

    @Test
    fun `placeholder artist still accepts an identical title but rejects a vague one`() {
        val identical = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Shape of You",
            wantedArtist = "YouTube Music",
            wantedDurationMs = 233_000L,
            candidateTitle = "Shape of You (Lyrics)",
            candidateUploader = "anyone",
            candidateDurationMs = 233_000L,
            overallScore = goodScore
        )
        assertTrue(identical)

        val vague = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "Shape of You",
            wantedArtist = "YouTube Music",
            wantedDurationMs = 233_000L,
            candidateTitle = "Shape of You Lofi Chill Remake",
            candidateUploader = "anyone",
            candidateDurationMs = 233_000L,
            overallScore = goodScore
        )
        assertFalse(vague)
    }

    @Test
    fun `alternate version markers are detected inside parentheses`() {
        // Regression: the old check ran on the normalised title, which strips
        // parenthesised text - exactly where uploads put "(Slowed + Reverb)".
        assertTrue(SoundCloudMatch.hasAlternateVersionMarker("Song (Slowed + Reverb)"))
        assertTrue(SoundCloudMatch.hasAlternateVersionMarker("Song [Nightcore]"))
        assertTrue(SoundCloudMatch.hasAlternateVersionMarker("Song - Live at Wembley"))
        assertTrue(SoundCloudMatch.hasAlternateVersionMarker("Song (Cover by Someone)"))
        assertFalse(SoundCloudMatch.hasAlternateVersionMarker("Song (Official Video)"))
    }

    @Test
    fun `marker detection respects word boundaries`() {
        // "Deliver" contains "live", "Discover" contains "cover". Penalising or
        // rejecting those would silently drop correct official uploads.
        assertFalse(SoundCloudMatch.hasAlternateVersionMarker("Deliver"))
        assertFalse(SoundCloudMatch.hasAlternateVersionMarker("Discover the Night"))
        assertFalse(SoundCloudMatch.containsMarker("18d", "8d"))
        assertTrue(SoundCloudMatch.containsMarker("Song 8d audio", "8d"))
    }

    @Test
    fun `non-latin titles stay comparable`() {
        // The old normaliser deleted every non-ASCII character, so every CJK title
        // normalised to an empty string and could never match anything.
        val verified = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "夜に駆ける",
            wantedArtist = "YOASOBI",
            wantedDurationMs = 261_000L,
            candidateTitle = "夜に駆ける",
            candidateUploader = "YOASOBI",
            candidateDurationMs = 261_000L,
            overallScore = goodScore
        )
        assertTrue(verified)

        val slowed = SoundCloudMatch.isStrictVerifiedMatch(
            wantedTitle = "夜に駆ける",
            wantedArtist = "YOASOBI",
            wantedDurationMs = 261_000L,
            candidateTitle = "夜に駆ける (Slowed + Reverb)",
            candidateUploader = "YOASOBI",
            candidateDurationMs = 261_000L,
            overallScore = goodScore
        )
        assertFalse(slowed)
    }

    @Test
    fun `placeholder artists are recognised`() {
        assertTrue(SoundCloudMatch.isPlaceholderArtist("Unknown Artist"))
        assertTrue(SoundCloudMatch.isPlaceholderArtist("YouTube Music"))
        assertFalse(SoundCloudMatch.isPlaceholderArtist("Imagine Dragons"))
    }
}
