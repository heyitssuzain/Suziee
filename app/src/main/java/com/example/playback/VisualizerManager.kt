package com.example.playback

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Captures real FFT data from the active ExoPlayer audio session.
 *
 * The platform [Visualizer] needs `RECORD_AUDIO` granted at runtime. When it is not
 * available we fall back to a deterministic, position-driven waveform instead of random
 * noise, so the UI stays smooth and never pulsates while playback is paused.
 */
class VisualizerManager {

    @Volatile
    private var visualizer: Visualizer? = null

    @Volatile
    private var attachedSessionId: Int = -1

    @Volatile
    private var latest: List<Float> = QUIET

    /** Latest captured bars, or [QUIET] when no capture is active. */
    fun latestAmplitudes(): List<Float> = latest

    /**
     * Attaches to [sessionId]. Safe to call repeatedly; an already-attached session is
     * simply re-enabled (pause() disables it, play re-enables the same instance).
     */
    fun attach(sessionId: Int) {
        if (sessionId <= 0) return

        val existing = visualizer
        if (sessionId == attachedSessionId && existing != null) {
            runCatching { existing.enabled = true }
            return
        }

        detach()
        try {
            val captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            val v = Visualizer(sessionId)
            v.captureSize = captureSize
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) latest = waveformToBars(waveform)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft != null) latest = fftToBars(fft)
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                true
            )
            v.enabled = true
            visualizer = v
            attachedSessionId = sessionId
            Log.i(TAG, "Attached to audio session $sessionId")
        } catch (t: Throwable) {
            // Missing runtime permission, OEM restriction, or an invalid session.
            Log.w(TAG, "Visualizer unavailable (${t.message}); using synthetic fallback")
            visualizer = null
            attachedSessionId = -1
        }
    }

    /** Stops capture and zeros the bars so a paused player looks quiet. */
    fun pause() {
        latest = QUIET
        runCatching { visualizer?.enabled = false }
    }

    /** Fully releases the platform Visualizer. */
    fun detach() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        attachedSessionId = -1
        latest = QUIET
    }

    /**
     * Deterministic fallback used when real capture is unavailable. Driven by the playback
     * position, not RNG, so seeking/pausing produces a stable, honest waveform.
     */
    fun synthetic(positionMs: Long, playing: Boolean): List<Float> {
        if (!playing) return QUIET
        val seconds = positionMs / 1000.0
        return List(16) { index ->
            val phase = seconds * 2.2 + index * 0.45
            val envelope = 0.35 + 0.35 * abs(sin(seconds * 0.9 + index * 0.2))
            (0.12 + envelope * abs(sin(phase))).toFloat().coerceIn(0.08f, 1f)
        }
    }

    private fun fftToBars(fft: ByteArray): List<Float> {
        // Magnitudes are stored as (real, imaginary) byte pairs; skip the DC bin.
        val bins = (fft.size / 2 - 1).coerceAtLeast(1)
        val perBar = (bins / 16).coerceAtLeast(1)
        return List(16) { bar ->
            var sum = 0.0
            var count = 0
            val start = 1 + bar * perBar
            val end = (start + perBar).coerceAtMost(bins + 1)
            var i = start
            while (i < end) {
                val real = fft[i * 2].toInt()
                val imaginary = fft[i * 2 + 1].toInt()
                sum += hypot(real.toDouble(), imaginary.toDouble())
                count++
                i++
            }
            val magnitude = if (count > 0) sum / count else 0.0
            (magnitude / 128.0).toFloat().coerceIn(0.06f, 1f)
        }
    }

    private fun waveformToBars(waveform: ByteArray): List<Float> {
        val perBar = (waveform.size / 16).coerceAtLeast(1)
        return List(16) { bar ->
            var peak = 0
            val start = bar * perBar
            val end = (start + perBar).coerceAtMost(waveform.size)
            var i = start
            while (i < end) {
                val value = abs(waveform[i].toInt())
                if (value > peak) peak = value
                i++
            }
            (peak / 128f).coerceIn(0.06f, 1f)
        }
    }

    private companion object {
        private const val TAG = "VisualizerManager"
        private val QUIET = List(16) { 0.08f }
    }
}
