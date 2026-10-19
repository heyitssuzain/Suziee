package com.example.data.remote.extraction

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.domain.model.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Terminal outcome of one extraction attempt.
 *
 * Exactly one of these is emitted per [SecureExtractionEngine.extractionEvents]
 * collection, after which the flow completes.
 */
sealed interface ExtractionEvent {

    /**
     * The authoritative result: the fully-signed `googlevideo` audio request the page's own
     * player just made, and which the engine verified belongs to the requested video. Its
     * `n` parameter has been transformed by YouTube's own JavaScript, which is what makes it
     * play without throttling. Highest confidence.
     */
    data class MediaCaptured(val stream: StreamInfo) : ExtractionEvent

    /**
     * A lower-confidence result: an audio-only format taken from the page's own player
     * response (`streamingData.adaptiveFormats`, parsed by the in-page V8 hook). Its URL is
     * session-bound and correct, but its `n` parameter has not necessarily been transformed
     * yet, so it may be throttled. Only used when the signed capture did not arrive inside
     * [SecureExtractionEngine.EARLY_CANDIDATE_GRACE_MS].
     */
    data class EarlyCandidate(val stream: StreamInfo) : ExtractionEvent

    /** The watch page itself could not load, so there is nothing to extract. */
    data class Failed(val reason: String) : ExtractionEvent
}

/**
 * The object exposed to the page as `window.SuzieeBridge`.
 *
 * Kept as its own public-facing class (rather than an anonymous object) so the
 * `@JavascriptInterface` methods are unambiguously reflectable, and so nothing but narrow
 * string sinks is ever reachable from page script.
 *
 * Every call carries the **transaction token** of the extraction session whose hook script
 * was injected into the document. A call bearing a different token came from a document
 * this session does not own - typically a pooled WebView's previous page whose timer is
 * still alive for a few milliseconds during the swap - and is dropped before it can touch
 * this session. That is the "transaction token pattern": one request, one token, and no
 * out-of-order track may ever load.
 */
internal class ExtractionBridge(
    private val transactionId: String,
    private val formatsSink: (videoId: String, payload: String) -> Unit,
    private val videoIdSink: (String) -> Unit
) {
    /**
     * Called by the in-page hook with the video id of the parsed player response and the
     * line-delimited `itag|mime|bitrate|length|url` records. The id may be empty when the
     * response does not carry one - in that case the payload is still used, because missing
     * metadata must never be treated as a mismatch.
     */
    @JavascriptInterface
    fun onFormats(transactionId: String?, videoId: String?, payload: String?) {
        if (transactionId != this.transactionId) return
        val body = payload ?: return
        runCatching { formatsSink(videoId.orEmpty(), body) }
    }

    /** Called by the in-page hook on every tick with the id the player is currently on. */
    @JavascriptInterface
    fun onVideoId(transactionId: String?, videoId: String?) {
        if (transactionId != this.transactionId) return
        val id = videoId ?: return
        runCatching { videoIdSink(id) }
    }
}

