package com.example.ui.player

import com.example.ui.theme.SuzieeTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.remote.lyrics.LyricsResult
import com.example.domain.model.LyricsLine
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.playback.RepeatMode as PlaybackRepeatMode
import com.example.ui.components.WaveformVisualizer
import com.example.ui.components.rememberDownloadUiStatus
import com.example.ui.theme.AuroraBackground
import com.example.ui.theme.SystemBarsVisibility
import com.example.ui.theme.rememberDominantColor
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PlayerScreen(
    playerManager: MusicPlayerManager,
    onMinimize: () -> Unit,
    onDownloadTrack: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onMinimize)

    val playbackState by playerManager.playbackState.collectAsState()
    val track = playbackState.currentTrack ?: run {
        onMinimize()
        return
    }

    val isFavorite by playerManager.repository.isFavorite(track.id).collectAsState(initial = false)
    val downloadStatus = rememberDownloadUiStatus(track.id)
    val scope = rememberCoroutineScope()
    val dominantColor = rememberDominantColor(track.artworkUrl)

    // The player owns the whole window; hide the bars until the user swipes them back.
    SystemBarsVisibility(hideBars = true, useDarkIcons = !SuzieeTheme.colors.isDark)

    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lyricsResult by remember { mutableStateOf<LyricsResult?>(null) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    // Fetch lyrics when track changes or lyrics tab opens
    LaunchedEffect(track.id, showLyrics) {
        if (showLyrics) {
            isLoadingLyrics = true
            val res = playerManager.repository.getLyrics(track)
            isLoadingLyrics = false
            lyricsResult = res.getOrNull()
        }
    }

    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val currentPosition = if (isUserSeeking) {
        seekPosition.toLong()
    } else {
        playbackState.currentPositionMs
    }

    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val sliderValue = currentPosition.toFloat().coerceIn(0f, duration.toFloat())

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("full_player_screen")
    ) {
        // High-resolution artwork becomes the room: blurred, darkened, and lit by its own
        // dominant colour. The cover itself stays sharp in the centre of the screen.
        AuroraBackground(
            dominantColor = dominantColor,
            modifier = Modifier.fillMaxSize(),
            isPlaying = playbackState.isPlaying,
            intensity = 1.15f
        )
        if (track.artworkUrl.isNotBlank()) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(64.dp)
                    .alpha(0.52f),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            SuzieeTheme.colors.bgPrimary.copy(alpha = 0.52f),
                            SuzieeTheme.colors.bgPrimary.copy(alpha = 0.76f),
                            SuzieeTheme.colors.bgPrimary.copy(alpha = 0.94f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .widthIn(max = 550.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Controls Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SuzieeTheme.colors.surfaceElevated.copy(alpha = 0.55f))
                        .testTag("player_minimize_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Minimize Player",
                        tint = SuzieeTheme.colors.textPrimary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PLAYING FROM SUZIEE",
                        color = SuzieeTheme.colors.accentSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = if (track.isDownloaded) "Offline Playback" else "High-Res Stream",
                        color = SuzieeTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            showQueue = !showQueue
                            if (showQueue) showLyrics = false
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SuzieeTheme.colors.surfaceElevated.copy(alpha = 0.55f))
                            .testTag("player_queue_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Queue",
                            tint = if (showQueue) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textSecondary
                        )
                    }
                    IconButton(
                        onClick = {
                            showLyrics = !showLyrics
                            if (showLyrics) showQueue = false
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SuzieeTheme.colors.surfaceElevated.copy(alpha = 0.55f))
                            .testTag("player_lyrics_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Middle Content: Artwork OR Lyrics OR Queue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    showLyrics -> {
                        // Live Synced Lyrics View (glassmorphic)
                        LyricsView(
                            lyricsResult = lyricsResult,
                            isLoading = isLoadingLyrics,
                            currentPositionMs = playbackState.currentPositionMs,
                            onSeek = { positionMs -> playerManager.seekTo(positionMs) }
                        )
                    }
                    showQueue -> {
                        // Queue View
                        QueueView(
                            queue = playbackState.queue,
                            currentIndex = playbackState.currentIndex,
                            onTrackSelected = { t -> playerManager.playTrack(t, playbackState.queue) },
                            onRemove = { id -> playerManager.removeFromQueue(id) }
                        )
                    }
                    else -> {
                        // Large Glowing Artwork View
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                        ) {
                            // Atmospheric Neon Aura
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                SuzieeTheme.colors.accentPrimary.copy(alpha = 0.28f),
                                                SuzieeTheme.colors.accentSecondary.copy(alpha = 0.08f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )

                            // Main Album Art Box
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(0.9f)
                                    .clip(RoundedCornerShape(28.dp))
                                    .border(2.dp, SuzieeTheme.colors.border, RoundedCornerShape(28.dp))
                                    .background(SuzieeTheme.colors.surfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                if (track.artworkUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = track.artworkUrl,
                                        contentDescription = "Album Artwork",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = SuzieeTheme.colors.accentPrimary,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Track Info & Favorite Heart
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = SuzieeTheme.colors.textPrimary,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = SuzieeTheme.colors.textSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                playerManager.repository.toggleFavorite(track)
                            }
                        },
                        modifier = Modifier.testTag("player_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) SuzieeTheme.colors.accentPrimary else SuzieeTheme.colors.textMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = { if (!downloadStatus.active) onDownloadTrack(track) },
                        modifier = Modifier.testTag("player_download_button")
                    ) {
                        if (downloadStatus.active) {
                            if (downloadStatus.percent < 0) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                CircularProgressIndicator(
                                    progress = { downloadStatus.percent / 100f },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                tint = if (track.isDownloaded) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // 4. Waveform visualizer
            WaveformVisualizer(
                amplitudes = playbackState.visualizerAmplitudes,
                isPlaying = playbackState.isPlaying,
                height = 32.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 5. Seekbar Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isUserSeeking = true
                        seekPosition = it
                    },
                    onValueChangeFinished = {
                        playerManager.seekTo(seekPosition.toLong())
                        isUserSeeking = false
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = SuzieeTheme.colors.accentSecondary,
                        activeTrackColor = SuzieeTheme.colors.accentPrimary,
                        inactiveTrackColor = SuzieeTheme.colors.border
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("player_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        color = SuzieeTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatTime(duration),
                        color = SuzieeTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Playback Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle
                IconButton(
                    onClick = { playerManager.toggleShuffle() },
                    modifier = Modifier.testTag("player_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.isShuffle) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textMuted
                    )
                }

                // Previous
                IconButton(
                    onClick = { playerManager.playPrevious() },
                    modifier = Modifier.testTag("player_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = SuzieeTheme.colors.textPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Play / Pause or Loading circle
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(SuzieeTheme.colors.accentPrimary, SuzieeTheme.colors.accentSecondary))
                        )
                        .clickable {
                            if (playbackState.errorMessage != null) {
                                playerManager.retryCurrent()
                            } else {
                                playerManager.togglePlayPause()
                            }
                        }
                        .testTag("player_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackState.isLoading) {
                        CircularProgressIndicator(
                            color = SuzieeTheme.colors.bgPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    } else if (playbackState.errorMessage != null) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = SuzieeTheme.colors.bgPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = SuzieeTheme.colors.bgPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Next
                IconButton(
                    onClick = { playerManager.playNext() },
                    modifier = Modifier.testTag("player_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = SuzieeTheme.colors.textPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Repeat
                IconButton(
                    onClick = { playerManager.toggleRepeat() },
                    modifier = Modifier.testTag("player_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == PlaybackRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (playbackState.repeatMode != PlaybackRepeatMode.OFF) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textMuted
                    )
                }
            }

            // While resolving/buffering the user can wait, pick another track, or cancel.
            if (playbackState.isLoading) {
                TextButton(
                    onClick = { playerManager.cancelPlayback() },
                    modifier = Modifier.testTag("player_cancel_button")
                ) {
                    Text(
                        text = "Cancel",
                        color = SuzieeTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            playbackState.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("player_error_message")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Premium glassmorphic lyrics panel.
 *
 * Presentation only: the lyric data, the active-line index and the auto-scroll behave
 * exactly as before, but the panel is now a frosted-glass surface with drifting ambient
 * light, per-line entrance animations and a soft neon glow on the line being sung.
 *
 * @param onSeek tapping a lyric jumps playback to that line (defaults to a no-op).
 */
@Composable
fun LyricsView(
    lyricsResult: LyricsResult?,
    isLoading: Boolean,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit = {}
) {
    // One infinite transition drives every ambient element, so they stay in sync.
    val ambient = rememberInfiniteTransition(label = "lyrics_ambient")
    val drift by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "drift"
    )
    val breathe by ambient.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "breathe"
    )
    val sheen by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5400, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "sheen"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
    ) {
        GlassBackdrop(drift = drift, breathe = breathe, sheen = sheen)

        when {
            isLoading -> LyricSkeleton(
                sheen = sheen,
                modifier = Modifier.fillMaxSize().padding(28.dp)
            )

            lyricsResult == null ||
                (lyricsResult.parsedLines.isEmpty() && lyricsResult.plainLyrics.isNullOrBlank()) ->
                LyricsEmptyState()

            lyricsResult.parsedLines.isNotEmpty() -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    SyncedLyrics(
                        lines = lyricsResult.parsedLines,
                        currentPositionMs = currentPositionMs,
                        onSeek = onSeek
                    )

                    // Fade above the list so lines slide "under" the floating header.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(58.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        SuzieeTheme.colors.bgPrimary.copy(alpha = 0.55f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    LyricsHeader(
                        breathe = breathe,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }

            else -> PlainLyrics(plainLyrics = lyricsResult?.plainLyrics)
        }
    }
}

/**
 * The frosted sheet behind everything: a translucent violet/cyan wash, two slowly drifting
 * light orbs and a diagonal highlight that sweeps across the glass.
 */
@Composable
private fun BoxScope.GlassBackdrop(drift: Float, breathe: Float, sheen: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        SuzieeTheme.colors.accentPrimary.copy(alpha = 0.22f + 0.06f * breathe),
                        SuzieeTheme.colors.surfaceCard.copy(alpha = 0.60f),
                        SuzieeTheme.colors.bgPrimary.copy(alpha = 0.80f),
                        SuzieeTheme.colors.accentSecondary.copy(alpha = 0.18f + 0.05f * breathe)
                    )
                )
            )
    )

    AmbientOrb(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (-50 + 90 * drift).dp, y = (-40 + 60 * drift).dp),
        size = 250.dp,
        color = SuzieeTheme.colors.accentPrimary,
        alpha = 0.30f + 0.12f * breathe
    )
    AmbientOrb(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = (40 - 80 * drift).dp, y = (30 - 50 * drift).dp),
        size = 300.dp,
        color = SuzieeTheme.colors.accentSecondary,
        alpha = 0.20f + 0.10f * breathe
    )

    // Travelling highlight - the "frosted glass" glint.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.075f),
                        Color.Transparent
                    ),
                    start = Offset(-320f + 1500f * sheen, -240f),
                    end = Offset(80f + 1500f * sheen, 960f)
                )
            )
    )

    // Crisp glass edge.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.30f),
                        SuzieeTheme.colors.accentSecondary.copy(alpha = 0.16f),
                        SuzieeTheme.colors.accentPrimary.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                shape = RoundedCornerShape(28.dp)
            )
    )
}

