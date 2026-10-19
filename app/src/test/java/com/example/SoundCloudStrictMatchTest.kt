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
    fun `rejects an unlabelled upload from a named non-artist channel`() {
        // THE FIX for "the official song plays wrong audio": an upload that matches title
        // and length exactly, but is published by a channel that is demonstrably not the
        // requested artist, is somebody else's recording (a cover, a re-upload, or a
        // different song that merely shares the title). It must never be played in place of
        // the requested official recording - an honest error is better.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "RandomGuy",
            candidateDurationMs = 204_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects an unlabelled cover that shares the exact official title`() {
        // "(Cover)" never appears in these titles - the only evidence is that a named
        // channel other than the artist published it.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Attention",
            wantedArtist = "Charlie Puth",
            wantedDurationMs = 209_000L,
            candidateTitle = "Attention",
            candidateUploader = "CoversByMike",
            candidateDurationMs = 209_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `rejects a different song that merely shares the title`() {
        // SoundCloud has many unrelated tracks named after a hit. Matching a title is not
        // matching a song, so a named uploader that is not the artist is a conflict.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Attention",
            wantedArtist = "Charlie Puth",
            wantedDurationMs = 209_000L,
            candidateTitle = "Attention",
            candidateUploader = "Someone Else",
            candidateDurationMs = 205_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `accepts a blank uploader that agrees on title and length`() {
        // Missing metadata (no uploader name at all) is not evidence of anything, so it
        // must not reject an otherwise consistent candidate. Only a *named* non-artist
        // uploader is treated as a different performer.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "",
            candidateDurationMs = 204_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts an official-looking channel that corroborates the artist`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons - Topic",
            candidateDurationMs = 204_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `accepts a fan channel whose name contains the artist`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons Fan Club",
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
        // "Shape of You Lofi Chill Remake": its own title says it is another recording
        // (lofi / remake), so it must not be played for a plain official request.
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

    @Test
    fun `rejects an upload whose uploader advertises a different recording`() {
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Slowed Sounds",
            candidateDurationMs = 204_000L
        )
        assertFalse(acceptable)
    }

    @Test
    fun `does not reject on a marker word that the request itself contains`() {
        // A blanket "the candidate title contains a marker word" rule would refuse the
        // official upload of a song that legitimately contains such a word.
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Lofi Girl",
            wantedArtist = "Some Artist",
            wantedDurationMs = 180_000L,
            candidateTitle = "Lofi Girl",
            candidateUploader = "Some Artist",
            candidateDurationMs = 180_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `an ambiguous uploader word alone is not a conflict`() {
        // "live" is an ambiguous marker word: it describes a different recording in a track
        // title but is common in channel names, so it must not be the *reason* a candidate
        // is refused. (The uploader here corroborates the artist, so the performer rule is
        // satisfied and only the marker rule is under test.)
        val acceptable = SoundCloudMatch.isAcceptableSubstitute(
            wantedTitle = "Believer",
            wantedArtist = "Imagine Dragons",
            wantedDurationMs = 204_000L,
            candidateTitle = "Believer",
            candidateUploader = "Imagine Dragons Live",
            candidateDurationMs = 204_000L
        )
        assertTrue(acceptable)
    }

    @Test
    fun `artist corroboration works from the uploader or from the title`() {
        assertTrue(
            SoundCloudMatch.artistCorroborates("Ed Sheeran", "Shape of You", "Ed Sheeran")
        )
        assertTrue(
            SoundCloudMatch.artistCorroborates("Ed Sheeran", "Ed Sheeran - Shape of You", "x")
        )
        assertFalse(
            SoundCloudMatch.artistCorroborates("Ed Sheeran", "Shape of You Lofi", "x")
        )
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
