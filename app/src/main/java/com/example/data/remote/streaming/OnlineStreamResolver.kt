package com.example.data.remote.streaming

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.data.remote.extraction.SecureExtractionEngine
import com.example.data.remote.ytdlp.SuzieeAudioExtractor
import com.example.data.remote.ytdlp.YtDlpStreamParser
import com.example.domain.model.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which engine produced the stream that is currently loaded. */
enum class StreamSource {
    NONE,

    /** Resolved by yt-dlp (the primary engine). */
    YTDLP,

    /** Captured by the headless WebView running YouTube's own player (fallback). */
    WEBVIEW,

    /** An already-downloaded local file; no network resolution happened. */
    LOCAL
}

/** A stream plus the engine that produced it. */
data class ResolvedStream(val info: StreamInfo, val source: StreamSource)

/**
 * The single place that turns a YouTube video id into playable audio.
 *
 * Two independent engines, both resolving the **same** video id:
 *
 *  1. **[SuzieeAudioExtractor]** (yt-dlp) - fast, no WebView, handles signatures and the
 *     `n` parameter. It is always tried first.
 *  2. **[SecureExtractionEngine]** (headless WebView) - a real YouTube browser session.
 *     It is started only if yt-dlp has not produced a stream after [HEDGE_DELAY_MS], so a
 *     healthy yt-dlp resolve costs no WebView traffic at all. Once started, both run in
 *     parallel and the **first usable stream wins**, which removes the old "wait for the
 *     primary to fail, then start the fallback" double wait.
 *
 * The engines are raced in an app-scoped scope rather than a `coroutineScope` of the
 * caller: a losing yt-dlp process must not keep the winning result waiting, and a new
 * song must be able to cancel the previous race immediately.
 */
class OnlineStreamResolver(
    context: Context,
    private val engine: SecureExtractionEngine = SecureExtractionEngine.get(context)
) {
    private val appContext = context.applicationContext

    /** Detached from any screen/playback job, but cancelled where it matters (see race). */
    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param hedgeDelayMs how long yt-dlp gets before the WebView fallback is started.
     *   Pass `0` for a failover, where the previous stream is already dead and waiting
     *   would only add latency.
     */
    suspend fun resolve(
        videoId: String,
        hedgeDelayMs: Long = HEDGE_DELAY_MS
    ): ResolvedStream? {
        if (!YtDlpStreamParser.isVideoId(videoId)) {
            Log.w(TAG, "Refusing to resolve a non-YouTube id: $videoId")
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        // Capacity 2: both engines always produce exactly one result, and trySend never
        // suspends, so a closed channel cannot strand a child coroutine.
        val results = Channel<Pair<StreamSource, StreamInfo?>>(2)

        val ytDlpJob: Job = raceScope.launch {
            val stream = try {
                SuzieeAudioExtractor.getFastAudioUrl(appContext, videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "yt-dlp engine failed: ${e.message}")
                null
            }
            results.trySend(StreamSource.YTDLP to stream)
        }

        val webViewJob: Job = raceScope.launch {
            val stream = try {
                if (hedgeDelayMs > 0L) delay(hedgeDelayMs)
                Log.i(TAG, "Starting WebView fallback for $videoId after ${hedgeDelayMs}ms")
                engine.resolve(videoId, timeoutMs = WEBVIEW_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "WebView fallback failed: ${e.message}")
                null
            }
            results.trySend(StreamSource.WEBVIEW to stream)
        }

        return try {
            var ytDlpDone = false
            var webViewDone = false

            while (!(ytDlpDone && webViewDone)) {
                val (source, stream) = results.receive()
                if (source == StreamSource.YTDLP) ytDlpDone = true else webViewDone = true

                if (stream != null) {
                    val winner = ResolvedStream(stream, source)
                    Log.i(
                        TAG,
                        "${source} won the race for $videoId in " +
                            "${SystemClock.elapsedRealtime() - startedAt}ms " +
                            "(mime=${stream.mimeType ?: "sniffed"})"
                    )
                    return winner
                }
            }

            Log.w(
                TAG,
                "Neither engine produced a stream for $videoId after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "(yt-dlp initialized=${SuzieeAudioExtractor.isInitialized})"
            )
            null
        } catch (e: CancellationException) {
            throw e
        } finally {
            // Stop the loser. Cancelling a completed job is a no-op; a still-running
            // WebView extraction tears itself down through its structured flow.
            ytDlpJob.cancel()
            webViewJob.cancel()
            results.close()
        }
    }

    companion object {
        private const val TAG = "OnlineStreamResolver"

        /**
         * How long yt-dlp gets before the WebView fallback is started in parallel.
         *
         * A healthy yt-dlp resolve is usually faster than this, so the fallback never
         * runs. When yt-dlp is slow or blocked, the fallback is already working by the
         * time the primary gives up.
         */
        const val HEDGE_DELAY_MS = 2_000L

        /**
         * How long the WebView fallback gets once it starts.
         *
         * It is a ceiling, not a wait: extraction completes as soon as YouTube's own
         * player makes the audio request. 8s was too tight on a cold mobile page load
         * (Chromium start + consent + player boot + media request), which is why the
         * fallback used to time out on every attempt.
         */
        const val WEBVIEW_TIMEOUT_MS = 15_000L
    }
}