/**
 * PRIMARY SOURCE - pre-warmed, headless extraction engine.
 *
 * ## Why a real browser engine is required
 * The pure HTTP route (call `youtubei/v1/player`, or scrape the watch page and decode
 * `signatureCipher`) cannot produce a playable URL for an anonymous client: the returned
 * `googlevideo` URLs are rejected/throttled until YouTube's own obfuscated JavaScript has
 * transformed the `n` parameter. Only a real Chromium session can do that - so we run
 * YouTube's player for real, invisibly, and take the URL it builds.
 *
 * ## Two capture paths, one engine
 *  1. **Signed media capture (authoritative).** `WebViewClient.shouldInterceptRequest`
 *     sees every request the page makes. The first
 *     `googlevideo.com/.../videoplayback?...mime=audio` request *is* the finished,
 *     `n`-transformed audio stream. It is captured immediately and handed to ExoPlayer -
 *     **after** it has been validated against the video the page reports playing, so a page
 *     that drifted to another video (bot interstitial, region block, autoplay) can never
 *     hand ExoPlayer a different song.
 *  2. **V8 player-response hook (early, lower confidence).** A JavaScript payload is
 *     injected into the page; it wraps `window.fetch` and `XMLHttpRequest` to observe
 *     `/youtubei/v1/player` responses (and polls the player's own retained response
 *     object), parses `streamingData.adaptiveFormats` *inside the V8 engine*, and pushes
 *     only the audio-only entries over a JavaScript bridge. Itag 251 (Opus 160 kbps) is
 *     preferred, then itag 140 (AAC 128 kbps). Responses that belong to a different video
 *     are never published at all.
 *
 * Path 2 normally lands first. The session still waits
 * [EARLY_CANDIDATE_GRACE_MS] for path 1, because path 1 is the one that is guaranteed to
 * be un-throttled; only if the signed capture has not arrived by then is the early
 * candidate used, which shortens the failure path considerably. If ExoPlayer still cannot
 * play it, the normal SoundCloud failover in `MusicPlayerManager` takes over.
 *
 * ## Identifying the transaction (no wrong-song playback)
 *  * every session gets a fresh transaction token, and bridge callbacks that echo any other
 *    token are dropped,
 *  * the page continuously reports the video id its player is on; a capture is dropped when
 *    the page is demonstrably on a *different* video (see [AudioIdentityPolicy]) and the
 *    page is asked to reload the requested one, at most [MAX_DRIFT_RELOADS] times,
 *  * a single re-navigation is attempted if nothing at all has been found by
 *    [RENAVIGATE_AT_MS] - the first page load can land on an interstitial; the deadline is
 *    never extended.
 *
 * ## Ads
 * Ads in YouTube's presentation layer are never reachable here: only the raw audio
 * container URL is handed to ExoPlayer, never the page's player payload.
 *
 * ## Pre-warmed pool
 * Up to [POOL_SIZE] WebViews are kept warm, never attached to a window, and reused across
 * resolves so the Chromium instantiation + HTTP cache are already in place when a song is
 * tapped. Idle instances are destroyed after [POOL_IDLE_MS], and the pool survives
 * independently of [com.example.playback.ExtractionService] (which only asks for a warm-up
 * and keeps the process alive; extraction works even if that service never starts).
 *
 * All WebView-touching methods run on the main thread; the network interceptor and the
 * JavaScript bridge callbacks arrive on worker threads and therefore only touch
 * thread-safe primitives and hand their results to the flow's channel.
 */
