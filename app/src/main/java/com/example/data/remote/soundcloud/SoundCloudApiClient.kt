package com.example.data.remote.soundcloud

import android.util.Log
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * FALLBACK SOURCE - SoundCloud public API.
 *
 * Triggered by `MusicPlayerManager` when the primary headless-WebView sniffer fails to
 * capture a YouTube stream (5s timeout) or when the captured YouTube stream dies with a
 * persistent playback error.
 *
 * ## Why the selection logic is so defensive
 * SoundCloud's official/label uploads are published with `policy = "SNIP"`: the track
 * advertises its real length (`full_duration`) but only ever streams a **30 second
 * preview**, flagged per-transcoding as `snipped = true`. A naive "best title match"
 * therefore resolves to the *official* upload and playback just stops at 0:30.
 *
 * So we:
 *   1. reject every SNIP / non-ALLOW track and every `snipped` transcoding,
 *   2. reject anything clearly truncated (`full_duration` >> `duration`) or too short,
 *   3. require the duration to be in the same ballpark as the requested track,
 *   4. rank what is left (title, artist, duration closeness, minus a penalty for
 *      decorations like "remix"/"slowed"/"cover"),
 *   5. try the ranked candidates **one by one**, and
 *   6. actually fetch a few bytes of each resolved URL to prove it plays before handing
 *      it to ExoPlayer. This is what turns "Could not play this track" into a track that
 *      plays from a different (worthwhile) upload of the same song.
 *
 * ## client_id
 * The API key is not a fixed constant; it rotates with every SoundCloud web release, so
 * it is scraped from the public web player's JS bundles, cached in memory, and refreshed
 * automatically on 401/403.
 */
class SoundCloudApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Cached, scraped SoundCloud API key. Cleared on 401/403 so we re-scrape it. */
    @Volatile
    private var cachedClientId: String? = null

    /**
     * Resolves [track] (matched by title + artist) to a directly playable stream.
     *
     * @param allowHls when false only a single progressive file is accepted. Used by the
     * simple downloader fast-path (an HLS playlist is not a file; when we must have one
     * file the HLS route is stitched together by `HlsDownloader` instead).
     * @return a verified, playable [StreamInfo], or null when nothing suitable was found.
     */
    suspend fun resolve(track: Track, allowHls: Boolean = true): StreamInfo? =
        withContext(Dispatchers.IO) {
            try {
                // Two attempts, but the second one only happens when the search itself came
                // back empty - that is the "cached client_id just expired" symptom. A
                // search that returns hits simply means no candidate was usable, and
                // re-running it would only burn time and bandwidth.
                repeat(2) { attempt ->
                    val key = clientId() ?: return@withContext null

                    val candidates = searchTracks(track, key)
                    if (candidates.isEmpty() && attempt == 0) {
                        // httpGet already dropped a 401/403 key, so this re-scrapes it.
                        cachedClientId = null
                        return@repeat
                    }

                    for (candidate in candidates.take(MAX_CANDIDATES)) {
                        val stream = resolveTranscoding(candidate, key, allowHls) ?: continue
                        if (verify(stream)) {
                            Log.i(
                                TAG,
                                "Selected '${candidate.optString("title")}' " +
                                    "(${candidate.optLong("duration") / 1000}s, ${stream.mimeType})"
                            )
                            return@withContext stream
                        }
                        Log.w(TAG, "Rejected after verification: '${candidate.optString("title")}'")
                    }

                    return@withContext null
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud resolve failed: ${e.message}")
                null
            }
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
    // search, filter and rank
    // ---------------------------------------------------------------------------------

    /** A search hit together with its match score. */
    private data class Scored(val track: JSONObject, val score: Double)

    /**
     * Searches SoundCloud (artist+title, then title alone) and returns acceptable
     * candidates, best match first.
     */
    private fun searchTracks(track: Track, clientId: String): List<JSONObject> {
        val queries = listOf(
            listOf(track.artist, track.title),
            listOf(track.title)
        )

        val seenIds = HashSet<Long>()
        val scored = mutableListOf<Scored>()

        for (parts in queries) {
            val query = parts.filter { it.isNotBlank() }.joinToString(" ").trim()
            if (query.isEmpty()) continue

            val collection = searchCollection(query, clientId) ?: continue
            for (i in 0 until collection.length()) {
                val candidate = collection.optJSONObject(i) ?: continue

                val id = candidate.optLong("id", -1L)
                if (id <= 0L || !seenIds.add(id)) continue
                if (!isAcceptable(candidate, track)) continue

                val score = matchScore(track, candidate)
                if (score < MIN_MATCH_SCORE) continue
                scored += Scored(candidate, score)
            }

            // Enough good options already - skip the second, broader query.
            if (scored.size >= 4) break
        }

        return scored.sortedByDescending { it.score }.map { it.track }
    }

    private fun searchCollection(query: String, clientId: String): JSONArray? {
        val url = "$API_URL/search/tracks?q=${encode(query)}&client_id=$clientId" +
            "&limit=15&app_locale=en&linked_partitioning=0"
        return httpGetJson(url)?.optJSONArray("collection")
    }

    /**
     * Hard filters. Anything rejected here can never be played back in full, so it must
     * not even be considered.
     */
    private fun isAcceptable(candidate: JSONObject, wanted: Track): Boolean {
        // Explicitly unstreamable?
        if (candidate.has("streamable") && !candidate.optBoolean("streamable")) return false

        // "SNIP" (and anything that is not a plain "ALLOW") means preview-only audio:
        // this is the exact reason official uploads stopped at 0:30.
        val policy = candidate.optString("policy")
        if (policy.isNotBlank() && !policy.equals("ALLOW", ignoreCase = true)) return false

        val duration = candidate.optLong("duration", 0L)
        val fullDuration = candidate.optLong("full_duration", duration)

        // 30s previews / very short clips.
        if (duration < MIN_TRACK_DURATION_MS) return false
        // Advertised length much longer than the stream we would get = truncated.
        if (fullDuration - duration > TRUNCATION_TOLERANCE_MS) return false

        // Same ballpark as the track the user actually asked for.
        if (wanted.durationSeconds > 0L) {
            val wantedMs = wanted.durationSeconds * 1000L
            val tolerance = maxOf(MIN_DURATION_TOLERANCE_MS, (wantedMs * DURATION_TOLERANCE_RATIO).toLong())
            if (abs(duration - wantedMs) > tolerance) return false
        }
        return true
    }

    /**
     * Scores how well a SoundCloud search hit matches the wanted track.
     * Title dominates, the uploader name helps, a close duration is a strong signal, and
     * decorated uploads (remix / slowed / cover / 8D ...) are pushed down so we stay as
     * close to the original recording as possible.
     */
    private fun matchScore(wanted: Track, candidate: JSONObject): Double {
        val normalizedTitle = normalize(candidate.optString("title"))
        val normalizedArtist = normalize(
            candidate.optJSONObject("user")?.optString("username").orEmpty()
        )

        val titleSimilarity = similarity(normalize(wanted.title), normalizedTitle)
        val artistSimilarity = similarity(normalize(wanted.artist), normalizedArtist)

        var score = titleSimilarity * 0.45 + artistSimilarity * 0.15

        val duration = candidate.optLong("duration", 0L).toDouble()
        if (duration > 0.0 && wanted.durationSeconds > 0L) {
            val wantedMs = wanted.durationSeconds * 1000.0
            val tolerance = maxOf(60_000.0, wantedMs * 0.4)
            val closeness = (1.0 - abs(duration - wantedMs) / tolerance).coerceIn(0.0, 1.0)
            score += closeness * 0.30
        } else {
            score += 0.15
        }

        DECORATION_PENALTIES.forEach { (word, penalty) ->
            if (normalizedTitle.contains(word)) score -= penalty
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
        return if (b.contains(a) || a.contains(b)) maxOf(jaccard, 0.8) else jaccard
    }

    // ---------------------------------------------------------------------------------
    // transcoding -> playable URL
    // ---------------------------------------------------------------------------------

    /**
     * Turns a SoundCloud track into a playable URL.
     *
     * Only non-snipped transcodings are considered. `progressive` (a plain MP3 that any
     * player can stream and seek) is preferred; otherwise the HLS rendition is used, and
     * only its `audio/mp4` (fragmented MP4/AAC) variant - the `audio/mpegurl` variant does
     * not resolve reliably.
     *
     * A transcoding `url` is not the media itself: it is a small API endpoint that, called
     * with the client_id, returns the real CDN URL in a `{"url": "..."}` payload.
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
            // A snipped transcoding is a 30s preview - never accept it.
            if (transcoding.optBoolean("snipped", false)) continue

            val format = transcoding.optJSONObject("format") ?: continue
            val mime = format.optString("mime_type").lowercase()
            when {
                format.optString("protocol") == "progressive" && progressive == null ->
                    progressive = transcoding
                format.optString("protocol") == "hls" &&
                    mime.startsWith("audio/mp4") && hls == null ->
                    hls = transcoding
            }
        }

        val chosen = progressive ?: hls?.takeIf { allowHls } ?: return null
        val isHls = chosen !== progressive

        val transcodingUrl = chosen.optString("url").takeIf { it.isNotBlank() } ?: return null
        val mediaUrl = httpGetJson("$transcodingUrl?client_id=$clientId")
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val mimeType = if (isHls) {
            // Explicit HLS hint: ExoPlayer then picks the HLS extractor even though the
            // playlist URL has no file extension to sniff.
            HLS_MIME
        } else {
            chosen.optJSONObject("format")
                ?.optString("mime_type")
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.startsWith("audio/") }
                ?: "audio/mpeg"
        }

        return StreamInfo(
            url = mediaUrl,
            mimeType = mimeType,
            headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "$BASE_URL/"
            )
        )
    }

    /**
     * Proves the resolved URL actually serves audio before we hand it to ExoPlayer.
     *
     * Reads only a few bytes and then closes the body, so a huge file is never buffered.
     * For an HLS URL we expect an `#EXTM3U` header, for a progressive URL we expect
     * anything but a playlist - this catches both dead URLs and a resolution that
     * silently returned the wrong kind of stream.
     */
    private fun verify(stream: StreamInfo): Boolean {
        return try {
            val request = Request.Builder()
                .url(stream.url)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "$BASE_URL/")
                .header("Range", "bytes=0-2047")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Verify HTTP ${response.code} for ${stream.url.take(60)}...")
                    return false
                }
                val body = response.body ?: return false
                val buffer = ByteArray(8)
                val read = body.byteStream().read(buffer)
                if (read <= 0) return false

                val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                if (stream.mimeType == HLS_MIME) head.startsWith("#EXTM3U") else !head.startsWith("#EXTM3U")
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Slow network, not a bad URL: accept optimistically so a genuinely playable
            // stream is not rejected just because our probe timed out. ExoPlayer will hit
            // the same URL with a longer read window and can still succeed.
            Log.w(TAG, "Verification timed out - accepting optimistically")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Verification error: ${e.message}")
            false
        }
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

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TAG = "SoundCloudApiClient"
        const val BASE_URL = "https://soundcloud.com"
        const val API_URL = "https://api-v2.soundcloud.com"

        /** Must match media3's MimeTypes.APPLICATION_M3U8 exactly. */
        const val HLS_MIME = "application/x-mpegURL"

        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        /** How many ranked candidates we are willing to try before giving up. */
        const val MAX_CANDIDATES = 6

        /** Below this score a search hit is considered unrelated to the wanted track. */
        const val MIN_MATCH_SCORE = 0.30

        /** Anything at/under this length is a preview clip, not a song. */
        const val MIN_TRACK_DURATION_MS = 45_000L

        /** Small drift between advertised and streamed length is normal (silence trim). */
        const val TRUNCATION_TOLERANCE_MS = 3_000L

        const val MIN_DURATION_TOLERANCE_MS = 45_000L
        const val DURATION_TOLERANCE_RATIO = 0.35

        /**
         * Uploads that are a different recording of the same song. Penalised, not banned:
         * for many mainstream tracks a full-length alternative is the only thing available.
         */
        val DECORATION_PENALTIES = mapOf(
            "remix" to 0.15,
            "slowed" to 0.20,
            "reverb" to 0.15,
            "sped up" to 0.20,
            "nightcore" to 0.25,
            "8d" to 0.20,
            "16d" to 0.20,
            "mashup" to 0.20,
            "bootleg" to 0.20,
            "cover" to 0.10,
            "live" to 0.15,
            "karaoke" to 0.30,
            "instrumental" to 0.25,
            "edit" to 0.10,
            "tribute" to 0.20
        )

        val SCRIPT_SRC_REGEX = Regex("""<script[^>]+src="([^"]+)""")
        val CLIENT_ID_REGEX = Regex("""client_id\s*[:=]\s*"([a-zA-Z0-9]{32})""")
        val CLIENT_ID_UNQUOTED_REGEX = Regex("""client_id=([a-zA-Z0-9]{32})""")
    }
}
