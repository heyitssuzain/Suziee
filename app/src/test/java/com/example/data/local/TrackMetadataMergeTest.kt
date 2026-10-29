package com.example.data.local

import com.example.data.local.entities.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetadataMergeTest {

    private fun entity(
        id: String = "track-1",
        downloaded: Boolean = false,
        path: String? = null,
        lyrics: String? = null
    ) = TrackEntity(
        id = id,
        title = "Title",
        artist = "Artist",
        durationSeconds = 180,
        artworkUrl = "",
        streamUrl = null,
        isDownloaded = downloaded,
        localFilePath = path,
        lyricsPlain = lyrics
    )

    @Test
    fun `search metadata cannot erase a completed download`() {
        val existing = entity(downloaded = true, path = "/files/downloads/track.webm")
        val incoming = entity(downloaded = false, path = null)

        val merged = TrackMetadataMerge.merge(existing, incoming)

        assertTrue(merged.isDownloaded)
        assertEquals("/files/downloads/track.webm", merged.localFilePath)
    }

    @Test
    fun `cached lyrics survive a metadata refresh with null lyrics`() {
        val existing = entity(lyrics = "cached words")
        val incoming = entity(lyrics = null)

        val merged = TrackMetadataMerge.merge(existing, incoming)

        assertEquals("cached words", merged.lyricsPlain)
    }

    @Test
    fun `a genuinely new track keeps its incoming state`() {
        val merged = TrackMetadataMerge.merge(null, entity(downloaded = false))
        assertFalse(merged.isDownloaded)
    }
}
