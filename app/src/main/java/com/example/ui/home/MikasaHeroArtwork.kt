package com.example.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.SuzieeTheme
import kotlin.math.sin

/**
 * The Mikasa hero: a breathing, floating portrait with a very slow parallax pan, a theme-aware
 * colour grade, and a drifting field of music-note particles.
 *
 * The artwork is a bundled drawable, so the hero is instant and works offline. Replace
 * `img_mikasa_hero.png` with licensed art to change the character without touching code.
 */
@Composable
fun MikasaHeroArtwork(
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalReducedMotion.current,
    isPlaying: Boolean = true,
    cornerRadius: Int = 28
) {
    val colors = SuzieeTheme.colors
    val transition = rememberInfiniteTransition(label = "mikasa_hero")

    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reducedMotion) 1f else 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mikasa_breath"
    )
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mikasa_float"
    )
    val pan by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reducedMotion) 24000 else 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mikasa_pan"
    )

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(
                Brush.verticalGradient(
                    listOf(colors.bgSecondary, colors.bgPrimary)
                )
            )
    ) {
        val localDensity = LocalDensity.current
        val heroWidthPx = with(localDensity) { maxWidth.toPx() }
        val heroHeightPx = with(localDensity) { maxHeight.toPx() }

        // A soft halo behind the character gives the flat artwork a lit-from-behind depth.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.accentPrimary.copy(alpha = 0.38f),
                            colors.accentSecondary.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(heroWidthPx * 0.58f, heroHeightPx * 0.30f),
                        radius = heroWidthPx * 0.85f
                    )
                )
        )

        Image(
            painter = painterResource(id = R.drawable.img_mikasa_hero),
            contentDescription = "Mikasa Ackerman artwork",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                    translationY = float.dp.toPx()
                    translationX = pan * 9.dp.toPx()
                    rotationY = pan * 1.2f
                    rotationX = -pan * 0.7f
                    cameraDistance = 18f * density
                },
            contentScale = ContentScale.Crop,
            // Keep the face in frame on tall cards and wide tablets alike.
            alignment = BiasAlignment(0.5f, 0.30f)
        )

        // Cinematic grade: cool top wash, dark base for the copy, and an accent bloom.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.bgPrimary.copy(alpha = 0.42f),
                            Color.Transparent,
                            colors.bgPrimary.copy(alpha = 0.35f),
                            colors.bgPrimary.copy(alpha = 0.96f)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.accentSecondary.copy(alpha = 0.22f),
                            Color.Transparent
                        ),
                        center = Offset(0f, heroHeightPx * 1.1f),
                        radius = heroWidthPx * 1.2f
                    )
                )
        )

        MusicParticles(
            modifier = Modifier.fillMaxSize(),
            reducedMotion = reducedMotion,
            isPlaying = isPlaying,
            tint = colors.accentSecondary
        )
    }
}

/**
 * A lightweight Compose Canvas particle field. Notes rise, drift sideways on a sine wave, and
 * fade out. It is deterministic (no random allocation on recomposition) and pauses when the
 * user has reduced motion enabled.
 */
@Composable
fun MusicParticles(
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalReducedMotion.current,
    isPlaying: Boolean = true,
    tint: Color = SuzieeTheme.colors.accentSecondary,
    count: Int = 22
) {
    val transition = rememberInfiniteTransition(label = "music_particles")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "music_particles_phase"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val motion = when {
            reducedMotion -> 0.15f
            isPlaying -> 1f
            else -> 0.45f
        }
        repeat(count) { index ->
            val seedX = ((index * 47 + 13) % 100) / 100f
            val seedY = ((index * 29 + 7) % 100) / 100f
            val travel = ((phase * motion + index * 0.137f) % 1f)
            val x = width * (0.06f + seedX * 0.88f) +
                sin((phase * 6.28f + index).toDouble()).toFloat() * width * 0.018f
            val y = height * (0.20f + seedY * 0.72f) - travel * height * 0.30f
            val alpha = (1f - travel) * (if (isPlaying) 0.58f else 0.32f)
            val radius = (1.3f + (index % 3) * 0.8f) * density
            val noteColor = tint.copy(alpha = alpha.coerceIn(0f, 0.7f))
            drawCircle(color = noteColor, radius = radius, center = Offset(x, y))
            // A tiny stem turns some dots into eighth notes.
            if (index % 3 == 0) {
                drawLine(
                    color = noteColor,
                    start = Offset(x + radius, y),
                    end = Offset(x + radius, y - radius * 4.2f),
                    strokeWidth = radius * 0.55f
                )
                drawCircle(
                    color = noteColor,
                    radius = radius * 0.9f,
                    center = Offset(x + radius * 2.1f, y - radius * 3.4f)
                )
            }
        }
    }
}
