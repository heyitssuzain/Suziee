package com.example.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A soft, extruded clay surface: a light outer bloom, a crisp white rim, and two very subtle
 * inner gradients that read as the "double shadow" of claymorphism. It stays a normal Compose
 * layout, so text and images inside remain fully accessible.
 */
@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SuzieeTheme.colors.cornerRadius),
    color: Color = SuzieeTheme.colors.surfaceCard,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = SuzieeTheme.colors
    Box(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = Color.White.copy(alpha = 0.85f),
                spotColor = colors.accentPrimary.copy(alpha = 0.20f),
                clip = false
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.70f),
                        color,
                        color
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.72f), shape)
    ) {
        // Outer-to-inner sheen (the "double" part of the soft shadow).
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.42f),
                            Color.Transparent,
                            colors.accentPrimary.copy(alpha = 0.055f)
                        )
                    )
                )
        )
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Press feedback with a springy scale. Used on the clay theme's primary actions and on every
 * card that is fun to tap. [pressedScale] below 1 shrinks; values above 1 give a "pop".
 */
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.32f, stiffness = 650f),
        label = "bouncy_clickable_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick
        )
}
