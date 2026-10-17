package com.example.ui.landing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeHighlight
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary
import kotlinx.coroutines.delay

@Composable
fun LandingScreen(
    onEnterApp: () -> Unit,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    var stage by remember { mutableIntStateOf(if (reducedMotion) 5 else 1) }

    val characterAlpha = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val characterScale = remember { Animatable(if (reducedMotion) 1f else 0.9f) }
    val brandAlpha = remember { Animatable(if (reducedMotion) 1f else 0f) }

    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) {
            // Stage 1: Darkness
            delay(400)
            stage = 2
            // Stage 2: Character reveal
            characterAlpha.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            characterScale.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            delay(200)
            stage = 3
            // Stage 3: Brand reveal
            brandAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            delay(200)
            stage = 4
            // Stage 4: Visualizer & Button
            delay(400)
            stage = 5
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "glow_cycle")
    val glowPhase by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeBgPrimary)
            .testTag("landing_screen")
    ) {
        // Background anime artwork with atmospheric gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(characterAlpha.value)
                .scale(characterScale.value)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_suziee_hero),
                contentDescription = "Suziee Character Artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // Bias the crop slightly above centre so the character's face stays framed
                // on tall and wide screens alike.
                alignment = Alignment(0.5f, 0.4f)
            )

            // Deep gradient overlay ensuring text readability and cyberpunk atmosphere
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                SuzieeBgPrimary.copy(alpha = 0.45f),
                                SuzieeBgPrimary.copy(alpha = 0.7f),
                                SuzieeBgPrimary.copy(alpha = 0.95f),
                                SuzieeBgPrimary
                            )
                        )
                    )
            )
        }

        // Cinematic ambient light + vignette, layered above the artwork.
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, SuzieeBgPrimary.copy(alpha = 0.85f)),
                            radius = 1400f
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-80).dp, y = 80.dp)
                    .size(280.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                SuzieeAccentPrimary.copy(alpha = 0.30f * glowPhase),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 90.dp, y = 60.dp)
                    .size(320.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                SuzieeAccentSecondary.copy(alpha = 0.22f * glowPhase),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        // Top skip button
        if (stage < 5 && !reducedMotion) {
            TextButton(
                onClick = onEnterApp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 20.dp)
                    .testTag("skip_intro_button")
            ) {
                Text(
                    text = "SKIP",
                    color = SuzieeTextSecondary,
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp)
                )
            }
        }

        // Center Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 28.dp, vertical = 48.dp)
                .widthIn(max = 500.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Brand Typography
            AnimatedVisibility(
                visible = stage >= 3,
                enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { 40 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SUZIEE",
                        color = SuzieeHighlight,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 8.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "ANIME MUSIC PLAYER",
                        color = SuzieeAccentSecondary.copy(alpha = glowPhase),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 4.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Waveform visualizer
            AnimatedVisibility(
                visible = stage >= 4,
                enter = fadeIn(tween(500))
            ) {
                WaveformVisualizer(
                    amplitudes = List(24) { 0.4f + (it % 5) * 0.12f },
                    isPlaying = true,
                    height = 36.dp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Enter Button & Tagline
            AnimatedVisibility(
                visible = stage >= 5,
                enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { 60 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "YOUR WORLD. YOUR MUSIC.",
                        color = SuzieeTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.5.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onEnterApp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("enter_suziee_button"),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SuzieeAccentPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = SuzieeBgPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "ENTER SUZIEE",
                            color = SuzieeBgPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
