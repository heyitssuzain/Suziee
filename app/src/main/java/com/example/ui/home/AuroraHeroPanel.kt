package com.example.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playback.PlaybackState
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.SuzieeTheme

/**
 * The cinematic Mikasa hero panel. This is exclusive to the Aurora Glass theme -- the
 * Claymorphism and Minimal home pages show the [AnimeTrendingShowcase] instead -- so it lives in
 * its own file rather than bloating [HomeScreen] with theme branching.
 */
@Composable
fun AuroraHeroPanel(
    playbackState: PlaybackState,
    reducedMotion: Boolean,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .testTag("home_hero_panel"),
        color = SuzieeTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, SuzieeTheme.colors.border)
    ) {
        // The portrait breathes, floats and slowly pans while music-note particles
        // drift across it. The copy below stays a normal Compose column for contrast.
        Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
            MikasaHeroArtwork(
                modifier = Modifier.fillMaxSize(),
                reducedMotion = reducedMotion,
                isPlaying = playbackState.isPlaying,
                cornerRadius = 0
            )

            // Live badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (playbackState.isPlaying) SuzieeTheme.colors.accentSecondary
                                else SuzieeTheme.colors.textMuted
                            )
                    )
                    Text(
                        text = if (playbackState.isPlaying) "NOW PLAYING" else "SUZIEE ORIGINAL",
                        color = SuzieeTheme.colors.highlight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
            ) {
                Text(
                    text = "IMMERSIVE ANIME SOUND",
                    color = SuzieeTheme.colors.accentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Explore Trending Anime & J-Music",
                    color = SuzieeTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDiscover,
                        colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.testTag("hero_explore_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = SuzieeTheme.colors.bgPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Discover Hits",
                            color = SuzieeTheme.colors.bgPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    WaveformVisualizer(
                        amplitudes = playbackState.visualizerAmplitudes,
                        isPlaying = playbackState.isPlaying,
                        height = 30.dp,
                        modifier = Modifier.width(110.dp)
                    )
                }
            }
        }
    }
}
