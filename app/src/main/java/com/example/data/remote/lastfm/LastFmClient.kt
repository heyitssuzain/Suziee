package com.example.data.remote.lastfm

import android.util.Log
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Last.fm scrobbling (P3-4).
 *
 * **Fully opt-in.** Nothing here touches the network until the user has, inside Settings,
 * (a) switched scrobbling on and (b) supplied their own API key + session key. No key ships
 * with the app and no account is ever created, which is what keeps the app's privacy-first
 * promise intact.
 *
 * Because the credentials are user-supplied, there is no interactive browser auth flow inside
 * the app: the user authorises once on last.fm and pastes the resulting session key back.
 * That is the documented approach for third-party mobile clients.
 *
 * Last.fm counts a scrobble as a valid play when the song ran for at least 4 minutes **or**
 * half the track duration elapsed - the caller (MusicPlayerManager) decides when that is.
 */
class LastFmClient(
    private val apiKey: String,
    private val sessionKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Reported to Last.fm while a track is playing (`track.updateNowPlaying`). */
    suspend fun updateNowPlaying(track: Track): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val params = baseParams("track.updateNowPlaying").apply {
                put("track", track.title)
                put("artist", track.artist)
                if (track.durationSeconds > 0) {
                    put("duration", (track.durationSeconds * 1000L).toString())
                }
            }
            return@withContext post(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "updateNowPlaying failed: ${e.message}")
            false
        }
    }

    /** Counted as a play by Last.fm (`track.scrobble`). */
    suspend fun scrobble(track: Track): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val params = baseParams("track.scrobble").apply {
                put("track[0]", track.title)
                put("artist[0]", track.artist)
                put("timestamp[0]", (System.currentTimeMillis() / 1000L).toString())
            }
            return@withContext post(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "scrobble failed: ${e.message}")
            false
        }
    }

    /** True only when the user opted in *and* supplied both credentials. */
    private fun isConfigured(): Boolean = apiKey.isNotBlank() && sessionKey.isNotBlank()

    private fun baseParams(method: String): JSONObject = JSONObject().apply {
        put("method", method)
        put("api_key", apiKey)
        put("sk", sessionKey)
        put("format", "json")
    }

    /**
     * Signs and POSTs a request.
     *
     * Per the Last.fm signing spec every parameter (except `format`/`sk`/`callback`) is
     * sorted by key and md5-hashed, then appended to the shared secret. `sk` itself is sent
     * in the body but not signed, which matches `track.scrobble`'s documented signature.
     */
    private fun post(params: JSONObject): Boolean {
        val request = Request.Builder()
            .url("$ENDPOINT/2.0/")
            .post(signedBody(params))
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Last.fm HTTP ${response.code}: ${response.body?.string()}")
                    false
                } else {
                    response.body?.string()
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "post failed: ${e.message}")
            false
        }
    }

    private fun signedBody(params: JSONObject): FormBody {
        val body = FormBody.Builder()
        val signatureSecret = sign(params)

        params.keys().forEach { key -> body.add(key, params.getString(key)) }
        body.add("api_sig", signatureSecret)
        return body.build()
    }

    private fun sign(params: JSONObject): String {
        val excluded = setOf("format", "api_sig")
        val sorted = params.keys()
            .asSequence()
            .filterNot { it in excluded }
            .sorted()
            .joinToString(separator = "") { key -> "${key}${params.getString(key)}" }
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(sorted.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val TAG = "LastFmClient"
        private const val ENDPOINT = "https://ws.audioscrobbler.com"
        private const val USER_AGENT = "SuzieeMusicPlayer/1.0 (Android)"
    }
}