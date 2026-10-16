package com.example.data.remote.fallback

import android.util.Log
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface FallbackStreamResolver {
    /**
     * Attempts fallback stream resolution for the given track.
     */
    suspend fun resolve(track: Track): Result<StreamInfo>

    /**
     * Returns true if this resolver is ready and operational.
     */
    fun isAvailable(): Boolean
}

/**
 * Resolves audio streams from community "front-end" instances (Piped / Invidious).
 * These loosely mirror the primary provider and act as a safety net when the
 * primary InnerTube extraction is blocked or rate limited.
 *
 * Short per-request timeouts keep the overall failure fast instead of making the
 * user stare at a spinner for a minute.
 */
class DistributedFallbackStreamResolver : FallbackStreamResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee"
    )

    private val invidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be"
    )

    override fun isAvailable(): Boolean = true

    override suspend fun resolve(track: Track): Result<StreamInfo> = withContext(Dispatchers.IO) {
        // Try Piped audio streams
        for (base in pipedInstances) {
            try {
                val url = "$base/streams/${track.id}"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", InnerTubeApiClient.MEDIA_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null && audioStreams.length() > 0) {
                            var bestUrl: String? = null
                            var bestMime: String? = null
                            var bestBitrate = 0L
                            for (i in 0 until audioStreams.length()) {
                                val stream = audioStreams.optJSONObject(i) ?: continue
                                val streamUrl = stream.optString("url")
                                val bitrate = stream.optLong("bitrate", 0L)
                                if (streamUrl.isNotBlank() && bitrate >= bestBitrate) {
                                    bestBitrate = bitrate
                                    bestUrl = streamUrl
                                    bestMime = stream.optString("mimeType")
                                        .substringBefore(";").trim().ifBlank { null }
                                }
                            }
                            if (!bestUrl.isNullOrBlank()) {
                                Log.i(TAG, "Resolved audio via Piped instance: $base")
                                return@withContext Result.success(StreamInfo(bestUrl, bestMime))
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
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", InnerTubeApiClient.MEDIA_USER_AGENT)
                    .build()
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
                                        val mime = type.substringBefore(";").trim().ifBlank { null }
                                        return@withContext Result.success(StreamInfo(streamUrl, mime))
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
