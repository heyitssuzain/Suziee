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
import com.example.data.repository.MusicRepository
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
        // Step used by the notification's / lock screen's seek-back and seek-forward
        // buttons (these setters live on the builder in Media3 1.5.x). The next/previous
        // *track* buttons are advertised by the MediaSession in MediaPlaybackService.
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
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

    /** The in-flight resolve/sniff job. Cancelled when the user picks another track. */
    private var resolveJob: Job? = null

    /** Where the currently loaded stream came from (drives the error failover). */
    private var currentSource: StreamSource = StreamSource.NONE

    /** Guards the SoundCloud failover so we only escalate once per track. */
    private var soundCloudTriedForCurrentTrack = false

    /** When the current request was made; used to log startup latency. */
    private var requestStartedAtMs = 0L

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
                        // Startup latency evidence: how long the user waited between tapping
                        // the song and the player being ready to produce audio.
                        if (requestStartedAtMs > 0L) {
                            Log.i(
                                TAG,
                                "First audio ready in " +
                                    "${SystemClock.elapsedRealtime() - requestStartedAtMs}ms " +
                                    "(source=$currentSource)"
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

                // Remember where we were, so a failover resumes mid-song instead of
                // restarting the recording from the beginning.
                val resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)

                // Failover trigger #2 from the spec ("persistent playback error"):
                // a sniffed YouTube URL can go stale - signatures expire and SABR chunks
                // end mid-stream. Rather than dead-ending on an error, silently re-resolve
                // this one track through SoundCloud. Guarded so we only escalate once.
                if (currentSource == StreamSource.YOUTUBE_SNIFFER && !soundCloudTriedForCurrentTrack) {
                    Log.w(TAG, "YouTube stream failed mid-playback - switching to SoundCloud fallback")
                    soundCloudTriedForCurrentTrack = true
                    failoverToSoundCloud(track, resumePositionMs)
                    return
                }

                // A SoundCloud stream can also drop on a flaky connection. One automatic
                // re-resolve (which now retries internally) turns a transient network
                // hiccup into a short buffering pause instead of an error message.
                if (currentSource == StreamSource.SOUNDCLOUD && !transientRetryUsed) {
                    Log.w(TAG, "SoundCloud stream error - one automatic re-resolve")
                    transientRetryUsed = true
                    failoverToSoundCloud(track, resumePositionMs)
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
                errorMessage = "Couldn't play this track. Check your connection and tap retry."
            )
        }
        Log.e(TAG, "Playback failed: ${error?.localizedMessage}")
    }

    /**
     * Honest "nothing playable" text for a track that could not be resolved at all.
     *
     * For a YouTube catalogue request we deliberately refuse to substitute a different
     * recording, so the message states that instead of blaming the network (which is what
     * made the old behaviour look like a connection problem when the app had actually
     * found only a wrong/fan-made version).
     */
    private fun unplayableMessage(track: Track): String =
        if (looksLikeYouTubeVideoId(track.id)) {
            "Couldn't play the official recording of this song. " +
                "No acceptable matching source was found - tap retry."
        } else {
            "Couldn't play this track. Check your connection and tap retry."
        }

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
            val intent = Intent(context, MediaPlaybackService::class.java)
            context.startService(intent)
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

        // Start of the user-visible latency window (used by the startup timing logs).
        requestStartedAtMs = SystemClock.elapsedRealtime()

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
     *     not capture a stream inside `YouTubeWebViewSniffer.SNIFF_TIMEOUT_MS` (8s). It is
     *     resolved in parallel (hedged) once the sniffer has been running for
     *     `FALLBACK_HEDGE_DELAY_MS`, so a sniffer failure does not add a second wait.
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
                            errorMessage = unplayableMessage(track)
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
        // A YouTube id means the user asked for a specific catalogue recording. If the
        // sniffer cannot produce it, SoundCloud may only be used as an acceptable
        // substitute - never an unrelated/slowed/cover recording.
        val officialRequest = looksLikeYouTubeVideoId(track.id)

        if (useSniffer && officialRequest) {
            Log.i(TAG, "PRIMARY: sniffing YouTube via headless WebView (${track.id})")
            val startedAt = SystemClock.elapsedRealtime()

            // Hedged request: the fallback resolve is started in the background while the
            // sniffer is still working, but only after HEDGE_DELAY_MS - so a normal, fast
            // sniffer (usually well under 2s) costs no extra network call at all, while a
            // slow/failing sniffer no longer pays for the fallback *after* its 8s window.
            // Only resolution is parallel; playback stays strictly single-source.
            // Scoped to the current resolve job so selecting another track cancels it too.
            val hedgedFallback = CoroutineScope(currentCoroutineContext()).async {
                try {
                    delay(FALLBACK_HEDGE_DELAY_MS)
                    Log.i(
                        TAG,
                        "HEDGE: sniffer still unresolved after ${FALLBACK_HEDGE_DELAY_MS}ms - " +
                            "resolving SoundCloud in parallel"
                    )
                    resolveSoundCloudWithRetry(track, strict = officialRequest)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let the hedge fail the resolve job: the sniffer may still win.
                    Log.w(TAG, "Hedged fallback failed: ${e.message}")
                    null
                }
            }

            // CRITICAL for cross-device reliability: a WebView that is missing, disabled or
            // outdated (very common on a friend's phone) throws while being constructed. If
            // that exception escaped, the SoundCloud fallback below would never run and the
            // user would see "Could not play this track" even though a stream was available.
            val sniffed = try {
                YouTubeWebViewSniffer(context).sniff(track.id)
            } catch (e: CancellationException) {
                hedgedFallback.cancel()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sniffer unavailable on this device (${e.message}) - using fallback")
                null
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            if (sniffed != null) {
                currentSource = StreamSource.YOUTUBE_SNIFFER
                // The primary won: drop the hedged fallback (it is usually still waiting on
                // its delay, so this normally also avoids the extra request entirely).
                hedgedFallback.cancel()
                Log.i(
                    TAG,
                    "PRIMARY OK: ${sniffed.mimeType ?: "media"} stream in ${elapsed}ms"
                )
                return sniffed
            }

            Log.w(
                TAG,
                "PRIMARY FAILED: sniffer gave no stream after ${elapsed}ms " +
                    "(window ${YouTubeWebViewSniffer.SNIFF_TIMEOUT_MS}ms) - using hedged " +
                    "SoundCloud fallback (strict=$officialRequest)"
            )

            val fallback = try {
                hedgedFallback.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Hedged fallback failed: ${e.message}")
                null
            }
            if (fallback != null) {
                currentSource = StreamSource.SOUNDCLOUD
                Log.i(
                    TAG,
                    "FALLBACK OK: SoundCloud stream - total "
                        + "${SystemClock.elapsedRealtime() - startedAt}ms after tap"
                )
            }
            return fallback
        }

        val fallback = resolveSoundCloudWithRetry(track, strict = officialRequest)
        if (fallback != null) currentSource = StreamSource.SOUNDCLOUD
        return fallback
    }

    /**
     * SoundCloud fallback with a bounded retry.
     *
     * Deliberately short: the previous revision retried three times with a linear backoff,
     * which added up to ~2.7s of pure waiting *after* the 8s sniffer window had already
     * elapsed. Two attempts with a 400ms pause keeps one retry for a flaky connection
     * without turning a failure into a multi-second stall. It also does **not** touch
     * [currentSource] - the caller owns that, because during a hedged resolve this method
     * can complete while the sniffer is still the source we are about to play.
     *
     * @param strict applies the substitution gate (see
     * [com.example.data.repository.MusicRepository.resolveStreamUrl]). When on and nothing
     * acceptable is found, it returns null on purpose: the caller shows an honest error
     * instead of playing a different recording.
     */
    private suspend fun resolveSoundCloudWithRetry(track: Track, strict: Boolean): StreamInfo? {
        var attempt = 0
        while (attempt < SOUNDCLOUD_ATTEMPTS) {
            attempt++
            val stream = try {
                repository.resolveStreamUrl(track, strict = strict).getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud resolve attempt $attempt failed: ${e.message}")
                null
            }

            if (stream != null && stream.url.isNotBlank()) {
                Log.i(TAG, "SoundCloud stream resolved (strict=$strict, attempt $attempt)")
                return stream
            }

            if (attempt < SOUNDCLOUD_ATTEMPTS) {
                delay(SOUNDCLOUD_RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    /**
     * Re-resolves the current track through SoundCloud only. Called when a sniffed
     * YouTube stream dies mid-playback (expired signature / truncated SABR chunk).
     *
     * @param resumePositionMs playback continues from here so the user does not hear
     * the song restart from the beginning after a mid-track failure.
     */
    private fun failoverToSoundCloud(track: Track, resumePositionMs: Long = 0L) {
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
                            errorMessage = unplayableMessage(track)
                        )
                    }
                    return@launch
                }

                startPlayback(streamInfo, track, resumePositionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SoundCloud failover failed: ${e.message}", e)
                _playbackState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = unplayableMessage(track)
                    )
                }
            }
        }
    }

    /**
     * Hands a resolved stream to ExoPlayer natively (background audio, MediaSession
     * notification, Bluetooth controls - the WebView is fully out of the picture here).
     *
     * @param startPositionMs optional position to resume from (failover path).
     */
    private fun startPlayback(streamInfo: StreamInfo, track: Track, startPositionMs: Long = 0L) {
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
        // Resume position must be applied before prepare() so the seek is honoured
        // during the initial open, not after playback has already started at 0.
        if (startPositionMs > 0L) {
            exoPlayer.seekTo(startPositionMs)
        }
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

        /** How many times the cheap SoundCloud fallback is tried before giving up. */
        private const val SOUNDCLOUD_ATTEMPTS = 2

        /** Pause between SoundCloud attempts. Kept short - it is pure user-visible delay. */
        private const val SOUNDCLOUD_RETRY_BACKOFF_MS = 400L

        /**
         * How long the sniffer gets before the fallback resolve is started in parallel.
         *
         * A normal sniff resolves well inside this, so the hedge usually never fires and
         * costs nothing at all. It only matters when the sniffer is slow or failing, where
         * it removes the second full wait from the user's startup time.
         */
        private const val FALLBACK_HEDGE_DELAY_MS = 3_000L

        /** Step used by the notification's seek-back / seek-forward buttons. */
        private const val SEEK_INCREMENT_MS = 10_000L
    }
}
