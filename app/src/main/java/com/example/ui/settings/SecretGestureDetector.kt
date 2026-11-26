package com.example.ui.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gesture modifiers behind the private dedication in Settings (PR-1).
 *
 * The gesture is deliberately awkward to trigger by accident — a two-finger **two-second**
 * hold — while still being easy on purpose. It is also the only way to reach the hidden
 * entry, so nothing in the composition, the strings or the logs ever says it exists.
 */

/** How long the user gets to place the second finger after the first one goes down. */
private const val SECOND_FINGER_WINDOW_MS = 400L

/** The hold required by the easter egg. */
private const val TWO_FINGER_HOLD_MS = 2_000L

/** Triple-tap window: the equally valid, spec'd alternative to the two-finger hold. */
private const val TRIPLE_TAP_WINDOW_MS = 2_000L

/**
 * Fires [onTrigger] once when **two fingers are held together for [holdDurationMs]**.
 *
 * Sequence: the first finger goes down inside this component, a second distinct pointer
 * lands within [SECOND_FINGER_WINDOW_MS], and both stay down for the full hold. Any early
 * lift cancels the gesture silently, which is what makes it feel like a deliberate
 * handshake rather than a stray tap.
 *
 * Implemented purely with stable Compose Foundation gesture APIs — no experimental pointer
 * opts and no raw `MotionEvent` plumbing.
 */
@Composable
fun Modifier.twoFingerLongPress(
    holdDurationMs: Long = TWO_FINGER_HOLD_MS,
    onTrigger: () -> Unit
): Modifier {
    val latest = rememberUpdatedState(onTrigger)

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val firstDown: PointerInputChange = awaitFirstDown(requireUnconsumed = false)

            var secondDown: PointerInputChange? = null
            val windowStart = System.currentTimeMillis()
            while (secondDown == null) {
                val remaining = SECOND_FINGER_WINDOW_MS - (System.currentTimeMillis() - windowStart)
                if (remaining <= 0L) break
                val event = withTimeoutOrNull(remaining) {
                    awaitPointerEvent(PointerEventPass.Main)
                } ?: break
                secondDown = event.changes.firstOrNull { change ->
                    change.pressed && change.id != firstDown.id
                }
            }

            if (secondDown == null) return@awaitEachGesture

            val holdStart = System.currentTimeMillis()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { change -> change.pressed } < 2) {
                    return@awaitEachGesture
                }
                if (System.currentTimeMillis() - holdStart >= holdDurationMs) {
                    latest.value()
                    return@awaitEachGesture
                }
            }
        }
    }
}

/** Mutable tap-bookkeeping that survives recomposition of the caller. */
private class TapCounter {
    var count: Int = 0
    var firstTapAt: Long = 0L

    fun register(now: Long, windowMs: Long, required: Int): Boolean {
        count = if (count == 0 || now - firstTapAt > windowMs) 1 else count + 1
        firstTapAt = now
        if (count >= required) {
            count = 0
            return true
        }
        return false
    }
}

/**
 * Fires [onTrigger] after [requiredTaps] taps land inside [windowMs].
 *
 * The companion to [twoFingerLongPress]: the same About section accepts either one, so the
 * secret stays reachable whether the person holds with two fingers or taps three times.
 */
@Composable
fun Modifier.tripleTapWithin(
    requiredTaps: Int = 3,
    windowMs: Long = TRIPLE_TAP_WINDOW_MS,
    onTrigger: () -> Unit
): Modifier {
    val latest = rememberUpdatedState(onTrigger)
    val counter = androidx.compose.runtime.remember { TapCounter() }

    return this.pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                if (counter.register(System.currentTimeMillis(), windowMs, requiredTaps)) {
                    latest.value()
                }
            }
        )
    }
}