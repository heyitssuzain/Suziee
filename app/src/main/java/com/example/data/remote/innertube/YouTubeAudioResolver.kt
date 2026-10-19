package com.example.data.remote.innertube

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.domain.model.StreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube video id to a directly playable audio URL using YouTube's own
 * `youtubei/v1/player` endpoint.
 *
 * This is the **only** online playback provider. There is no Python runtime, no yt-dlp
 * binary and no WebView on the playback path.
 *
 * Why not the WebView? YouTube's web clients now answer with SABR-only streaming data:
 * `adaptiveFormats` entries no longer contain a `url`, only `serverAbrStreamingUrl`, which
 * ExoPlayer cannot play. Verified against `m.youtube.com` and `www.youtube.com`: 25 and 22
 * formats respectively, **zero** with a direct URL. A browser session therefore cannot
 * hand a playable stream to ExoPlayer at all.
 *
 * The Android-VR client is different: when the request carries the visitor data from a
 * normal YouTube page, it returns ordinary signed `googlevideo.com` URLs (HTTP 206,
 * `audio/mp4`). That is what this class does:
 *
 *  1. fetch `https://m.youtube.com/` once per session to obtain `VISITOR_DATA` and the
 *     InnerTube API key (both are in YouTube's own bootstrap config),
 *  2. POST the video id to `/youtubei/v1/player` with the Android-VR client,
 *  3. pick Opus 251 / AAC 140 and hand the URL to ExoPlayer with the matching headers.
 *
 * The session is cached in memory and in SharedPreferences, so after the first warm-up a
 * resolve costs exactly one ~1 second POST. [warmUp] is called from Application.onCreate
 * so the first tap usually finds the session already available.
 */
class YouTubeAudioResolver(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sessionLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: Session? = null

    /** Last failure reason, kept for diagnostics/logcat. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Starts fetching the visitor session in the background. Safe to call repeatedly. */
    fun warmUp() {
        scope.launch {
            ensureSession(refresh = false)
        }
    }

    /**
     * Resolves [videoId] to a playable audio stream, or null when YouTube refuses.
     *
     * On a stale/refused session the visitor data is refreshed and the request is retried
     * with the fallback client, so a long-lived app recovers without a restart.
     */
    suspend fun resolve(videoId: String): StreamInfo? = withContext(Dispatchers.IO) {
        if (!YouTubeAudioPolicy.isVideoId(videoId)) {
            lastFailure = "not a YouTube video id: $videoId"
            Log.w(TAG, lastFailure!!)
            return@withContext null
        }

        val startedAt = SystemClock.elapsedRealtime()
        val current = ensureSession(refresh = false)
        if (current == null) {
            lastFailure = "could not obtain a YouTube visitor session"
            Log.w(TAG, "$lastFailure for $videoId")
            return@withContext null
        }

        // Fast path: Android-VR. Fallback: iOS, then one refreshed Android-VR attempt.
        var format = requestPlayer(current, videoId, Client.ANDROID_VR)
            ?: requestPlayer(current, videoId, Client.IOS)

        if (format == null) {
            Log.w(TAG, "Both clients failed for $videoId (${lastFailure}); refreshing session")
            val refreshed = ensureSession(refresh = true)
            if (refreshed != null) {
                format = requestPlayer(refreshed, videoId, Client.ANDROID_VR)
            }
        }

        if (format == null) {
            Log.w(
                TAG,
                "No playable stream for $videoId after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms (${lastFailure ?: "unknown"})"
            )
            return@withContext null
        }

        Log.i(
            TAG,
            "Resolved $videoId in ${SystemClock.elapsedRealtime() - startedAt}ms " +
                "(itag=${format.itag}, mime=${format.mimeType})"
        )
        StreamInfo(
            url = format.url,
            mimeType = format.mimeType,
            headers = mapOf(
                "User-Agent" to Client.ANDROID_VR.userAgent,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )
    }

    /**
     * Returns the cached session, loading it from disk or fetching it from YouTube when
     * needed. The mutex makes the one-time fetch safe when playback, downloads and the
     * startup warm-up ask at the same time.
     */
    private suspend fun ensureSession(refresh: Boolean): Session? = sessionLock.withLock {
        val now = System.currentTimeMillis()

        if (!refresh) {
            session?.takeIf { it.isFresh(now) }?.let { return@withLock it }
            loadPersisted(now)?.let {
                session = it
                return@withLock it
            }
        }

        val fetched = withContext(Dispatchers.IO) { fetchSession() }
        if (fetched != null) {
            session = fetched
            persist(fetched)
        }
        fetched
    }

    /** GETs YouTube's mobile home page and extracts the visitor data / API key. */
    private fun fetchSession(): Session? {
        val request = Request.Builder()
            .url(HOME_URL)
            .header("User-Agent", PAGE_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            // Bypasses the EU consent interstitial; harmless elsewhere.
            .header("Cookie", "SOCS=CAI")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastFailure = "home page HTTP ${response.code}"
                    Log.w(TAG, lastFailure!!)
                    return null
                }
                val html = response.body?.string().orEmpty()
                val parsed = YouTubeAudioPolicy.parseSession(html)
                if (parsed == null) {
                    lastFailure = "visitor data missing from YouTube home page"
                    Log.w(TAG, lastFailure!!)
                    return null
                }
                Session(
                    visitorData = parsed.visitorData,
                    apiKey = parsed.apiKey,
                    fetchedAtMs = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            lastFailure = "session request failed: ${e.message}"
            Log.w(TAG, lastFailure!!, e)
            null
        }
    }

    /**
     * POSTs one `player` request and converts the response into a validated audio format.
     * Returns null on any HTTP error, non-OK playability status, or no playable audio.
     */
    private fun requestPlayer(
        session: Session,
        videoId: String,
        clientType: Client
    ): YouTubeAudioPolicy.AudioFormat? {
        val requestBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", clientType.toJson(session.visitorData))
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(PLAYER_URL)
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", clientType.userAgent)
            .header("X-Goog-Visitor-Id", session.visitorData)
            .header("X-Goog-Api-Key", session.apiKey)
            .header("X-YouTube-Client-Name", clientType.clientId)
            .header("X-YouTube-Client-Version", clientType.clientVersion)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastFailure = "${clientType.name} HTTP ${response.code}"
                    Log.w(TAG, "$lastFailure for $videoId")
                    return null
                }

                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) {
                    lastFailure = "${clientType.name} returned invalid JSON"
                    Log.w(TAG, "$lastFailure for $videoId")
                    return null
                }

                val playability = root.optJSONObject("playabilityStatus")
                val status = playability?.optString("status").orEmpty()
                if (status != "OK") {
                    val reason = playability?.optString("reason").orEmpty()
                    lastFailure = "${clientType.name} $status: $reason"
                    Log.w(TAG, "$lastFailure for $videoId")
                    return null
                }

                val streamingData = root.optJSONObject("streamingData")
                if (streamingData == null) {
                    lastFailure = "${clientType.name} returned no streamingData"
                    Log.w(TAG, "$lastFailure for $videoId")
                    return null
                }

                val formats = mutableListOf<YouTubeAudioPolicy.RawAudioFormat>()
                formats += streamingData.optJSONArray("adaptiveFormats").toRawFormats()
                formats += streamingData.optJSONArray("formats").toRawFormats()

                val picked = YouTubeAudioPolicy.pickAudioFormat(formats)
                if (picked == null) {
                    lastFailure = "${clientType.name} returned only SABR/unplayable formats " +
                        "(${formats.size} entries)"
                    Log.w(TAG, "$lastFailure for $videoId")
                }
                picked
            }
        } catch (e: Exception) {
            lastFailure = "${clientType.name} request failed: ${e.message}"
            Log.w(TAG, lastFailure!!, e)
            null
        }
    }

    private fun JSONArray?.toRawFormats(): List<YouTubeAudioPolicy.RawAudioFormat> {
        if (this == null) return emptyList()
        val result = ArrayList<YouTubeAudioPolicy.RawAudioFormat>(length())
        for (index in 0 until length()) {
            val format = optJSONObject(index) ?: continue
            result += YouTubeAudioPolicy.RawAudioFormat(
                itag = format.optInt("itag", -1),
                mimeType = format.optString("mimeType").takeIf { it.isNotBlank() },
                url = format.optString("url").takeIf { it.isNotBlank() },
                bitrate = format.optInt("bitrate", 0)
            )
        }
        return result
    }

    private fun loadPersisted(now: Long): Session? {
        val visitor = prefs.getString(KEY_VISITOR, null) ?: return null
        val key = prefs.getString(KEY_API_KEY, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        val loaded = Session(visitor, key, fetchedAt)
        return loaded.takeIf { it.isFresh(now) }
    }

    private fun persist(value: Session) {
        prefs.edit()
            .putString(KEY_VISITOR, value.visitorData)
            .putString(KEY_API_KEY, value.apiKey)
            .putLong(KEY_FETCHED_AT, value.fetchedAtMs)
            .apply()
    }

    private data class Session(
        val visitorData: String,
        val apiKey: String,
        val fetchedAtMs: Long
    ) {
        fun isFresh(now: Long): Boolean {
            val age = now - fetchedAtMs
            return age in 0L..SESSION_TTL_MS
        }
    }

    /** The two anonymous clients that are known to return direct URLs. */
    private enum class Client(
        val clientId: String,
        val clientVersion: String,
        val userAgent: String,
        val deviceMake: String,
        val deviceModel: String,
        val osName: String,
        val osVersion: String,
        val androidSdk: Int?
    ) {
        ANDROID_VR(
            clientId = "28",
            clientVersion = "1.65.10",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdk = 32
        ),
        IOS(
            clientId = "5",
            clientVersion = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 " +
                "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            androidSdk = null
        );

        fun toJson(visitorData: String): JSONObject = JSONObject().apply {
            put("clientName", name)
            put("clientVersion", clientVersion)
            put("deviceMake", deviceMake)
            put("deviceModel", deviceModel)
            put("osName", osName)
            put("osVersion", osVersion)
            androidSdk?.let { put("androidSdkVersion", it) }
            put("userAgent", userAgent)
            put("hl", "en")
            put("gl", "US")
            put("visitorData", visitorData)
        }
    }

    companion object {
        private const val TAG = "YouTubeAudioResolver"

        private const val HOME_URL = "https://m.youtube.com/"
        private const val PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        /** A browser-like UA for the page that carries the visitor data. */
        private const val PAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        private const val PREFS = "suziee_youtube_session"
        private const val KEY_VISITOR = "visitor_data"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_FETCHED_AT = "fetched_at"

        /** Visitor data is cheap to refresh; six hours avoids using an expired session. */
        private const val SESSION_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
