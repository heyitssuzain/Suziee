package com.example.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.security.PasswordHash
import com.example.ui.theme.SuzieeTheme
import kotlinx.coroutines.launch

/**
 * Password gate for the private dedication (PR-2).
 *
 * The dedication key is **never** stored, logged or shipped as a string: the only thing in
 * this repository is its one-way SHA-256 digest, and this dialog hashes whatever was typed
 * and compares digests with a constant-time comparison. An incorrect guess therefore cannot
 * be narrowed down by timing, and the APK's string table contains nothing to extract.
 *
 * Wrong password behaviour is deliberately gentle â€” a shake, a soft line of copy, and the
 * field cleared. There is no lockout and no attempt counter, because tracking attempts would
 * contradict the privacy-first promise the rest of the app is built on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordDialog(
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var isWrong by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Shake offset driven by a single animation; paused whenever nothing is wrong so an
    // idle dialog costs nothing.
    val shake = remember { Animatable(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("secret_password_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = SuzieeTheme.colors.accentPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "A little secret",
                    color = SuzieeTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Only one person knows the way in here.",
                    color = SuzieeTheme.colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value
                        isWrong = false
                    },
                    singleLine = true,
                    // The key is a name, never a word, so the mask must not offer autofill
                    // suggestions: no keyboard type hints, no correction, no prediction.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { }),
                    placeholder = { Text("â€¦", color = SuzieeTheme.colors.textMuted) },
                    isError = isWrong,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SuzieeTheme.colors.textPrimary,
                        unfocusedTextColor = SuzieeTheme.colors.textPrimary,
                        focusedBorderColor = SuzieeTheme.colors.accentPrimary,
                        unfocusedBorderColor = SuzieeTheme.colors.border,
                        errorBorderColor = SuzieeTheme.colors.error,
                        cursorColor = SuzieeTheme.colors.accentPrimary
                    ),
                    supportingText = {
                        if (isWrong) {
                            Text(
                                text = "That's not it... try again \uD83D\uDC97",
                                color = SuzieeTheme.colors.error,
                                fontSize = 12.sp
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationX = shake.value }
                        .testTag("secret_password_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (PasswordHash.matches(input)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAuthenticated()
                    } else {
                        isWrong = true
                        input = ""
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch {
                            shake.animateTo(0f)
                            // A short left/right wobble: enough to read as "no", not enough
                            // to feel like an error state on the phone.
                            repeat(3) {
                                shake.animateTo(14f, tween(60, easing = FastOutSlowInEasing))
                                shake.animateTo(-14f, tween(60, easing = FastOutSlowInEasing))
                            }
                            shake.animateTo(0f, tween(90))
                        }
                    }
                },
                enabled = input.isNotBlank()
            ) {
                Text("Enter", color = SuzieeTheme.colors.accentPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", color = SuzieeTheme.colors.textSecondary)
            }
        },
        containerColor = SuzieeTheme.colors.surfaceCard,
        shape = RoundedCornerShape(28.dp)
    )
}

/**
 * The decorative heart that drifts behind the romantic overlay.
 *
 * Driven by one infinite transition and a deterministic per-index phase, so the animation
 * is identical between recompositions and cheap enough to keep at 60fps. Respects
 * `LocalReducedMotion`: when motion is reduced the hearts stop travelling and only pulse.
 */
@Composable
fun RomanticHeartField(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    heartCount: Int = 18
) {
    val colors = SuzieeTheme.colors
    val palette = remember(colors.accentPrimary, colors.accentSecondary, colors.highlight) {
        listOf(
            colors.accentPrimary,
            colors.accentSecondary,
            colors.highlight,
            Color(0xFFFF8FB8),
            Color(0xFFFFC9DC)
        )
    }

    val transition = rememberInfiniteTransition(label = "romantic_hearts")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (reducedMotion) 0 else 9000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "drift"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(modifier = modifier.fillMaxSize()) {
        for (index in 0 until heartCount) {
            // Deterministic per-heart layout: no randomness, so the field is stable across
            // recompositions, screen rotations and theme switches.
            val phase = (index * 0.137f) % 1f
            val xFraction = 0.04f + ((index * 0.2117f) % 0.92f)
            val travel = (drift + phase) % 1f
            val scale = 0.45f + ((index * 7) % 3) * 0.28f
            val size = (14 + ((index * 11) % 5) * 5).dp
            val tint = palette[index % palette.size]
            val sway = if (reducedMotion) 0f else 26f * kotlin.math.sin((travel + phase) * 6.2831f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (xFraction - 0.5f) * size.width * 3f + sway
                        translationY = -size.height.toPx() * 14f * travel + sway * 0.2f
                        rotationZ = sway * 0.4f
                        val alphaFactor = if (reducedMotion) {
                            0.55f
                        } else {
                            (1f - kotlin.math.abs(travel - 0.5f) * 1.6f).coerceIn(0f, 1f)
                        }
                        alpha = 0.75f * alphaFactor
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(size * scale * (if (reducedMotion) pulse else 1f))
                ) {
                    // The glow beneath the glyph keeps the heart legible over the message.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.22f * pulse)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(tint, Color.Transparent)
                                ),
                                RoundedCornerShape(50)
                            )
                    )
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(size * scale * 0.72f)
                    )
                }
            }
        }
    }
}
