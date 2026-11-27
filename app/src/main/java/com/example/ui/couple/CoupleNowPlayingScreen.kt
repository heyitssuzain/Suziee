package com.example.ui.couple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard

@Composable
fun CoupleNowPlayingScreen(
    controller: CoupleSpaceController,
    playerManager: MusicPlayerManager,
    state: CoupleUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val playback by playerManager.playbackState.collectAsState()
    val track = playback.currentTrack
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    CoupleScreenScaffold(
        title = "Listen together",
        subtitle = "Each phone plays its own stream. Only the playhead is synchronised.",
        onBack = onBack
    ) {
        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        if (track != null && track.artworkUrl.isNotBlank()) {
                            AsyncImage(
                                model = track.artworkUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = coupleAccentText(),
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track?.title ?: "Nothing playing",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track?.artist ?: "Play a track, then push it to your person.",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        CoupleStatusPill(
                            state = state.connectionState,
                            authenticated = state.authenticated
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                val duration = playback.durationMs.coerceAtLeast(0L)
                val position = (dragPosition?.toLong() ?: playback.currentPositionMs)
                    .coerceIn(0L, if (duration > 0L) duration else 0L)
                Slider(
                    value = if (duration > 0L) position.toFloat() else 0f,
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { playerManager.seekTo(it.toLong()) }
                        dragPosition = null
                    },
                    enabled = duration > 0L,
                    valueRange = 0f..(if (duration > 0L) duration.toFloat() else 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = coupleAccentContainer(),
                        activeTrackColor = coupleAccentContainer(),
                        inactiveTrackColor = colors.surfaceElevated
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = formatDurationMs(position),
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatDurationMs(duration),
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = playerManager::playPrevious, enabled = track != null) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = colors.textPrimary
                        )
                    }
                    IconButton(onClick = playerManager::togglePlayPause, enabled = track != null) {
                        Icon(
                            imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playback.isPlaying) "Pause" else "Play",
                            tint = coupleAccentText(),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = playerManager::playNext, enabled = track != null) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = colors.textPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CouplePrimaryButton(
                text = "Push my playback",
                onClick = controller::pushCurrentPlayback,
                enabled = track != null && state.authenticated,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Sync
            )
            CoupleSecondaryButton(
                text = "Resync",
                onClick = controller::resyncNow,
                enabled = state.authenticated,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.GraphicEq
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Synchronisation",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                CoupleInfoRow("State", if (state.authenticated) "Connected" else "Offline")
                CoupleInfoRow(
                    "Clock offset",
                    if (state.authenticated) "${state.clockOffsetMs} ms" else "—"
                )
                CoupleInfoRow(
                    "Round trip",
                    if (state.authenticated) "${state.clockRttMs} ms" else "—"
                )
                CoupleInfoRow(
                    "Last sync",
                    state.lastSyncAt?.let { formatRelative(it) } ?: "Never"
                )
                CoupleInfoRow(
                    "They are",
                    when {
                        !state.authenticated -> "Not connected"
                        state.peerListening -> "Listening with you"
                        else -> "Online, not playing"
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Drift under 300 ms is ignored. 300–1500 ms is corrected with a temporary " +
                        "3% speed change. Larger drift seeks, at most once every 8 seconds.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Audio never travels between the phones. Both players resolve the same track " +
                "independently; only control events cross the encrypted DataChannel.",
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}
