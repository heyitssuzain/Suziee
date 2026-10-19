package com.example.data.remote.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the rules that decide whether a YouTube response can be played.
 *
 * The important one is [skipsSabrOnlyFormats]: YouTube's web clients now return
 * `adaptiveFormats` entries without a `url` (SABR only). The old WebView provider handed
 * those to ExoPlayer and every song failed; the native provider must reject them and use
 * the Android-VR response instead.
 */
class YouTubeAudioPolicyTest {

    private val audioUrl =
        "https://rr1---sn-abc.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm"

    private fun format(
        itag: Int,
        mime: String? = "audio/webm",
        url: String? = audioUrl,
        bitrate: Int = 0
    ) = YouTubeAudioPolicy.RawAudioFormat(itag, mime, url, bitrate)

    @Test
    fun `accepts only real 11 character video ids`() {
        assertTrue(YouTubeAudioPolicy.isVideoId("dQw4w9WgXcQ"))
        assertTrue(YouTubeAudioPolicy.isVideoId("a_b-c_D-123"))

        assertFalse(YouTubeAudioPolicy.isVideoId(""))
        assertFalse(YouTubeAudioPolicy.isVideoId("dQw4w9WgXc"))
        assertFalse(YouTubeAudioPolicy.isVideoId("dQw4w9WgXcQ1"))
        assertFalse(YouTubeAudioPolicy.isVideoId("dQw4w9WgXc "))
        assertFalse(YouTubeAudioPolicy.isVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(YouTubeAudioPolicy.isVideoId("dQw4w9WgXcQ --exec rm"))
    }

    @Test
    fun `extracts visitor data and api key from the bootstrap config`() {
        val html = """
            <script>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyExampleKey1234567890",
            "VISITOR_DATA":"CgtExampleVisitorData123","HL":"en"});</script>
        """.trimIndent()

        val session = YouTubeAudioPolicy.parseSession(html)

        assertEquals("CgtExampleVisitorData123", session?.visitorData)
        assertEquals("AIzaSyExampleKey1234567890", session?.apiKey)
    }

    @Test
    fun `rejects a page without visitor data or key`() {
        assertNull(YouTubeAudioPolicy.parseSession(null))
        assertNull(YouTubeAudioPolicy.parseSession(""))
        assertNull(YouTubeAudioPolicy.parseSession("<html>consent</html>"))
        assertNull(
            YouTubeAudioPolicy.parseSession("""{"VISITOR_DATA":"abc"}""")
        )
    }

    @Test
    fun `prefers opus 251 over aac 140`() {
        val picked = YouTubeAudioPolicy.pickAudioFormat(
            listOf(
                format(140, "audio/mp4; codecs=\"mp4a.40.2\""),
                format(251, "audio/webm; codecs=\"opus\"")
            )
        )

        assertEquals(251, picked?.itag)
        assertEquals("audio/webm", picked?.mimeType)
    }

    @Test
    fun `falls back to aac 140 when opus is missing`() {
        val picked = YouTubeAudioPolicy.pickAudioFormat(
            listOf(format(140, "audio/mp4; codecs=\"mp4a.40.2\""))
        )

        assertEquals(140, picked?.itag)
        assertEquals("audio/mp4", picked?.mimeType)
    }

    @Test
    fun `skips SABR only formats without a direct url`() {
        val sabrOnly = listOf(
            format(251, url = null),
            format(140, url = null),
            format(137, mime = "video/mp4", url = null)
        )

        assertNull(YouTubeAudioPolicy.pickAudioFormat(sabrOnly))
    }

    @Test
    fun `skips video only formats`() {
        val picked = YouTubeAudioPolicy.pickAudioFormat(
            listOf(
                format(137, mime = "video/mp4", bitrate = 5_000_000),
                format(251, mime = "audio/webm", bitrate = 160_000)
            )
        )

        assertEquals(251, picked?.itag)
    }

    @Test
    fun `rejects non googlevideo and non https media urls`() {
        assertTrue(YouTubeAudioPolicy.isPlayableMediaUrl(audioUrl))
        assertFalse(YouTubeAudioPolicy.isPlayableMediaUrl("http://rr1.googlevideo.com/x"))
        assertFalse(YouTubeAudioPolicy.isPlayableMediaUrl("https://evil.example/x"))
        assertFalse(
            YouTubeAudioPolicy.isPlayableMediaUrl("https://googlevideo.com.evil.example/x")
        )
        assertFalse(YouTubeAudioPolicy.isPlayableMediaUrl(""))
        assertFalse(YouTubeAudioPolicy.isPlayableMediaUrl(null))
    }

    @Test
    fun `normalises mime types and strips codecs`() {
        assertEquals(
            "audio/webm",
            YouTubeAudioPolicy.normalizeMime("audio/webm; codecs=\"opus\"")
        )
        assertEquals(
            "audio/mp4",
            YouTubeAudioPolicy.normalizeMime("audio/mp4; codecs=\"mp4a.40.2\"")
        )
        assertNull(YouTubeAudioPolicy.normalizeMime("video/mp4"))
        assertNull(YouTubeAudioPolicy.normalizeMime("  "))
        assertNull(YouTubeAudioPolicy.normalizeMime(null))
    }

    @Test
    fun `uses the highest bitrate when no preferred itag exists`() {
        val picked = YouTubeAudioPolicy.pickAudioFormat(
            listOf(
                format(249, mime = "audio/webm", bitrate = 50_000),
                format(250, mime = "audio/webm", bitrate = 70_000)
            )
        )

        assertEquals(250, picked?.itag)
    }

    @Test
    fun `infers the container from the itag when mime is missing`() {
        val picked = YouTubeAudioPolicy.pickAudioFormat(
            listOf(format(140, mime = null))
        )

        assertEquals("audio/mp4", picked?.mimeType)
    }
}