/** A soft glowing blob. Multiple gradient stops keep it smooth without a blur pass. */
@Composable
private fun AmbientOrb(
    modifier: Modifier = Modifier,
    size: Dp,
    color: Color,
    alpha: Float
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.30f),
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            )
    )
}

/** Shimmering placeholder lines shown while lyrics are being fetched. */
@Composable
private fun LyricSkeleton(sheen: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ShimmerBar(widthFraction = 0.36f, height = 12.dp, sheen = sheen)
        Spacer(modifier = Modifier.height(30.dp))
        listOf(0.62f, 0.80f, 0.50f, 0.88f, 0.68f, 0.42f, 0.74f, 0.56f).forEach { fraction ->
            ShimmerBar(widthFraction = fraction, height = 18.dp, sheen = sheen)
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ShimmerBar(widthFraction: Float, height: Dp, sheen: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.05f)
                    ),
                    start = Offset(-280f + 950f * sheen, 0f),
                    end = Offset(60f + 950f * sheen, 0f)
                )
            )
    )
}

@Composable
private fun LyricsEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                SuzieeTheme.colors.accentPrimary.copy(alpha = 0.38f),
                                SuzieeTheme.colors.accentSecondary.copy(alpha = 0.24f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = null,
                    tint = SuzieeTheme.colors.highlight,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Lyrics unavailable",
                color = SuzieeTheme.colors.textPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "We couldn't find lyrics for this track yet.",
                color = SuzieeTheme.colors.textMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LyricsHeader(breathe: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(SuzieeTheme.colors.accentSecondary.copy(alpha = 0.45f + 0.55f * breathe))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "SYNCED LYRICS",
            color = SuzieeTheme.colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.4.sp
        )
    }
}

