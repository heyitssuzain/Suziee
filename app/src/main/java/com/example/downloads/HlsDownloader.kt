package com.example.downloads

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads an HLS (m3u8) stream into **one** playable file, so the existing file-based
 * offline playback path works unchanged.
 *
 * ## Why this exists
 * An m3u8 is just a text index of segment URLs, so saving it verbatim would produce an
 * unplayable text file. When a resolver returns an HLS manifest (some streams are only
 * offered that way), this fetches the real playlist and concatenates its pieces:
 *
 *   fMP4 (`#EXT-X-MAP` + `styp/moof/mdat` segments) -> init segment + all segments in
 *   order == a valid fragmented MP4, saved as `.m4a`.
 *
 * MPEG-TS and ADTS/AAC playlists are concatenated the same way (`.ts` / `.aac`).
 */
class HlsDownloader(
    private val client: OkHttpClient,
    private val defaultUserAgent: String
) {

    /** A finished download: the file on disk, its container and the byte count. */
    data class Downloaded(val file: File, val bytes: Long, val extension: String)

    /**
     * @param playlistUrl  the resolved HLS URL (master or media playlist)
     * @param headers      per-stream headers (User-Agent / Referer / cookies)
     * @param targetDir    directory to write into (must exist or be creatable)
     * @param baseName     file name without extension (already sanitized by the caller)
     * @param onProgress   0..100, awaited as each segment lands (so writes stay ordered)
     */
    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        targetDir: File,
        baseName: String,
        onProgress: (suspend (Int) -> Unit)? = null
    ): Downloaded = withContext(Dispatchers.IO) {
        // 1. Walk master playlists down to the media playlist (bounded, in case of a loop).
        var currentUrl = playlistUrl
        var playlist = fetchText(currentUrl, headers)
        var hops = 0
        while (hops < MAX_PLAYLIST_HOPS) {
            val variant = firstVariant(playlist) ?: break
            currentUrl = resolveUri(currentUrl, variant)
            playlist = fetchText(currentUrl, headers)
            hops++
        }

        // 2. Collect the init segment (fMP4) and the media segments, in order.
        val initUrl = EXT_X_MAP_REGEX.find(playlist)?.groupValues?.get(1)
            ?.let { resolveUri(currentUrl, it) }
        val segmentRefs = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        if (segmentRefs.isEmpty()) {
            throw IOException("HLS playlist contains no media segments")
        }

        val extension = containerFor(initUrl != null, segmentRefs.first())

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Cannot create download directory ${targetDir.absolutePath}")
        }
        val destination = File(targetDir, "$baseName.$extension")
        if (destination.exists()) destination.delete()

        // 3. Write init + every segment into a single file.
        var written = 0L
        destination.outputStream().buffered(BUFFER_SIZE).use { output ->
            if (initUrl != null) {
                val init = fetchBytesWithRetry(initUrl, headers)
                output.write(init)
                written += init.size
            }

            segmentRefs.forEachIndexed { index, ref ->
                val bytes = fetchBytesWithRetry(resolveUri(currentUrl, ref), headers)
                output.write(bytes)
                written += bytes.size
                onProgress?.invoke(((index + 1) * 100) / segmentRefs.size)
            }
            output.flush()
        }

        Log.i(TAG, "Stitched ${segmentRefs.size} HLS segments into $extension ($written bytes)")
        Downloaded(destination, written, extension)
    }

    /**
     * The first line of a master playlist is the variant URL. A media playlist's first
     * non-comment line is a segment, so this returns null unless `#EXT-X-STREAM-INF` is
     * present.
     */
    private fun firstVariant(playlist: String): String? {
        if (!playlist.contains("#EXT-X-STREAM-INF")) return null
        return playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun containerFor(hasInitSegment: Boolean, firstSegmentRef: String): String {
        val ref = firstSegmentRef.substringBefore('?').lowercase()
        return when {
            hasInitSegment || ref.endsWith(".m4s") || ref.endsWith(".mp4") -> "m4a"
            ref.endsWith(".ts") -> "ts"
            ref.endsWith(".aac") -> "aac"
            ref.endsWith(".mp3") -> "mp3"
            else -> "m4a"
        }
    }

    /**
     * Resolves a playlist reference against the playlist URL.
     * Absolute URLs are returned **byte-for-byte untouched** - CDN URLs are signed, and
     * re-serialising them (e.g. through `HttpUrl`) can break the signature.
     */
    private fun resolveUri(base: String, reference: String): String {
        if (reference.startsWith("http://", true) || reference.startsWith("https://", true)) {
            return reference
        }
        val queryStart = base.indexOf('?').let { if (it >= 0) it else base.length }
        val directory = base.substring(0, queryStart).substringBeforeLast('/', "") + "/"
        return directory + reference.trimStart('/')
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", headers["User-Agent"] ?: defaultUserAgent)
            .header("Referer", headers["Referer"] ?: "https://www.youtube.com/")
        headers.forEach { (name, value) ->
            if (!name.equals("User-Agent", true) && !name.equals("Referer", true)) {
                builder.header(name, value)
            }
        }
        return builder.build()
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        client.newCall(buildRequest(url, headers)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for playlist")
            return response.body?.string() ?: throw IOException("Empty playlist response")
        }
    }

    private suspend fun fetchBytesWithRetry(
        url: String,
        headers: Map<String, String>
    ): ByteArray {
        var lastError: Exception? = null
        repeat(MAX_SEGMENT_ATTEMPTS) { attempt ->
            try {
                return fetchBytes(url, headers)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Segment attempt ${attempt + 1} failed: ${e.message}")
                delay(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        throw IOException("Segment download failed after $MAX_SEGMENT_ATTEMPTS attempts", lastError)
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        client.newCall(buildRequest(url, headers)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for segment")
            val body = response.body ?: throw IOException("Empty segment response")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw IOException("Zero-byte segment")
            return bytes
        }
    }

    private companion object {
        const val TAG = "HlsDownloader"
        const val MAX_PLAYLIST_HOPS = 3
        const val MAX_SEGMENT_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 400L
        const val BUFFER_SIZE = 64 * 1024

        val EXT_X_MAP_REGEX = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"")
    }
}
