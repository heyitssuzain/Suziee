package com.example.downloads

import android.content.Context
import android.util.Log
import com.example.data.local.SuzieeDatabase
import com.example.data.local.entities.DownloadEntity
import com.example.data.repository.MusicRepository
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val context: Context,
    private val database: SuzieeDatabase,
    private val repository: MusicRepository
) {
    private val downloadDao = database.downloadDao()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val downloadsFlow: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    fun downloadTrack(track: Track) {
        if (activeJobs.containsKey(track.id)) return

        val job = scope.launch {
            try {
                downloadDao.upsertDownload(
                    DownloadEntity(
                        trackId = track.id,
                        status = "RESOLVING",
                        progressPercent = 0
                    )
                )

                // 1. Resolve stream URL
                val streamResult = repository.resolveStreamUrl(track)
                if (streamResult.isFailure) {
                    downloadDao.upsertDownload(
                        DownloadEntity(
                            trackId = track.id,
                            status = "FAILED",
                            errorMessage = "Stream extraction failed: ${streamResult.exceptionOrNull()?.message}"
                        )
                    )
                    return@launch
                }

                val streamInfo = streamResult.getOrThrow()
                val streamUrl = streamInfo.url

                // 2. Download file to app-private storage
                downloadDao.upsertDownload(
                    DownloadEntity(
                        trackId = track.id,
                        status = "DOWNLOADING",
                        progressPercent = 5
                    )
                )

                val musicDir = File(context.filesDir, "downloads")
                if (!musicDir.exists()) musicDir.mkdirs()

                // Sanitize file name
                val safeFileName = "${track.id}_${track.title.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.m4a"
                val destinationFile = File(musicDir, safeFileName)

                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", com.example.data.remote.innertube.InnerTubeApiClient.MEDIA_USER_AGENT)
                    .addHeader("Referer", "https://www.youtube.com/")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        downloadDao.upsertDownload(
                            DownloadEntity(
                                trackId = track.id,
                                status = "FAILED",
                                errorMessage = "HTTP error ${response.code}"
                            )
                        )
                        return@launch
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    val totalBytes = body.contentLength()

                    var downloadedBytes = 0L
                    val buffer = ByteArray(8 * 1024)

                    body.byteStream().use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            var read: Int
                            var lastProgressUpdate = 0
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                if (totalBytes > 0) {
                                    val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                    if (percent - lastProgressUpdate >= 5) {
                                        lastProgressUpdate = percent
                                        downloadDao.upsertDownload(
                                            DownloadEntity(
                                                trackId = track.id,
                                                status = "DOWNLOADING",
                                                progressPercent = percent,
                                                totalBytes = totalBytes,
                                                downloadedBytes = downloadedBytes,
                                                localFilePath = destinationFile.absolutePath
                                            )
                                        )
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    // Completed!
                    downloadDao.upsertDownload(
                        DownloadEntity(
                            trackId = track.id,
                            status = "COMPLETED",
                            progressPercent = 100,
                            totalBytes = totalBytes,
                            downloadedBytes = downloadedBytes,
                            localFilePath = destinationFile.absolutePath
                        )
                    )

                    // Update track in database with local file path
                    repository.markTrackDownloaded(track.id, destinationFile.absolutePath)
                    Log.i(TAG, "Download finished successfully: ${destinationFile.absolutePath}")
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled for ${track.id}")
                downloadDao.upsertDownload(
                    DownloadEntity(
                        trackId = track.id,
                        status = "CANCELLED",
                        errorMessage = "Download cancelled"
                    )
                )
                cleanupPartialFile(track.id)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${track.id}: ${e.message}", e)
                downloadDao.upsertDownload(
                    DownloadEntity(
                        trackId = track.id,
                        status = "FAILED",
                        errorMessage = e.localizedMessage ?: "Unknown download error"
                    )
                )
                cleanupPartialFile(track.id)
            } finally {
                activeJobs.remove(track.id)
            }
        }
        activeJobs[track.id] = job
    }

    fun cancelDownload(trackId: String) {
        activeJobs[trackId]?.cancel()
        activeJobs.remove(trackId)
        cleanupPartialFile(trackId)
    }

    suspend fun deleteDownload(trackId: String) = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(trackId)
        if (download?.localFilePath != null) {
            val file = File(download.localFilePath)
            if (file.exists()) file.delete()
        }
        repository.removeDownloadedTrack(trackId)
    }

    private fun cleanupPartialFile(trackId: String) {
        try {
            val musicDir = File(context.filesDir, "downloads")
            musicDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(trackId)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DownloadManager"
    }
}
