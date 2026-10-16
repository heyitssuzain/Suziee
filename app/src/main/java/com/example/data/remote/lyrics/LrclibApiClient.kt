package com.example.data.remote.lyrics

import android.util.Log
import com.example.domain.model.LyricsLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class LyricsResult(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val parsedLines: List<LyricsLine> = emptyList()
)

class LrclibApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val timeTagPattern = Pattern.compile("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)")

    suspend fun getLyrics(trackTitle: String, artist: String, durationSeconds: Long): Result<LyricsResult> =
        withContext(Dispatchers.IO) {
            try {
                // First try exact get
                val cleanTitle = cleanSearchTerm(trackTitle)
                val cleanArtist = cleanSearchTerm(artist)

                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")

                var getUrl = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist"
                if (durationSeconds > 0) {
                    getUrl += "&duration=$durationSeconds"
                }

                val request = Request.Builder()
                    .url(getUrl)
                    .addHeader("User-Agent", "SuzieeMusicPlayer/1.0 (Android)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val result = parseLyricsJson(body)
                        if (result != null) return@withContext Result.success(result)
                    }
                }

                // If exact get returned 404, search LRCLIB
                val searchUrl = "https://lrclib.net/api/search?q=$encodedTitle+$encodedArtist"
                val searchRequest = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", "SuzieeMusicPlayer/1.0 (Android)")
                    .build()

                client.newCall(searchRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val jsonArray = JSONArray(body)
                        if (jsonArray.length() > 0) {
                            val first = jsonArray.getJSONObject(0)
                            val result = parseLyricsJson(first.toString())
                            if (result != null) return@withContext Result.success(result)
                        }
                    }
                }

                Result.failure(Exception("No lyrics found for track"))
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics fetch error: ${e.message}")
                Result.failure(e)
            }
        }

    private fun parseLyricsJson(jsonString: String): LyricsResult? {
        return try {
            val json = JSONObject(jsonString)
            val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() }
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }

            if (plain == null && synced == null) return null

            val parsedLines = synced?.let { parseSyncedLyrics(it) } ?: emptyList()
            LyricsResult(plainLyrics = plain, syncedLyrics = synced, parsedLines = parsedLines)
        } catch (e: Exception) {
            null
        }
    }

    fun parseSyncedLyrics(syncedLyrics: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        val rawLines = syncedLyrics.split("\n")
        for (raw in rawLines) {
            val matcher = timeTagPattern.matcher(raw.trim())
            if (matcher.matches()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val millisPart = matcher.group(3)
                val ms = when {
                    millisPart == null -> 0L
                    millisPart.length == 1 -> millisPart.toLong() * 100
                    millisPart.length == 2 -> millisPart.toLong() * 10
                    else -> millisPart.take(3).toLong()
                }
                val totalMs = (min * 60 + sec) * 1000 + ms
                val text = matcher.group(4)?.trim() ?: ""
                lines.add(LyricsLine(timeMs = totalMs, text = text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun cleanSearchTerm(term: String): String {
        return term
            .replace(Regex("(?i)\\[.*?\\]|\\(.*?\\)"), "") // remove bracketed tags like [MV], (Official Audio)
            .replace(Regex("(?i)ft\\..*|feat\\..*|official|audio|video|lyrics"), "")
            .trim()
    }

    companion object {
        private const val TAG = "LrclibApiClient"
    }
}
