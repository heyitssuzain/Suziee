package com.example.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Frosted-glass card used by Aurora Glass (and as an accent surface in the other themes).
 * The blur lives in the *background artwork*, not on this card, so text stays razor sharp.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SuzieeTheme.colors.cornerRadius),
    tint: Color = SuzieeTheme.colors.glassTint,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = SuzieeTheme.colors
    Box(
        modifier = modifier
            .clip(shape)
            .background(tint)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        colors.glassBorder,
                        colors.glassBorder.copy(alpha = 0.20f),
                        colors.accentSecondary.copy(alpha = 0.24f),
                        colors.glassBorder.copy(alpha = 0.35f)
                    )
                ),
                shape = shape
            )
    ) {
        // A soft top-edge specular highlight.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.Transparent
                        )
                    )
                )
        )
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Animated aurora field. The dominant album colour becomes the light source, with a slow
 * counter-moving secondary orb so the background never feels static.
 *
 * On Android 12+ the orbs use a real blur; older devices get the same gradients without the
 * (unsupported) blur pass, so nothing crashes on minSdk 24.
 */
@Composable
fun AuroraBackground(
    dominantColor: Color,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    intensity: Float = 1f
) {
    val colors = SuzieeTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "aurora_field")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (reducedMotion) 26000 else 14000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aurora_phase"
    )

    val accent = if (dominantColor == Color.Unspecified) colors.accentPrimary else dominantColor
    val secondary = if (dominantColor == Color.Unspecified) colors.accentSecondary else {
        // A cooler companion derived from the dominant colour, kept in the same family.
        Color(
            red = (accent.red * 0.55f + 0.15f).coerceIn(0f, 1f),
            green = (accent.green * 0.65f + 0.25f).coerceIn(0f, 1f),
            blue = (accent.blue * 0.70f + 0.35f).coerceIn(0f, 1f),
            alpha = 1f
        )
    }
    val motion = if (isPlaying) 1f else 0.35f

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(colors.bgPrimary, colors.bgSecondary, colors.bgPrimary)
            )
        )
    ) {
        Box(
            modifier = Modifier
                .size(420.dp)
                .offset(
                    x = (-110 + 150 * phase * motion).dp,
                    y = (-90 + 70 * phase * motion).dp
                )
                .blur(70.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.52f * intensity),
                            accent.copy(alpha = 0.18f * intensity),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(380.dp)
                .offset(
                    x = (180 - 140 * phase * motion).dp,
                    y = (320 + 90 * phase * motion).dp
                )
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            secondary.copy(alpha = 0.40f * intensity),
                            secondary.copy(alpha = 0.12f * intensity),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(260.dp)
                .offset(
                    x = (40 + 90 * phase).dp,
                    y = (-30 + 50 * phase).dp
                )
                .blur(60.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f * intensity),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
    }
}

/**
 * Small process-wide cache so returning to the same track does not re-decode its artwork.
 * Keyed by URL; 48 entries is plenty for a session and never grows unbounded.
 */
private val dominantColorCache = object : LruCache<String, Color>(48) {}

/**
 * Extracts the dominant colour of the current artwork with AndroidX Palette.
 *
 * The bitmap is decoded at 200px on [Dispatchers.IO] so palette extraction never touches the
 * UI thread, and the resolved colour is cached so track switches stay under ~50ms.
 * A deterministic hue is derived from the URL while the palette is loading or when the image
 * cannot be decoded, so the aurora never flashes a generic grey.
 */
@Composable
fun rememberDominantColor(artworkUrl: String?): Color {
    val context = LocalContext.current
    val fallback = remember(artworkUrl) { fallbackArtworkColor(artworkUrl) }
    var color by remember(artworkUrl) { mutableStateOf(fallback) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            color = fallback
            return@LaunchedEffect
        }

        val cached = withContext(Dispatchers.IO) { dominantColorCache.get(artworkUrl) }
        if (cached != null) {
            color = cached
            return@LaunchedEffect
        }

        val resolved = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(200)
                .allowHardware(false)
                .build()
            val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
            if (result is SuccessResult) {
                val bitmap = runCatching { result.drawable.toBitmap() }.getOrNull()
                if (bitmap != null) {
                    val palette = runCatching {
                        Palette.from(bitmap).maximumColorCount(16).generate()
                    }.getOrNull()
                    val dominant = palette?.getDominantColor(fallback.toArgbInt())
                    if (dominant != null) {
                        val resolvedColor = Color(dominant)
                        dominantColorCache.put(artworkUrl, resolvedColor)
                        resolvedColor
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }

        if (resolved != null) color = resolved
    }
    return color
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)

private fun fallbackArtworkColor(url: String?): Color {
    if (url.isNullOrBlank()) return Color(0xFFA78BFA)
    val hue = (url.hashCode() and 0x7FFFFFFF) % 360
    return Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(hue.toFloat(), 0.52f, 0.86f)
        )
    )
}

/**
 * Shared card facade: clay on Pink Claymorphism, glass on Aurora Glass, and a crisp bordered
 * surface on Minimal Slate. Screens call this instead of branching on the theme id.
 */
@Composable
fun ThemedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SuzieeTheme.colors.cornerRadius),
    color: Color = SuzieeTheme.colors.surfaceCard,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = SuzieeTheme.colors
    when {
        colors.isClay -> ClayCard(
            modifier = modifier,
            shape = shape,
            color = color,
            contentPadding = contentPadding,
            content = content
        )

        colors.isGlass -> GlassCard(
            modifier = modifier,
            shape = shape,
            contentPadding = contentPadding,
            content = content
        )

        else -> Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
            shadowElevation = 2.dp
        ) {
            Box(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}
