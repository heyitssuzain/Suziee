package com.example.data.remote.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules that decide what yt-dlp output is allowed to become an ExoPlayer source.
 *
 * These replace the old SoundCloud substitution tests: there is no second catalogue any
 * more, so the question is no longer "is this the same song?" but "is this a playable
 * googlevideo URL for the exact video id we asked yt-dlp for?".
 */
class YtDlpStreamParserTest {

    private val videoId = "dQw4w9WgXcQ"
    private val audioUrl =
        "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&mime=audio%2Fwebm&itag=251"

    @Test
    fun watchUrlContainsTheExactRequestedVideo() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            YtDlpStreamParser.watchUrl(videoId)
        )
    }

    @Test
    fun videoIdsMustBeExactlyElevenUrlSafeCharacters() {
        assertTrue(YtDlpStreamParser.isVideoId("dQw4w9WgXcQ"))
        assertTrue(YtDlpStreamParser.isVideoId("a_b-c_D-123"))

        assertFalse(YtDlpStreamParser.isVideoId(""))
        assertFalse(YtDlpStreamParser.isVideoId("dQw4w9WgXc"))
        assertFalse(YtDlpStreamParser.isVideoId("dQw4w9WgXcQ1"))
        assertFalse(YtDlpStreamParser.isVideoId("dQw4w9WgXc "))
        assertFalse(YtDlpStreamParser.isVideoId("https://youtu.be/dQw4w9WgXcQ"))
        // Argument-injection attempts can never become a second yt-dlp argument.
        assertFalse(YtDlpStreamParser.isVideoId("dQw4w9WgXcQ --exec rm"))
        assertFalse(YtDlpStreamParser.isVideoId("../dQw4w9WgXcQ"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun watchUrlRefusesAnInvalidId() {
        YtDlpStreamParser.watchUrl("not-a-video")
    }

    @Test
    fun acceptsSignedGooglevideoAudio() {
        val stream = YtDlpStreamParser.toStreamInfo(
            url = audioUrl,
            formatId = "251",
            extension = "webm",
            headers = null
        )

        requireNotNull(stream)
        assertEquals(audioUrl, stream.url)
        assertEquals("audio/webm", stream.mimeType)
    }

    @Test
    fun rejectsAnythingThatIsNotHttpsGooglevideo() {
        assertNull(YtDlpStreamParser.toStreamInfo("", "251", "webm", null))
        assertNull(YtDlpStreamParser.toStreamInfo(null, "251", "webm", null))
        assertNull(
            YtDlpStreamParser.toStreamInfo(
                "http://rr3---sn-abc.googlevideo.com/videoplayback?itag=251",
                "251",
                "webm",
                null
            )
        )
        assertNull(
            YtDlpStreamParser.toStreamInfo(
                "https://googlevideo.com.evil.example/videoplayback?itag=251",
                "251",
                "webm",
                null
            )
        )
        assertNull(
            YtDlpStreamParser.toStreamInfo(
                "https://evil.example/googlevideo.com/videoplayback",
                "251",
                "webm",
                null
            )
        )
        // A local path / non-URL line from yt-dlp output is never handed to the player.
        assertNull(YtDlpStreamParser.toStreamInfo("/data/user/0/app/yt-dlp", "251", "webm", null))
    }

    @Test
    fun formatIdWinsOverAConflictingExtension() {
        assertEquals(
            "audio/mp4",
            YtDlpStreamParser.toStreamInfo(audioUrl, "140", "webm", null)?.mimeType
        )
        assertEquals(
            "audio/webm",
            YtDlpStreamParser.toStreamInfo(audioUrl, "251", "mp4", null)?.mimeType
        )
    }

    @Test
    fun fallsBackToTheContainerExtensionWhenFormatIdIsMissing() {
        assertEquals(
            "audio/webm",
            YtDlpStreamParser.toStreamInfo(audioUrl, null, "webm", null)?.mimeType
        )
        assertEquals(
            "audio/mp4",
            YtDlpStreamParser.toStreamInfo(audioUrl, null, "m4a", null)?.mimeType
        )
        assertEquals(
            "audio/mpeg",
            YtDlpStreamParser.toStreamInfo(audioUrl, null, "mp3", null)?.mimeType
        )
        assertNull(YtDlpStreamParser.toStreamInfo(audioUrl, null, "bin", null)?.mimeType)
    }

    @Test
    fun infersTheContainerFromTheGooglevideoQuery() {
        val webm = "https://rr3---sn-abc.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm"
        val m4a = "https://rr3---sn-abc.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4"

        assertEquals(
            "audio/webm",
            YtDlpStreamParser.toStreamInfo(webm, null, null, null)?.mimeType
        )
        assertEquals(
            "audio/mp4",
            YtDlpStreamParser.toStreamInfo(m4a, null, null, null)?.mimeType
        )
    }

    @Test
    fun hlsManifestGetsTheExactMedia3MimeType() {        val stream = YtDlpStreamParser.toStreamInfo(
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/id/abc",
            formatId = null,
            extension = null,
            headers = null
        )

        requireNotNull(stream)
        assertEquals("application/x-mpegURL", stream.mimeType)
    }

    @Test
    fun keepsRequiredHeadersAndDropsHopByHopOnes() {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://www.youtube.com/",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate",
            "Range" to "bytes=0-1023",
            "Host" to "rr3---sn-abc.googlevideo.com",
            "X-Blank" to " ",
            "Content-Length" to "123"
        )

        val sanitized = YtDlpStreamParser.sanitizeHeaders(headers)

        assertEquals("Mozilla/5.0", sanitized["User-Agent"])
        assertEquals("https://www.youtube.com/", sanitized["Referer"])
        assertEquals("en-US,en;q=0.9", sanitized["Accept-Language"])
        assertFalse(sanitized.containsKey("Accept-Encoding"))
        assertFalse(sanitized.containsKey("Range"))
        assertFalse(sanitized.containsKey("Host"))
        assertFalse(sanitized.containsKey("Content-Length"))
        assertFalse(sanitized.containsKey("X-Blank"))
    }

    @Test
    fun blankHeadersBecomeAnEmptyMap() {
        assertTrue(YtDlpStreamParser.sanitizeHeaders(null).isEmpty())
        assertTrue(YtDlpStreamParser.sanitizeHeaders(emptyMap()).isEmpty())
    }
}
