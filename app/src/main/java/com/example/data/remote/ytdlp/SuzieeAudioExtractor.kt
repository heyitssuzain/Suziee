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
 *  * `--socket-timeout 10` plus a hard per-attempt watchdog bound the worst case; the
 *    watchdog kills the child process, so a dead network cannot leave a Python process
 *    behind.
 *  * `--force-ipv4` avoids the IPv6 route that used to stall the watch-page request for
 *    ~40 seconds on dual-stack mobile networks before yt-dlp fell back to IPv4.
 *  * Two clients are tried (`android_vr`, then yt-dlp's default list); either one is
 *    enough to produce a direct audio URL.
 *  * Initialization (unpacking the bundled Python runtime) runs once on a background
 *    thread. It is awaited, and a failure is retried after a minute rather than
 *    disabling yt-dlp for the life of the process.
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

    /** Hard upper bound for one yt-dlp client attempt. */
    private const val EXTRACTION_TIMEOUT_MS = 12_000L

    /**
     * Opus (251) first: best quality/size for streaming. AAC (140) is the broadly
     * compatible fallback, then whatever yt-dlp considers the best audio-only stream.
     */
    private const val FORMAT_SELECTOR = "251/140/bestaudio"

    /**
     * The Android-VR client returns direct `googlevideo` URLs without the SABR-only
     * payload the web clients now hand out, and does so with the fewest round trips.
     * `null` means "let yt-dlp pick its default client list", used as the second attempt
     * so a removed/renamed client can never leave the app with no path at all.
     */
    private const val ANDROID_VR_CLIENT = "android_vr"

    /** yt-dlp nightly builds track YouTube changes faster than the stable channel. */
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val UPDATE_DELAY_MS = 3_000L

    /** Retry a failed initialization after this long instead of disabling yt-dlp forever. */
    private const val INIT_RETRY_AFTER_MS = 60_000L
    private const val PREFS = "suziee_ytdlp"
    private const val KEY_LAST_UPDATE_MS = "last_update_ms"

    /** Serialises one-time initialization only; extraction itself runs concurrently. */
    private val initLock = Mutex()

    @Volatile
    private var initialized = false

    /**
     * When initialization last failed, or `0` if it has not failed. A transient failure
     * (storage pressure while unpacking Python, for example) is retried after
     * [INIT_RETRY_AFTER_MS] instead of disabling yt-dlp for the life of the process.
     */
    @Volatile
    private var initFailedAtMs = 0L

    /** Read-only diagnostic used in failure logs; not a control-flow flag. */
    val isInitialized: Boolean get() = initialized

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
     *
     * Two client attempts, each with its own process and watchdog:
     *  1. `android_vr` - fastest and returns direct URLs (no SABR);
     *  2. yt-dlp's default client list - the safety net if the first client is blocked,
     *     renamed or the video is only offered to another client.
     */
    private fun extract(videoId: String, timeoutMs: Long): StreamInfo? {
        val clients = listOf(ANDROID_VR_CLIENT, null)
        clients.forEachIndexed { index, client ->
            val stream = extractOnce(videoId, timeoutMs, client)
            if (stream != null) return stream
            if (index < clients.lastIndex) {
                Log.i(
                    TAG,
                    "yt-dlp client '${client ?: "default"}' produced nothing for $videoId - " +
                        "retrying with ${clients[index + 1] ?: "default"} clients"
                )
            }
        }
        return null
    }

    /** One yt-dlp process for one player client; null when it produced no playable URL. */
    private fun extractOnce(videoId: String, timeoutMs: Long, playerClient: String?): StreamInfo? {
        val request = YoutubeDLRequest(YtDlpStreamParser.watchUrl(videoId)).apply {
            addOption("-f", FORMAT_SELECTOR)
            // Print the resolved URL only. This is intentionally not --dump-json: the
            // JSON parser in youtubedl-android depends on Jackson at runtime only, and a
            // single URL is all ExoPlayer needs.
            addOption("--get-url")
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--no-warnings")
            // A broken IPv6 route used to stall the watch-page request for ~40s before
            // yt-dlp fell back to IPv4. The watchdog then killed it and every song looked
            // "unplayable" even though the IPv4 path resolves in seconds.
            addOption("--force-ipv4")
            addOption("--socket-timeout", "10")
            addOption("--extractor-retries", "1")
            if (playerClient != null) {
                addOption("--extractor-args", "youtube:player_client=$playerClient")
            }
        }

        // Must be unique: youtubedl-android rejects a process id that is already running.
        val processId = "suziee-$videoId-${playerClient ?: "default"}-${UUID.randomUUID()}"
        val startedAt = SystemClock.elapsedRealtime()

        val watchdog = maintenanceScope.launch {
            delay(timeoutMs)
            val killed = runCatching {
                YoutubeDL.getInstance().destroyProcessById(processId)
            }.getOrDefault(false)
            if (killed) {
                Log.w(
                    TAG,
                    "yt-dlp ($playerClient) exceeded ${timeoutMs}ms for $videoId - process killed"
                )
            }
        }

        Log.i(TAG, "yt-dlp attempt client=${playerClient ?: "default"} video=$videoId")
        return try {
            val response = YoutubeDL.getInstance().execute(request, processId)
            // `--no-playlist` + a single-format selector prints exactly one URL. Scan the
            // whole stream anyway: a stray notice must not hide the URL on the next line.
            val reportedUrl = YtDlpStreamParser.pickStreamUrl(response.out)

            val stream = YtDlpStreamParser.toStreamInfo(
                url = reportedUrl,
                formatId = null,
                extension = null,
                headers = null
            )
            if (stream == null) {
                Log.w(
                    TAG,
                    "yt-dlp ($playerClient) returned no playable URL for $videoId " +
                        "(out=${response.out.take(160).replace('\n', ' ')} " +
                        "err=${response.err.take(240).replace('\n', ' ')})"
                )
            } else {
                Log.i(
                    TAG,
                    "yt-dlp ($playerClient) resolved $videoId in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms " +
                        "(mime=${stream.mimeType ?: "sniffed"})"
                )
            }
            stream
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // YoutubeDLException carries yt-dlp's stderr; the message is enough here and
            // avoids dumping a stack trace on every blocked/bot-checked attempt.
            Log.w(TAG, "yt-dlp ($playerClient) failed for $videoId: ${e.message}")
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
            val failedAt = initFailedAtMs
            if (failedAt != 0L &&
                SystemClock.elapsedRealtime() - failedAt < INIT_RETRY_AFTER_MS
            ) {
                return@withLock false
            }

            withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    YoutubeDL.getInstance().init(context)
                    initialized = true
                    initFailedAtMs = 0L
                    Log.i(
                        TAG,
                        "yt-dlp core initialized in " +
                            "${SystemClock.elapsedRealtime() - startedAt}ms " +
                            "(version=${YoutubeDL.version(context) ?: "bundled"})"
                    )
                    true
                } catch (e: Throwable) {
                    // The app still works: the headless WebView is a complete fallback.
                    initFailedAtMs = SystemClock.elapsedRealtime()
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
