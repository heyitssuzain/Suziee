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
import androidx.media3.exoplayer.source.MediaSource
import com.example.data.local.SuzieeDatabase
import com.example.data.preferences.SuzieePreferences
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.repository.MusicRepository
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

enum class RepeatMode {
    OFF, ALL, ONE
}

/**
 * Which provider actually supplied the stream currently loaded into ExoPlayer.
 * Used to decide whether a playback failure is worth retrying against the fallback.
 */
private enum class StreamSource {
    NONE,

    /** Captured by the headless WebView sniffer (primary). */
    YOUTUBE_SNIFFER,

    /** Resolved through the SoundCloud public API (fallback). */
    SOUNDCLOUD,

    /** A local, already-downloaded file. */
    LOCAL
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
     * Default media HTTP headers.
     *
     * Media CDNs are picky: googlevideo answers 403 without a matching User-Agent and a
     * YouTube Referer. These are the baseline applied to every stream; a resolved
     * [StreamInfo] can add or override entries (e.g. the browser session Cookie the
     * WebView sniffer captured, or a SoundCloud Referer).
     */
    private val baseMediaHeaders = mapOf(
        "User-Agent" to InnerTubeApiClient.MEDIA_USER_AGENT,
        "Referer" to "https://www.youtube.com/",
        "Origin" to "https://www.youtube.com",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(InnerTubeApiClient.MEDIA_USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(baseMediaHeaders)

    private val dataSourceFactory: DataSource.Factory =
        DefaultDataSource.Factory(context, httpDataSourceFactory)

    /**
     * Builds a [MediaSource] for one stream.
     *
     * When the stream needs extra headers we build a dedicated data-source factory just
     * for it, so the per-stream Cookie/Referer cannot leak into other tracks. Without
     * extra headers we reuse the shared factory (the common, zero-cost path).
     */
    private fun buildMediaSource(mediaItem: MediaItem, headers: Map<String, String>): MediaSource {
        if (headers.isEmpty()) {
            return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }

        val perStreamHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: InnerTubeApiClient.MEDIA_USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(baseMediaHeaders + headers)

        val perStreamFactory: DataSource.Factory =
            DefaultDataSource.Factory(context, perStreamHttpFactory)

        return DefaultMediaSourceFactory(perStreamFactory).createMediaSource(mediaItem)
    }

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

    /** The in-flight resolve/sniff job. Cancelled when the user picks another track. */
    private var resolveJob: Job? = null

    /** Where the currently loaded stream came from (drives the error failover). */
    private var currentSource: StreamSource = StreamSource.NONE

    /** Guards the SoundCloud failover so we only escalate once per track. */
    private var soundCloudTriedForCurrentTrack = false

    /** Guards the "one automatic re-resolve after a mid-playback error" behaviour. */
    private var transientRetryUsed = false

    /** Mirrors the user's "Autoplay Next Track" preference (defaults to on). */
    private var autoplayEnabled = true

    init {
        // Track the autoplay preference so `handlePlaybackEnded` can honour it. Read once
        // here and kept up to date by the collector; cheap and never blocks the UI.
        scope.launch {
            preferences.isAutoplayEnabled.collect { autoplayEnabled = it }
        }

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

                val track = _playbackState.value.currentTrack
                if (track == null) {
                    failPlayback(error)
                    return
                }

                // Failover trigger #2 from the spec ("persistent playback error"):
                // a sniffed YouTube URL can go stale - signatures expire and SABR chunks
                // end mid-stream. Rather than dead-ending on an error, silently re-resolve
                // this one track through SoundCloud. Guarded so we only escalate once.
                if (currentSource == StreamSource.YOUTUBE_SNIFFER && !soundCloudTriedForCurrentTrack) {
                    Log.w(TAG, "YouTube stream failed mid-playback - switching to SoundCloud fallback")
                    soundCloudTriedForCurrentTrack = true
                    failoverToSoundCloud(track)
                    return
                }

                // A SoundCloud stream can also drop on a flaky connection. One automatic
                // re-resolve (which now retries internally) turns a transient network
                // hiccup into a short buffering pause instead of an error message.
                if (currentSource == StreamSource.SOUNDCLOUD && !transientRetryUsed) {
                    Log.w(TAG, "SoundCloud stream error - one automatic re-resolve")
                    transientRetryUsed = true
                    failoverToSoundCloud(track)
                    return
                }

                failPlayback(error)
            }
        })
    }

    /** Surfaces a genuine, user-facing playback failure (after every retry was spent). */
    private fun failPlayback(error: PlaybackException?) {
        _playbackState.update {
            it.copy(
                isLoading = false,
                isPlaying = false,
                errorMessage = "Couldn't play this track. Check your connection and tap retry."
            )
        }
        Log.e(TAG, "Playback failed: ${error?.localizedMessage}")
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

    /**
     * Resolves [track] and starts playback, using the block-resistant dual-source
     * architecture:
     *
     *  1. **Local file** - if the track has been downloaded, just play it.
     *  2. **PRIMARY - headless WebView sniffer**: a real, invisible YouTube browser
     *     session. It is the only thing that can produce a working (n-transformed)
     *     googlevideo URL, because YouTube's `n` challenge must be solved by their own JS.
     *  3. **FALLBACK - SoundCloud public API**: used automatically when the sniffer does
     *     not capture a stream inside `YouTubeWebViewSniffer.SNIFF_TIMEOUT_MS` (5s).
     *
     * The custom native UI is untouched by all of this: it simply observes
     * [PlaybackState.isLoading], which stays true from the moment a song is tapped until
     * ExoPlayer reports STATE_READY/STATE_BUFFERING is over.
     */
    private fun resolveAndStartPlayback(track: Track) {
        // A new selection always wins: drop whatever the previous track was doing
        // (including a WebView that may still be sniffing) and reset the failover guard.
        resolveJob?.cancel()
        soundCloudTriedForCurrentTrack = false
        transientRetryUsed = false
        currentSource = StreamSource.NONE

        resolveJob = scope.launch {
            try {
                _playbackState.update { it.copy(isLoading = true, errorMessage = null) }

                // Record to history (best effort - never block or crash playback).
                runCatching { repository.recordPlayed(track) }
                    .onFailure { Log.w(TAG, "Failed to record play history: ${it.message}") }

                // 1. Local, already-downloaded track.
                val localFile = track.localFilePath
                    ?.takeIf { track.isDownloaded && !it.isNullOrBlank() }
                    ?.let { File(it) }
                    ?.takeIf { it.exists() }

                if (localFile != null) {
                    Log.i(TAG, "Playing local offline track: ${localFile.absolutePath}")
                    currentSource = StreamSource.LOCAL
                    startPlayback(StreamInfo(url = Uri.fromFile(localFile).toString()), track)
                    return@launch
                }

                // 2. + 3. Remote: sniffer first, SoundCloud fallback second.
                val streamInfo = resolveRemoteStream(track, useSniffer = true)
                if (streamInfo == null) {
                    Log.e(TAG, "Both sources failed for ${track.title}")
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = "Couldn't play this track. Check your connection and tap retry."
                        )
                    }
                    return@launch
                }

                startPlayback(streamInfo, track)
            } catch (e: CancellationException) {
                throw e
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

    /**
     * Tries the two remote sources in order and remembers which one won.
     *
     * @param useSniffer false to skip straight to SoundCloud (used by the failover).
     */
    private suspend fun resolveRemoteStream(track: Track, useSniffer: Boolean): StreamInfo? {
        if (useSniffer && looksLikeYouTubeVideoId(track.id)) {
            Log.i(TAG, "PRIMARY: sniffing YouTube via headless WebView (${track.id})")
            // CRITICAL for cross-device reliability: a WebView that is missing, disabled or
            // outdated (very common on a friend's phone) throws while being constructed. If
            // that exception escaped, the SoundCloud fallback below would never run and the
            // user would see "Could not play this track" even though a stream was available.
            val sniffed = try {
                YouTubeWebViewSniffer(context).sniff(track.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sniffer unavailable on this device (${e.message}) - using fallback")
                null
            }
            if (sniffed != null) {
                currentSource = StreamSource.YOUTUBE_SNIFFER
                Log.i(TAG, "Sniffer captured a ${sniffed.mimeType ?: "media"} stream")
                return sniffed
            }
            Log.w(
                TAG,
                "Sniffer timed out after ${YouTubeWebViewSniffer.SNIFF_TIMEOUT_MS}ms - " +
                    "FALLBACK: SoundCloud"
            )
        }

        return resolveSoundCloudWithRetry(track)
    }

    /**
     * SoundCloud fallback with a few retries and backoff.
     *
     * Resolving is a handful of small HTTP calls, so retrying is cheap and it means a slow
     * or briefly flaky connection produces a slightly longer "buffering" state instead of
     * an immediate failure.
     */
    private suspend fun resolveSoundCloudWithRetry(track: Track): StreamInfo? {
        for (attempt in 0 until SOUNDCLOUD_ATTEMPTS) {
            val stream = try {
                repository.resolveStreamUrl(track).getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud resolve attempt ${attempt + 1} failed: ${e.message}")
                null
            }

            if (stream != null && stream.url.isNotBlank()) {
                currentSource = StreamSource.SOUNDCLOUD
                Log.i(TAG, "FALLBACK: playing SoundCloud stream")
                return stream
            }

            if (attempt < SOUNDCLOUD_ATTEMPTS - 1) {
                delay(SOUNDCLOUD_RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        return null
    }

    /**
     * Re-resolves the current track through SoundCloud only. Called when a sniffed
     * YouTube stream dies mid-playback (expired signature / truncated SABR chunk).
     */
    private fun failoverToSoundCloud(track: Track) {
        resolveJob?.cancel()
        resolveJob = scope.launch {
            try {
                _playbackState.update { it.copy(isLoading = true, errorMessage = null) }

                val streamInfo = resolveRemoteStream(track, useSniffer = false)
                if (streamInfo == null) {
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = "Couldn't play this track. Check your connection and tap retry."
                        )
                    }
                    return@launch
                }

                startPlayback(streamInfo, track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SoundCloud failover failed: ${e.message}", e)
                _playbackState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = "Couldn't play this track. Check your connection and tap retry."
                    )
                }
            }
        }
    }

    /**
     * Hands a resolved stream to ExoPlayer natively (background audio, MediaSession
     * notification, Bluetooth controls - the WebView is fully out of the picture here).
     */
    private fun startPlayback(streamInfo: StreamInfo, track: Track) {
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(if (track.artworkUrl.isNotBlank()) Uri.parse(track.artworkUrl) else null)
            .build()

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(streamInfo.url))
            .setMediaId(track.id)
            .setMediaMetadata(mediaMetadata)

        // The mime hint matters for extension-less CDN URLs (".../videoplayback?...")
        // and for the SoundCloud HLS playlist.
        if (!streamInfo.mimeType.isNullOrBlank()) {
            mediaItemBuilder.setMimeType(streamInfo.mimeType)
        }

        // Make sure the media session service is running before we start.
        ensurePlaybackServiceStarted()

        exoPlayer.setMediaSource(buildMediaSource(mediaItemBuilder.build(), streamInfo.headers))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** YouTube video ids are 11 URL-safe characters; anything else skips the sniffer. */
    private fun looksLikeYouTubeVideoId(id: String): Boolean =
        id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun togglePlayPause() {
        try {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                val state = exoPlayer.playbackState
                if (state == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                } else if (state == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
                    // The player errored or was stopped - it needs a fresh prepare().
                    exoPlayer.prepare()
                }
                exoPlayer.playWhenReady = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "togglePlayPause failed: ${e.message}", e)
        }
    }

    /** Re-resolves and restarts the current track (used by the error "Retry" button). */
    fun retryCurrent() {
        val track = _playbackState.value.currentTrack ?: return
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "retry: reset failed: ${e.message}")
        }
        // A manual retry may pick a different source, so clear the failover guards too.
        soundCloudTriedForCurrentTrack = false
        transientRetryUsed = false
        currentSource = StreamSource.NONE
        _playbackState.update { it.copy(errorMessage = null, isLoading = true) }
        resolveAndStartPlayback(track)
    }

    /**
     * Aborts an in-flight resolve/load and returns the player to an idle (not-error) state.
     * Wired to the "Cancel" affordance shown while a track is buffering/resolving.
     */
    fun cancelPlayback() {
        try {
            resolveJob?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "cancel: job cancel failed: ${e.message}")
        }
        resolveJob = null
        try {
            exoPlayer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "cancel: stop failed: ${e.message}")
        }
        _playbackState.update { it.copy(isLoading = false, isPlaying = false, errorMessage = null) }
    }

    /** Moves the queue cursor to [index] and starts that track. */
    private fun switchToIndex(index: Int) {
        val track = _playbackState.value.queue.getOrNull(index) ?: return
        _playbackState.update { it.copy(currentIndex = index, currentTrack = track) }
        resolveAndStartPlayback(track)
    }

    fun playNext() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        val nextIndex = state.currentIndex + 1
        when {
            nextIndex < state.queue.size -> switchToIndex(nextIndex)
            // Repeat ALL (or manual Next while repeat is on) wraps to the start of the queue.
            state.repeatMode != RepeatMode.OFF -> switchToIndex(0)
            // End of the queue with no repeat: keep the finished track loaded and idle.
            else -> _playbackState.update { it.copy(isPlaying = false) }
        }
    }

    fun playPrevious() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        // Standard behaviour: more than 3s into the song restarts it instead of skipping back.
        if (exoPlayer.currentPosition > 3000L) {
            exoPlayer.seekTo(0)
            return
        }
        val prevIndex = state.currentIndex - 1
        if (prevIndex >= 0) {
            switchToIndex(prevIndex)
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
        // ExoPlayer is kept at REPEAT_MODE_OFF on purpose. We only ever load ONE media item
        // at a time, so letting ExoPlayer itself repeat would make "Repeat All" silently
        // loop the current track forever (and auto-advance would never run). Repeat is
        // handled manually in `handlePlaybackEnded` / `playNext` instead.
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
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
        val state = _playbackState.value

        // Repeat ONE: replay the current track. ExoPlayer's own repeat is OFF, so this is
        // the only place it can happen.
        if (state.repeatMode == RepeatMode.ONE) {
            exoPlayer.seekTo(0)
            exoPlayer.playWhenReady = true
            return
        }

        // Honour the "Autoplay Next Track" setting instead of always advancing.
        if (!autoplayEnabled) {
            Log.i(TAG, "Track ended; autoplay is disabled - stopping")
            _playbackState.update { it.copy(isPlaying = false) }
            return
        }

        // End of the queue without repeat: stop cleanly instead of looping or erroring.
        if (state.currentIndex >= state.queue.lastIndex && state.repeatMode != RepeatMode.ALL) {
            Log.i(TAG, "Playlist finished")
            _playbackState.update { it.copy(isPlaying = false) }
            return
        }

        playNext()
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
        resolveJob?.cancel()
        try {
            exoPlayer.release()
        } catch (e: Exception) {
            Log.w(TAG, "Player release failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MusicPlayerManager"

        /** How many times the cheap SoundCloud fallback is retried before giving up. */
        private const val SOUNDCLOUD_ATTEMPTS = 3

        /** Linear backoff between SoundCloud attempts (attempt 1 waits 900ms, etc.). */
        private const val SOUNDCLOUD_RETRY_BACKOFF_MS = 900L
    }
}