@SuppressLint("SetJavaScriptEnabled")
class SecureExtractionEngine private constructor(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Warm, unused WebViews. Only ever touched on the main thread. */
    private val idlePool = ArrayDeque<WebView>()

    @Volatile
    private var activeLeases = 0

    /**
     * Transaction token of the session that currently owns the engine.
     *
     * Only one extraction is live at a time (a new song cancels the previous one), so a
     * capture that arrives while this is *not* its own token belongs to a session the user
     * has already moved on from and is dropped.
     */
    @Volatile
    private var activeTransactionId: String? = null

    private fun beginTransaction(transactionId: String) {
        activeTransactionId = transactionId
    }

    private fun endTransaction(transactionId: String) {
        if (activeTransactionId == transactionId) activeTransactionId = null
    }

    private fun isCurrentTransaction(transactionId: String): Boolean =
        activeTransactionId == transactionId

    private val idleReaper = Runnable {
        if (activeLeases == 0 && idlePool.isNotEmpty()) {
            val released = idlePool.size
            while (idlePool.isNotEmpty()) destroyWebView(idlePool.pollFirst())
            Log.i(TAG, "Idle pool released ($released WebView(s)) to keep memory low")
        }
    }

    /**
     * Resolves one YouTube video id into a playable stream, or null when nothing usable was
     * captured inside [timeoutMs].
     *
     * Runs on the main thread (a WebView requirement) but never blocks it: the flow
     * suspends, the WebView drives itself through `Handler` callbacks, and the result
     * arrives through the channel.
     */
    suspend fun resolve(videoId: String, timeoutMs: Long = SNIFF_TIMEOUT_MS): StreamInfo? =
        withContext(Dispatchers.Main) {
            if (videoId.isBlank()) return@withContext null

            val startedAt = SystemClock.elapsedRealtime()
            val event = try {
                withTimeoutOrNull(timeoutMs) { extractionEvents(videoId).first() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Includes the "flow completed without an event" case.
                Log.w(TAG, "Extraction failed for $videoId: ${e.message}", e)
                null
            }

            val stream = when (event) {
                is ExtractionEvent.MediaCaptured -> event.stream
                is ExtractionEvent.EarlyCandidate -> event.stream
                else -> null
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            if (stream == null) {
                Log.w(
                    TAG,
                    "Primary extractor produced no stream for $videoId after ${elapsed}ms " +
                        "(window ${timeoutMs}ms) - SoundCloud fallback allowed"
                )
            } else {
                val confidence =
                    if (event is ExtractionEvent.MediaCaptured) "signed-media" else "early-player-json"
                Log.i(
                    TAG,
                    "Primary extractor resolved $videoId in ${elapsed}ms " +
                        "(confidence=$confidence, mime=${stream.mimeType ?: "unknown"})"
                )
            }
            stream
        }

    /**
     * The event stream behind [resolve]. Exposed as a [Flow] so the WebView callbacks can be
     * bridged with structured concurrency: the collection owns the session, and cancelling
     * it (a new song, a timeout) always tears the WebView down through [awaitClose].
     */
    fun extractionEvents(videoId: String): Flow<ExtractionEvent> = callbackFlow {
        val session = ExtractionSession(videoId) { event -> trySend(event) }
        session.start()
        awaitClose { session.dispose() }
    }.flowOn(Dispatchers.Main.immediate)

    /** Creates one warm WebView if the pool is empty. Must be called on the main thread. */
    fun warmUp() {
        mainHandler.post {
            if (idlePool.isEmpty() && activeLeases == 0) {
                try {
                    idlePool.addLast(createWebView())
                    Log.i(TAG, "Headless extraction engine pre-warmed (no page fetched)")
                    scheduleIdleReap()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not pre-warm a headless WebView: ${e.message}")
                }
            }
        }
    }

    /** Number of in-flight extractions; used by the pre-warm service before it stops itself. */
    internal fun leaseCount(): Int = activeLeases

    /** Destroys every pooled WebView (used when the app is finished with playback). */
    fun shutdown() {
        mainHandler.post {
            mainHandler.removeCallbacks(idleReaper)
            while (idlePool.isNotEmpty()) destroyWebView(idlePool.pollFirst())
        }
    }

    // ---------------------------------------------------------------------------------
    // Pool
    // ---------------------------------------------------------------------------------

    /** Takes a warm WebView, or builds a new one. Called on the main thread. */
    private fun acquire(): WebView? {
        val pooled = idlePool.pollFirst()
        if (pooled != null) {
            activeLeases++
            Log.i(TAG, "Reusing warm headless WebView (pool now ${idlePool.size})")
            return pooled
        }
        return try {
            createWebView().also { activeLeases++ }
        } catch (e: Exception) {
            Log.w(TAG, "WebView unavailable on this device: ${e.message}")
            null
        }
    }

    /** Returns a WebView to the pool (or destroys it). Called on the main thread. */
    private fun release(view: WebView?) {
        activeLeases = (activeLeases - 1).coerceAtLeast(0)
        if (view == null) return
        try {
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.webViewClient = WebViewClient()
            view.loadUrl(BLANK_URL)
        } catch (e: Exception) {
            Log.w(TAG, "WebView reset failed: ${e.message}")
        }
        if (idlePool.size < POOL_SIZE) {
            idlePool.addLast(view)
            scheduleIdleReap()
        } else {
            destroyWebView(view)
        }
    }

    private fun scheduleIdleReap() {
        mainHandler.removeCallbacks(idleReaper)
        mainHandler.postDelayed(idleReaper, POOL_IDLE_MS)
    }

    private fun destroyWebView(view: WebView) {
        try {
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.loadUrl(BLANK_URL)
            view.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView teardown failed: ${e.message}")
        }
    }

    /**
     * A realistic mobile Chrome identity, an invisible size and a muted, gesture-free
     * playback setup. Nothing is ever loaded here: the pool stays traffic-free until a real
     * resolve navigates it to a watch page.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        view.visibility = View.GONE
        view.layoutParams = ViewGroup.LayoutParams(0, 0)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Autoplay without a user gesture - otherwise the player never starts and no
            // media request is ever made for us to capture.
            mediaPlaybackRequiresUserGesture = false
            // Cosmetic resources are wasteful here; the interceptor blocks the rest.
            loadsImagesAutomatically = false
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = MOBILE_USER_AGENT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        return view
    }

    // ---------------------------------------------------------------------------------
    // One extraction session
    // ---------------------------------------------------------------------------------

    private inner class ExtractionSession(
        private val requestedVideoId: String,
        private val emit: (ExtractionEvent) -> Unit
    ) {
        /** This session's transaction token (see [ExtractionBridge]). */
        val transactionId: String = UUID.randomUUID().toString().replace("-", "").take(8)

        private val finished = AtomicBoolean(false)
        private var webView: WebView? = null
        private var hasLease = false
        private var earlyCandidate: AudioFormatCandidate? = null
        private var disposed = false

        /**
         * Video id the page's own player is on, as last reported by the in-page hook.
         * `null` until the hook has run; read from WebView worker threads at capture time,
         * so it is volatile.
         */
        @Volatile
        private var pageVideoId: String? = null

        /** Guards the bounded drift recovery (main thread + bridge thread). */
        private val driftReloads = AtomicInteger(0)

        private val watchUrl = "$WATCH_URL$requestedVideoId"

        /** Fires when the grace window for the early (player-json) candidate elapses. */
        private val acceptEarlyRunnable = Runnable {
            val candidate = earlyCandidate ?: return@Runnable
            val stream = buildStreamInfo(candidate) ?: return@Runnable
            if (finish(ExtractionEvent.EarlyCandidate(stream))) {
                Log.i(
                    TAG,
                    "[$transactionId] Early player-json candidate accepted (itag=${candidate.itag}, " +
                        "${candidate.mimeType}) after ${EARLY_CANDIDATE_GRACE_MS}ms wait for " +
                        "$requestedVideoId - note: its `n` parameter may not be transformed yet"
                )
            }
        }

        /**
         * One re-navigation while the deadline still has room for it: the first page load can
         * land on an interstitial or a half-rendered player, and a fresh load frequently does
         * not. Skipped whenever anything was already found; the deadline is never extended.
         */
        private val renavigateRunnable = Runnable {
            if (finished.get() || earlyCandidate != null || disposed) return@Runnable
            val view = webView ?: return@Runnable
            Log.w(
                TAG,
                "[$transactionId] Nothing captured after ${RENAVIGATE_AT_MS}ms - reloading the " +
                    "watch page once for $requestedVideoId"
            )
            runCatching { view.loadUrl(watchUrl) }
        }

        fun start() {
            beginTransaction(transactionId)
            val view = acquire()
            if (view == null) {
                endTransaction(transactionId)
                finish(ExtractionEvent.Failed("no-webview"))
                return
            }
            hasLease = true
            webView = view

            view.webViewClient = sessionClient()
            // A pooled WebView is reused: drop the previous session's bridge and expose this
            // session's, so a late callback from an older page can never reach us. The token
            // the hook echoes back is the belt-and-braces check on top of that swap.
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.addJavascriptInterface(
                ExtractionBridge(
                    transactionId = transactionId,
                    formatsSink = { videoId, payload -> onFormatsPayload(videoId, payload) },
                    videoIdSink = { reported -> onPageVideoId(reported) }
                ),
                BRIDGE_NAME
            )
            Log.i(TAG, "[$transactionId] Secure extraction engine navigating to $watchUrl")
            view.loadUrl(watchUrl)
            mainHandler.postDelayed(renavigateRunnable, RENAVIGATE_AT_MS)
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            mainHandler.removeCallbacks(acceptEarlyRunnable)
            mainHandler.removeCallbacks(renavigateRunnable)
            endTransaction(transactionId)
            val view = webView
            webView = null
            if (hasLease) {
                hasLease = false
                release(view)
            }
        }

        // -----------------------------------------------------------------------------
        // Results
        // -----------------------------------------------------------------------------

        /** True when this call won the race and the session now has its terminal event. */
        private fun finish(event: ExtractionEvent): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            mainHandler.removeCallbacks(acceptEarlyRunnable)
            mainHandler.removeCallbacks(renavigateRunnable)
            emit(event)
            return true
        }

        /**
         * Path 1: the signed media request. Called from a WebView worker thread, so it only
         * touches the (thread-safe) channel, [AtomicBoolean]/[AtomicInteger] and
         * `Handler.post` - never the WebView. Completing the capture synchronously is
         * deliberate: an earlier revision gated it behind an asynchronous JavaScript
         * round-trip, and every official song fell through to the fallback whenever that
         * callback was dropped.
         */
        fun onAudioRequest(rawUrl: String) {
            if (finished.get()) return

            // Identity validation #1: a capture from a session the user has already moved
            // on from belongs to another song.
            if (!isCurrentTransaction(transactionId)) {
                Log.w(
                    TAG,
                    "[$transactionId] Dropping capture - transaction is no longer active " +
                        "(a newer request owns the engine)"
                )
                return
            }

            // Identity validation #2: the page's own player tells us which video it is on.
            // A mismatch is positive evidence that this stream is a different song.
            if (AudioIdentityPolicy.isForeignCapture(requestedVideoId, pageVideoId)) {
                Log.w(
                    TAG,
                    "[$transactionId] Dropping capture - page is on '${pageVideoId}' while " +
                        "'$requestedVideoId' was requested (drift)"
                )
                healDrift()
                return
            }

            val stream = buildStreamInfo(rawUrl) ?: return
            if (finish(ExtractionEvent.MediaCaptured(stream))) {
                Log.i(
                    TAG,
                    "[$transactionId] Captured signed audio stream " +
                        "(${stream.mimeType ?: "unknown"}) for $requestedVideoId"
                )
                // The WebView must not stay in the playback path: stop the page load and let
                // ExoPlayer own playback from here.
                mainHandler.post { runCatching { webView?.stopLoading() } }
                mainHandler.post { logPlayingVideoId() }
            }
        }

        /** Path 2: audio-only formats parsed by the in-page V8 hook. Bridge thread. */
        private fun onFormatsPayload(videoId: String, payload: String) {
            if (finished.get() || earlyCandidate != null) return

            // A pooled WebView can still have the previous page's timers alive for a few
            // milliseconds after it is re-navigated. Those belong to a different song, so
            // they are ignored - but only on positive evidence: an empty id is not a
            // mismatch, and the signed capture below is never gated by this.
            if (videoId.isNotBlank() && videoId != requestedVideoId) {
                Log.w(
                    TAG,
                    "[$transactionId] Ignoring early candidate for '$videoId' while " +
                        "'$requestedVideoId' was requested (stale page callback?)"
                )
                return
            }

            val best = AudioFormatSelector.pickBest(AudioFormatSelector.parse(payload)) ?: return
            mainHandler.post {
                if (finished.get() || earlyCandidate != null) return@post
                earlyCandidate = best
                Log.i(
                    TAG,
                    "[$transactionId] V8 hook: audio-only itag=${best.itag} (${best.mimeType}, " +
                        "${best.bitrate / 1000}kbps) offered - giving the signed media capture " +
                        "${EARLY_CANDIDATE_GRACE_MS}ms to arrive first"
                )
                mainHandler.postDelayed(acceptEarlyRunnable, EARLY_CANDIDATE_GRACE_MS)
            }
        }

        /** The page's player reports which video it is on. Also feeds the drift guard. */
        private fun onPageVideoId(reported: String) {
            val id = AudioIdentityPolicy.clean(reported)
            if (id.isEmpty()) return
            pageVideoId = id
            if (id != requestedVideoId) {
                Log.w(
                    TAG,
                    "[$transactionId] Page reports '$id' while '$requestedVideoId' was " +
                        "requested - captures of '$id' will be dropped (drift recovery)"
                )
                healDrift()
            }
        }

        /**
         * Bounded drift recovery: ask the page to load the requested video again. At most
         * [MAX_DRIFT_RELOADS] attempts, and every capture of the foreign video keeps being
         * dropped in the meantime - so a page that cannot be recovered fails honestly
         * instead of playing the wrong song.
         */
        private fun healDrift() {
            if (driftReloads.get() >= MAX_DRIFT_RELOADS) return
            driftReloads.incrementAndGet()
            mainHandler.post {
                if (finished.get() || disposed) return@post
                runCatching { webView?.evaluateJavascript(healScript(requestedVideoId), null) }
            }
        }

        private fun logPlayingVideoId() {
            mainHandler.post {
                val view = webView
                if (view == null) return@post
                runCatching {
                    view.evaluateJavascript(CURRENT_VIDEO_ID_JS) { value ->
                        val reported = AudioIdentityPolicy.clean(value)
                        if (reported.isNotEmpty() && reported != requestedVideoId) {
                            Log.w(
                                TAG,
                                "[$transactionId] WARNING: page plays '$reported' while " +
                                    "'$requestedVideoId' was requested (drift)"
                            )
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------------------
        // WebView client
        // -----------------------------------------------------------------------------

        private fun sessionClient(): WebViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val host = request.url.host?.lowercase().orEmpty()

                // Wasted traffic (ads, artwork, stylesheets, fonts) never reaches the network.
                if (AudioTrafficPolicy.shouldBlock(host, url)) return emptyResponse()

                // The capture we are waiting for. The request itself is allowed to continue
                // (answering it with an empty body used to make the player error out and
                // re-negotiate, which broke the whole path).
                if (isAudioStreamRequest(host, url)) {
                    onAudioRequest(url)
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // Best effort: usually lands in the outgoing document, which is why the hook
                // is also installed in onPageFinished. Harmless when it does not stick.
                injectHook(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectHook(view)
                nudgePlayer(view)
                mainHandler.postDelayed({ nudgePlayer(view) }, NUDGE_RETRY_MS)
            }

            /**
             * If the watch page itself cannot be loaded there is nothing to extract, so hand
             * over to the fallback immediately instead of burning the rest of the window.
             * Sub-resource failures (a blocked ad host, a tracker) are expected.
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                Log.w(
                    TAG,
                    "[$transactionId] Watch page failed to load (code ${error?.errorCode}) - " +
                        "resolving via the fallback immediately"
                )
                finish(ExtractionEvent.Failed("page-error-${error?.errorCode}"))
            }
        }

        private fun injectHook(view: WebView?) {
            if (view == null || disposed) return
            runCatching { view.evaluateJavascript(hookScript(transactionId, requestedVideoId), null) }
        }

        private fun nudgePlayer(view: WebView?) {
            if (view == null || disposed) return
            runCatching { view.evaluateJavascript(NUDGE_JS, null) }
        }
    }

    // ---------------------------------------------------------------------------------
    // URL construction
    // ---------------------------------------------------------------------------------

    /**
     * True for the CDN request that actually carries the audio payload: the host AND the
     * path AND the `mime=audio` marker are required, because a bare host match would also
     * catch video and manifest requests.
     */
    private fun isAudioStreamRequest(host: String, url: String): Boolean {
        if (!host.endsWith(GOOGLEVIDEO_HOST_SUFFIX)) return false
        val lower = url.lowercase()
        return lower.contains("videoplayback") && lower.contains("mime=audio")
    }

    /** Stream info for the signed media request captured by the interceptor. */
    private fun buildStreamInfo(rawUrl: String): StreamInfo? {
        val playableUrl = AudioFormatSelector.stripChunkParameters(rawUrl)
        if (playableUrl.isBlank()) return null
        return StreamInfo(
            url = playableUrl,
            mimeType = AudioFormatSelector.mimeTypeFromUrl(playableUrl),
            headers = mediaHeaders()
        )
    }

    /** Stream info for an audio-only format taken from the player response. */
    private fun buildStreamInfo(candidate: AudioFormatCandidate): StreamInfo =
        StreamInfo(
            url = AudioFormatSelector.stripChunkParameters(candidate.url),
            mimeType = candidate.mimeType,
            headers = mediaHeaders()
        )

    /**
     * The stream is bound to the cookie session the browser just established, so hand that
     * cookie to ExoPlayer together with a matching UA/Referer.
     */
    private fun mediaHeaders(): Map<String, String> {
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(YOUTUBE_ORIGIN)
        }.getOrNull()

        return buildMap {
            put("User-Agent", MOBILE_USER_AGENT)
            put("Referer", "$YOUTUBE_ORIGIN/")
            put("Origin", YOUTUBE_ORIGIN)
            put("Accept-Language", "en-US,en;q=0.9")
            if (!cookies.isNullOrBlank()) put("Cookie", cookies)
        }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        private const val TAG = "SecureExtractionEngine"

        /**
         * How long the engine gets to reach the first audio request before the SoundCloud
         * fallback is allowed to proceed. This is a ceiling, not a wait: the flow completes
         * the moment a request is captured.
         */
        const val SNIFF_TIMEOUT_MS = 8_000L

        /**
         * How long an early (player-json) candidate defers to the signed media capture.
         *
         * The signed capture is the un-throttled one, so it always wins when it arrives; this
         * window only decides how long we wait for it before falling back to the
         * player-response URL. 2.5s is chosen against the two other timings: a healthy signed
         * capture lands inside ~2s (so it is essentially never preempted), while a dead end
         * is cut from the full [SNIFF_TIMEOUT_MS] to well under the 3s hedge that
         * `MusicPlayerManager` uses to start the SoundCloud fallback in parallel.
         */
        const val EARLY_CANDIDATE_GRACE_MS = 2_500L

        /**
         * When nothing has been captured by then, the watch page is loaded one more time.
         * Bounded and never extends the deadline: a healthy session has already finished.
         */
        private const val RENAVIGATE_AT_MS = 4_000L

        /** Ceiling on how often a page that drifted is asked to reload the requested video. */
        private const val MAX_DRIFT_RELOADS = 2

        /** Warm WebViews kept for reuse. Two covers a track change arriving mid-resolve. */
        private const val POOL_SIZE = 2

        /** How long pooled WebViews are kept before being destroyed to release memory. */
        private const val POOL_IDLE_MS = 120_000L

        private const val NUDGE_RETRY_MS = 1_500L
        private const val BRIDGE_NAME = "SuzieeBridge"

        private const val WATCH_URL = "https://m.youtube.com/watch?v="
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        private const val GOOGLEVIDEO_HOST_SUFFIX = "googlevideo.com"
        private const val BLANK_URL = "about:blank"

        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var instance: SecureExtractionEngine? = null

        /** App-scoped singleton: the pool must outlive any single resolve or service. */
        fun get(context: Context): SecureExtractionEngine =
            instance ?: synchronized(this) {
                instance ?: SecureExtractionEngine(context.applicationContext).also {
                    instance = it
                }
            }

        /** Quotes/escapes a value for safe interpolation into an injected script. */
        private fun jsLiteral(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        /** The full hook for one session, with the bridge name, token and video id baked in. */
        private fun hookScript(transactionId: String, requestedVideoId: String): String =
            HOOK_JS
                .replace("__BRIDGE__", BRIDGE_NAME)
                .replace("__TRANSACTION__", jsLiteral(transactionId))
                .replace("__REQUESTED__", jsLiteral(requestedVideoId))

        /** Tiny, idempotent nudge that puts a drifted page back on the requested video. */
        private fun healScript(requestedVideoId: String): String =
            "(function(){try{var p=document.getElementById('movie_player');" +
                "if(p&&p.loadVideoById){p.loadVideoById(" + jsLiteral(requestedVideoId) + ");}" +
                "}catch(e){}})();"

        /**
         * Reads the id of the video the page's own player is currently on. Diagnostics only:
         * it never decides whether a capture is used.
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

        /**
         * The in-page extraction hook (a real V8 hook, not a scraper).
         *
         *  * masks the most obvious automation/browser-identity tells, which reduces how
         *    often the page answers with a bot interstitial instead of a player response,
         *  * wraps `window.fetch` and `XMLHttpRequest` so a `/youtubei/v1/player` response is
         *    parsed the instant it arrives,
         *  * polls the player's own retained response object as a safety net (the initial
         *    response is inlined into the watch page, so this usually hits immediately),
         *  * parses `streamingData.adaptiveFormats` **inside the engine** and pushes only
         *    audio-only records over the bridge,
         *  * reports the video the player is on every tick, so the engine knows whether a
         *    capture is really the requested song,
         *  * never publishes a player response that belongs to a *different* video, so the
         *    app can never be handed another song's audio.
         *
         * `__BRIDGE__`, `__TRANSACTION__` and `__REQUESTED__` are replaced before injection.
         * Entries that only carry a `signatureCipher` are skipped on purpose: deciphering
         * them needs YouTube's obfuscated JS, and guessing would produce a broken URL.
         */
        private const val HOOK_JS = """
            (function () {
              var BRIDGE = window["__BRIDGE__"];
              if (!BRIDGE) { return; }
              var TX = __TRANSACTION__;
              var REQUESTED = __REQUESTED__;

              if (!window.__suzieePrint) {
                window.__suzieePrint = 1;
                try {
                  Object.defineProperty(navigator, "webdriver", {
                    get: function () { return false; }, configurable: true
                  });
                } catch (e) {}
                try {
                  Object.defineProperty(navigator, "hardwareConcurrency", {
                    get: function () { return 8; }, configurable: true
                  });
                } catch (e) {}
                try {
                  Object.defineProperty(navigator, "languages", {
                    get: function () { return ["en-US", "en"]; }, configurable: true
                  });
                } catch (e) {}
                try {
                  var toDataURL = HTMLCanvasElement.prototype.toDataURL;
                  HTMLCanvasElement.prototype.toDataURL = function () {
                    try {
                      var ctx = this.getContext ? this.getContext("2d") : null;
                      if (ctx && this.width > 0 && this.height > 0) {
                        ctx.fillStyle = "rgba(0,0,0,0.01)";
                        ctx.fillRect(0, 0, 1, 1);
                      }
                    } catch (e) {}
                    return toDataURL.apply(this, arguments);
                  };
                } catch (e) {}
              }

              function recordsFrom(streamingData) {
                var out = [];
                try {
                  if (!streamingData || !streamingData.adaptiveFormats) { return out; }
                  var formats = streamingData.adaptiveFormats;
                  for (var i = 0; i < formats.length && i < 40; i++) {
                    var f = formats[i] || {};
                    var mime = String(f.mimeType || "");
                    if (mime.indexOf("audio/") !== 0) { continue; }
                    var url = String(f.url || "");
                    if (!url) { continue; }
                    out.push([
                      f.itag || 0,
                      mime.split(";")[0].trim(),
                      f.bitrate || 0,
                      f.contentLength || 0,
                      url
                    ].join("|"));
                  }
                } catch (e) {}
                return out;
              }

              function publish(response) {
                try {
                  if (!response) { return false; }
                  var details = response.videoDetails;
                  var videoId = "";
                  if (details && details.videoId) { videoId = String(details.videoId); }
                  if (videoId && REQUESTED && videoId !== REQUESTED) { return false; }
                  var records = recordsFrom(response.streamingData);
                  if (!records.length) { return false; }
                  BRIDGE.onFormats(TX, videoId, records.join("\n"));
                  return true;
                } catch (e) { return false; }
              }

              function currentVideoId() {
                try {
                  var p = document.getElementById("movie_player");
                  if (p && p.getVideoData) {
                    var d = p.getVideoData() || {};
                    return String(d.video_id || "");
                  }
                } catch (e) {}
                return "";
              }

              function report() {
                var id = currentVideoId();
                if (id) { try { BRIDGE.onVideoId(TX, id); } catch (e) {} }
                return id;
              }

              function scan() {
                try {
                  var player = document.getElementById("movie_player");
                  var response = null;
                  if (player && player.getPlayerResponse) {
                    response = player.getPlayerResponse();
                  }
                  if ((!response || !response.streamingData) && window.ytInitialPlayerResponse) {
                    response = window.ytInitialPlayerResponse;
                  }
                  return publish(response);
                } catch (e) { return false; }
              }

              if (!window.__suzieeOnPlayerJson) {
                window.__suzieeOnPlayerJson = function (text) {
                  try { publish(JSON.parse(text)); } catch (e) {}
                };
              }

              if (!window.__suzieeHooked) {
                window.__suzieeHooked = true;

                var originalFetch = window.fetch;
                if (originalFetch) {
                  window.fetch = function () {
                    var target = "";
                    try {
                      var first = arguments[0];
                      target = String((first && first.url) || first || "");
                    } catch (e) {}
                    var promise = originalFetch.apply(this, arguments);
                    if (target.indexOf("/youtubei/v1/player") >= 0) {
                      try {
                        promise.then(function (response) {
                          try {
                            response.clone().text().then(function (text) {
                              window.__suzieeOnPlayerJson(text);
                            });
                          } catch (e) {}
                        }, function () {});
                      } catch (e) {}
                    }
                    return promise;
                  };
                }

                var OriginalXhr = window.XMLHttpRequest;
                if (OriginalXhr && OriginalXhr.prototype) {
                  var originalOpen = OriginalXhr.prototype.open;
                  var originalSend = OriginalXhr.prototype.send;
                  OriginalXhr.prototype.open = function (method, url) {
                    try { this.__suzieeUrl = String(url || ""); } catch (e) {}
                    return originalOpen.apply(this, arguments);
                  };
                  OriginalXhr.prototype.send = function () {
                    var xhr = this;
                    try {
                      if (String(xhr.__suzieeUrl || "").indexOf("/youtubei/v1/player") >= 0) {
                        xhr.addEventListener("load", function () {
                          try { window.__suzieeOnPlayerJson(xhr.responseText); } catch (e) {}
                        });
                      }
                    } catch (e) {}
                    return originalSend.apply(this, arguments);
                  };
                }
              }

              if (window.__suzieeTimer) { clearInterval(window.__suzieeTimer); }
              var tries = 0;
              window.__suzieeTimer = setInterval(function () {
                tries = tries + 1;
                report();
                scan();
                if (tries >= 20) {
                  clearInterval(window.__suzieeTimer);
                  window.__suzieeTimer = null;
                }
              }, 400);
            })();
        """

        /**
         * Forces the page's player to start without a user gesture and keeps the page
         * silent. Muting is essential: the headless WebView is a real player, and ExoPlayer
         * owns audible playback. Muting does not stop the media request we wait for -
         * YouTube still fetches the audio track.
         */
        private const val NUDGE_JS = """
            (function() {
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
