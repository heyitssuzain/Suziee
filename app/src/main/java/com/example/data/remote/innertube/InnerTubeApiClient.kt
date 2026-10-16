package com.example.data.remote.innertube

import android.util.Log
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

    suspend fun search(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        try {
            val url = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240401.01.00")
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
                        put("clientVersion", "2.20240401.00.00")
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

    suspend fun resolveStream(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        // First try Android client player endpoint
        try {
            val url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.29.35")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", "com.google.android.youtube/19.29.35 (Linux; U; Android 14)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val directUrl = extractDirectAudioUrl(bodyString)
                    if (!directUrl.isNullOrBlank()) {
                        return@withContext Result.success(directUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android player endpoint failed: ${e.message}")
        }

        // Try iOS client player endpoint (often returns direct audio streams without n-signature)
        try {
            val url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.29.1")
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val directUrl = extractDirectAudioUrl(bodyString)
                    if (!directUrl.isNullOrBlank()) {
                        return@withContext Result.success(directUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "iOS player endpoint failed: ${e.message}")
        }

        Result.failure(Exception("Could not extract playable audio stream directly from InnerTube"))
    }

    private fun extractDirectAudioUrl(jsonString: String): String? {
        try {
            val json = JSONObject(jsonString)
            val streamingData = json.optJSONObject("streamingData") ?: return null

            // Check adaptive formats first (prefer audio/mp4 or audio/webm)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            var bestAudioUrl: String? = null
            var highestBitrate = 0L

            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.optJSONObject(i) ?: continue
                    val mimeType = format.optString("mimeType")
                    if (mimeType.contains("audio/")) {
                        val directUrl = format.optString("url")
                        val bitrate = format.optLong("bitrate", 0L)
                        if (directUrl.isNotBlank() && bitrate >= highestBitrate) {
                            highestBitrate = bitrate
                            bestAudioUrl = directUrl
                        }
                    }
                }
            }

            if (!bestAudioUrl.isNullOrBlank()) return bestAudioUrl

            // Check combined formats as fallback
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val format = formats.optJSONObject(i) ?: continue
                    val directUrl = format.optString("url")
                    if (directUrl.isNotBlank()) {
                        return directUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting direct audio URL: ${e.message}")
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
    }
}
