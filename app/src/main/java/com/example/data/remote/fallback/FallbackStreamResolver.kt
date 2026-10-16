package com.example.data.remote.fallback

import android.util.Log
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface FallbackStreamResolver {
    /**
     * Attempts fallback stream resolution for the given track.
     */
    suspend fun resolve(track: Track): Result<String>

    /**
     * Returns true if this resolver is ready and operational.
     */
    fun isAvailable(): Boolean
}

class DistributedFallbackStreamResolver : FallbackStreamResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Public privacy-friendly API endpoints providing audio stream resolution
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://piped-api.lunar.icu"
    )

    private val invidiousInstances = listOf(
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://inv.tux.pizza"
    )

    override fun isAvailable(): Boolean = true

    override suspend fun resolve(track: Track): Result<String> = withContext(Dispatchers.IO) {
        // Try Piped audio streams
        for (base in pipedInstances) {
            try {
                val url = "$base/streams/${track.id}"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null && audioStreams.length() > 0) {
                            var bestUrl: String? = null
                            var bestBitrate = 0L
                            for (i in 0 until audioStreams.length()) {
                                val stream = audioStreams.optJSONObject(i) ?: continue
                                val streamUrl = stream.optString("url")
                                val bitrate = stream.optLong("bitrate", 0L)
                                if (streamUrl.isNotBlank() && bitrate >= bestBitrate) {
                                    bestBitrate = bitrate
                                    bestUrl = streamUrl
                                }
                            }
                            if (!bestUrl.isNullOrBlank()) {
                                Log.i(TAG, "Resolved audio via Piped instance: $base")
                                return@withContext Result.success(bestUrl)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped instance $base failed: ${e.message}")
            }
        }

        // Try Invidious audio streams
        for (base in invidiousInstances) {
            try {
                val url = "$base/api/v1/videos/${track.id}"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                        if (adaptiveFormats != null) {
                            for (i in 0 until adaptiveFormats.length()) {
                                val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                                val type = fmt.optString("type")
                                if (type.startsWith("audio/")) {
                                    val streamUrl = fmt.optString("url")
                                    if (streamUrl.isNotBlank()) {
                                        Log.i(TAG, "Resolved audio via Invidious instance: $base")
                                        return@withContext Result.success(streamUrl)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious instance $base failed: ${e.message}")
            }
        }

        Result.failure(Exception("All fallback stream resolvers exhausted for video ${track.id}"))
    }

    companion object {
        private const val TAG = "FallbackResolver"
    }
}
