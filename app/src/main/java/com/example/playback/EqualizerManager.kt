package com.example.playback

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Wraps the platform [Equalizer] for the active ExoPlayer audio session.
 *
 * Devices expose different band counts (commonly 5, sometimes 10), so the UI always reads
 * the real band count and level range from the effect instead of hard-coding a layout.
 */
class EqualizerManager {

    @Volatile
    private var equalizer: Equalizer? = null

    @Volatile
    private var attachedSessionId: Int = -1

    val isAvailable: Boolean get() = equalizer != null

    fun attach(sessionId: Int) {
        if (sessionId <= 0) return
        if (sessionId == attachedSessionId && equalizer != null) return
        release()
        try {
            val eq = Equalizer(0, sessionId)
            eq.enabled = true
            equalizer = eq
            attachedSessionId = sessionId
            Log.i(TAG, "Equalizer attached (${eq.numberOfBands} bands)")
        } catch (t: Throwable) {
            // Some OEMs disable audio effects for certain sessions.
            Log.w(TAG, "Equalizer unavailable: ${t.message}")
            equalizer = null
            attachedSessionId = -1
        }
    }

    fun bandCount(): Int = runCatching { equalizer?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)

    fun bandLevelRange(): Pair<Short, Short>? {
        val range = runCatching { equalizer?.bandLevelRange }.getOrNull() ?: return null
        if (range.size < 2) return null
        return range[0] to range[1]
    }

    fun centerFreq(band: Short): Int =
        runCatching { (equalizer?.getCenterFreq(band) ?: 0) / 1000 }.getOrDefault(0)

    fun bandLevels(): List<Short> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()) }
        }.getOrDefault(emptyList())
    }

    fun setBandLevel(band: Short, level: Short) {
        runCatching { equalizer?.setBandLevel(band, level) }
    }

    fun presetNames(): List<String> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
        }.getOrDefault(emptyList())
    }

    fun applyPreset(index: Short) {
        runCatching { equalizer?.usePreset(index) }
    }

    fun setEnabled(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
    }

    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
        attachedSessionId = -1
    }

    private companion object {
        private const val TAG = "EqualizerManager"
    }
}
