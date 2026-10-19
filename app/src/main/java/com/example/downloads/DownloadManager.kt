package com.example.downloads

import android.content.Context
import android.util.Log
import com.example.data.local.SuzieeDatabase
import com.example.data.local.entities.DownloadEntity
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.remote.streaming.OnlineStreamResolver
import com.example.data.repository.MusicRepository
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Saves a track to app-private storage so it can be played with no network at all.
 *
 * Resolution goes through the same [OnlineStreamResolver] that playback uses, so the file
 * is the requested recording, not a substitute. Two save routes are tried:
 *
 *  1. **Progressive** - a plain WebM/MP4 audio file the CDN serves as one file. This is
 *     what the player API's Opus/AAC formats produce, so it is the normal path.
 *  2. **HLS stitch** - if a resolver hands back an m3u8, `HlsDownloader` fetches the
 *     playlist and writes the init segment plus every media segment into a single file,
 *     giving a genuine offline file instead of saving an unplayable text playlist.
 *
 * The finished file is registered on the track (`localFilePath`) and the player detects
 * that and plays straight from disk - no `m3u8` text file is ever handed to ExoPlayer.
 */
class DownloadManager(
    private val context: Context,
    private val database: SuzieeDatabase,
    private val repository: MusicRepository,
    private val streamResolver: OnlineStreamResolver
) {
    private val downloadDao = database.downloadDao()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val hlsDownloader = HlsDownloader(httpClient, InnerTubeApiClient.MEDIA_USER_AGENT)

    val downloadsFlow: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    fun downloadTrack(track: Track) {
        if (activeJobs.containsKey(track.id)) return

        val job = scope.launch {
            try {
                // Already saved offline? Nothing to do (the player resolves the same local
                // path, which is not an http URL and must never be fed to OkHttp).
                val existingPath = track.localFilePath
                if (track.isDownloaded && !existingPath.isNullOrBlank() && File(existingPath).exists()) {
                    upsert(
                        track.id,
                        status = "COMPLETED",
                        progress = 100,
                        localFilePath = existingPath
                    )
                    return@launch
                }

                upsert(track.id, status = "RESOLVING", progress = 0)

                val streamInfo = resolveForDownload(track)
                if (streamInfo == null) {
                    upsert(
                        track.id,
                        status = "FAILED",
                        error = "Could not find a downloadable stream for this track"
                    )
                    return@launch
                }

                upsert(track.id, status = "DOWNLOADING", progress = 5)

                val musicDir = File(context.filesDir, "downloads")
                val baseName = "${track.id}_${sanitize(track.title)}"

                val finished: File = performDownloadWithRetry(track, streamInfo, musicDir, baseName)

                val size = if (finished.exists()) finished.length() else 0L
                upsert(
                    track.id,
                    status = "COMPLETED",
                    progress = 100,
                    totalBytes = size,
                    downloadedBytes = size,
                    localFilePath = finished.absolutePath
                )
                repository.markTrackDownloaded(track.id, finished.absolutePath)
                Log.i(TAG, "Saved offline copy: ${finished.absolutePath} ($size bytes)")
            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled for ${track.id}")
                upsert(track.id, status = "CANCELLED", error = "Download cancelled")
                cleanupPartialFile(track.id)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${track.id}: ${e.message}", e)
                upsert(track.id, status = "FAILED", error = e.localizedMessage ?: "Download failed")
                cleanupPartialFile(track.id)
            } finally {
                activeJobs.remove(track.id)
            }
        }
        activeJobs[track.id] = job
    }

    /**
     * Finds something we can save as one file. The shared resolver returns the requested
     * video's audio-only stream; playback and downloads use the same provider.
     */
    private suspend fun resolveForDownload(track: Track): StreamInfo? {
        val resolved = streamResolver.resolve(track.id)
        if (resolved != null) {
            Log.i(TAG, "Download source for ${track.id}: ${resolved.source}")
        }
        return resolved?.info
    }

    /**
     * Routes to the progressive or HLS path and retries the *whole* download once on a
     * transient failure (timeout / dropped connection). Partial files from the failed
     * attempt are removed first so we never append onto a corrupt half-file.
     */
    private suspend fun performDownloadWithRetry(
        track: Track,
        streamInfo: StreamInfo,
        musicDir: File,
        baseName: String
    ): File {
        var attempt = 0
        while (true) {
            try {
                return if (isHlsStream(streamInfo)) {
                    downloadHls(track, streamInfo, musicDir, baseName)
                } else {
                    downloadProgressive(track, streamInfo, musicDir, baseName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) throw e
                Log.w(TAG, "Download attempt $attempt failed (${e.message}) - retrying")
                cleanupPartialFile(track.id)
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    private fun isHlsStream(stream: StreamInfo): Boolean =
        stream.mimeType.equals(HLS_MIME, ignoreCase = true) ||
            stream.url.contains(".m3u8", ignoreCase = true)

    /** Route 2: stitch the HLS playlist into a single offline file. */
    private suspend fun downloadHls(
        track: Track,
        stream: StreamInfo,
        musicDir: File,
        baseName: String
    ): File {
        val downloaded = hlsDownloader.download(
            playlistUrl = stream.url,
            headers = stream.headers,
            targetDir = musicDir,
            baseName = baseName,
            onProgress = { percent ->
                // Awaited, so progress writes can never land after the COMPLETED write.
                upsert(track.id, status = "DOWNLOADING", progress = percent.coerceIn(5, 99))
            }
        )
        return downloaded.file
    }

    /** Route 1: plain single-file download with byte-level progress. */
    private suspend fun downloadProgressive(
        track: Track,
        stream: StreamInfo,
        musicDir: File,
        baseName: String
    ): File = withContext(Dispatchers.IO) {
        if (!musicDir.exists()) musicDir.mkdirs()

        val extension = when {
            stream.mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
            stream.mimeType?.contains("ogg", ignoreCase = true) == true -> "ogg"
            stream.mimeType?.contains("mpeg", ignoreCase = true) == true -> "mp3"
            stream.mimeType?.contains("mp4", ignoreCase = true) == true -> "m4a"
            stream.url.contains(".webm", ignoreCase = true) -> "webm"
            stream.url.contains(".mp3", ignoreCase = true) -> "mp3"
            else -> "m4a"
        }
        val destinationFile = File(musicDir, "$baseName.$extension")

        // The resolver returns the headers the CDN expects (ANDROID/IOS User-Agent /
        // Referer). Keep the generic media UA as a baseline; a per-stream header overrides
        // it.
        val requestHeaders = mutableMapOf(
            "User-Agent" to InnerTubeApiClient.MEDIA_USER_AGENT
        )
        requestHeaders.putAll(stream.headers)

        val request = Request.Builder()
            .url(stream.url)
            .apply { requestHeaders.forEach { (name, value) -> addHeader(name, value) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error ${response.code}")
            }
            val body = response.body ?: throw Exception("Empty response body")
            val totalBytes = body.contentLength()

            var downloadedBytes = 0L
            val buffer = ByteArray(8 * 1024)
            var lastReported = 0

            body.byteStream().use { input ->
                destinationFile.outputStream().buffered().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent - lastReported >= 5) {
                                lastReported = percent
                                upsert(
                                    track.id,
                                    status = "DOWNLOADING",
                                    progress = percent.coerceIn(5, 99),
                                    totalBytes = totalBytes,
                                    downloadedBytes = downloadedBytes,
                                    localFilePath = destinationFile.absolutePath
                                )
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        destinationFile
    }

    fun cancelDownload(trackId: String) {
        activeJobs[trackId]?.cancel()
        activeJobs.remove(trackId)
        cleanupPartialFile(trackId)
    }

    /**
     * Re-runs a previously failed download. The Track is reloaded from the database so the
     * caller only needs the id (which is all the downloads screen has).
     */
    fun retryDownload(trackId: String) {
        scope.launch {
            val entity = runCatching { database.trackDao().getTrackById(trackId) }.getOrNull()
            if (entity == null) {
                upsert(trackId, status = "FAILED", error = "Track is no longer available")
                return@launch
            }
            downloadTrack(entity.toDomain())
        }
    }

    /** Clears a failed/queued download row without touching the library or playlists. */
    suspend fun dismissFailedDownload(trackId: String) {
        downloadDao.deleteDownload(trackId)
    }

    suspend fun deleteDownload(trackId: String) = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(trackId)
        if (download?.localFilePath != null) {
            val file = File(download.localFilePath)
            if (file.exists()) file.delete()
        }
        repository.removeDownloadedTrack(trackId)
    }

    private suspend fun upsert(
        trackId: String,
        status: String,
        progress: Int? = null,
        error: String? = null,
        totalBytes: Long? = null,
        downloadedBytes: Long? = null,
        localFilePath: String? = null
    ) {
        val existing = downloadDao.getDownload(trackId)
        downloadDao.upsertDownload(
            DownloadEntity(
                trackId = trackId,
                status = status,
                progressPercent = progress ?: existing?.progressPercent ?: 0,
                errorMessage = error ?: if (status == "DOWNLOADING" || status == "COMPLETED") null else existing?.errorMessage,
                totalBytes = totalBytes ?: existing?.totalBytes ?: 0L,
                downloadedBytes = downloadedBytes ?: existing?.downloadedBytes ?: 0L,
                localFilePath = localFilePath ?: existing?.localFilePath
            )
        )
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(80)

    private fun cleanupPartialFile(trackId: String) {
        try {
            val musicDir = File(context.filesDir, "downloads")
            musicDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(trackId)) file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DownloadManager"

        /** Must match media3's MimeTypes.APPLICATION_M3U8 exactly. */
        private const val HLS_MIME = "application/x-mpegURL"

        /** Total tries for one download (initial + retries) before it is marked FAILED. */
        private const val MAX_DOWNLOAD_ATTEMPTS = 2

        private const val RETRY_BACKOFF_MS = 1_500L
    }
}
