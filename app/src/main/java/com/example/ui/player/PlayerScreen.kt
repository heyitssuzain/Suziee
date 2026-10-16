package com.example.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.remote.lyrics.LyricsResult
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.playback.RepeatMode as PlaybackRepeatMode
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeBgSecondary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeHighlight
import com.example.ui.theme.SuzieeSurfaceCard
import com.example.ui.theme.SuzieeSurfaceElevated
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary
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
    val scope = rememberCoroutineScope()

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

    // Rotating vinyl effect
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_rotate")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(22000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        SuzieeBgSecondary,
                        SuzieeBgPrimary,
                        Color(0xFF07080E)
                    )
                )
            )
            .testTag("full_player_screen")
    ) {
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
                    modifier = Modifier.testTag("player_minimize_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Minimize Player",
                        tint = SuzieeTextPrimary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PLAYING FROM SUZIEE",
                        color = SuzieeAccentSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = if (track.isDownloaded) "Offline Playback" else "High-Res Stream",
                        color = SuzieeTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            showQueue = !showQueue
                            if (showQueue) showLyrics = false
                        },
                        modifier = Modifier.testTag("player_queue_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Queue",
                            tint = if (showQueue) SuzieeAccentSecondary else SuzieeTextSecondary
                        )
                    }
                    IconButton(
                        onClick = {
                            showLyrics = !showLyrics
                            if (showLyrics) showQueue = false
                        },
                        modifier = Modifier.testTag("player_lyrics_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) SuzieeAccentSecondary else SuzieeTextSecondary
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
                        // Live Synced Lyrics View
                        LyricsView(
                            lyricsResult = lyricsResult,
                            isLoading = isLoadingLyrics,
                            currentPositionMs = playbackState.currentPositionMs
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
                                                SuzieeAccentPrimary.copy(alpha = 0.28f),
                                                SuzieeAccentSecondary.copy(alpha = 0.08f),
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
                                    .border(2.dp, SuzieeBorder, RoundedCornerShape(28.dp))
                                    .background(SuzieeSurfaceElevated),
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
                                        tint = SuzieeAccentPrimary,
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
                        color = SuzieeTextPrimary,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = SuzieeTextSecondary,
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
                            tint = if (isFavorite) SuzieeAccentPrimary else SuzieeTextMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = { onDownloadTrack(track) },
                        modifier = Modifier.testTag("player_download_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = if (track.isDownloaded) SuzieeAccentSecondary else SuzieeTextMuted,
                            modifier = Modifier.size(24.dp)
                        )
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
                        thumbColor = SuzieeAccentSecondary,
                        activeTrackColor = SuzieeAccentPrimary,
                        inactiveTrackColor = SuzieeBorder
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("player_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        color = SuzieeTextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatTime(duration),
                        color = SuzieeTextSecondary,
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
                        tint = if (playbackState.isShuffle) SuzieeAccentSecondary else SuzieeTextMuted
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
                        tint = SuzieeTextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Play / Pause or Loading circle
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(SuzieeAccentPrimary, SuzieeAccentSecondary))
                        )
                        .clickable { playerManager.togglePlayPause() }
                        .testTag("player_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackState.isLoading) {
                        CircularProgressIndicator(
                            color = SuzieeBgPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = SuzieeBgPrimary,
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
                        tint = SuzieeTextPrimary,
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
                        tint = if (playbackState.repeatMode != PlaybackRepeatMode.OFF) SuzieeAccentSecondary else SuzieeTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun LyricsView(
    lyricsResult: LyricsResult?,
    isLoading: Boolean,
    currentPositionMs: Long
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp)),
        color = SuzieeSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder)
    ) {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SuzieeAccentSecondary)
                }
            }
            lyricsResult == null || (lyricsResult.parsedLines.isEmpty() && lyricsResult.plainLyrics.isNullOrBlank()) -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No lyrics found for this track.",
                        color = SuzieeTextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            lyricsResult.parsedLines.isNotEmpty() -> {
                // Synced Lyrics with active line auto-scrolling
                val listState = rememberLazyListState()
                val lines = lyricsResult.parsedLines

                // Find active line index
                val activeIndex = lines.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)

                LaunchedEffect(activeIndex) {
                    if (activeIndex in lines.indices) {
                        listState.animateScrollToItem(
                            index = (activeIndex - 2).coerceAtLeast(0)
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isCurrent = index == activeIndex
                        Text(
                            text = line.text,
                            color = if (isCurrent) SuzieeAccentSecondary else SuzieeTextMuted,
                            style = if (isCurrent) {
                                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            else -> {
                // Plain lyrics fallback
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(20.dp)
                ) {
                    item {
                        Text(
                            text = lyricsResult.plainLyrics ?: "",
                            color = SuzieeTextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 28.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
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
        color = SuzieeSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            item {
                Text(
                    text = "PLAYBACK QUEUE (${queue.size})",
                    color = SuzieeAccentSecondary,
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
                        .background(if (isCurrent) SuzieeSurfaceElevated else Color.Transparent)
                        .clickable { onTrackSelected(track) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (isCurrent) SuzieeAccentSecondary else SuzieeTextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.widthIn(min = 24.dp)
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = track.title,
                            color = if (isCurrent) SuzieeAccentSecondary else SuzieeTextPrimary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = SuzieeTextSecondary,
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
