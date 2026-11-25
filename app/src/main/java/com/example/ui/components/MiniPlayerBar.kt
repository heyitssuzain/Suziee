package com.example.ui.components

import com.example.ui.theme.SuzieeTheme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.playback.PlaybackState
import com.example.ui.theme.LocalReducedMotion
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    onExpandPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit = {},
    onRetry: () -> Unit = onTogglePlayPause,
    modifier: Modifier = Modifier
) {
    val track = state.currentTrack ?: return

    // Subtle breathing artwork while audio is playing. Honours the app-wide reduced-motion
    // setting: when motion is reduced the artwork simply sits still.
    val reducedMotion = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val artworkScale by animateFloatAsState(
        targetValue = if (state.isPlaying && !reducedMotion) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "mini_player_art_pulse"
    )

    val progress = if (state.durationMs > 0) {
        (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onExpandPlayer)
            .testTag("mini_player_bar"),
        color = SuzieeTheme.colors.surfaceCard,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border)
    ) {
        Column {
            // Linear progress indicator on top edge
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = SuzieeTheme.colors.accentSecondary,
                trackColor = SuzieeTheme.colors.border
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .scale(artworkScale)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SuzieeTheme.colors.border),
                    contentAlignment = Alignment.Center
                ) {
                    if (track.artworkUrl.isNotBlank()) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.size(46.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = SuzieeTheme.colors.accentPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title and Artist
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = track.title,
                        color = SuzieeTheme.colors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = SuzieeTheme.colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Previous track
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSkipPrevious()
                    },
                    modifier = Modifier.testTag("mini_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = SuzieeTheme.colors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Play / Pause or Loading Indicator
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(36.dp)
                            .padding(6.dp),
                        color = SuzieeTheme.colors.accentSecondary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (state.errorMessage != null) onRetry() else onTogglePlayPause()
                        },
                        modifier = Modifier.testTag("mini_play_pause_button")
                    ) {
                        Icon(
                            imageVector = when {
                                state.errorMessage != null -> Icons.Default.Refresh
                                state.isPlaying -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = when {
                                state.errorMessage != null -> "Retry"
                                state.isPlaying -> "Pause"
                                else -> "Play"
                            },
                            tint = SuzieeTheme.colors.accentSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Next track
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSkipNext()
                    },
                    modifier = Modifier.testTag("mini_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = SuzieeTheme.colors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
