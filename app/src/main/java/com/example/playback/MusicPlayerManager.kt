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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import coil.imageLoader
import coil.request.ImageRequest
import com.example.data.local.SuzieeDatabase
import com.example.data.preferences.SuzieePreferences
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.remote.lastfm.LastFmClient
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
    val preferences: SuzieePreferences,
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

    /** Real FFT capture from the active audio session, with a deterministic fallback. */
    private val visualizerManager = VisualizerManager()

    /** System equalizer for the active audio session. */
    private val equalizerManager = EqualizerManager()
    val equalizer: EqualizerManager get() = equalizerManager

    /** Sleep timer: 0 means off. */
    private var sleepTimerJob: Job? = null
    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes.asStateFlow()
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()

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

    /** Last.fm (P3-4) opt-in state. Null until every credential has been supplied. */
    @Volatile
    private var lastFmClient: LastFmClient? = null

    /** The track id already reported as "now playing", so a re-play does not re-send it. */
    private var lastFmNowPlayingId: String? = null

    /** Ids that have already been scrobbled, so a replay is only counted once. */
    private val lastFmScrobbledIds = mutableSetOf<String>()

    /**
     * Rebuilds the Last.fm client from the three opt-in preferences. Reads both credentials
     * synchronously from the DataStore snapshot on first call; the collector re-invokes this
     * on every change, so a freshly pasted session key becomes usable immediately.
     */
    private fun rebuildLastFmClient() {
        scope.launch {
            val enabled = preferences.isLastFmEnabled.first()
            val key = preferences.lastFmApiKey.first()
            val session = preferences.lastFmSessionKey.first()
            lastFmClient = if (enabled && key.isNotBlank() && session.isNotBlank()) {
                LastFmClient(key, session).also {
                    lastFmNowPlayingId = null
                }
            } else {
                null
            }
        }
    }

    /** P3-4: report the track as playing to Last.fm, when the user opted in. */
    private fun reportNowPlayingToLastFm(track: Track) {
        if (lastFmNowPlayingId == track.id) return
        lastFmNowPlayingId = track.id
        val client = lastFmClient ?: return
        scope.launch {
            runCatching { client.updateNowPlaying(track) }
        }
    }

    /** P3-4: count a finished play to Last.fm, once per track. */
    private fun scrobbleToLastFm(track: Track) {
        if (lastFmScrobbledIds.contains(track.id)) return
        lastFmScrobbledIds.add(track.id)
        val client = lastFmClient ?: return
        scope.launch {
            runCatching { client.scrobble(track) }
        }
    }

    /** P3-4: forget per-session state when the user resets the queue/track. */
    private fun resetLastFmSession() {
        lastFmNowPlayingId = null
    }

    /** When the current request was made; used to log startup latency. */
    private var requestStartedAtMs = 0L

    /** Mirrors the user's "Autoplay Next Track" preference (defaults to on). */
    private var autoplayEnabled = true

    // -----------------------------------------------------------------------------------------
    // Couple Space seam
    // -----------------------------------------------------------------------------------------

    /**
     * Optional listener installed by Couple Space. It is null for users who never pair, so
     * the existing player keeps its original zero-overhead path.
     */
    var playbackSyncSink: PlaybackSyncSink? = null

    /** True while a remote command is being applied; suppresses the echo-back event. */
    private var applyingRemote = false

    /** A short tail guard for asynchronous ExoPlayer callbacks. */
    private var suppressSyncUntil = 0L

    /** Applied once the remote track is actually ready, not while it is still resolving. */
    private var pendingRemoteSeekMs: Long = -1L
    private var pendingRemotePlayWhenReady: Boolean? = null

    init {
        // Track the autoplay preference so `handlePlaybackEnded` can honour it. Read once
        // here and kept up to date by the collector; cheap and never blocks the UI.
        scope.launch {
            preferences.isAutoplayEnabled.collect { autoplayEnabled = it }
        }

        // Build the Last.fm client only when the user has enabled scrobbling *and* supplied
        // credentials. A partial setup (enabled but no key) simply stays disabled.
        // Each collector updates its own field and rebuilds, so a change to any of the three
        // Settings values is reflected immediately.
        scope.launch {
            preferences.isLastFmEnabled.collect { rebuildLastFmClient() }
        }
        scope.launch {
            preferences.lastFmApiKey.collect { rebuildLastFmClient() }
        }
        scope.launch {
            preferences.lastFmSessionKey.collect { rebuildLastFmClient() }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    visualizerManager.attach(exoPlayer.audioSessionId)
                    equalizerManager.attach(exoPlayer.audioSessionId)
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
                        // A remote TRACK command may arrive before the stream is ready.
                        // Apply its seek/pause intent now, once ExoPlayer can honour it.
                        val pendingSeek = pendingRemoteSeekMs
                        if (pendingSeek >= 0L) {
                            pendingRemoteSeekMs = -1L
                            runCatching { exoPlayer.seekTo(pendingSeek) }
                            _playbackState.update { it.copy(currentPositionMs = pendingSeek) }
                        }
                        pendingRemotePlayWhenReady?.let { shouldPlay ->
                            pendingRemotePlayWhenReady = null
                            runCatching { exoPlayer.playWhenReady = shouldPlay }
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
        pendingRemoteSeekMs = -1L
        pendingRemotePlayWhenReady = null
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
        emitSync(
            type = PlaybackSyncEventType.TRACK,
            track = track,
            queue = selection.queue,
            positionMs = 0L,
            playing = true
        )
        // Warm Coil's cache for the next song so the art is already decoded when it starts.
        preloadArtworkFor(selection.currentIndex + 1)
    }

    /**
     * P1-4: prefetch the next track's artwork. Runs on Coil's own executor, so this only
     * queues work and never blocks the playback path.
     */
    private fun preloadArtworkFor(index: Int) {
        val url = _playbackState.value.queue.getOrNull(index)?.artworkUrl ?: return
        if (url.isBlank()) return
        try {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).build()
            )
        } catch (e: Exception) {
            Log.d(TAG, "Artwork preload skipped: ${e.message}")
        }
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

        // P3-4: report "now playing" once per track. Skipped entirely when the user has
        // not opted into Last.fm, in which case lastFmClient is null.
        resetLastFmSession()
        reportNowPlayingToLastFm(track)
    }

    fun togglePlayPause() {
        val willPlay = !exoPlayer.isPlaying
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
            emitSync(
                type = if (willPlay) PlaybackSyncEventType.PLAY else PlaybackSyncEventType.PAUSE,
                track = _playbackState.value.currentTrack,
                queue = _playbackState.value.queue,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                playing = willPlay
            )
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
        emitSync(
            type = PlaybackSyncEventType.TRACK,
            track = track,
            queue = _playbackState.value.queue,
            positionMs = 0L,
            playing = true
        )
        preloadArtworkFor(index + 1)
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
        emitSync(
            type = PlaybackSyncEventType.QUEUE,
            track = _playbackState.value.currentTrack,
            queue = updatedQueue,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            playing = exoPlayer.isPlaying
        )
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
            _playbackState.update { it.copy(currentPositionMs = 0L) }
            emitSync(
                type = PlaybackSyncEventType.SEEK,
                track = state.currentTrack,
                queue = state.queue,
                positionMs = 0L,
                playing = exoPlayer.isPlaying
            )
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
        emitSync(
            type = PlaybackSyncEventType.SEEK,
            track = _playbackState.value.currentTrack,
            queue = _playbackState.value.queue,
            positionMs = positionMs,
            playing = exoPlayer.isPlaying
        )
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
        emitSync(
            type = PlaybackSyncEventType.QUEUE,
            track = currentTrack,
            queue = newQueue,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            playing = exoPlayer.isPlaying
        )
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
            emitSync(
                type = PlaybackSyncEventType.QUEUE,
                track = _playbackState.value.currentTrack,
                queue = updated,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                playing = exoPlayer.isPlaying
            )
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
        emitSync(
            type = PlaybackSyncEventType.QUEUE,
            track = _playbackState.value.currentTrack,
            queue = currentQueue,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            playing = exoPlayer.isPlaying
        )
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
        emitSync(
            type = PlaybackSyncEventType.QUEUE,
            track = null,
            queue = emptyList(),
            positionMs = 0L,
            playing = false
        )
    }

    /**
     * Applies a command received from the paired device.
     *
     * This is the only entry point the Couple Space networking layer has into the player.
     * The [applyingRemote] guard and the short suppression window prevent the resulting
     * callbacks from being re-broadcast as new local commands, which is what would otherwise
     * create an infinite play/pause or seek echo between the two phones.
     */
    fun applyRemotePlayback(command: RemotePlaybackCommand) {
        applyingRemote = true
        try {
            when (command.action) {
                RemotePlaybackAction.TRACK -> {
                    val remoteTrack = command.track ?: return
                    val queue = command.queue.ifEmpty { listOf(remoteTrack) }
                    val sameTrack = _playbackState.value.currentTrack?.id == remoteTrack.id
                    if (!sameTrack) {
                        playTrack(remoteTrack, queue)
                    } else {
                        _playbackState.update { it.copy(queue = queue) }
                    }

                    val targetPosition = command.positionMs.coerceAtLeast(0L)
                    pendingRemoteSeekMs = targetPosition
                    pendingRemotePlayWhenReady = command.playing
                    if (exoPlayer.playbackState == Player.STATE_READY) {
                        pendingRemoteSeekMs = -1L
                        exoPlayer.seekTo(targetPosition)
                        _playbackState.update { it.copy(currentPositionMs = targetPosition) }
                        exoPlayer.playWhenReady = command.playing
                        pendingRemotePlayWhenReady = null
                    }
                    if (command.speed in 0.5f..2.0f) {
                        exoPlayer.setPlaybackParameters(PlaybackParameters(command.speed))
                    }
                }

                RemotePlaybackAction.PLAY, RemotePlaybackAction.RESUME -> {
                    if (exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
                        exoPlayer.prepare()
                    }
                    exoPlayer.playWhenReady = true
                }

                RemotePlaybackAction.PAUSE -> exoPlayer.pause()

                RemotePlaybackAction.SEEK -> {
                    val position = command.positionMs.coerceAtLeast(0L)
                    exoPlayer.seekTo(position)
                    _playbackState.update { it.copy(currentPositionMs = position) }
                }

                RemotePlaybackAction.NEXT -> playNext()
                RemotePlaybackAction.PREVIOUS -> playPrevious()
                RemotePlaybackAction.RATE -> {
                    if (command.speed in 0.5f..2.0f) {
                        exoPlayer.setPlaybackParameters(PlaybackParameters(command.speed))
                    }
                }

                RemotePlaybackAction.QUEUE -> {
                    if (command.queue.isNotEmpty()) {
                        _playbackState.update { it.copy(queue = command.queue) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote playback apply failed: ${e.message}")
        } finally {
            applyingRemote = false
            suppressSyncUntil = SystemClock.elapsedRealtime() + REMOTE_SYNC_SUPPRESS_MS
        }
    }

    /**
     * Emits an intent-level event only for real local user actions. Progress ticks are
     * deliberately not emitted; Couple Space derives live position from the clock instead of
     * flooding the DataChannel.
     */
    private fun emitSync(
        type: PlaybackSyncEventType,
        track: Track?,
        queue: List<Track>,
        positionMs: Long,
        playing: Boolean
    ) {
        if (applyingRemote) return
        if (SystemClock.elapsedRealtime() < suppressSyncUntil) return
        val sink = playbackSyncSink ?: return
        val event = PlaybackSyncEvent(
            type = type,
            track = track,
            queue = queue,
            positionMs = positionMs.coerceAtLeast(0L),
            isPlaying = playing,
            speed = runCatching { exoPlayer.playbackParameters.speed }.getOrDefault(1f),
            issuedAt = System.currentTimeMillis()
        )
        runCatching { sink.onLocalPlaybackEvent(event) }
            .onFailure { Log.w(TAG, "Playback sync sink failed: ${it.message}") }
    }

    private fun handlePlaybackEnded() {
        val state = _playbackState.value

        // P3-4: a track that ran to the end has been genuinely listened to, so it counts as a
        // valid Last.fm play. No-op when the user has not opted in (lastFmClient is null).
        state.currentTrack?.let { scrobbleToLastFm(it) }

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
                    // Prefer real FFT data; fall back to a deterministic position-driven
                    // waveform when the platform denies capture (e.g. RECORD_AUDIO not granted).
                    val captured = visualizerManager.latestAmplitudes()
                    val amps = if (captured.any { it > 0.12f }) {
                        captured
                    } else {
                        visualizerManager.synthetic(pos, playing = true)
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
        // Zero the bars while paused so the visualizer looks quiet, not random.
        visualizerManager.pause()
        _playbackState.update {
            it.copy(visualizerAmplitudes = List(16) { 0.08f })
        }
    }

    /**
     * Stops playback after [minutes] minutes. Passing 0 or a negative value cancels it.
     * The remaining time is exposed so a dialog/notification can show a countdown.
     */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerMinutes.value = 0
            _sleepTimerRemainingMs.value = 0L
            return
        }
        _sleepTimerMinutes.value = minutes
        _sleepTimerRemainingMs.value = minutes * 60_000L
        sleepTimerJob = scope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0 && isActive) {
                delay(1_000L)
                remaining -= 1_000L
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0L)
            }
            if (remaining <= 0) {
                _sleepTimerMinutes.value = 0
                _sleepTimerRemainingMs.value = 0L
                exoPlayer.pause()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerMinutes.value = 0
        _sleepTimerRemainingMs.value = 0L
    }

    fun release() {
        playbackSyncSink = null
        stopProgressTracker()
        visualizerManager.detach()
        equalizerManager.release()
        sleepTimerJob?.cancel()
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

        /** Tail guard so asynchronous ExoPlayer callbacks cannot echo a remote command. */
        private const val REMOTE_SYNC_SUPPRESS_MS = 900L
    }
}
