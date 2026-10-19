package com.example.data.remote.ytdlp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.domain.model.StreamInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * PRIMARY online-audio engine: yt-dlp running on the device through youtubedl-android.
 *
 * yt-dlp owns the parts that kept breaking in the old scraper: YouTube's player
 * signature changes and the `n` parameter. The bundled build of yt-dlp already contains
 * a QuickJS runtime for that (0.18.x), so extraction does not depend on a WebView.
 *
 * ## Speed choices
 *  * `-f 251/140/bestaudio` selects a single, progressive audio-only stream (Opus WebM
 *    first, AAC/MP4 second) - no video is downloaded and no muxing/ffmpeg is involved.
 *  * `--get-url` + `--skip-download` resolves the URL without writing media to disk and
 *    without parsing a large JSON document.
 *  * `--no-playlist` prevents a playlist id from turning one tap into a multi-item job.
 *  * `--socket-timeout 5` plus a hard watchdog bound the worst case; the watchdog kills
 *    the child process, so a dead network cannot leave a Python process behind.
 *  * Initialization (unpacking the bundled Python runtime) runs once on a background
 *    thread. It is awaited, never repeated, and a failure is remembered so it cannot
 *    delay every track.
 *
 * ## Self-healing
 * YouTube breaks old extractors within weeks. When an extraction fails, a later
 * background update (at most once a week, never while another extraction is running)
 * fetches the current yt-dlp nightly from GitHub. The current song still falls back to
 * the WebView engine immediately - the update only improves the next attempt.
 *
 * This object is stateless with respect to the UI: it holds no Activity, no player and
 * no WebView, only the application context needed for yt-dlp's own storage.
 */
object SuzieeAudioExtractor {

    private const val TAG = "SuzieeAudioExtractor"

    /** Hard upper bound for one resolve; yt-dlp's own socket timeout is 5s. */
    private const val EXTRACTION_TIMEOUT_MS = 7_000L

    /**
     * Opus (251) first: best quality/size for streaming. AAC (140) is the broadly
     * compatible fallback, then whatever yt-dlp considers the best audio-only stream.
     */
    private const val FORMAT_SELECTOR = "251/140/bestaudio"

    /** yt-dlp nightly builds track YouTube changes faster than the stable channel. */
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val UPDATE_DELAY_MS = 3_000L
    private const val PREFS = "suziee_ytdlp"
    private const val KEY_LAST_UPDATE_MS = "last_update_ms"

    /** Serialises one-time initialization only; extraction itself runs concurrently. */
    private val initLock = Mutex()

    @Volatile
    private var initialized = false

    /** True once init failed; retrying on every track would only add delay. */
    @Volatile
    private var initFailed = false

    /** How many yt-dlp child processes are in flight (used to avoid updating under them). */
    private val activeExtractions = AtomicInteger(0)

