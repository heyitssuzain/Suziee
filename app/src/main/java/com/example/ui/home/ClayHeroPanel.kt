package com.example.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.playback.PlaybackState
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.SuzieeTheme

/**
 * Pink Clay hero (P3-8).
 *
 * Claymorphism reads as soft, chunky and bouncy: three artwork cards are stacked with a
 * slight rotation and offset, the front one swelling a little while music plays. It sits on
 * a heavily rounded "clay" surface with a thick border - the language of this theme.
 * Reduced motion removes the scale change and leaves the stack still.
 */
@Composable
fun ClayHeroPanel(
    playbackState: PlaybackState,
    reducedMotion: Boolean,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val current = playbackState.currentTrack

    val pop by animateFloatAsState(
        targetValue = if (playbackState.isPlaying && !reducedMotion) 1f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "clay_hero_pop"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(38.dp))
            .testTag("home_hero_panel"),
        color = colors.surfaceCard,
        border = BorderStroke(2.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "SOFT & LOUD",
                color = colors.accentPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "Your mood, wrapped in clay",
                color = colors.textPrimary,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Overlapping "clay" stack. The front card carries the real artwork; the two
            // behind it swing outward while playing so the stack looks like it is breathing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            rotationZ = -9f + 4f * pop
                            translationX = -46f - 6f * pop
                            translationY = 12f
                        }
                        .alpha(0.55f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(colors.surfaceElevated)
                )
                Box(
                    modifier = Modifier
                        .size(155.dp)
                        .graphicsLayer {
                            rotationZ = 8f - 3f * pop
                            translationX = 46f + 6f * pop
                            translationY = -8f
                        }
                        .alpha(0.7f)
                        .clip(RoundedCornerShape(30.dp))
                        .background(colors.surfaceElevated)
                )
                Box(
                    modifier = Modifier
                        .size(168.dp)
                        .scale(1f + 0.04f * pop)
                        .clip(RoundedCornerShape(32.dp))
                        .background(colors.surfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    if (current?.artworkUrl?.isNotBlank() == true) {
                        AsyncImage(
                            model = current.artworkUrl,
                            contentDescription = "Artwork for ${current.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            WaveformVisualizer(
                                amplitudes = playbackState.visualizerAmplitudes,
                                isPlaying = playbackState.isPlaying,
                                height = 40.dp,
                                modifier = Modifier.width(120.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = current?.title ?: "Pick something soft",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = current?.artist ?: "Your clay playlist is waiting",
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDiscover,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.testTag("hero_explore_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = colors.bgPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Start listening",
                        color = colors.bgPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                WaveformVisualizer(
                    amplitudes = playbackState.visualizerAmplitudes,
                    isPlaying = playbackState.isPlaying,
                    height = 26.dp,
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }
}

/**
 * Minimal Slate hero (P3-8): editorial, one large cover, lots of whitespace, no decoration.
 * The only motion is a very subtle scale on the artwork while it is playing.
 */
@Composable
fun MinimalHeroPanel(
    playbackState: PlaybackState,
    reducedMotion: Boolean,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val current = playbackState.currentTrack

    val lift by animateFloatAsState(
        targetValue = if (playbackState.isPlaying && !reducedMotion) 1f else 0.94f,
        animationSpec = tween(durationMillis = 700),
        label = "minimal_hero_lift"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .testTag("home_hero_panel"),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
            Text(
                text = "EDITORIAL PICK",
                color = colors.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = current?.title ?: "Music, quietly",
                color = colors.textPrimary,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp
                )
            )
            Text(
                text = current?.artist ?: "Nothing playing right now",
                color = colors.textMuted,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .scale(lift),
                contentAlignment = Alignment.CenterStart
            ) {
                if (current?.artworkUrl?.isNotBlank() == true) {
                    AsyncImage(
                        model = current.artworkUrl,
                        contentDescription = "Artwork for ${current.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        WaveformVisualizer(
                            amplitudes = playbackState.visualizerAmplitudes,
                            isPlaying = playbackState.isPlaying,
                            height = 60.dp,
                            modifier = Modifier.width(150.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDiscover,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = colors.textPrimary
                ),
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.testTag("hero_explore_button")
            ) {
                Text(
                    text = "EXPLORE →",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}