/** Un-timed lyrics: same glass treatment, gentle reveal. */
@Composable
private fun PlainLyrics(plainLyrics: String?) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(plainLyrics) { revealed = true }

    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "plain_reveal"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 26f
            },
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 30.dp)
    ) {
        item {
            Text(
                text = plainLyrics.orEmpty().trim(),
                color = SuzieeTheme.colors.textPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricsLine>,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val activeIndex = lines.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)

    // LRCLIB gives line-level timing, so each line's duration is shared across its words.
    // That is enough for a convincing karaoke sweep without a second lyrics provider.
    val activeStart = lines.getOrNull(activeIndex)?.timeMs ?: 0L
    val activeEnd = lines.getOrNull(activeIndex + 1)?.timeMs
        ?: (activeStart + 4_000L)
    val lineProgress = if (currentPositionMs <= activeStart) {
        0f
    } else {
        ((currentPositionMs - activeStart).toFloat() /
            (activeEnd - activeStart).coerceAtLeast(1L).toFloat())
            .coerceIn(0f, 1f)
    }
    val nextLineTime = lines.getOrNull(activeIndex + 1)?.timeMs
    val instrumental = currentPositionMs < (lines.firstOrNull()?.timeMs ?: 0L) ||
        (nextLineTime != null &&
            nextLineTime - activeStart > 6_000L &&
            lineProgress > 0.86f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Half the panel as vertical padding, minus half a line, so that bringing the active
        // line to the top of the *content* area lands it dead centre - with finished lines
        // above and upcoming ones below.
        val verticalPadding = maxOf(24.dp, (maxHeight / 2) - 30.dp)

        LaunchedEffect(activeIndex, lines.size) {
            if (activeIndex in lines.indices) {
                listState.animateScrollToItem(activeIndex)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = verticalPadding, horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                LyricLineItem(
                    text = line.text,
                    isActive = index == activeIndex,
                    progress = if (index == activeIndex) lineProgress else 0f,
                    onClick = { onSeek(line.timeMs) }
                )
            }
        }

        AnimatedVisibility(
            visible = instrumental,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        ) {
            InstrumentalPulse()
        }
    }
}

/** Pulsing waveform shown during intros and long instrumental gaps. */
@Composable
private fun InstrumentalPulse() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        WaveformVisualizer(
            amplitudes = List(24) { index ->
                0.28f + ((index * 37) % 100) / 100f * 0.62f
            },
            isPlaying = true,
            height = 24.dp,
            modifier = Modifier.width(132.dp)
        )
        Text(
            text = "INSTRUMENTAL",
            color = SuzieeTheme.colors.textMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * One lyric line with word-by-word karaoke.
 *
 * The active line is bold, scaled up, and its words light from left to right as the line's
 * time window elapses. Inactive lines stay at 0.3 alpha so the current lyric always wins.
 */
@Composable
private fun LyricLineItem(
    text: String,
    isActive: Boolean,
    progress: Float,
    onClick: () -> Unit
) {
    val colors = SuzieeTheme.colors
    val activation by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "line_activation"
    )

    val tokens = remember(text) {
        Regex("""\S+""").findAll(text).map { it.value }.toList()
    }
    val activeWord = if (isActive) {
        (progress * tokens.size).toInt().coerceIn(0, (tokens.size - 1).coerceAtLeast(0))
    } else {
        -1
    }
    val annotated = remember(text, isActive, activeWord) {
        buildAnnotatedString {
            var cursor = 0
            tokens.forEachIndexed { index, token ->
                val start = text.indexOf(token, cursor).let { if (it < 0) cursor else it }
                if (start > cursor) append(text.substring(cursor, start))
                val isCurrent = isActive && index == activeWord
                val isSung = isActive && index < activeWord
                withStyle(
                    SpanStyle(
                        color = when {
                            isCurrent -> colors.highlight
                            isSung -> colors.textPrimary
                            else -> colors.textMuted.copy(alpha = 0.62f)
                        },
                        fontWeight = when {
                            isCurrent -> FontWeight.ExtraBold
                            isSung -> FontWeight.Bold
                            else -> FontWeight.Medium
                        },
                        shadow = if (isCurrent) {
                            Shadow(
                                color = colors.accentSecondary.copy(alpha = 0.75f),
                                blurRadius = 24f
                            )
                        } else {
                            null
                        }
                    )
                ) {
                    append(token)
                }
                cursor = start + token.length
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .graphicsLayer {
                val scale = 0.95f + 0.07f * activation
                scaleX = scale
                scaleY = scale
                // Exactly 0.3 for inactive lines, per the kinetic-lyrics spec.
                alpha = if (isActive) 1f else 0.3f
                translationY = (1f - activation) * 14f
            },
        contentAlignment = Alignment.Center
    ) {
        // Soft neon halo behind the line being sung.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SuzieeTheme.colors.accentPrimary.copy(alpha = 0.32f * activation),
                            SuzieeTheme.colors.accentSecondary.copy(alpha = 0.12f * activation),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = annotated,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.3.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            // Gradient underline that fills with the line's progress.
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .height(3.dp)
                    .width((26 + 52 * progress).dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                SuzieeTheme.colors.accentPrimary,
                                SuzieeTheme.colors.accentSecondary
                            )
                        )
                    )
                    .alpha(activation)
            )
        }
    }
}

@Composable
fun QueueView(
    queue: List<Track>,
    currentIndex: Int,
    onTrackSelected: (Track) -> Unit,
    onRemove: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp)),
        color = SuzieeTheme.colors.surfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            item {
                Text(
                    text = "PLAYBACK QUEUE (${queue.size})",
                    color = SuzieeTheme.colors.accentSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            itemsIndexed(queue) { index, track ->
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCurrent) SuzieeTheme.colors.surfaceElevated else Color.Transparent)
                        .clickable { onTrackSelected(track) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (isCurrent) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.widthIn(min = 24.dp)
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = track.title,
                            color = if (isCurrent) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = SuzieeTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
