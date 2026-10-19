package com.example.data.remote.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure half of the headless extraction engine.
 *
 * The WebView half (navigation, the V8 hook, the media-request interceptor) can only be
 * exercised on a real device; everything that *decides* what to play - format selection,
 * bridge payload parsing and URL normalisation - is pure Kotlin and is verified here so a
 * regression cannot reach a release without CI failing.
 */
class AudioFormatSelectorTest {

    private fun candidate(
        itag: Int,
        mimeType: String,
        bitrate: Int = 128_000,
        url: String = "https://rr1---sn-x.googlevideo.com/videoplayback?itag=$itag"
    ) = AudioFormatCandidate(itag, mimeType, bitrate, 0L, url)

    // ---------------------------------------------------------------------------------
    // pickBest
    // ---------------------------------------------------------------------------------

    @Test
    fun `prefers opus (itag 251) over aac (itag 140)`() {
        val best = AudioFormatSelector.pickBest(
            listOf(
                candidate(140, "audio/mp4"),
                candidate(251, "audio/webm", bitrate = 160_000)
            )
        )
        assertEquals(251, best?.itag)
    }

    @Test
    fun `uses aac when opus is not offered`() {
        val best = AudioFormatSelector.pickBest(
            listOf(
                candidate(139, "audio/mp4", bitrate = 48_000),
                candidate(140, "audio/mp4", bitrate = 128_000)
            )
        )
        assertEquals(140, best?.itag)
    }

    @Test
    fun `honours the highest bitrate within the same itag rank`() {
        val best = AudioFormatSelector.pickBest(
            listOf(
                candidate(140, "audio/mp4", bitrate = 128_000),
                candidate(140, "audio/mp4", bitrate = 256_000)
            )
        )
        assertEquals(256_000, best?.bitrate)
    }

    @Test
    fun `ignores video-only formats`() {
        val best = AudioFormatSelector.pickBest(
            listOf(
                candidate(137, "video/mp4", bitrate = 4_000_000),
                candidate(140, "audio/mp4")
            )
        )
        assertEquals(140, best?.itag)
    }

    @Test
    fun `ignores candidates without a url (signatureCipher only)`() {
        val best = AudioFormatSelector.pickBest(
            listOf(candidate(251, "audio/webm", url = ""))
        )
        assertNull(best)
    }

    @Test
    fun `returns null when nothing usable is offered`() {
        assertNull(AudioFormatSelector.pickBest(emptyList()))
        assertNull(AudioFormatSelector.pickBest(listOf(candidate(137, "video/mp4"))))
    }

    // ---------------------------------------------------------------------------------
    // Bridge payload parsing
    // ---------------------------------------------------------------------------------

    @Test
    fun `parses the line-delimited payload sent by the in-page hook`() {
        val payload = buildString {
            append("251|audio/webm|160000|3300000|https://rr1.googlevideo.com/videoplayback?a=1\n")
            append("140|audio/mp4|128000|2600000|https://rr2.googlevideo.com/videoplayback?b=2\n")
        }
        val parsed = AudioFormatSelector.parse(payload)

        assertEquals(2, parsed.size)
        assertEquals(251, parsed[0].itag)
        assertEquals("audio/webm", parsed[0].mimeType)
        assertEquals(160_000, parsed[0].bitrate)
        assertEquals(3_300_000L, parsed[0].contentLength)
        assertTrue(parsed[0].url.endsWith("a=1"))
    }

    @Test
    fun `keeps a pipe character inside the url`() {
        val parsed = AudioFormatSelector.parse(
            "251|audio/webm|160000|100|https://x.googlevideo.com/videoplayback?a=1|2&b=3"
        )
        assertEquals(1, parsed.size)
        assertEquals("https://x.googlevideo.com/videoplayback?a=1|2&b=3", parsed[0].url)
    }

    @Test
    fun `skips malformed lines but keeps the good ones`() {
        val payload = buildString {
            append("not-a-record\n")
            append("140|audio/mp4|oops|100|https://rr.googlevideo.com/videoplayback\n")
            append("140|audio/mp4|128000|100|https://rr.googlevideo.com/videoplayback\n")
            append("251|audio/webm|160000|100|\n")
        }
        val parsed = AudioFormatSelector.parse(payload)

        // The unparseable bitrate degrades to 0 instead of discarding the format, the empty
        // URL is skipped, and the nonsense line is skipped.
        assertEquals(2, parsed.size)
        assertEquals(140, parsed[0].itag)
        assertEquals(0, parsed[0].bitrate)
        assertEquals(140, parsed[1].itag)
        assertEquals(128_000, parsed[1].bitrate)
    }

    @Test
    fun `parses an empty or null payload as no candidates`() {
        assertTrue(AudioFormatSelector.parse(null).isEmpty())
        assertTrue(AudioFormatSelector.parse("").isEmpty())
        assertTrue(AudioFormatSelector.parse("   ").isEmpty())
    }

    @Test
    fun `strips the codecs attribute from a mime type`() {
        assertEquals("audio/webm", AudioFormatSelector.normalizeMimeType("audio/webm; codecs=\"opus\""))
        assertEquals("audio/mp4", AudioFormatSelector.normalizeMimeType("audio/mp4; codecs=\"mp4a.40.2\""))
        assertNull(AudioFormatSelector.normalizeMimeType("  "))
        assertNull(AudioFormatSelector.normalizeMimeType("nonsense"))
    }

    // ---------------------------------------------------------------------------------
    // URL normalisation
    // ---------------------------------------------------------------------------------

    @Test
    fun `detects the container mime type from a sniffed cdn url`() {
        assertEquals(
            "audio/mp4",
            AudioFormatSelector.mimeTypeFromUrl(
                "https://rr1.googlevideo.com/videoplayback?mime=audio%2Fmp4&itag=140"
            )
        )
        assertEquals(
            "audio/webm",
            AudioFormatSelector.mimeTypeFromUrl(
                "https://rr1.googlevideo.com/videoplayback?mime=audio%2Fwebm&itag=251"
            )
        )
        assertNull(
            AudioFormatSelector.mimeTypeFromUrl(
                "https://rr1.googlevideo.com/videoplayback?mime=video%2Fmp4"
            )
        )
    }

    @Test
    fun `drops the chunk parameters but preserves the signature`() {
        val raw = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&mime=audio%2Fmp4&" +
            "n=TzL%3D%3D&sig=Ab%2Fc%3D&range=0-65535&rn=4&rbuf=1500&sq=3&sabr=1"
        val clean = AudioFormatSelector.stripChunkParameters(raw)

        assertTrue(clean.startsWith("https://rr1---sn-x.googlevideo.com/videoplayback?"))
        assertTrue(clean.contains("mime=audio%2Fmp4"))
        assertTrue(clean.contains("n=TzL%3D%3D"))
        assertTrue(clean.contains("sig=Ab%2Fc%3D"))
        assertTrue(!clean.contains("range="))
        assertTrue(!clean.contains("rn="))
        assertTrue(!clean.contains("rbuf="))
        assertTrue(!clean.contains("sq="))
        assertTrue(!clean.contains("sabr="))
    }

    @Test
    fun `leaves a url without a query untouched`() {
        val url = "https://rr1.googlevideo.com/videoplayback"
        assertEquals(url, AudioFormatSelector.stripChunkParameters(url))
    }
}
