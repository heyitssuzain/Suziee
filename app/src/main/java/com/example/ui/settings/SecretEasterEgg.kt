package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.SuzieeTheme

/**
 * The private romantic dedication (PR-1 .. PR-5).
 *
 * Presented as an overlay rather than a navigation destination, so the back stack stays
 * clean and ExoPlayer keeps playing the whole time — the overlay never touches playback.
 *
 * Everything is local: no network call, no analytics, no new permission, nothing persisted.
 * The only gate is the SHA-256 comparison in [PasswordDialog], which happens before this
 * screen is ever shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretEasterEgg(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current

    var isBursting by remember { mutableStateOf(false) }
    val burstScale = remember { androidx.compose.animation.core.Animatable(0.6f) }

    // Staggered entrance: title, message, button, 100ms apart.
    var showTitle by remember { mutableStateOf(false) }
    var showMessage by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showTitle = true
        kotlinx.coroutines.delay(100)
        showMessage = true
        kotlinx.coroutines.delay(100)
        showButton = true
    }

    // A burst of hearts that expands once per tap of the "Made with love" button. The delay
    // covers the spring's settle time so the burst is fully visible before it is torn down.
    LaunchedEffect(isBursting) {
        if (isBursting) {
            burstScale.snapTo(0.6f)
            burstScale.animateTo(2.6f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            kotlinx.coroutines.delay(700)
            isBursting = false
        }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.bgPrimary,
                        colors.surfaceElevated,
                        colors.bgPrimary
                    )
                )
            )
            .semantics { contentDescription = "A private message" }
            .testTag("secret_easter_egg_screen")
    ) {
        RomanticHeartField(reducedMotion = reducedMotion)

        // One extra burst centred on the button, layered above the ambient field.
        if (isBursting) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        scaleX = burstScale.value
                        scaleY = burstScale.value
                        alpha = (2.6f - burstScale.value).coerceIn(0f, 1f)
                    }
            ) {
                RomanticHeartBurst(reducedMotion = reducedMotion)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = "",
                        color = colors.textPrimary
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = showTitle,
                    enter = scaleIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        initialScale = 0.9f
                    ) + fadeIn(animationSpec = tween(400)),
                    exit = scaleOut(targetScale = 0.95f) + fadeOut(animationSpec = tween(300))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Suziee Loves Prerna \uD83D\uDC97",
                            color = colors.accentPrimary,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 32.sp
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("secret_title")
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "for the one person this was built for",
                            color = colors.textMuted,
                            style = MaterialTheme.typography.bodySmall,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                AnimatedVisibility(
                    visible = showMessage,
                    enter = fadeIn(animationSpec = tween(450)) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it / 6 }
                    )
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = colors.surfaceCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            colors.border
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = ROMANTIC_MESSAGE,
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 24.sp
                                ),
                                textAlign = TextAlign.Start
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "\u2014 You're the melody in my chaos \uD83C\uDFB6",
                                color = colors.textMuted,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "\u2014 Distance means so little when someone means so much \u2728",
                                color = colors.textMuted,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                AnimatedVisibility(
                    visible = showButton,
                    enter = fadeIn(animationSpec = tween(400)) + slideInVertically(
                        initialOffsetY = { it / 4 }
                    )
                ) {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isBursting = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("made_with_love_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = colors.bgPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Made with love \uD83D\uDC97",
                            color = colors.bgPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("secret_close_button")
                ) {
                    Text(
                        text = "Close",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

/**
 * The hidden entry card (PR-1).
 *
 * Rendered only while [visible] is true, with the spec'd slide-in plus fade so it appears
 * "at the bottom of Settings with a gentle slide-in animation".
 */
@Composable
fun SecretEntryCard(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            initialOffsetY = { it }
        ) + fadeIn(tweenDuration = 350, easing = FastOutSlowInEasing),
        exit = fadeOut(tweenDuration = 200)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = colors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.accentPrimary),
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag("secret_entry_row")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(colors.accentPrimary, colors.accentSecondary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = colors.bgPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = "A Little Secret \uD83D\uDC97",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "just for you",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Concentric ring of hearts used for the button burst. */
@Composable
private fun RomanticHeartBurst(reducedMotion: Boolean) {
    val colors = SuzieeTheme.colors
    val palette = remember(colors.accentPrimary, colors.accentSecondary, colors.highlight) {
        listOf(colors.accentPrimary, colors.accentSecondary, colors.highlight)
    }

    for (index in 0 until 12) {
        val angle = (index.toFloat() / 12f) * 6.2831f
        val radius = 110f + (index % 3) * 34f
        val tint = palette[index % palette.size]
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = radius * kotlin.math.cos(angle)
                    translationY = radius * kotlin.math.sin(angle) * 0.7f
                    alpha = if (reducedMotion) 0.5f else 0.85f
                }
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size((14 + (index % 3) * 6).dp)
                    .alpha(0.9f)
            )
        }
    }
}

/** The message body, kept out of the composable so it reads as prose and not code. */
private const val ROMANTIC_MESSAGE: String =
    "Even in your absence, you remain a beautiful part of every little thing I do. " +
        "No matter how far apart we are, a part of my heart will always find its way to you. " +
        "You are my favourite thought, my sweetest feeling, and a little piece of happiness " +
        "I never want to lose.\n\nI will always love you, Prerna. \uD83D\uDC97"