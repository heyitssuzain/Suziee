package com.example.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.data.remote.extraction.SecureExtractionEngine

/**
 * Lifecycle host for the headless extraction engine.
 *
 * The engine's WebView pool lives in [SecureExtractionEngine] (an app-scoped
 * singleton) and works whether or not this service is running. This service exists purely
 * as an **optional optimisation**: starting it while the app is in the foreground asks for
 * a warm engine, so the first song the user taps does not pay for Chromium instantiation
 * on the critical path.
 *
 * It is deliberately *not* a foreground service and never posts a notification: it does no
 * user-visible work, holds no wake lock, and stops itself as soon as no extraction is in
 * flight (see [IDLE_STOP_MS]) - so it cannot keep the process alive or drain the battery.
 */
class ExtractionService : Service() {

    private lateinit var repository: SecureExtractionEngine
    private val handler = Handler(Looper.getMainLooper())

    private val idleStop = Runnable {
        if (repository.leaseCount() == 0) {
            Log.i(TAG, "No extraction in ${IDLE_STOP_MS}ms - stopping the pre-warm service")
            stopSelf()
        } else {
            rescheduleIdleStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SecureExtractionEngine.get(this)
        repository.warmUp()
        rescheduleIdleStop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        repository.warmUp()
        rescheduleIdleStop()
        // Never restart on its own: a killed warm-up is not worth resurrecting a process for.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        super.onDestroy()
    }

    private fun rescheduleIdleStop() {
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    companion object {
        private const val TAG = "ExtractionService"

        /** How long the warm pool is worth keeping the service alive after the last use. */
        private const val IDLE_STOP_MS = 90_000L

        /**
         * Asks for a warm extraction engine. Idempotent and completely safe to call often:
         * a failure to start (e.g. a background-start restriction on some OEM build) is
         * logged and otherwise ignored - extraction still works, it just starts cold.
         */
        fun ensureStarted(context: Context) {
            try {
                context.startService(Intent(context, ExtractionService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Pre-warm service not allowed here: ${e.message}")
            }
        }
    }
}
