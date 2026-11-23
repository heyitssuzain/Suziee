package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The three Suziee design identities.
 *
 * The id is persisted as [storageKey] in DataStore, so the string values must stay stable.
 */
enum class SuzieeThemeId(
    val storageKey: String,
    val label: String,
    val tagline: String
) {
    MINIMAL_SLATE(
        storageKey = "minimal_slate",
        label = "Minimal Slate",
        tagline = "Editorial calm, grey surfaces, deep-wine ink."
    ),
    PINK_CLAY(
        storageKey = "pink_clay",
        label = "Pink Claymorphism",
        tagline = "Pastel candy, 30.dp corners, bouncy springs."
    ),
    AURORA_GLASS(
        storageKey = "aurora_glass",
        label = "Aurora Glass",
        tagline = "Dark glass with aurora light from the album art."
    );

    companion object {
        fun fromKey(key: String?): SuzieeThemeId =
            SuzieeThemeId.entries.firstOrNull { it.storageKey == key } ?: AURORA_GLASS
    }
}

/**
 * Every colour the UI needs, expressed semantically so screens never hard-code a hex value.
 *
 * [isDark] drives system-bar icon contrast. [isClay] / [isGlass] let shared surfaces opt into
 * the material language of the active theme instead of branching on the theme id everywhere.
 */
@Immutable
data class SuzieeColors(
    val themeId: SuzieeThemeId,
    val isDark: Boolean,
    val isClay: Boolean,
    val isGlass: Boolean,
    val bgPrimary: Color,
    val bgSecondary: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val highlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val success: Color,
    val error: Color,
    val cornerRadius: Dp,
    val accentGradient: List<Color>
) {
    /** Used by glass cards and the aurora player backdrop. */
    val glassTint: Color
        get() = if (isDark) Color.White.copy(alpha = 0.075f) else Color.White.copy(alpha = 0.55f)

    val glassBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.90f)
}

// ---------------------------------------------------------------------------------------------
// Palettes
// ---------------------------------------------------------------------------------------------

private val MinimalSlate = SuzieeColors(
    themeId = SuzieeThemeId.MINIMAL_SLATE,
    isDark = false,
    isClay = false,
    isGlass = false,
    bgPrimary = Color(0xFFF7F7F8),
    bgSecondary = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEFEFF2),
    accentPrimary = Color(0xFF5E1B2A), // Deep wine
    accentSecondary = Color(0xFF8E4A5A),
    highlight = Color(0xFF5E1B2A),
    textPrimary = Color(0xFF2A1A1E),
    textSecondary = Color(0xFF64646D),
    textMuted = Color(0xFF9898A2),
    border = Color(0xFFE4E4E9),
    success = Color(0xFF2F7D5A),
    error = Color(0xFFB3263B),
    cornerRadius = 20.dp,
    accentGradient = listOf(Color(0xFF5E1B2A), Color(0xFF9A5A68))
)

private val PinkClay = SuzieeColors(
    themeId = SuzieeThemeId.PINK_CLAY,
    isDark = false,
    isClay = true,
    isGlass = false,
    bgPrimary = Color(0xFFFFF4F8),
    bgSecondary = Color(0xFFFFEAF2),
    surfaceCard = Color(0xFFFFFAFC),
    surfaceElevated = Color(0xFFFFE2EC),
    accentPrimary = Color(0xFFD9557B),
    accentSecondary = Color(0xFFF49BB8),
    highlight = Color(0xFF6B2A3F),
    textPrimary = Color(0xFF4A2430),
    textSecondary = Color(0xFF8A5A6B),
    textMuted = Color(0xFFB08A99),
    border = Color(0xFFF3C7D7),
    success = Color(0xFF3F8F6B),
    error = Color(0xFFC84B5A),
    cornerRadius = 30.dp,
    accentGradient = listOf(Color(0xFFD9557B), Color(0xFFF49BB8))
)

private val AuroraGlass = SuzieeColors(
    themeId = SuzieeThemeId.AURORA_GLASS,
    isDark = true,
    isClay = false,
    isGlass = true,
    bgPrimary = Color(0xFF090A12),
    bgSecondary = Color(0xFF111321),
    surfaceCard = Color(0xCC161828),
    surfaceElevated = Color(0xE6191C2D),
    accentPrimary = Color(0xFFA78BFA),
    accentSecondary = Color(0xFF67E8F9),
    highlight = Color(0xFFF5F3FF),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFA5A9BC),
    textMuted = Color(0xFF64748B),
    border = Color(0xFF282B45),
    success = Color(0xFF86EFAC),
    error = Color(0xFFFDA4AF),
    cornerRadius = 26.dp,
    accentGradient = listOf(Color(0xFFA78BFA), Color(0xFF67E8F9))
)

