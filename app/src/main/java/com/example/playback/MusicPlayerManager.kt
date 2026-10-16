package com.example.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.data.local.SuzieeDatabase
import com.example.data.preferences.SuzieePreferences
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.repository.MusicRepository
import com.example.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

enum class RepeatMode {
    OFF, ALL, ONE
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentTrack: Track? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffle: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val visualizerAmplitudes: List<Float> = List(16) { 0.15f }
)

@OptIn(UnstableApi::class)
class MusicPlayerManager(
    private val context: Context,
    private val database: SuzieeDatabase,
    private val preferences: SuzieePreferences,
    val repository: MusicRepository = MusicRepository(database)
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Media requests (googlevideo.com and friends) require a matching User-Agent and
     * a YouTube Referer, otherwise the CDN answers 403 Forbidden. These default
     * request properties are applied to every stream ExoPlayer fetches.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(InnerTubeApiClient.MEDIA_USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(
            mapOf(
                "User-Agent" to InnerTubeApiClient.MEDIA_USER_AGENT,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )

    private val dataSourceFactory: DataSource.Factory =
        DefaultDataSource.Factory(context, httpDataSourceFactory)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000, // min buffer
                    60_000, // max buffer
                    1_000,  // buffer needed to start playback
                    3_000   // buffer needed after a rebuffer
                )
                .build()
        )
        .build()
        .apply {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var progressTrackerJob: Job? = null
    private var originalQueue: List<Track> = emptyList()
    private var serviceStarted = false

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(isLoading = true) }
                    }
                    Player.STATE_READY -> {
                        val duration = exoPlayer.duration.coerceAtLeast(0L)
                        _playbackState.update {
                            it.copy(
                                isLoading = false,
                                durationMs = duration,
                                errorMessage = null
                            )
                        }
                    }
                    Player.STATE_ENDED -> {
                        handlePlaybackEnded()
                    }
                    Player.STATE_IDLE -> {
                        _playbackState.update { it.copy(isLoading = false) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                _playbackState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = "Playback failed: ${error.localizedMessage ?: "Unknown media error"}"
                    )
                }
            }
        })
    }

    /**
     * Starts the playback service lazily, right as playback begins.
     *
     * Previously this ran at app launch via startForegroundService(), but nothing was
     * playing yet, so Android raised a "did not call startForeground" ANR. We now:
     *   1. Start it only when we actually have media to play, and
     *   2. Use a plain started service (no 5-second foreground deadline). Media3's
     *      MediaSessionService promotes itself to foreground as soon as playback is
     *      ongoing, so background playback still works.
     */
    private fun ensurePlaybackServiceStarted() {
        if (serviceStarted) return
        try {
            val intent = Intent(context, MediaPlaybackService::class.java)
            context.startService(intent)
            serviceStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaPlaybackService: ${e.message}", e)
        }
    }

    fun playTrack(track: Track, newQueue: List<Track> = listOf(track)) {
        val queue = if (newQueue.none { it.id == track.id }) {
            newQueue + track
        } else {
            newQueue
        }
        originalQueue = queue
        val targetQueue = if (_playbackState.value.isShuffle) queue.shuffled() else queue
        val index = targetQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        _playbackState.update {
            it.copy(
                queue = targetQueue,
                currentIndex = index,
                currentTrack = track,
                isLoading = true,
                errorMessage = null,
                currentPositionMs = 0L,
                durationMs = track.durationSeconds * 1000L
            )
        }

        resolveAndStartPlayback(track)
    }

    private fun resolveAndStartPlayback(track: Track) {
        scope.launch {
            try {
                _playbackState.update { it.copy(isLoading = true, errorMessage = null) }

                // Record to history (best effort - never block or crash playback).
                runCatching { repository.recordPlayed(track) }
                    .onFailure { Log.w(TAG, "Failed to record play history: ${it.message}") }

                // Resolve either a local file or a remote stream.
                val localFile = track.localFilePath
                    ?.takeIf { track.isDownloaded && !it.isNullOrBlank() }
                    ?.let { File(it) }
                    ?.takeIf { it.exists() }

                val mediaUri: Uri
                val mimeType: String?

                if (localFile != null) {
                    Log.i(TAG, "Playing local offline track: ${localFile.absolutePath}")
                    mediaUri = Uri.fromFile(localFile)
                    mimeType = null
                } else {
                    val streamResult = repository.resolveStreamUrl(track)
                    val streamInfo = streamResult.getOrNull()
                    if (streamInfo == null) {
                        val reason = streamResult.exceptionOrNull()?.message ?: "Unknown error"
                        Log.e(TAG, "Failed stream resolution: $reason")
                        _playbackState.update {
                            it.copy(
                                isLoading = false,
                                isPlaying = false,
                                errorMessage = "Couldn't play this track. Tap retry or pick another song."
                            )
                        }
                        return@launch
                    }
                    Log.i(TAG, "Playing resolved stream: ${streamInfo.url.take(80)}...")
                    mediaUri = Uri.parse(streamInfo.url)
                    mimeType = streamInfo.mimeType
                }

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(if (track.artworkUrl.isNotBlank()) Uri.parse(track.artworkUrl) else null)
                    .build()

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setMediaId(track.id)
                    .setMediaMetadata(mediaMetadata)

                if (!mimeType.isNullOrBlank()) {
                    mediaItemBuilder.setMimeType(mimeType)
                }

                // Make sure the media session service is running before we start.
                ensurePlaybackServiceStarted()

                exoPlayer.setMediaItem(mediaItemBuilder.build())
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Playback start failed: ${e.message}", e)
                _playbackState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = "Playback failed: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun togglePlayPause() {
        try {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
                exoPlayer.playWhenReady = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "togglePlayPause failed: ${e.message}", e)
        }
    }

    fun playNext() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        val nextIndex = state.currentIndex + 1
        if (nextIndex < state.queue.size) {
            val nextTrack = state.queue[nextIndex]
            _playbackState.update { it.copy(currentIndex = nextIndex, currentTrack = nextTrack) }
            resolveAndStartPlayback(nextTrack)
        } else if (state.repeatMode == RepeatMode.ALL) {
            val firstTrack = state.queue.first()
            _playbackState.update { it.copy(currentIndex = 0, currentTrack = firstTrack) }
            resolveAndStartPlayback(firstTrack)
        }
    }

    fun playPrevious() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        if (exoPlayer.currentPosition > 3000L) {
            exoPlayer.seekTo(0)
            return
        }
        val prevIndex = state.currentIndex - 1
        if (prevIndex >= 0) {
            val prevTrack = state.queue[prevIndex]
            _playbackState.update { it.copy(currentIndex = prevIndex, currentTrack = prevTrack) }
            resolveAndStartPlayback(prevTrack)
        } else {
            exoPlayer.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _playbackState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun toggleShuffle() {
        val currentShuffle = !_playbackState.value.isShuffle
        val currentTrack = _playbackState.value.currentTrack
        val newQueue = if (currentShuffle) {
            val shuffled = originalQueue.toMutableList()
            if (currentTrack != null) {
                shuffled.remove(currentTrack)
                shuffled.shuffle()
                listOf(currentTrack) + shuffled
            } else {
                shuffled.shuffled()
            }
        } else {
            originalQueue
        }
        val newIndex = if (currentTrack != null) newQueue.indexOfFirst { it.id == currentTrack.id } else 0
        _playbackState.update {
            it.copy(
                isShuffle = currentShuffle,
                queue = newQueue,
                currentIndex = newIndex.coerceAtLeast(0)
            )
        }
    }

    fun toggleRepeat() {
        val nextRepeat = when (_playbackState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        exoPlayer.repeatMode = when (nextRepeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _playbackState.update { it.copy(repeatMode = nextRepeat) }
    }

    fun addToQueue(track: Track) {
        val currentQueue = _playbackState.value.queue
        if (currentQueue.isEmpty()) {
            playTrack(track)
        } else {
            val updated = currentQueue + track
            originalQueue = originalQueue + track
            _playbackState.update { it.copy(queue = updated) }
        }
    }

    fun removeFromQueue(trackId: String) {
        val currentQueue = _playbackState.value.queue.toMutableList()
        val removeIndex = currentQueue.indexOfFirst { it.id == trackId }
        if (removeIndex != -1) {
            currentQueue.removeAt(removeIndex)
            val newIndex = if (removeIndex < _playbackState.value.currentIndex) {
                _playbackState.value.currentIndex - 1
            } else {
                _playbackState.value.currentIndex
            }
            _playbackState.update { it.copy(queue = currentQueue, currentIndex = newIndex) }
        }
    }

    fun clearQueue() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        originalQueue = emptyList()
        _playbackState.update {
            it.copy(
                isPlaying = false,
                currentTrack = null,
                queue = emptyList(),
                currentIndex = -1,
                currentPositionMs = 0L,
                durationMs = 0L
            )
        }
    }

    private fun handlePlaybackEnded() {
        when (_playbackState.value.repeatMode) {
            RepeatMode.ONE -> {
                exoPlayer.seekTo(0)
                exoPlayer.playWhenReady = true
            }
            else -> {
                playNext()
            }
        }
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = scope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                    // Generate realistic smooth audio visualizer amplitudes
                    val amps = List(16) {
                        val base = 0.2f + 0.75f * Random.nextFloat()
                        base.coerceIn(0.1f, 1.0f)
                    }
                    _playbackState.update {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = if (dur > 0) dur else it.durationMs,
                            visualizerAmplitudes = amps
                        )
                    }
                }
                delay(150)
            }
        }
    }

    private fun stopProgressTracker() {
        progressTrackerJob?.cancel()
        // Reset visualizer to quiet gentle state
        _playbackState.update {
            it.copy(visualizerAmplitudes = List(16) { 0.12f })
        }
    }

    fun release() {
        stopProgressTracker()
        try {
            exoPlayer.release()
        } catch (e: Exception) {
            Log.w(TAG, "Player release failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MusicPlayerManager"
    }
}
