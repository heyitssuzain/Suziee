package com.example.couple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CouplePlaybackSyncTest {

    @Test
    fun clockSampleComputesOffsetAndRoundTrip() {
        val clock = CoupleClockSync()
        val sample = clock.onPong(t0 = 1_000L, t1 = 1_300L, t2 = 1_300L, now = 1_100L)
        assertEquals(250L, sample.offsetMs)
        assertEquals(100L, sample.rttMs)
        assertEquals(1_050L, clock.remoteToLocal(1_300L))
        assertEquals(1_300L, clock.localToRemote(1_050L))
        assertTrue(clock.hasSample())
    }

    @Test
    fun bestSampleUsesTheLowestRoundTrip() {
        val clock = CoupleClockSync()
        clock.onPong(t0 = 0L, t1 = 500L, t2 = 500L, now = 400L) // rtt 400, offset 300
        clock.onPong(t0 = 1_000L, t1 = 1_020L, t2 = 1_020L, now = 1_030L) // rtt 30, offset 5
        assertEquals(5L, clock.offsetMs())
        assertEquals(30L, clock.bestSample()!!.rttMs)
    }

    @Test
    fun expectedPositionAddsTransitAge() {
        val clock = CoupleClockSync()
        clock.onPong(t0 = 1_000L, t1 = 1_300L, t2 = 1_300L, now = 1_100L) // offset 250
        val expected = PlaybackSyncPolicy.expectedPositionMs(
            basePositionMs = 5_000L,
            issuedAtRemoteMs = 1_300L,
            localNowMs = 1_250L,
            clock = clock,
            playing = true
        )
        assertEquals(5_200L, expected)
        assertEquals(
            5_000L,
            PlaybackSyncPolicy.expectedPositionMs(
                basePositionMs = 5_000L,
                issuedAtRemoteMs = 1_300L,
                localNowMs = 1_250L,
                clock = clock,
                playing = false
            )
        )
    }

    @Test
    fun driftPolicyIgnoresSmallDrift() {
        val decision = PlaybackSyncPolicy.decide(
            expectedPositionMs = 10_000L,
            actualPositionMs = 10_250L,
            playing = true,
            lastSeekAtMs = 0L,
            nowMs = 100_000L
        )
        assertEquals(DriftDecision.NONE, decision.decision)
    }

    @Test
    fun driftPolicyUsesTemporarySpeedForMediumDrift() {
        val ahead = PlaybackSyncPolicy.decide(
            expectedPositionMs = 10_500L,
            actualPositionMs = 10_000L,
            playing = true,
            lastSeekAtMs = 0L,
            nowMs = 100_000L
        )
        assertEquals(DriftDecision.SPEED_UP, ahead.decision)
        assertEquals(1.03f, ahead.speed, 0.0001f)

        val behind = PlaybackSyncPolicy.decide(
            expectedPositionMs = 9_500L,
            actualPositionMs = 10_000L,
            playing = true,
            lastSeekAtMs = 0L,
            nowMs = 100_000L
        )
        assertEquals(DriftDecision.SLOW_DOWN, behind.decision)
        assertEquals(0.97f, behind.speed, 0.0001f)
    }

    @Test
    fun largeDriftSeeksButIsRateLimited() {
        val seek = PlaybackSyncPolicy.decide(
            expectedPositionMs = 13_000L,
            actualPositionMs = 10_000L,
            playing = true,
            lastSeekAtMs = 0L,
            nowMs = 100_000L
        )
        assertEquals(DriftDecision.SEEK, seek.decision)
        assertEquals(13_000L, seek.seekPositionMs)

        val limited = PlaybackSyncPolicy.decide(
            expectedPositionMs = 13_000L,
            actualPositionMs = 10_000L,
            playing = true,
            lastSeekAtMs = 95_000L,
            nowMs = 100_000L
        )
        assertEquals(DriftDecision.NONE, limited.decision)

        val paused = PlaybackSyncPolicy.decide(
            expectedPositionMs = 13_000L,
            actualPositionMs = 10_000L,
            playing = false,
            lastSeekAtMs = 0L,
            nowMs = 100_000L
        )
        assertEquals(DriftDecision.NONE, paused.decision)
    }

    @Test
    fun lamportClockAdvancesAndObservesRemoteEvents() {
        val clock = CoupleLamportClock()
        assertEquals(0L, clock.current())
        assertEquals(1L, clock.next())
        clock.observe(10L)
        assertEquals(11L, clock.next())
        clock.restore(5L)
        assertEquals(11L, clock.current())
        clock.restore(20L)
        assertEquals(20L, clock.current())
        clock.observe(4L)
        assertEquals(20L, clock.current())
    }

    @Test
    fun versionTieBreakIsDeterministicByActor() {
        assertTrue(versionIsNewer(2L, "a", 1L, "z"))
        assertTrue(versionIsNewer(1L, "b", 1L, "a"))
        assertFalse(versionIsNewer(1L, "a", 1L, "b"))
        assertFalse(versionIsNewer(1L, "a", 1L, "a"))
    }
}
