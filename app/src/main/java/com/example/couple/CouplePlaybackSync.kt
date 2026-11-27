package com.example.couple

import kotlin.math.abs

data class ClockSample(
    val offsetMs: Long,
    val rttMs: Long
)

/**
 * NTP-style offset estimation over the DataChannel.
 *
 * The sample with the lowest round-trip time is the least noisy estimate; the offset is
 * smoothed by keeping a short bounded history. The clock is only used to place a remote
 * playhead, never to make security decisions.
 */
class CoupleClockSync {
    private val samples = ArrayDeque<ClockSample>()

    fun onPong(t0: Long, t1: Long, t2: Long, now: Long): ClockSample {
        val rtt = ((now - t0) - (t2 - t1)).coerceAtLeast(0L)
        val offset = ((t1 - t0) + (t2 - now)) / 2L
        val sample = ClockSample(offsetMs = offset, rttMs = rtt)
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        return sample
    }

    fun hasSample(): Boolean = samples.isNotEmpty()

    fun bestSample(): ClockSample? = samples.minByOrNull { it.rttMs }

    fun offsetMs(): Long = bestSample()?.offsetMs ?: 0L

    fun remoteToLocal(remoteMs: Long): Long = remoteMs - offsetMs()

    fun localToRemote(localMs: Long): Long = localMs + offsetMs()

    fun reset() = samples.clear()

    private companion object {
        const val MAX_SAMPLES = 8
    }
}

enum class DriftDecision {
    NONE,
    SPEED_UP,
    SLOW_DOWN,
    SEEK
}

data class DriftCorrection(
    val decision: DriftDecision,
    val speed: Float = 1f,
    val seekPositionMs: Long = 0L
)

/**
 * Drift correction policy.
 *
 * Small drift is corrected with a temporary 3% speed change, which is inaudible and keeps
 * the two players together without a seek. A seek is reserved for genuinely broken
 * synchronisation and is rate-limited so a noisy network cannot make the song stutter.
 */
object PlaybackSyncPolicy {
    const val NONE_THRESHOLD_MS = 300L
    const val SEEK_THRESHOLD_MS = 1_500L
    const val MIN_SEEK_INTERVAL_MS = 8_000L
    const val SPEED_CORRECTION = 0.03f

    /**
     * Expected playhead at [localNowMs], given the sender's position at [issuedAtRemoteMs].
     * The age includes transit time, which is exactly what a seek should account for.
     */
    fun expectedPositionMs(
        basePositionMs: Long,
        issuedAtRemoteMs: Long,
        localNowMs: Long,
        clock: CoupleClockSync,
        playing: Boolean
    ): Long {
        if (!playing) return basePositionMs.coerceAtLeast(0L)
        val sentAtLocal = clock.remoteToLocal(issuedAtRemoteMs)
        val age = (localNowMs - sentAtLocal).coerceAtLeast(0L)
        return (basePositionMs + age).coerceAtLeast(0L)
    }

    fun decide(
        expectedPositionMs: Long,
        actualPositionMs: Long,
        playing: Boolean,
        lastSeekAtMs: Long,
        nowMs: Long
    ): DriftCorrection {
        if (!playing) return DriftCorrection(DriftDecision.NONE)
        val drift = expectedPositionMs - actualPositionMs
        val magnitude = abs(drift)
        if (magnitude <= NONE_THRESHOLD_MS) return DriftCorrection(DriftDecision.NONE)

        if (magnitude >= SEEK_THRESHOLD_MS) {
            if (nowMs - lastSeekAtMs < MIN_SEEK_INTERVAL_MS) {
                return DriftCorrection(DriftDecision.NONE)
            }
            return DriftCorrection(
                decision = DriftDecision.SEEK,
                seekPositionMs = expectedPositionMs.coerceAtLeast(0L)
            )
        }

        return if (drift > 0) {
            DriftCorrection(
                decision = DriftDecision.SPEED_UP,
                speed = 1f + SPEED_CORRECTION
            )
        } else {
            DriftCorrection(
                decision = DriftDecision.SLOW_DOWN,
                speed = 1f - SPEED_CORRECTION
            )
        }
    }
}

/**
 * Lamport clock for shared-playlist conflict resolution. Every local mutation advances the
 * clock; every remote event observes its clock first. Ties are broken by the actor id, so
 * both phones independently choose the same winner.
 */
class CoupleLamportClock(initial: Long = 0L) {
    private var value: Long = initial

    @Synchronized
    fun current(): Long = value

    @Synchronized
    fun observe(remote: Long) {
        if (remote > value) value = remote
    }

    @Synchronized
    fun next(): Long {
        value += 1
        return value
    }

    @Synchronized
    fun restore(value: Long) {
        this.value = maxOf(this.value, value)
    }
}

fun versionIsNewer(
    incomingLamport: Long,
    incomingActor: String,
    existingLamport: Long,
    existingActor: String
): Boolean = incomingLamport > existingLamport ||
    (incomingLamport == existingLamport && incomingActor > existingActor)
