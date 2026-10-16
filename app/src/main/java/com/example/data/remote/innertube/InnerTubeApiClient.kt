package com.example.data.remote.innertube

import android.net.Uri
import android.util.Log
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class InnerTubeApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * A single Innertube client profile. Different clients are treated by YouTube as
     * different app versions; trying several of them dramatically improves the odds
     * that at least one returns a playable, non-throttled audio stream.
     */
    private data class ClientSpec(
        val name: String,
        val version: String,
        val androidSdk: Int?,
        val userAgent: String
    )

    private val playerClients = listOf(
        ClientSpec(
            name = "ANDROID_VR",
            version = "1.60.19",
            androidSdk = 32,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip"
        ),
        ClientSpec(
            name = "IOS",
            version = "19.45.4",
            androidSdk = null,
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)"
        ),
        ClientSpec(
            name = "ANDROID",
            version = "19.44.38",
            androidSdk = 34,
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"
        ),
        ClientSpec(
            name = "WEB_REMIX",
            version = "1.20241113.01.00",
            androidSdk = null,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    )

    suspend fun search(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        try {
            val url = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241113.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                // Filter for songs/videos
                put("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo=")
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://music.youtube.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext searchFallbackYouTube(query)
                }

                val bodyString = response.body?.string() ?: return@withContext Result.success(emptyList())
                val tracks = parseSearchResponse(bodyString)
                if (tracks.isNotEmpty()) {
                    Result.success(tracks)
                } else {
                    searchFallbackYouTube(query)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary InnerTube search failed, trying fallback: ${e.message}")
            searchFallbackYouTube(query)
        }
    }

    private suspend fun searchFallbackYouTube(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20241113.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", "$query music audio")
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Search failed with HTTP ${response.code}"))
                }
                val bodyString = response.body?.string() ?: return@withContext Result.success(emptyList())
                val tracks = parseGeneralYouTubeSearch(bodyString)
                Result.success(tracks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "All search attempts failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q=$encoded"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                // Format: window.google.ac.h(["query",[["sug1",...],["sug2",...]]])
                val startIndex = body.indexOf("[[")
                val endIndex = body.lastIndexOf("]]")
                if (startIndex != -1 && endIndex != -1) {
                    val arrayJson = JSONArray(body.substring(startIndex, endIndex + 2))
                    val list = mutableListOf<String>()
                    for (i in 0 until minOf(arrayJson.length(), 8)) {
                        val item = arrayJson.optJSONArray(i)
                        val suggestion = item?.optString(0)
                        if (!suggestion.isNullOrBlank()) {
                            list.add(suggestion)
                        }
                    }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Suggestions error: ${e.message}")
        }
        emptyList()
    }

    /**
     * Tries several Innertube clients in order and returns the first playable audio stream.
     */
    suspend fun resolveStream(videoId: String): Result<StreamInfo> = withContext(Dispatchers.IO) {
        for (clientSpec in playerClients) {
            try {
                val info = queryPlayer(videoId, clientSpec)
                if (info != null) {
                    Log.i(TAG, "Resolved stream via ${clientSpec.name}: ${info.mimeType}")
                    return@withContext Result.success(info)
                }
            } catch (e: Exception) {
                Log.w(TAG, "${clientSpec.name} player request failed: ${e.message}")
            }
        }
        Result.failure(Exception("Could not extract a playable audio stream from InnerTube"))
    }

    private fun queryPlayer(videoId: String, spec: ClientSpec): StreamInfo? {
        val url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", spec.name)
                    put("clientVersion", spec.version)
                    put("hl", "en")
                    put("gl", "US")
                    if (spec.androidSdk != null) put("androidSdkVersion", spec.androidSdk)
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("User-Agent", spec.userAgent)
            .addHeader("X-Goog-Api-Format-Version", "2")
            .addHeader("X-YouTube-Client-Name", clientNameId(spec.name))
            .addHeader("X-YouTube-Client-Version", spec.version)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyString = response.body?.string() ?: return null
            return extractStreamInfo(bodyString)
        }
    }

    private fun clientNameId(name: String): String = when (name) {
        "ANDROID_VR" -> "28"
        "IOS" -> "5"
        "ANDROID" -> "3"
        "WEB_REMIX" -> "67"
        else -> "3"
    }

    /**
     * Picks the best audio-only stream from the player response, falling back to a
     * combined (muxed) format when no adaptive audio track is available.
     */
    private fun extractStreamInfo(jsonString: String): StreamInfo? {
        try {
            val json = JSONObject(jsonString)
            val status = json.optJSONObject("playabilityStatus")?.optString("status")
            if (status != null && status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                Log.w(TAG, "playabilityStatus=$status for a client")
                return null
            }

            val streamingData = json.optJSONObject("streamingData") ?: return null

            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val bestAudio = pickBestAudio(adaptiveFormats)
            if (bestAudio != null) return bestAudio

            // Fallback: a muxed format (contains both audio and video).
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val format = formats.optJSONObject(i) ?: continue
                    val streamUrl = extractFormatUrl(format) ?: continue
                    val mime = format.optString("mimeType").substringBefore(";").trim()
                    return StreamInfo(streamUrl, mime.ifBlank { null })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting stream info: ${e.message}")
        }
        return null
    }

    private fun pickBestAudio(formats: JSONArray?): StreamInfo? {
        if (formats == null) return null
        var bestUrl: String? = null
        var bestMime: String? = null
        var bestScore = -1L

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (!mimeType.contains("audio/")) continue

            val streamUrl = extractFormatUrl(format) ?: continue
            val container = mimeType.substringBefore(";").trim()
            val bitrate = format.optLong("bitrate", 0L)

            // Prefer mp4/AAC (best device compatibility) over webm/Opus, then highest bitrate.
            val containerScore = when {
                container.contains("mp4") -> 2L
                container.contains("webm") -> 1L
                else -> 0L
            }
            val score = containerScore * 100_000_000L + bitrate
            if (score > bestScore) {
                bestScore = score
                bestUrl = streamUrl
                bestMime = container.ifBlank { null }
            }
        }
        return bestUrl?.let { StreamInfo(it, bestMime) }
    }

    /**
     * Reads a playable URL from a format object, handling both the plain `url` field
     * and the `signatureCipher` / `cipher` variants used by some clients.
     */
    private fun extractFormatUrl(format: JSONObject): String? {
        val direct = format.optString("url")
        if (direct.isNotBlank()) return direct

        val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
        if (cipher.isNotBlank()) {
            try {
                val parsed = Uri.parse("https://example.com/?$cipher")
                val url = parsed.getQueryParameter("url")
                if (!url.isNullOrBlank()) return url
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cipher url: ${e.message}")
            }
        }
        return null
    }

    private fun parseSearchResponse(jsonString: String): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val root = JSONObject(jsonString)
            val contents = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until contents.length()) {
                val section = contents.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("musicCardShelfRenderer") ?: continue

                val items = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val listItem = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    val track = parseMusicResponsiveListItem(listItem)
                    if (track != null && tracks.none { it.id == track.id }) {
                        tracks.add(track)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search response: ${e.message}")
        }
        return tracks
    }

    private fun parseMusicResponsiveListItem(item: JSONObject): Track? {
        try {
            val flexColumns = item.optJSONArray("flexColumns") ?: return null
            if (flexColumns.length() == 0) return null

            // Title
            val titleCol = flexColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: return null

            val title = titleCol.optJSONObject(0)?.optString("text") ?: return null

            // Video ID
            val navEndpoint = titleCol.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            var videoId = navEndpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
            if (videoId.isNullOrBlank()) {
                videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            }
            if (videoId.isNullOrBlank()) return null

            // Artist and Duration
            var artist = "Unknown Artist"
            var durationSeconds = 0L

            if (flexColumns.length() > 1) {
                val subCol = flexColumns.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")

                if (subCol != null && subCol.length() > 0) {
                    val artistCandidate = subCol.optJSONObject(0)?.optString("text")
                    if (!artistCandidate.isNullOrBlank()) {
                        artist = artistCandidate
                    }

                    // Check last run for duration (e.g., "3:45")
                    val lastRun = subCol.optJSONObject(subCol.length() - 1)?.optString("text") ?: ""
                    durationSeconds = parseDurationToSeconds(lastRun)
                }
            }

            // Thumbnail
            var artworkUrl = ""
            val thumbnails = item.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")

            if (thumbnails != null && thumbnails.length() > 0) {
                val bestThumb = thumbnails.optJSONObject(thumbnails.length() - 1)
                artworkUrl = bestThumb?.optString("url") ?: ""
            }
            if (artworkUrl.isBlank()) {
                artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            return Track(
                id = videoId,
                title = title,
                artist = artist,
                durationSeconds = durationSeconds,
                artworkUrl = artworkUrl
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseGeneralYouTubeSearch(jsonString: String): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val root = JSONObject(jsonString)
            val primaryContents = root.optJSONObject("contents")
                ?.optJSONObject("twoColumnSearchResultsRenderer")
                ?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until primaryContents.length()) {
                val section = primaryContents.optJSONObject(i) ?: continue
                val itemSection = section.optJSONObject("itemSectionRenderer") ?: continue
                val contents = itemSection.optJSONArray("contents") ?: continue

                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j) ?: continue
                    val videoRenderer = item.optJSONObject("videoRenderer") ?: continue

                    val videoId = videoRenderer.optString("videoId")
                    if (videoId.isBlank()) continue

                    val titleObj = videoRenderer.optJSONObject("title")
                    val title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: titleObj?.optString("simpleText") ?: continue

                    val ownerObj = videoRenderer.optJSONObject("ownerText")
                        ?: videoRenderer.optJSONObject("shortBylineText")
                    val artist = ownerObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: "YouTube Music"

                    val lengthText = videoRenderer.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                    val durationSeconds = parseDurationToSeconds(lengthText)

                    val artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                    val track = Track(
                        id = videoId,
                        title = title,
                        artist = artist,
                        durationSeconds = durationSeconds,
                        artworkUrl = artworkUrl
                    )

                    if (tracks.none { it.id == track.id }) {
                        tracks.add(track)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in general YouTube search parse: ${e.message}")
        }
        return tracks
    }

    private fun parseDurationToSeconds(text: String): Long {
        if (text.isBlank() || !text.contains(":")) return 0L
        val parts = text.trim().split(":")
        return try {
            if (parts.size == 2) {
                parts[0].toLong() * 60 + parts[1].toLong()
            } else if (parts.size == 3) {
                parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val TAG = "InnerTubeApiClient"

        /** Shared browser-like UA used for direct media (googlevideo) requests. */
        const val MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
