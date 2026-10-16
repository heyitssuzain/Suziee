package com.example.data.remote.soundcloud

import android.util.Log
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * FALLBACK SOURCE - SoundCloud public API.
 *
 * Triggered by `MusicPlayerManager` when the primary headless-WebView sniffer fails to
 * capture a YouTube stream (5s timeout) or when the captured YouTube stream dies with a
 * persistent playback error.
 *
 * Why SoundCloud? It is the only mainstream catalogue we could verify end-to-end that
 * still serves audio to anonymous clients:
 *   - the public `api-v2.soundcloud.com` search endpoint works with a plain `client_id`
 *     (no OAuth, no user login),
 *   - the track's `media.transcodings` resolve to a directly playable stream
 *     (a progressive MP3, or an HLS `.m3u8` playlist that ExoPlayer plays natively).
 *
 * The catch is the `client_id`: it is not a fixed constant, it rotates with every
 * SoundCloud web release. So we scrape it from the public web player: load
 * `soundcloud.com`, collect the `a-v2.sndcdn.com` JS bundles it references, and pull the
 * 32-char `client_id` out of one of them. The value is cached in memory and refreshed
 * automatically whenever the API answers 401/403.
 *
 * Nothing here touches the UI - it is pure network + JSON, safe on Dispatchers.IO.
 */
class SoundCloudApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Cached, scraped SoundCloud API key. Cleared on 401/403 so we re-scrape it. */
    @Volatile
    private var cachedClientId: String? = null

    /**
     * Resolves [track] (matched by title + artist) to a directly playable stream.
     *
     * @param allowHls when false, only a single progressive file is accepted. The
     * downloader uses this because an HLS playlist cannot be saved as one audio file.
     * @return a playable [StreamInfo], or null when nothing suitable was found.
     */
    suspend fun resolve(track: Track, allowHls: Boolean = true): StreamInfo? =
        withContext(Dispatchers.IO) {
            // Two attempts: the first may fail because our cached client_id just expired.
            repeat(2) { attempt ->
                try {
                    val clientId = clientId() ?: return@withContext null

                    val match = searchTrack(track, clientId)
                    if (match != null) {
                        val stream = resolveTranscoding(match, clientId, allowHls)
                        if (stream != null) return@withContext stream
                    }

                    // Nothing usable. Refresh the key and retry once, then give up.
                    if (attempt == 0) cachedClientId = null else return@withContext null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "SoundCloud attempt ${attempt + 1} failed: ${e.message}")
                    cachedClientId = null
                }
            }
            null
        }

    // ---------------------------------------------------------------------------------
    // client_id discovery
    // ---------------------------------------------------------------------------------

    private fun clientId(): String? {
        cachedClientId?.let { return it }
        try {
            val html = httpGet(BASE_URL) ?: return null
            val bundles = SCRIPT_SRC_REGEX.findAll(html)
                .map { it.groupValues[1] }
                .filter { it.contains("sndcdn.com") }
                .distinct()
                .toList()

            for (bundle in bundles) {
                val js = httpGet(bundle) ?: continue
                val match = CLIENT_ID_REGEX.find(js) ?: CLIENT_ID_UNQUOTED_REGEX.find(js) ?: continue
                val id = match.groupValues[1]
                cachedClientId = id
                Log.i(TAG, "Discovered SoundCloud client_id")
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "client_id discovery failed: ${e.message}")
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // search + match
    // ---------------------------------------------------------------------------------

    /** Searches SoundCloud and returns the best-matching, streamable track object. */
    private fun searchTrack(track: Track, clientId: String): JSONObject? {
        val query = listOf(track.artist, track.title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$API_URL/search/tracks?q=$encoded&client_id=$clientId" +
            "&limit=10&app_locale=en&linked_partitioning=0"

        val json = httpGetJson(url) ?: return null
        val collection = json.optJSONArray("collection") ?: return null

        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until collection.length()) {
            val candidate = collection.optJSONObject(i) ?: continue
            if (!candidate.optBoolean("streamable", true)) continue
            val score = matchScore(track, candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        // Guard against a wildly wrong result (e.g. a 30s "slowed + reverb" edit of some
        // unrelated song) being played back when we simply had no good match.
        return best?.takeIf { bestScore >= MIN_MATCH_SCORE }
    }

    /**
     * Scores how well a SoundCloud search hit matches the wanted track: mostly on title,
     * a little on the uploader name, with a bonus/penalty for a close/absurd duration.
     */
    private fun matchScore(wanted: Track, candidate: JSONObject): Double {
        val candidateTitle = candidate.optString("title")
        val candidateArtist = candidate.optJSONObject("user")?.optString("username").orEmpty()

        val titleScore = similarity(normalize(wanted.title), normalize(candidateTitle))
        val artistScore = similarity(normalize(wanted.artist), normalize(candidateArtist))

        var score = titleScore * 0.8 + artistScore * 0.2

        val durationMs = candidate.optLong("duration", 0L)
        if (durationMs > 0L && wanted.durationSeconds > 0L) {
            val deltaSeconds = abs(durationMs / 1000.0 - wanted.durationSeconds)
            when {
                deltaSeconds <= 5.0 -> score += 0.15
                deltaSeconds > 60.0 -> score -= 0.20
            }
        }
        return score
    }

    /** Lowercases and strips decorations like "(Official Video)", "[HD]" and punctuation. */
    private fun normalize(value: String): String {
        var text = value.lowercase()
        text = text.replace(Regex("\\([^)]*\\)"), " ")
        text = text.replace(Regex("\\[[^]]*]"), " ")
        text = text
            .replace("official video", " ")
            .replace("official audio", " ")
            .replace("lyric video", " ")
            .replace("lyrics", " ")
            .replace("audio", " ")
            .replace("topic", " ")
            .replace("remastered", " ")
            .replace("feat.", " ")
            .replace("ft.", " ")
        text = text.replace(Regex("[^a-z0-9 ]"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Token-overlap (Jaccard) similarity with a containment bonus. 0.0 .. 1.0 */
    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val tokensA = a.split(' ').filter { it.length > 1 }.toSet()
        val tokensB = b.split(' ').filter { it.length > 1 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val common = tokensA.count { tokensB.contains(it) }.toDouble()
        val jaccard = common / (tokensA.size + tokensB.size - common).toDouble()
        return if (b.contains(a) || a.contains(b)) max(jaccard, 0.8) else jaccard
    }

    // ---------------------------------------------------------------------------------
    // transcoding -> playable URL
    // ---------------------------------------------------------------------------------

    /**
     * Turns a SoundCloud track into a playable URL.
     *
     * SoundCloud exposes several `transcodings` per track. We prefer `progressive`
     * (a plain MP3 that any player can stream and seek) and only use `hls` when nothing
     * progressive exists. The transcoding `url` is not the media itself - it is a small
     * API endpoint that, called with the client_id, returns the real CDN URL in a
     * `{"url": "..."}` payload.
     */
    private fun resolveTranscoding(
        trackObject: JSONObject,
        clientId: String,
        allowHls: Boolean
    ): StreamInfo? {
        val transcodings = trackObject.optJSONObject("media")?.optJSONArray("transcodings")
            ?: return null

        var progressive: JSONObject? = null
        var hls: JSONObject? = null
        for (i in 0 until transcodings.length()) {
            val transcoding = transcodings.optJSONObject(i) ?: continue
            when (transcoding.optJSONObject("format")?.optString("protocol")) {
                "progressive" -> if (progressive == null) progressive = transcoding
                "hls" -> if (hls == null) hls = transcoding
            }
        }

        val chosen = progressive ?: hls?.takeIf { allowHls } ?: return null
        val isHls = chosen !== progressive

        val transcodingUrl = chosen.optString("url")
        if (transcodingUrl.isBlank()) return null

        val mediaUrl = httpGetJson("$transcodingUrl?client_id=$clientId")
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val mimeType = if (isHls) {
            // Explicit HLS hint: ExoPlayer then picks the HLS extractor even though the
            // playlist URL has no file extension to sniff. The literal matches media3's
            // MimeTypes.APPLICATION_M3U8.
            "application/x-mpegURL"
        } else {
            chosen.optJSONObject("format")
                ?.optString("mime_type")
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.startsWith("audio/") }
                ?: "audio/mpeg"
        }

        Log.i(TAG, "Resolved SoundCloud stream (${if (isHls) "hls" else "progressive"}, $mimeType)")
        return StreamInfo(
            url = mediaUrl,
            mimeType = mimeType,
            headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "$BASE_URL/"
            )
        )
    }

    // ---------------------------------------------------------------------------------
    // tiny HTTP helpers
    // ---------------------------------------------------------------------------------

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$BASE_URL/")
            .header("Origin", BASE_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for ${url.take(70)}...")
                // An expired/blocked key is the usual cause; drop it so the next call
                // re-discovers a fresh one.
                if (response.code == 401 || response.code == 403) cachedClientId = null
                return null
            }
            return response.body?.string()
        }
    }

    private fun httpGetJson(url: String): JSONObject? {
        val body = httpGet(url) ?: return null
        return runCatching { JSONObject(body) }
            .onFailure { Log.w(TAG, "Malformed JSON from ${url.take(60)}...: ${it.message}") }
            .getOrNull()
    }

    private companion object {
        const val TAG = "SoundCloudApiClient"
        const val BASE_URL = "https://soundcloud.com"
        const val API_URL = "https://api-v2.soundcloud.com"

        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        /** Below this score we consider the search hit unrelated and refuse to play it. */
        const val MIN_MATCH_SCORE = 0.35

        val SCRIPT_SRC_REGEX = Regex("""<script[^>]+src="([^"]+)""")
        val CLIENT_ID_REGEX = Regex("""client_id\s*[:=]\s*"([a-zA-Z0-9]{32})""")
        val CLIENT_ID_UNQUOTED_REGEX = Regex("""client_id=([a-zA-Z0-9]{32})""")
    }
}
