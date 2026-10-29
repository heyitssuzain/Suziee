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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
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

    /**
     * Monotonic token per track. Cancelling bumps it, so a coroutine that is stuck in a
     * blocking socket read cannot rename its temp file over a newer download or overwrite
     * the newer row when it finally unwinds.
     */
    private val downloadTokens = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val hlsDownloader = HlsDownloader(httpClient, InnerTubeApiClient.MEDIA_USER_AGENT)

    val downloadsFlow: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    init {
        // Repair rows left behind by an interrupted process/download and restore the track
        // flag for a completed file that an earlier metadata REPLACE had cleared. This is
        // deliberately IO-backed and never blocks app startup.
        reconcileDownloads()
    }

    fun downloadTrack(track: Track) {
        if (activeJobs.containsKey(track.id)) return

        val token = downloadTokens.merge(track.id, 1L) { old, _ -> old + 1L } ?: 1L

        // LAZY is deliberate: the id must be in activeJobs before the coroutine can run,
        // otherwise a very fast failure can remove the entry before it is inserted and the
        // completed job would block every later retry for this track.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Persist metadata first. The track row may otherwise be absent for a song
                // opened from a queue/history entry, which would make markTrackDownloaded()
                // update zero rows and leave the file invisible in the downloads library.
                repository.rememberTrack(track)
                ensureCurrentDownload(track.id, token)

                // Already saved offline? Check the database and the actual file, not only
                // the caller's (possibly stale) Track copy.
                val knownDownload = downloadDao.getDownload(track.id)
                val knownTrack = database.trackDao().getTrackById(track.id)
                val existingPath = knownDownload?.localFilePath
                    ?: knownTrack?.localFilePath
                    ?: track.localFilePath
                if (DownloadFilePolicy.isUsable(existingPath)) {
                    upsert(
                        track.id,
                        status = "COMPLETED",
                        progress = 100,
                        localFilePath = existingPath
                    )
                    repository.markTrackDownloaded(track.id, existingPath!!)
                    return@launch
                }
                if (!existingPath.isNullOrBlank()) {
                    // The file is genuinely gone. Do not delete the metadata/row: show a
                    // missing state that can be retried rather than silently forgetting it.
                    repository.markTrackDownloadMissing(track.id)
                }

                ensureCurrentDownload(track.id, token)
                upsert(track.id, status = "RESOLVING", progress = 0)

                val streamInfo = resolveForDownload(track)
                ensureCurrentDownload(track.id, token)
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

                val finished: File = performDownloadWithRetry(
                    track = track,
                    streamInfo = streamInfo,
                    musicDir = musicDir,
                    baseName = baseName,
                    token = token
                )
                ensureCurrentDownload(track.id, token)
                if (!DownloadFilePolicy.isUsable(finished.absolutePath)) {
                    throw IOException("Downloaded file is missing or empty")
                }

                val size = finished.length()
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
                if (isCurrentDownload(track.id, token)) {
                    upsert(track.id, status = "CANCELLED", error = "Download cancelled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${track.id}: ${e.message}", e)
                if (isCurrentDownload(track.id, token)) {
                    upsert(track.id, status = "FAILED", error = e.localizedMessage ?: "Download failed")
                }
            } finally {
                // Remove only this job. A cancelled job may finish after a retry has already
                // installed a newer job for the same track.
                activeJobs.remove(track.id, coroutineContext[Job])
            }
        }
        activeJobs[track.id] = job
        job.start()
    }

    private fun isCurrentDownload(trackId: String, token: Long): Boolean =
        downloadTokens[trackId] == token

    private fun ensureCurrentDownload(trackId: String, token: Long) {
        if (!isCurrentDownload(trackId, token)) {
            throw CancellationException("Download was superseded")
        }
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
     * transient failure (timeout / dropped connection). Each attempt writes a fresh unique
     * `.part` file and removes only that file, so a retry never appends to a corrupt
     * half-file and a superseded job cannot delete a newer retry's temp file.
     */
    private suspend fun performDownloadWithRetry(
        track: Track,
        streamInfo: StreamInfo,
        musicDir: File,
        baseName: String,
        token: Long
    ): File {
        var attempt = 0
        while (true) {
            try {
                return if (isHlsStream(streamInfo)) {
                    downloadHls(track, streamInfo, musicDir, baseName, token)
                } else {
                    downloadProgressive(track, streamInfo, musicDir, baseName, token)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) throw e
                Log.w(TAG, "Download attempt $attempt failed (${e.message}) - retrying")
                // Each route removes only its own unique .part file. A global cleanup here
                // could delete the temp file of a newer retry after a quick cancel/retry.
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
        baseName: String,
        token: Long
    ): File {
        val downloaded = hlsDownloader.download(
            playlistUrl = stream.url,
            headers = stream.headers,
            targetDir = musicDir,
            baseName = baseName,
            shouldCommit = { isCurrentDownload(track.id, token) },
            onProgress = { percent ->
                ensureCurrentDownload(track.id, token)
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
        baseName: String,
        token: Long
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
        // Unique per attempt: a cancelled job that is still blocked in a socket must not
        // delete the .part file of the retry the user started immediately afterwards.
        val tempFile = File.createTempFile("${baseName}_", PART_SUFFIX, musicDir)

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

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()

                var downloadedBytes = 0L
                val buffer = ByteArray(8 * 1024)
                var lastReported = 0

                body.byteStream().use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (percent - lastReported >= 5) {
                                    lastReported = percent
                                    ensureCurrentDownload(track.id, token)
                                    upsert(
                                        track.id,
                                        status = "DOWNLOADING",
                                        progress = percent.coerceIn(5, 99),
                                        totalBytes = totalBytes,
                                        downloadedBytes = downloadedBytes
                                    )
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (downloadedBytes <= 0L) {
                    throw IOException("Downloaded file is empty")
                }
                if (totalBytes > 0L && downloadedBytes != totalBytes) {
                    throw IOException(
                        "Download incomplete: $downloadedBytes of $totalBytes bytes"
                    )
                }
            }

            // Only replace the previous good file after the new one is fully written and
            // this download is still the current one (a cancel/retry may have superseded it).
            ensureCurrentDownload(track.id, token)
            if (destinationFile.exists() && !destinationFile.delete()) {
                throw IOException("Could not replace ${destinationFile.name}")
            }
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }
            destinationFile
        } catch (e: Exception) {
            // Never delete a previously completed file just because a retry failed.
            tempFile.delete()
            throw e
        }
    }

    fun cancelDownload(trackId: String) {
        val job = activeJobs[trackId] ?: return
        // Invalidate the running token first. A blocking OkHttp read may ignore coroutine
        // cancellation for a while, but it can no longer commit its file or row.
        val cancelledToken = downloadTokens.merge(trackId, 1L) { old, _ -> old + 1L } ?: 1L
        job.cancel()
        activeJobs.remove(trackId, job)
        // The cancelled coroutine removes its own unique .part file when it unwinds. Do not
        // touch every temp file here: a rapid cancel/retry may already have started one.
        scope.launch {
            if (downloadTokens[trackId] == cancelledToken) {
                upsert(trackId, status = "CANCELLED", error = "Download cancelled")
            }
        }
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
        val track = database.trackDao().getTrackById(trackId)
        val path = download?.localFilePath ?: track?.localFilePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) file.delete()
        }
        // Explicit user deletion is the only path that removes the library row.
        repository.removeDownloadedTrack(trackId)
    }

    /**
     * Repairs download state after process death/restart. Completed files are preserved and
     * their track flag is restored; an interrupted download becomes a visible FAILED row
     * with a Retry action instead of an eternal 5% progress row. A missing completed file is
     * reported the same way and is never silently forgotten.
     */
    fun reconcileDownloads() {
        scope.launch {
            try {
                val rows = downloadDao.getAllDownloadsOnce()
                val rowsById = rows.associateBy { it.trackId }
                val flaggedTracks = database.trackDao().getDownloadedTrackEntities()
                val flaggedById = flaggedTracks.associateBy { it.id }

                rows.forEach { row ->
                    // A download started after this scan began owns its row. Do not turn a
                    // live RESOLVING/DOWNLOADING row into FAILED from a stale snapshot.
                    if (activeJobs.containsKey(row.trackId)) return@forEach

                    val track = flaggedById[row.trackId]
                        ?: database.trackDao().getTrackById(row.trackId)
                    val path = row.localFilePath ?: track?.localFilePath

                    if (DownloadFilePolicy.isUsable(path)) {
                        // A valid file wins over stale status/path combinations, including a
                        // failed retry that kept the previous completed file.
                        val file = File(path!!)
                        if (row.status != "COMPLETED" ||
                            row.localFilePath != file.absolutePath ||
                            row.progressPercent != 100
                        ) {
                            downloadDao.upsertDownload(
                                row.copy(
                                    status = "COMPLETED",
                                    progressPercent = 100,
                                    totalBytes = file.length(),
                                    downloadedBytes = file.length(),
                                    localFilePath = file.absolutePath,
                                    errorMessage = null
                                )
                            )
                        }
                        repository.markTrackDownloaded(row.trackId, file.absolutePath)
                    } else if (row.status == "COMPLETED" ||
                        row.status == "RESOLVING" ||
                        row.status == "DOWNLOADING" ||
                        row.status == "QUEUED" ||
                        track?.isDownloaded == true
                    ) {
                        cleanupPartialFile(row.trackId)
                        repository.markTrackDownloadMissing(row.trackId)
                        markDownloadFailed(
                            row,
                            if (row.status == "COMPLETED") {
                                "Downloaded file is missing. Tap Retry to download it again."
                            } else {
                                "Download was interrupted. Tap Retry to download it again."
                            }
                        )
                    }
                }

                // A track can still carry the downloaded flag while its row was already
                // removed (or was written by an older build). Reconcile those too.
                flaggedTracks.forEach { track ->
                    if (activeJobs.containsKey(track.id)) return@forEach
                    if (rowsById.containsKey(track.id)) return@forEach

                    if (DownloadFilePolicy.isUsable(track.localFilePath)) {
                        val file = File(track.localFilePath!!)
                        downloadDao.upsertDownload(
                            DownloadEntity(
                                trackId = track.id,
                                status = "COMPLETED",
                                progressPercent = 100,
                                totalBytes = file.length(),
                                downloadedBytes = file.length(),
                                localFilePath = file.absolutePath
                            )
                        )
                    } else {
                        repository.markTrackDownloadMissing(track.id)
                        markDownloadFailed(
                            DownloadEntity(trackId = track.id, status = "COMPLETED"),
                            "Downloaded file is missing. Tap Retry to download it again."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download reconciliation failed: ${e.message}", e)
            }
        }
    }

    private suspend fun markDownloadFailed(row: DownloadEntity, message: String) {
        downloadDao.upsertDownload(
            row.copy(
                status = "FAILED",
                progressPercent = 0,
                downloadedBytes = 0,
                localFilePath = null,
                errorMessage = message
            )
        )
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
                localFilePath = localFilePath ?: existing?.localFilePath,
                // Keep the row in a stable position instead of jumping to the top on every
                // 5% progress update.
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
        )
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(80)

    private fun cleanupPartialFile(trackId: String) {
        try {
            val musicDir = File(context.filesDir, "downloads")
            musicDir.listFiles()?.forEach { file ->
                // Only temporary files. A completed file for this track must survive a
                // failed retry/cancellation; it is deleted only by explicit user action.
                if (file.name.startsWith(trackId) && file.name.endsWith(PART_SUFFIX)) {
                    file.delete()
                }
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

        /** In-progress bytes are written here, then atomically renamed on success. */
        private const val PART_SUFFIX = ".part"
    }
}
