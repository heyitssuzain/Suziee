package com.example

import com.example.domain.model.Track
import com.example.voice.VoiceCommand
import com.example.voice.VoiceCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun testParsePlayCommand() {
        val result = VoiceCommandParser.parse("play Believer")
        assertTrue(result is VoiceCommand.PlaySearch)
        assertEquals("believer", (result as VoiceCommand.PlaySearch).query)
    }

    @Test
    fun testParseSearchCommand() {
        val result = VoiceCommandParser.parse("search Naruto opening")
        assertTrue(result is VoiceCommand.SearchOnly)
        assertEquals("naruto opening", (result as VoiceCommand.SearchOnly).query)
    }

    @Test
    fun testParsePauseAndResume() {
        assertEquals(VoiceCommand.Pause, VoiceCommandParser.parse("pause"))
        assertEquals(VoiceCommand.Pause, VoiceCommandParser.parse("pause music"))
        assertEquals(VoiceCommand.Resume, VoiceCommandParser.parse("resume"))
        assertEquals(VoiceCommand.Resume, VoiceCommandParser.parse("play music"))
    }

    @Test
    fun testParseNextAndPrevious() {
        assertEquals(VoiceCommand.Next, VoiceCommandParser.parse("play the next song"))
        assertEquals(VoiceCommand.Next, VoiceCommandParser.parse("next"))
        assertEquals(VoiceCommand.Previous, VoiceCommandParser.parse("previous song"))
    }

    @Test
    fun testParseShuffleAndRepeat() {
        assertEquals(VoiceCommand.Shuffle, VoiceCommandParser.parse("shuffle"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("repeat this song"))
    }

    @Test
    fun testParseLyricsAndDownloads() {
        assertEquals(VoiceCommand.OpenLyrics, VoiceCommandParser.parse("open lyrics"))
        assertEquals(VoiceCommand.OpenDownloads, VoiceCommandParser.parse("play my downloads"))
    }

    @Test
    fun testTrackDurationFormat() {
        val track = Track(
            id = "test_1",
            title = "Blue Bird",
            artist = "Ikimono Gakari",
            durationSeconds = 216L
        )
        assertEquals("3:36", track.formattedDuration)

        val zeroTrack = Track(
            id = "test_2",
            title = "Unknown",
            artist = "Unknown",
            durationSeconds = 0L
        )
        assertEquals("--:--", zeroTrack.formattedDuration)
    }
}