    /**
     * App-scoped scope for initialization, watchdogs and the weekly binary update.
     * Deliberately independent of the caller's job: a cancelled resolve must not cancel
     * the process kill switch, and an update must survive a track change.
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts the one-time yt-dlp initialization off the main thread.
     *
     * Called from [com.example.SuzieeApplication] so the first tap does not pay for the
     * Python runtime extraction. Safe to call repeatedly.
     */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        maintenanceScope.launch {
            ensureInitialized(appContext)
        }
    }

    /**
     * Resolves [videoId] to a direct, playable audio URL.
     *
     * @param timeoutMs hard ceiling; enforced by killing the yt-dlp process.
     * @return the stream, or null when yt-dlp is unavailable, times out or produces
     * nothing usable. The caller then uses the WebView fallback.
     */
    suspend fun getFastAudioUrl(
        context: Context,
        videoId: String,
        timeoutMs: Long = EXTRACTION_TIMEOUT_MS
    ): StreamInfo? {
        // Never build a yt-dlp URL from an arbitrary string: only a real video id.
        if (!YtDlpStreamParser.isVideoId(videoId)) {
            Log.w(TAG, "Not a YouTube video id - skipping yt-dlp: $videoId")
            return null
        }

        val appContext = context.applicationContext
        if (!ensureInitialized(appContext)) return null

        activeExtractions.incrementAndGet()
        val startedAt = SystemClock.elapsedRealtime()
        val stream = try {
            withContext(Dispatchers.IO) {
                extract(videoId, timeoutMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "yt-dlp extraction failed for $videoId: ${e.message}")
            null
        } finally {
            activeExtractions.decrementAndGet()
        }

        if (stream == null) {
            Log.w(
                TAG,
                "yt-dlp produced no stream for $videoId after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            scheduleBinaryUpdate(appContext)
        }
        return stream
    }

    /**
     * The yt-dlp invocation itself. Runs on an IO thread and blocks there, which is why
     * the watchdog lives in [maintenanceScope] instead of the caller's coroutine.
     */
    private fun extract(videoId: String, timeoutMs: Long): StreamInfo? {
        val request = YoutubeDLRequest(YtDlpStreamParser.watchUrl(videoId)).apply {
            addOption("-f", FORMAT_SELECTOR)
            // Print the resolved URL only. This is intentionally not --dump-json: the
            // JSON parser in youtubedl-android depends on Jackson at runtime only, and a
            // single URL is all ExoPlayer needs.
            addOption("--get-url")
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "5")
            addOption("--extractor-retries", "1")
        }

        // Must be unique: youtubedl-android rejects a process id that is already running.
        val processId = "suziee-$videoId-${UUID.randomUUID()}"
        val startedAt = SystemClock.elapsedRealtime()

        val watchdog = maintenanceScope.launch {
            delay(timeoutMs)
            val killed = runCatching {
                YoutubeDL.getInstance().destroyProcessById(processId)
            }.getOrDefault(false)
            if (killed) {
                Log.w(TAG, "yt-dlp exceeded ${timeoutMs}ms for $videoId - process killed")
            }
        }

        return try {
            val response = YoutubeDL.getInstance().execute(request, processId)
            // `--no-playlist` + a single-format selector prints exactly one URL. Take the
            // first non-blank line; the parser then validates it is HTTPS googlevideo.
            val reportedUrl = response.out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }

            val stream = YtDlpStreamParser.toStreamInfo(
                url = reportedUrl,
                formatId = null,
                extension = null,
                headers = null
            )
            if (stream == null) {
                Log.w(TAG, "yt-dlp returned no playable URL for $videoId (out=${reportedUrl?.take(80)})")
            } else {
                Log.i(
                    TAG,
                    "yt-dlp resolved $videoId in ${SystemClock.elapsedRealtime() - startedAt}ms " +
                        "(mime=${stream.mimeType ?: "sniffed"})"
                )
            }
            stream
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // YoutubeDLException carries yt-dlp's stderr; the message is enough here and
            // avoids dumping a stack trace on every blocked/bot-checked attempt.
            Log.w(TAG, "yt-dlp failed for $videoId: ${e.message}")
            null
        } finally {
            watchdog.cancel()
            // Harmless if the process already exited; guarantees no orphan.
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
    }

    private suspend fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        return initLock.withLock {
            if (initialized) return@withLock true
            if (initFailed) return@withLock false

            withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    YoutubeDL.getInstance().init(context)
                    initialized = true
                    Log.i(
                        TAG,
                        "yt-dlp core initialized in " +
                            "${SystemClock.elapsedRealtime() - startedAt}ms " +
                            "(version=${YoutubeDL.version(context) ?: "bundled"})"
                    )
                    true
                } catch (e: Throwable) {
                    // The app still works: the headless WebView is a complete fallback.
                    initFailed = true
                    Log.e(TAG, "yt-dlp initialization failed - WebView fallback remains", e)
                    false
                }
            }
        }
    }

    /**
     * Best-effort weekly binary refresh. Never on the critical path and never while an
     * extraction is running (the updater replaces the executable on disk).
     */
    private fun scheduleBinaryUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_UPDATE_MS, 0L) < UPDATE_INTERVAL_MS) return

        maintenanceScope.launch {
            delay(UPDATE_DELAY_MS)
            if (activeExtractions.get() > 0) {
                Log.i(TAG, "Skipping yt-dlp update - an extraction is still running")
                return@launch
            }
            prefs.edit().putLong(KEY_LAST_UPDATE_MS, now).apply()
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    context,
                    YoutubeDL.UpdateChannel.NIGHTLY
                )
                Log.i(
                    TAG,
                    "yt-dlp binary update: $status in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            } catch (e: Throwable) {
                Log.w(TAG, "yt-dlp binary update failed (kept the bundled build): ${e.message}")
            }
        }
    }
}
