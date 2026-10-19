package com.example

import com.example.data.remote.soundcloud.SoundCloudMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the SoundCloud *substitution* gate used when SoundCloud stands in
 * for a requested official YouTube recording.
 *
 * Two defects are covered here:
 *
 *  1. The gate must **never reject a legitimate recording just because a metadata field
 *     is missing**. The first revision required positive proof (title + artist + duration
 *     + score floor) and therefore answered "No verified matching source found" for
 *     mainstream songs, whose search records routinely come back with `Unknown Artist`
 *     and/or a zero duration. That was a playback regression.
 *  2. The gate must still refuse a candidate that is demonstrably a *different
 *     recording* - slowed/reverb/nightcore/cover uploads, a different song, or a wildly
 *     different length - unless the user explicitly asked for an alternate version.
 */
class SoundCloudStrictMatchTest {

    // ---------------------------------------------------------------------------------
    // The regression: missing metadata must not reject an otherwise consistent candidate
    // ---------------------------------------------------------------------------------

    @Test
    fun `accepts Starboy - the exact song that failed with no verified source found`() {
        // Taken from the reported screenshot: the requested title carries a
        // "(feat. ...)" decoration and SoundCloud's upload does not.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Starboy (feat. Daft Punk)",
            wantedArtist = "The Weeknd",
            wantedDurationMs = 231_000L,
            candidateTitle = "Starboy",
            candidateUploader = "The Weeknd",
            candidateDurationMs = 231_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts a candidate with an unknown duration when the title agrees`() {
        // Duration 0 == "the backend did not tell us". Absence of evidence is not
        // evidence of a different recording.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 0L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts a candidate with a placeholder artist and unknown duration`() {
        // The plain-YouTube search path reports "YouTube Music"/"Unknown Artist" and
        // sometimes a zero duration. This combination used to score below the floor and
        // was rejected outright.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Shape of You",
            wantedArtist = "YouTube Music",
            wantedDurationMs = 0L,
            candidateTitle = "Shape of You",
            candidateUploader = "",
            candidateDurationMs = 0L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts the same song by the same artist with matching duration`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 204_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts when the requested artist appears in the upload title`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Shape of You",
            wantedArtist = "Ed Sheeran",
            wantedDurationMs = 233_000L,
            candidateTitle = "Ed Sheeran - Shape of You",
            candidateUploader = "SomeChannel",
            candidateDurationMs = 233_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts an unlabelled upload that agrees on title and length`() {
        // A re-upload of the official audio with an unknown uploader name is
        // indistinguishable from the official audio by metadata alone - and it is the
        // same recording. Only *labelled* alternate versions are refused.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "RandomGuy",
            candidateDurationMs = 204_000L
        )
        assertTrue(acceptable)
    }

    // ---------------------------------------------------------------------------------
    // Conflicts: still refused
    // ---------------------------------------------------------------------------------

    @Test
    fun `rejects a slowed plus reverb upload for a plain official request`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer (Slowed + Reverb)",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 204_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects an explicitly labelled cover`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer (Cover by RandomGuy)",
            candidateUploader = "RandomGuy",
            candidateDurationMs = 204_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects a completely different song by the same artist`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Radioactive",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 187_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects a candidate whose length does not line up`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 264_000L // +60s
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects a placeholder artist with a clearly different title`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Shape of You",
            wantedArtist = "YouTube Music",
            wantedDurationMs = 233_000L,
            candidateTitle = "Lofi Chill Beat",
            candidateUploader = "anyone",
            candidateDurationMs = 233_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects a weak title match that the artist does not corroborate`() {
        // "Shape of You Lofi Chill Remake": no marker word, but only a partial title
        // overlap (0.5) - so it needs the artist, which is nowhere to be found.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Shape of You",
            wantedArtist = "Ed Sheeran",
            wantedDurationMs = 233_000L,
            candidateTitle = "Shape of You Lofi Chill Remake",
            candidateUploader = "anyone",
            candidateDurationMs = 233_000L
        )
        assertFalse(acceptable)
    }

    // ---------------------------------------------------------------------------------
    // Explicit alternate-version requests are preserved
    // ---------------------------------------------------------------------------------

    @Test
    fun `honours an explicit request for an alternate version`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer (Slowed + Reverb)",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer Slowed and Reverb",
            candidateUploader = "Imagine Dragons",
            candidateDurationMs = 204_000L
        )
        assertTrue(acceptable)
    }

    // ---------------------------------------------------------------------------------
    // Marker detection / normalisation helpers
    // ---------------------------------------------------------------------------------

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
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "夜に駆ける",
            wantedArtist = "YOASOBI",
            wantedDurationMs = 261_000L,
            candidateTitle = "夜に駆ける",
            candidateUploader = "YOASOBI",
            candidateDurationMs = 261_000L
        )
        assertTrue(acceptable)

        val slowed = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "夜に駆ける",
            wantedArtist = "YOASOBI",
            wantedDurationMs = 261_000L,
            candidateTitle = "夜に駆ける (Slowed + Reverb)",
            candidateUploader = "YOASOBI",
            candidateDurationMs = 261_000L
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
