package com.example.playback

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.domain.model.StreamInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PRIMARY SOURCE - headless YouTube stream sniffer.
 *
 * ## Why a WebView at all?
 * The pure HTTP approach (ask `youtubei/v1/player`, or scrape the watch page and decode
 * `signatureCipher`) is dead for anonymous clients. The watch page *does* still return
 * `playabilityStatus: OK` with `adaptiveFormats`, but the resulting `googlevideo` URLs
 * answer **403 Forbidden** because YouTube now requires the `n` (throttling) parameter
 * to be descrambled by their obfuscated JS. Only a real browser engine can do that.
 *
 * So we drive a genuine, invisible Chromium session: it runs YouTube's own player, and
 * the player itself asks the CDN for audio using fully-signed, n-transformed URLs. We
 * simply observe those requests and hand the URL to Media3/ExoPlayer.
 *
 * ## How it works
 *  1. A 0dp, `View.GONE` WebView (never attached to a window) loads
 *     `m.youtube.com/watch?v=<id>` with JavaScript enabled, a modern mobile
 *     User-Agent, and `mediaPlaybackRequiresUserGesture = false` so the player
 *     autoplays without a tap.
 *  2. `WebViewClient.shouldInterceptRequest` sees *every* network request the page
 *     makes - script, XHR, media segments, ads. It does two things:
 *       - **AD FILTER**: ad/tracker hosts are answered with an empty
 *         `WebResourceResponse`, so the request never reaches the network.
 *       - **AUDIO SNIFFER**: any `googlevideo.com/.../videoplayback?...mime=audio`
 *         request is the real audio stream. That URL is captured.
 *  3. The captured URL is handed to ExoPlayer **immediately** - nothing gates the capture,
 *     because blocking it on an extra asynchronous check is what made official songs fall
 *     through to the fallback and fail. The WebView is muted for the whole session and torn
 *     down as soon as a URL is captured, so it never becomes a second sound source.
 *     (The video id the page reports is logged, not enforced - see [SniffSession].)
 *
 * Every method that touches the WebView runs on the main thread; the interceptor
 * callback itself fires on a WebView worker thread, so it only touches thread-safe
 * primitives and never the WebView directly.
 *
 * Any failure (no WebView available, 8s window elapsed, watch page error, no audio request
 * seen) returns `null` and the caller falls through to the SoundCloud fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeWebViewSniffer(private val context: Context) {

    /**
     * Loads the YouTube watch page in a hidden WebView and returns the first real audio
     * stream URL it sees, or null on timeout / failure.
     *
     * Runs entirely on the main thread (WebView requirement) and always tears the
     * WebView down before returning, so nothing is leaked.
     */
    suspend fun sniff(videoId: String, timeoutMs: Long = SNIFF_TIMEOUT_MS): StreamInfo? =
        withContext(Dispatchers.Main) {
            if (videoId.isBlank()) return@withContext null

            val startedAt = SystemClock.elapsedRealtime()
            val session = SniffSession(context, videoId)
            try {
                session.start("$WATCH_URL$videoId")
                val captured = withTimeoutOrNull(timeoutMs) { session.awaitStream() }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (captured == null) {
                    Log.w(
                        TAG,
                        "Primary sniffer produced no stream for $videoId after ${elapsed}ms " +
                            "(window ${timeoutMs}ms) - SoundCloud fallback allowed"
                    )
                } else {
                    Log.i(TAG, "Primary sniffer resolved $videoId in ${elapsed}ms")
                }
                captured
            } catch (e: Exception) {
                Log.w(TAG, "Sniffer failed for $videoId: ${e.message}", e)
                null
            } finally {
                session.dispose()
            }
        }

    /**
     * One sniffing attempt: owns the WebView, the interceptor and the result future.
     * Created and destroyed per resolve so no YouTube page state leaks between tracks.
     */
    private class SniffSession(private val context: Context, private val requestedVideoId: String) {

        /** Completed with the captured stream, or never completed on timeout. */
        private val result = CompletableDeferred<StreamInfo?>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val disposed = AtomicBoolean(false)

        /** Only the first usable capture wins; later requests are ignored. */
        private val captureStarted = AtomicBoolean(false)

        private var webView: WebView? = null

        fun start(url: String) {
            val view = createWebView()
            webView = view
            view.loadUrl(url)
        }

        suspend fun awaitStream(): StreamInfo? = result.await()

        /** Tears the WebView down. Must be called on the main thread. */
        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            val view = webView ?: return
            webView = null
            try {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.webViewClient = WebViewClient()
                view.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "WebView teardown failed: ${e.message}")
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun createWebView(): WebView {
            val view = WebView(context)

            // Invisible: the browser must never be seen by the user.
            view.visibility = View.GONE
            view.layoutParams = ViewGroup.LayoutParams(0, 0)

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Autoplay without a user gesture - otherwise the player never starts
                // and no media request is ever made for us to sniff.
                mediaPlaybackRequiresUserGesture = false
                // We only care about media requests; skip pixels to save bandwidth.
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // A realistic mobile Chrome identity avoids the bot-detection wall.
                userAgentString = MOBILE_USER_AGENT
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAcceptThirdPartyCookies(view, true)
                }
            }

            view.webViewClient = object : WebViewClient() {

                /**
                 * Called for every outgoing request on a WebView worker thread.
                 *
                 * Returning null = "continue loading normally".
                 * Returning a response = "here is the body, do not hit the network".
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val host = request.url.host?.lowercase().orEmpty()

                    // ----- AD FILTER -------------------------------------------------
                    // Ad/tracker requests are short-circuited with an empty body: the
                    // page gets a 200-ish empty response instead of the ad creative, so
                    // nothing is fetched, nothing renders, nothing is paid for.
                    //
                    // NOTE: we deliberately do NOT block the whole `google.com` domain.
                    // YouTube's own player bootstrap depends on it, and breaking that
                    // would break the primary source entirely - a bad trade for an ad
                    // filter. Only the ad/telemetry sub-domains are blocked.
                    if (isAdHost(host)) {
                        return emptyResponse()
                    }

                    // ----- AUDIO SNIFFER ---------------------------------------------
                    // A googlevideo "videoplayback" request carrying `mime=audio` is the
                    // genuine, fully-signed audio stream the YouTube player just built.
                    // This is the moment we were waiting for: capture it and stop.
                    if (isAudioStreamRequest(host, url)) {
                        // Capture immediately. Gating the capture behind an asynchronous
                        // JavaScript round-trip (as the previous revision did) lost the
                        // stream entirely whenever that callback did not fire in time,
                        // which made every official song fall through to the fallback and
                        // fail with "no verified source found".
                        if (captureStarted.compareAndSet(false, true)) {
                            val stream = buildStreamInfo(url)
                            if (stream == null) {
                                captureStarted.set(false)
                            } else {
                                if (result.complete(stream)) {
                                    Log.i(
                                        TAG,
                                        "Captured audio stream " +
                                            "(${stream.mimeType ?: "unknown"}) for $requestedVideoId"
                                    )
                                    // The WebView must not stay in the playback path: stop
                                    // loading and let ExoPlayer own playback from here.
                                    mainHandler.post {
                                        runCatching { this@SniffSession.webView?.stopLoading() }
                                    }
                                    // Non-blocking sanity check, for diagnostics only: if the
                                    // page had drifted to another video (YouTube autoplay when
                                    // the requested one is unavailable) we want it in the log.
                                    mainHandler.post { logPlayingVideoId() }
                                } else {
                                    captureStarted.set(false)
                                }
                            }
                        }
                        // Let the media load normally. Answering it with an empty body (as
                        // the previous revision did) made the page's player error out and
                        // re-negotiate, which broke the capture path.
                        return null
                    }

                    // Everything else loads normally.
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Nudge the player to start. The autoplay setting usually suffices;
                    // this helps on pages where the <video> element needs an explicit
                    // play() call. Harmless if there is no media element yet.
                    nudgePlayer(view)
                    mainHandler.postDelayed({ nudgePlayer(view) }, NUDGE_RETRY_MS)
                }

                /**
                 * If the watch page itself cannot be loaded there is nothing to sniff, so
                 * hand over to the fallback immediately instead of burning the rest of the
                 * 8s window. Only main-frame failures count - sub-resource failures (an ad
                 * host we block on purpose, a tracker) are expected and harmless.
                 */
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame != true) return
                    Log.w(
                        TAG,
                        "Sniffer watch page failed to load (code ${error?.errorCode}) - " +
                            "resolving via fallback immediately"
                    )
                    result.complete(null)
                    mainHandler.post { runCatching { this@SniffSession.webView?.stopLoading() } }
                }
            }

            return view
        }

        private fun nudgePlayer(view: WebView?) {
            if (view == null || disposed.get()) return
            runCatching { view.evaluateJavascript(NUDGE_JS, null) }
        }

        /**
         * Diagnostics only: logs the video id the page's own player is on.
         *
         * This is deliberately **not** a gate. The capture is already committed by the time
         * this runs - gating it on this callback is what broke official-song playback - but
         * the line is invaluable evidence when a capture is suspected of belonging to the
         * wrong video (YouTube auto-advances when the requested video is unavailable).
         */
        private fun logPlayingVideoId() {
            mainHandler.post {
                val view = webView
                if (view == null || disposed.get()) return@post
                runCatching {
                    view.evaluateJavascript(CURRENT_VIDEO_ID_JS) { value ->
                        val reported = value
                            ?.trim()
                            ?.trim('"')
                            ?.takeIf { it.isNotEmpty() && it != "null" }
                            ?: return@evaluateJavascript
                        if (reported != requestedVideoId) {
                            Log.w(
                                TAG,
                                "WARNING: page reports '$reported' while " +
                                    "'$requestedVideoId' was requested (autoplay drift?)"
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------------
        // Matching + URL construction
        // ---------------------------------------------------------------------------------

        /**
         * True for the CDN request that actually carries the audio payload.
         * We match the host AND the path AND the `mime=audio` marker - a bare
         * "googlevideo.com" match would also catch video and manifest requests.
         */
        private fun isAudioStreamRequest(host: String, url: String): Boolean {
            if (!host.endsWith(GOOGLEVIDEO_HOST_SUFFIX)) return false
            val lower = url.lowercase()
            return lower.contains("videoplayback") && lower.contains("mime=audio")
        }

        /**
         * Converts the sniffed request URL into something ExoPlayer can stream from
         * start to finish.
         *
         * The browser asks for the audio in byte chunks, so its URL carries `range`,
         * `rn`, `rbuf`, `sq` (and, for SABR, `sabr`). Those parameters are *not* part of
         * the URL signature, so dropping them is safe and turns the chunk request back
         * into a full-file request. Everything else (notably the transformed `n` and the
         * `sig`) is preserved verbatim, because that is exactly what makes the URL work.
         */
        private fun buildStreamInfo(rawUrl: String): StreamInfo? {
            val playableUrl = stripChunkParameters(rawUrl)
            if (playableUrl.isBlank()) return null

            val lower = playableUrl.lowercase()
            val mimeType = when {
                lower.contains("mime=audio%2fmp4") || lower.contains("mime=audio/mp4") -> "audio/mp4"
                lower.contains("mime=audio%2fwebm") || lower.contains("mime=audio/webm") -> "audio/webm"
                else -> null
            }

            // The stream is bound to the cookie session the browser just established,
            // so hand that cookie to ExoPlayer along with a matching UA/Referer.
            val cookies = runCatching {
                CookieManager.getInstance().getCookie(YOUTUBE_ORIGIN)
            }.getOrNull()

            val headers = buildMap {
                put("User-Agent", MOBILE_USER_AGENT)
                put("Referer", "$YOUTUBE_ORIGIN/")
                put("Origin", YOUTUBE_ORIGIN)
                put("Accept-Language", "en-US,en;q=0.9")
                if (!cookies.isNullOrBlank()) put("Cookie", cookies)
            }

            return StreamInfo(url = playableUrl, mimeType = mimeType, headers = headers)
        }

        /**
         * Removes the byte-range/chunking query parameters while preserving the original
         * percent-encoding of every other value (a naive Uri rebuild would double-encode
         * the `n` and `sig` values and break the signature).
         */
        private fun stripChunkParameters(url: String): String {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return url

            val base = url.substring(0, queryStart)
            val kept = url.substring(queryStart + 1)
                .split('&')
                .filter { parameter ->
                    val key = parameter.substringBefore('=')
                    key.lowercase() !in DROPPED_QUERY_PARAMETERS
                }

            return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }

        /** Ad/tracker hosts that are answered with an empty body. */
        private fun isAdHost(host: String): Boolean =
            AD_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }

        private fun emptyResponse(): WebResourceResponse =
            WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0))
            )
    }

    companion object {
        private const val TAG = "YouTubeSniffer"

        /**
         * How long the headless WebView gets to reach the first audio request before the
         * SoundCloud fallback is allowed to proceed.
         *
         * This is a ceiling, not a wait: `awaitStream()` completes the moment an audio
         * request is captured, so a healthy sniff returns in a few hundred ms. The window
         * was widened from 5s to 8s on request so slow cold-start WebViews on mobile
         * networks are not cut off prematurely.
         */
        const val SNIFF_TIMEOUT_MS = 8_000L

        private const val NUDGE_RETRY_MS = 1_500L

        /**
         * Reads the id of the video the page's own player is currently on. Used **only**
         * for the diagnostic line in [SniffSession.logPlayingVideoId]: if YouTube has
         * auto-advanced to a different video we want that visible in the log. It never
         * decides whether a capture is used - that would reintroduce the bug where a
         * missing/blocked JS callback discarded an otherwise good stream.
         */
        private const val CURRENT_VIDEO_ID_JS = """
            (function() {
              try {
                var p = document.getElementById('movie_player');
                if (p && p.getVideoData) {
                  var d = p.getVideoData() || {};
                  return d.video_id || '';
                }
                return '';
              } catch (e) { return ''; }
            })();
        """

        private const val WATCH_URL = "https://m.youtube.com/watch?v="
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        private const val GOOGLEVIDEO_HOST_SUFFIX = "googlevideo.com"

        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        /**
         * Chunk/range parameters the browser adds to media requests. None of them are
         * covered by the URL signature, so dropping them is safe.
         */
        private val DROPPED_QUERY_PARAMETERS = setOf("range", "rn", "rbuf", "sq", "sabr")

        /**
         * Ad and telemetry hosts. Everything here is either an ad creative, an ad
         * tracking pixel or an ad auction endpoint - none of it is needed to play audio.
         *
         * (We intentionally leave `google.com`, `youtube.com`, `ytimg.com` and
         * `gstatic.com` alone: the player needs them.)
         */
        private val AD_HOST_SUFFIXES = listOf(
            "doubleclick.net",
            "googleads.g.doubleclick.net",
            "static.doubleclick.net",
            "googlesyndication.com",
            "pagead2.googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "ads.youtube.com",
            "imasdk.googleapis.com",
            "s0.2mdn.net",
            "2mdn.net",
            "moatads.com",
            "googletagservices.com",
            "googletagmanager.com",
            "google-analytics.com",
            "ssl.google-analytics.com",
            "scorecardresearch.com",
            "ad.doubleclick.net"
        )

        /**
         * Forces the page's player to start without a user gesture and keeps the page
         * silent.
         *
         * Muting is essential: the headless WebView is a real player, and ExoPlayer owns
         * playback from the moment a stream is captured. Muting does not stop the media
         * request we are waiting for - YouTube still fetches the audio track.
         */
        private const val NUDGE_JS = """            (function() {
              try {
                var p = document.getElementById('movie_player');
                if (p) {
                  if (p.mute) { p.mute(); }
                  if (p.playVideo) { p.playVideo(); }
                }
                var vids = document.getElementsByTagName('video');
                for (var i = 0; i < vids.length; i++) {
                  vids[i].muted = true;
                  if (vids[i].play) { try { vids[i].play(); } catch (e) {} }
                }
                var auds = document.getElementsByTagName('audio');
                for (var j = 0; j < auds.length; j++) { auds[j].muted = true; }
              } catch (e) {}
            })();
        """
    }
}
