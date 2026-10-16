package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary

@Composable
fun WaveformVisualizer(
    amplitudes: List<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 20,
    height: Dp = 48.dp,
    primaryColor: Color = SuzieeAccentPrimary,
    secondaryColor: Color = SuzieeAccentSecondary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val totalBars = barCount.coerceAtLeast(8)
        val spacing = 3.dp.toPx()
        val barWidth = (canvasWidth - (spacing * (totalBars - 1))) / totalBars

        for (i in 0 until totalBars) {
            val normalizedAmp = if (i < amplitudes.size) amplitudes[i] else 0.2f
            val dynamicFactor = if (isPlaying) {
                // Combine real amplitude with subtle sine wave animation
                val wave = kotlin.math.sin(phase + (i * 0.45f)).toFloat()
                (normalizedAmp * 0.7f + (wave * 0.15f + 0.15f)).coerceIn(0.12f, 1f)
            } else {
                0.12f
            }

            val barHeight = (canvasHeight * dynamicFactor).coerceAtLeast(4.dp.toPx())
            val xOffset = i * (barWidth + spacing)
            val yOffset = (canvasHeight - barHeight) / 2f

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(primaryColor, secondaryColor),
                    startY = yOffset,
                    endY = yOffset + barHeight
                ),
                topLeft = Offset(xOffset, yOffset),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
