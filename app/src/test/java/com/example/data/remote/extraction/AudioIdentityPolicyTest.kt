package com.example.data.remote.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the drift guard of the secure extraction engine.
 *
 * The rule that matters: a captured stream may only be dropped on **positive evidence** that
 * the page is on a different video. Reporting "unknown" and dropping the capture anyway is
 * exactly what previously starved every official song of its stream (it fell through to the
 * SoundCloud substitute), so the "no evidence" cases are asserted explicitly.
 */
class AudioIdentityPolicyTest {

    @Test
    fun `drops a capture when the page reports a different video`() {
        assertTrue(
            AudioIdentityPolicy.isForeignCapture(
                requestedVideoId = "dQw4w9WgXcQ",
                reportedVideoId = "kJQP7kiw5Fk"
            )
        )
    }

    @Test
    fun `keeps a capture when the page reports the requested video`() {
        assertFalse(
            AudioIdentityPolicy.isForeignCapture(
                requestedVideoId = "dQw4w9WgXcQ",
                reportedVideoId = "dQw4w9WgXcQ"
            )
        )
    }

    @Test
    fun `keeps a capture when the reported video is unknown`() {
        // The hook may not have run yet, or the page may use a player shape we cannot read.
        // Absence of a reported id is not evidence of a wrong video.
        assertFalse(AudioIdentityPolicy.isForeignCapture("dQw4w9WgXcQ", null))
        assertFalse(AudioIdentityPolicy.isForeignCapture("dQw4w9WgXcQ", ""))
        assertFalse(AudioIdentityPolicy.isForeignCapture("dQw4w9WgXcQ", "   "))
        assertFalse(AudioIdentityPolicy.isForeignCapture("dQw4w9WgXcQ", "null"))
        assertFalse(AudioIdentityPolicy.isForeignCapture("dQw4w9WgXcQ", "undefined"))
        assertFalse(AudioIdentityPolicy.isForeignCapture("dQw4w9WgXcQ", "\"\""))
    }

    @Test
    fun `keeps a capture when nothing was requested`() {
        assertFalse(AudioIdentityPolicy.isForeignCapture("", "dQw4w9WgXcQ"))
        assertFalse(AudioIdentityPolicy.isForeignCapture("   ", "dQw4w9WgXcQ"))
    }

    @Test
    fun `cleans quoted and padded bridge values`() {
        // evaluateJavascript returns a JSON string literal, and the bridge can report
        // whitespace, so both are normalised before comparing.
        assertEquals("dQw4w9WgXcQ", AudioIdentityPolicy.clean("\"dQw4w9WgXcQ\""))
        assertEquals("dQw4w9WgXcQ", AudioIdentityPolicy.clean("  dQw4w9WgXcQ  "))
        assertEquals("", AudioIdentityPolicy.clean(null))
        assertEquals("", AudioIdentityPolicy.clean("NULL"))
    }

    @Test
    fun `a quoted id from the page still matches the requested id`() {
        assertFalse(
            AudioIdentityPolicy.isForeignCapture(
                requestedVideoId = "dQw4w9WgXcQ",
                reportedVideoId = "\"dQw4w9WgXcQ\""
            )
        )
    }
}