fun suzieeColorsFor(themeId: SuzieeThemeId): SuzieeColors = when (themeId) {
    SuzieeThemeId.MINIMAL_SLATE -> MinimalSlate
    SuzieeThemeId.PINK_CLAY -> PinkClay
    SuzieeThemeId.AURORA_GLASS -> AuroraGlass
}

// ---------------------------------------------------------------------------------------------
// Composition locals + accessor object
// ---------------------------------------------------------------------------------------------

val LocalSuzieeColors = staticCompositionLocalOf { AuroraGlass }
val LocalSuzieeThemeId = staticCompositionLocalOf { SuzieeThemeId.AURORA_GLASS }

/** App-wide reduced-motion flag. Set once from the persisted preference in MainActivity. */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * `SuzieeTheme.colors` mirrors `MaterialTheme.colorScheme`: a cheap, composable-aware read of
 * the active palette. Existing screens can migrate one colour at a time without touching
 * playback, Room, or Media3 code.
 */
object SuzieeTheme {
    val colors: SuzieeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSuzieeColors.current

    val themeId: SuzieeThemeId
        @Composable
        @ReadOnlyComposable
        get() = LocalSuzieeThemeId.current
}

@Composable
fun SuzieeTheme(
    themeId: SuzieeThemeId = SuzieeThemeId.AURORA_GLASS,
    content: @Composable () -> Unit
) {
    val colors = remember(themeId) { suzieeColorsFor(themeId) }
    val colorScheme = remember(themeId) { colors.toMaterialScheme() }
    val shapes = remember(themeId) {
        Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(colors.cornerRadius),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(colors.cornerRadius + 8.dp)
        )
    }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !colors.isDark
                isAppearanceLightNavigationBars = !colors.isDark
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    CompositionLocalProvider(
        LocalSuzieeColors provides colors,
        LocalSuzieeThemeId provides themeId
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SuzieeTypography,
            shapes = shapes,
            content = content
        )
    }
}

private fun SuzieeColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = accentPrimary,
        onPrimary = bgPrimary,
        primaryContainer = surfaceElevated,
        onPrimaryContainer = highlight,
        secondary = accentSecondary,
        onSecondary = bgPrimary,
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = highlight,
        tertiary = accentSecondary,
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgSecondary,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        outline = border,
        error = error
    )
} else {
    lightColorScheme(
        primary = accentPrimary,
        onPrimary = Color.White,
        primaryContainer = surfaceElevated,
        onPrimaryContainer = textPrimary,
        secondary = accentSecondary,
        onSecondary = Color.White,
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = textPrimary,
        tertiary = accentSecondary,
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgSecondary,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        outline = border,
        error = error
    )
}

// ---------------------------------------------------------------------------------------------
// Edge-to-edge helpers
// ---------------------------------------------------------------------------------------------

/**
 * Immersive mode for the full-screen player / intro.
 *
 * Bars are hidden with a transient swipe behaviour; leaving the composable (or setting
 * [hideBars] back to false) restores them. Callers keep drawing edge-to-edge underneath.
 */
@Composable
fun SystemBarsVisibility(hideBars: Boolean, useDarkIcons: Boolean? = null) {
    val view = LocalView.current
    val darkIcons = useDarkIcons ?: !LocalSuzieeColors.current.isDark

    androidx.compose.runtime.DisposableEffect(hideBars, darkIcons) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hideBars) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * A soft gradient behind the status-bar icons. Without it, white system icons disappear on the
 * light themes and dark icons disappear on the dark themes.
 */
@Composable
fun TopSystemBarScrim(
    modifier: Modifier = Modifier,
    color: Color = SuzieeTheme.colors.bgPrimary,
    extraHeight: Dp = 26.dp
) {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { topInset.toDp() } + extraHeight)
            .background(
                Brush.verticalGradient(
                    listOf(
                        color.copy(alpha = 0.96f),
                        color.copy(alpha = 0.72f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
fun BottomSystemBarScrim(
    modifier: Modifier = Modifier,
    color: Color = SuzieeTheme.colors.bgPrimary,
    extraHeight: Dp = 12.dp
) {
    val density = LocalDensity.current
    val bottomInset = WindowInsets.navigationBars.getBottom(density)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { bottomInset.toDp() } + extraHeight)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.72f),
                        color.copy(alpha = 0.96f)
                    )
                )
            )
    )
}
