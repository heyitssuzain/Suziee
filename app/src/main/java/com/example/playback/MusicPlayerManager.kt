package com.example.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
import com.example.data.remote.streaming.OnlineStreamResolver
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
     * The online source: the native YouTube player-API client.
     *
     * Shared with [com.example.downloads.DownloadManager] through the application, so
     * playback and offline saving resolve through exactly the same provider.
     */
    val streamResolver = OnlineStreamResolver(context)

    /**
     * The resolver plus the one-shot failover guard. It never touches ExoPlayer - the
     * player handoff stays here.
     */
    private val coordinator = PlaybackCoordinator(streamResolver)

    /**
     * Default media HTTP headers.
     *
     * Media CDNs are picky: googlevideo answers 403 without a matching User-Agent and a
     * YouTube Referer. These are the baseline applied to every stream; a resolved
     * [StreamInfo] can add or override entries (the ANDROID/IOS User-Agent used to obtain
     * the URL).
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
        // Step used by the notification's / lock screen's seek-back and seek-forward
        // buttons (these setters live on the builder in Media3 1.5.x). The next/previous
        // *track* buttons are advertised by the MediaSession in SuzieeMediaService.
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()
        .apply {
            // Audio focus enabled (`handleAudioFocus = true`): ducking, pausing for calls and
            // resuming afterwards are handled by Media3/ExoPlayer, and a focus loss never
            // leaves a stale notification behind.
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

    /** The in-flight resolve/extraction job. Cancelled when the user picks another track. */
    private var resolveJob: Job? = null

    /**
     * Identifies the latest resolve request. A resolver call may block in OkHttp even after
     * its coroutine is cancelled; comparing this token before handing a result to ExoPlayer
     * keeps a late result for the old song from replacing the song the user just chose.
     */
    private var requestGeneration = 0L

    /** True while the one bounded automatic re-resolve for the current track is running. */
    private var recovering = false

    /** ENDED is delivered again by some players/OEM controllers; advance at most once. */
    private var lastHandledEndedMediaId: String? = null

    /** When the current request was made; used to log startup latency. */
    private var requestStartedAtMs = 0L

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
                        // Startup latency evidence: how long the user waited between tapping
                        // the song and the player being ready to produce audio.
                        if (requestStartedAtMs > 0L) {
                            Log.i(
                                TAG,
                                "First audio ready in " +
                                    "${SystemClock.elapsedRealtime() - requestStartedAtMs}ms " +
                                    "(source=${coordinator.currentSource})"
                            )
                            requestStartedAtMs = 0L
                        }
                        _playbackState.update {
                            it.copy(
                                isLoading = false,
                                durationMs = duration,
                                errorMessage = null
                            )
                        }
                    }
                    Player.STATE_ENDED -> {
                        val mediaId = exoPlayer.currentMediaItem?.mediaId
                        val currentId = _playbackState.value.currentTrack?.id
                        if (!PlaybackEventPolicy.shouldHandleEnded(
                                eventMediaId = mediaId,
                                currentTrackId = currentId,
                                lastHandledMediaId = lastHandledEndedMediaId
                            )
                        ) {
                            Log.w(
                                TAG,
                                "Ignoring stale/duplicate ENDED (item=$mediaId, current=$currentId)"
                            )
                            return
                        }
                        lastHandledEndedMediaId = mediaId
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

                // Ignore an error from the media item that was already replaced. This is
                // common when a user presses Next while the previous URL is still opening:
                // the old source fails after the new request has started.
                val failedMediaId = exoPlayer.currentMediaItem?.mediaId
                if (!PlaybackEventPolicy.isForCurrentTrack(failedMediaId, track.id)) {
                    Log.w(
                        TAG,
                        "Ignoring stale player error (item=$failedMediaId, current=${track.id})"
                    )
                    return
                }

                // A second error callback can arrive while the first recovery is still
                // resolving. Acting on it would burn the retry budget and briefly show a
                // false "Couldn't play" error even though the recovery is about to succeed.
                if (recovering) {
                    Log.i(TAG, "Player error while recovery is in progress - ignored")
                    return
                }

                // Remember where we were, so a failover resumes mid-song instead of
                // restarting the recording from the beginning.
                val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)

                // A resolved googlevideo URL can go stale mid-track (expired signature or
                // a dropped CDN connection). Re-resolve this one track through the ladder
                // instead of dead-ending; the coordinator bounds the attempts.
                if (PlaybackFailurePolicy.isRecoverable(
                        error.errorCode,
                        coordinator.currentSource
                    ) &&
                    coordinator.claimFailover()
                ) {
                    Log.w(
                        TAG,
                        "Stream failed mid-playback (was ${coordinator.currentSource}, " +
                            "code=${error.errorCode}) - re-resolving"
                    )
                    recoverWithFallback(track, resumePositionMs)
                    return
                }

                failPlayback(error)
            }
        })
    }

    /** Surface an honest, user-facing failure (after every retry was spent). */
    private fun failPlayback(error: PlaybackException?) {
        _playbackState.update {
            it.copy(
                isLoading = false,
                isPlaying = false,
                errorMessage = "Couldn't play this track. Tap retry."
            )
        }
        Log.e(TAG, "Playback failed: ${error?.localizedMessage}")
    }

    /**
     * Honest "nothing playable" text for a track that could not be resolved at all.
     * No substitute recording is ever played, so this is a real failure, not a mismatch.
     */
    private fun unplayableMessage(): String =
        "Couldn't play this track. Tap retry."

    /**
     * Makes sure the media session service is running so the notification and lock
     * screen reflect playback.
     *
     * This is deliberately called on **every** playback start and is idempotent:
     * `startService` on an already-running service merely delivers another
     * `onStartCommand`. The old one-shot `serviceStarted` flag was a bug - after the
     * service stopped itself (e.g. `onTaskRemoved` with a paused player) the flag still
     * said "started", so every later track played with no session and no notification.
     */
    private fun ensurePlaybackServiceStarted() {
        try {
            val intent = Intent(context, SuzieeMediaService::class.java)
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SuzieeMediaService: ${e.message}", e)
        }
    }

    fun playTrack(track: Track, newQueue: List<Track> = listOf(track)) {
        val selection = PlaybackQueue.select(
            track = track,
            requested = newQueue,
            shuffle = _playbackState.value.isShuffle
        )
        originalQueue = selection.originalQueue

        // Start of the user-visible latency window (used by the startup timing logs).
        requestStartedAtMs = SystemClock.elapsedRealtime()

        _playbackState.update {
            it.copy(
                queue = selection.queue,
                currentIndex = selection.currentIndex,
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
     * Resolves [track] and starts playback:
     *
     *  1. **Local file** - if the track has been downloaded, just play it.
     *  2. **Online** - the native YouTube player-API client resolves the requested video
     *     id to one audio-only stream (Opus/AAC). There is no Python/yt-dlp and no WebView
     *     on this path.
     *
     * The custom native UI is untouched by all of this: it simply observes
     * [PlaybackState.isLoading], which stays true from the moment a song is tapped until
     * ExoPlayer reports STATE_READY/STATE_BUFFERING is over.
     */
    private fun resolveAndStartPlayback(track: Track) {
        // A new selection always wins: drop whatever the previous track was doing and
        // reset the failover guard. The generation token also rejects a result that was
        // already blocked in the network stack when the cancel was requested.
        resolveJob?.cancel()
        coordinator.resetForNewTrack()
        recovering = false
        lastHandledEndedMediaId = null
        val generation = ++requestGeneration

        resolveJob = scope.launch {
            try {
                _playbackState.update { it.copy(isLoading = true, errorMessage = null) }

                // Bring the media session service up as soon as a track is selected, not
                // only once a stream URL exists: the session then already exists when
                // playback starts, so the notification/lock-screen surfaces cannot miss the
                // first update. Idempotent (see ensurePlaybackServiceStarted).
                ensurePlaybackServiceStarted()

                // Record to history off the critical path: a DB write must never delay
                // resolution or playback. Failures are logged, nothing more.
                scope.launch {
                    runCatching { repository.recordPlayed(track) }
                        .onFailure { Log.w(TAG, "Failed to record play history: ${it.message}") }
                }

                // 1. Local, already-downloaded track.
                val localFile = track.localFilePath
                    ?.takeIf { track.isDownloaded && !it.isNullOrBlank() }
                    ?.let { File(it) }
                    ?.takeIf { it.exists() }

                if (localFile != null) {
                    if (generation != requestGeneration) return@launch
                    Log.i(TAG, "Playing local offline track: ${localFile.absolutePath}")
                    coordinator.noteLocalSource()
                    startPlayback(StreamInfo(url = Uri.fromFile(localFile).toString()), track)
                    return@launch
                }

                // 2. Remote: the native YouTube player API.
                val streamInfo = coordinator.resolve(track)
                if (generation != requestGeneration) {
                    Log.i(TAG, "Dropping stale resolve result for '${track.title}'")
                    return@launch
                }
                if (streamInfo == null) {
                    Log.e(
                        TAG,
                        "No stream for '${track.title}' (${streamResolver.lastFailure() ?: "unknown"})"
                    )
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = unplayableMessage()
                        )
                    }
                    return@launch
                }

                startPlayback(streamInfo, track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != requestGeneration) return@launch
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
     * Re-resolves the current track. Called when a stream dies mid-playback (expired
     * signature / dropped CDN connection).
     *
     * @param resumePositionMs playback continues from here so the user does not hear
     * the song restart from the beginning after a mid-track failure.
     */
    private fun recoverWithFallback(track: Track, resumePositionMs: Long = 0L) {
        if (recovering) return
        recovering = true
        resolveJob?.cancel()
        val generation = ++requestGeneration
        resolveJob = scope.launch {
            try {
                // Small backoff: a dropped socket or a just-expired URL is often ready on
                // the next request, and this avoids hammering the player endpoint.
                delay(RECOVERY_BACKOFF_MS)
                _playbackState.update { it.copy(isLoading = true, errorMessage = null) }

                val streamInfo = coordinator.resolve(track)
                if (generation != requestGeneration) {
                    Log.i(TAG, "Dropping stale recovery result for '${track.title}'")
                    return@launch
                }
                if (streamInfo == null) {
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = unplayableMessage()
                        )
                    }
                    return@launch
                }

                startPlayback(streamInfo, track, resumePositionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != requestGeneration) return@launch
                Log.e(TAG, "Recovery resolve failed: ${e.message}", e)
                _playbackState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = unplayableMessage()
                    )
                }
            } finally {
                if (generation == requestGeneration) recovering = false
            }
        }
    }

    /**
     * Hands a resolved stream to ExoPlayer natively (background audio, MediaSession
     * notification, Bluetooth controls - no browser engine is involved).
     *
     * @param startPositionMs optional position to resume from (failover path).
     */
    private fun startPlayback(streamInfo: StreamInfo, track: Track, startPositionMs: Long = 0L) {
        lastHandledEndedMediaId = null
        recovering = false

        // Complete, immutable metadata for every surface at once: the notification panel and
        // the lock screen render title/artist/artwork from *this* object, so it is built in
        // full before the item is handed to the player. `isBrowsable = false` (the 1.5.x
        // replacement for FOLDER_TYPE_NONE) + `isPlayable` tell the system this is a playable
        // track and not a folder, which is what stops some OEM media controls from collapsing
        // the notification into a folder row.
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(if (track.artworkUrl.isNotBlank()) Uri.parse(track.artworkUrl) else null)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(streamInfo.url))
            .setMediaId(track.id)
            .setMediaMetadata(mediaMetadata)

        // The mime hint matters for extension-less CDN URLs (".../videoplayback?...")
        // and for an HLS manifest, if the extractor produced one.
        if (!streamInfo.mimeType.isNullOrBlank()) {
            mediaItemBuilder.setMimeType(streamInfo.mimeType)
        }

        // Make sure the media session service is running before we start.
        ensurePlaybackServiceStarted()

        exoPlayer.setMediaSource(buildMediaSource(mediaItemBuilder.build(), streamInfo.headers))
        // Resume position must be applied before prepare() so the seek is honoured
        // during the initial open, not after playback has already started at 0.
        if (startPositionMs > 0L) {
            exoPlayer.seekTo(startPositionMs)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun togglePlayPause() {
        try {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                val state = exoPlayer.playbackState
                if (state == Player.STATE_ENDED) {
                    // A user replay of the finished item must be allowed to advance again
                    // when it ends; clear the duplicate-ENDED guard.
                    lastHandledEndedMediaId = null
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
        coordinator.resetForNewTrack()
        _playbackState.update { it.copy(errorMessage = null, isLoading = true) }
        resolveAndStartPlayback(track)
    }

    /**
     * Aborts an in-flight resolve/load and returns the player to an idle (not-error) state.
     * Wired to the "Cancel" affordance shown while a track is buffering/resolving.
     */
    fun cancelPlayback() {
        requestGeneration++
        recovering = false
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
        val nextIndex = PlaybackQueue.manualNextIndex(
            currentIndex = state.currentIndex,
            queueSize = state.queue.size,
            repeatMode = state.repeatMode
        )
        if (nextIndex != null) {
            switchToIndex(nextIndex)
        } else {
            // End of the queue without repeat: keep the finished track loaded and idle.
            _playbackState.update { it.copy(isPlaying = false) }
        }
    }

    /**
     * Inserts [track] directly after the currently playing item. The cursor is not moved,
     * so the inserted song starts only when its turn arrives.
     */
    fun playNext(track: Track) {
        val state = _playbackState.value
        val currentId = state.currentTrack?.id
        if (currentId == null || state.queue.isEmpty()) {
            playTrack(track)
            return
        }

        val updatedQueue = PlaybackQueue.insertNext(state.queue, state.currentIndex, track)
        originalQueue = if (state.isShuffle) {
            val originalIndex = originalQueue.indexOfFirst { it.id == currentId }
            if (originalIndex >= 0) {
                PlaybackQueue.insertNext(originalQueue, originalIndex, track)
            } else {
                originalQueue + track
            }
        } else {
            updatedQueue
        }
        _playbackState.update { it.copy(queue = updatedQueue) }
        Log.i(
            TAG,
            "Queued '${track.title}' next after '${state.currentTrack.title}' " +
                "(position ${state.currentIndex + 1})"
        )
    }

    fun playPrevious() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        // Standard behaviour: more than 3s into the song restarts it instead of skipping back.
        if (exoPlayer.currentPosition > 3000L) {
            exoPlayer.seekTo(0)
            return
        }
        val prevIndex = PlaybackQueue.previousIndex(state.currentIndex, state.queue.size)
        if (prevIndex != null) {
            switchToIndex(prevIndex)
        } else {
            exoPlayer.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            lastHandledEndedMediaId = null
        }
        exoPlayer.seekTo(positionMs)
        _playbackState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun toggleShuffle() {
        val currentShuffle = !_playbackState.value.isShuffle
        val currentTrack = _playbackState.value.currentTrack
        val newQueue = if (currentShuffle) {
            PlaybackQueue.shuffledWithCurrentFirst(originalQueue, currentTrack)
        } else {
            originalQueue
        }
        val newIndex = if (currentTrack != null) {
            newQueue.indexOfFirst { it.id == currentTrack.id }
        } else {
            0
        }
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
        val state = _playbackState.value
        val currentQueue = state.queue.toMutableList()
        val removeIndex = currentQueue.indexOfFirst { it.id == trackId }
        if (removeIndex == -1) return

        currentQueue.removeAt(removeIndex)
        val originalIndex = originalQueue.indexOfFirst { it.id == trackId }
        if (originalIndex >= 0) {
            originalQueue = originalQueue.toMutableList().apply { removeAt(originalIndex) }
        }
        val newIndex = when {
            currentQueue.isEmpty() -> -1
            removeIndex < state.currentIndex -> state.currentIndex - 1
            removeIndex == state.currentIndex -> state.currentIndex - 1
            else -> state.currentIndex
        }
        _playbackState.update { it.copy(queue = currentQueue, currentIndex = newIndex) }
    }

    fun clearQueue() {
        requestGeneration++
        recovering = false
        lastHandledEndedMediaId = null
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
            lastHandledEndedMediaId = null
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

        val nextIndex = PlaybackQueue.autoAdvanceIndex(
            currentIndex = state.currentIndex,
            queueSize = state.queue.size,
            repeatMode = state.repeatMode
        )
        if (nextIndex == null) {
            Log.i(TAG, "Playlist finished")
            _playbackState.update { it.copy(isPlaying = false) }
            return
        }

        switchToIndex(nextIndex)
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

        /** Step used by the notification's seek-back / seek-forward buttons. */
        private const val SEEK_INCREMENT_MS = 10_000L

        /** Small delay before re-resolving a failed stream, to avoid hammering the API. */
        private const val RECOVERY_BACKOFF_MS = 350L
    }
}
