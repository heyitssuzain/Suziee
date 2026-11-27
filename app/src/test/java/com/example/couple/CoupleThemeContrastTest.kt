package com.example.couple

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.SuzieeThemeId
import com.example.ui.theme.suzieeColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG contrast checks for the semantic colours used by Couple Space.
 *
 * These are pure JVM checks over [suzieeColorsFor]; no device or Robolectric runtime is
 * required. They exist because a fourth theme (or a palette tweak) must not silently make a
 * chat bubble, a status pill or a primary button unreadable.
 */
class CoupleThemeContrastTest {

    @Test
    fun exactlyTheThreeExistingThemesAreSupported() {
        assertEquals(3, SuzieeThemeId.entries.size)
    }

    @Test
    fun bodyTextMeetsAaOnEveryTheme() {
        SuzieeThemeId.entries.forEach { themeId ->
            val colors = suzieeColorsFor(themeId)
            val surface = over(colors.surfaceCard, colors.bgPrimary)
            assertAtLeast(4.5, colors.textPrimary, colors.bgPrimary, "$themeId primary on page")
            assertAtLeast(4.5, colors.textPrimary, surface, "$themeId primary on card")
            assertAtLeast(4.5, colors.textSecondary, colors.bgPrimary, "$themeId secondary on page")
            assertAtLeast(4.5, colors.textSecondary, surface, "$themeId secondary on card")
        }
    }

    @Test
    fun accentTextMeetsAaOnEveryTheme() {
        SuzieeThemeId.entries.forEach { themeId ->
            val colors = suzieeColorsFor(themeId)
            val surface = over(colors.surfaceCard, colors.bgPrimary)
            val accentText = if (colors.isDark) colors.accentPrimary else colors.highlight
            assertAtLeast(4.5, accentText, surface, "$themeId accent text")
        }
    }

    @Test
    fun primaryButtonMeetsAaOnEveryTheme() {
        SuzieeThemeId.entries.forEach { themeId ->
            val colors = suzieeColorsFor(themeId)
            val container = if (colors.isDark) colors.accentPrimary else colors.highlight
            val content = if (colors.isDark) colors.bgPrimary else Color.White
            assertAtLeast(4.5, content, container, "$themeId primary button")
        }
    }

    @Test
    fun chatBubbleTextMeetsAaOnEveryTheme() {
        SuzieeThemeId.entries.forEach { themeId ->
            val colors = suzieeColorsFor(themeId)
            val card = over(colors.surfaceCard, colors.bgPrimary)
            val elevated = over(colors.surfaceElevated, card)
            val outgoing = over(colors.accentPrimary.copy(alpha = 0.18f), elevated)
            assertAtLeast(4.5, colors.textPrimary, outgoing, "$themeId outgoing bubble")
            assertAtLeast(4.5, colors.textPrimary, elevated, "$themeId incoming bubble")
        }
    }

    @Test
    fun statusPillLabelsMeetAaOnEveryTheme() {
        SuzieeThemeId.entries.forEach { themeId ->
            val colors = suzieeColorsFor(themeId)
            val card = over(colors.surfaceCard, colors.bgPrimary)

            val successBg = over(colors.success.copy(alpha = 0.14f), card)
            val successLabel = if (colors.isDark) colors.success else colors.textPrimary
            assertAtLeast(4.5, successLabel, successBg, "$themeId connected pill")

            val errorBg = over(colors.error.copy(alpha = 0.14f), card)
            val errorLabel = if (colors.isDark) colors.error else colors.textPrimary
            assertAtLeast(4.5, errorLabel, errorBg, "$themeId failed pill")

            val offlineBg = over(colors.textMuted.copy(alpha = 0.14f), card)
            assertAtLeast(4.5, colors.textSecondary, offlineBg, "$themeId offline pill")
        }
    }

    private fun assertAtLeast(
        minimum: Double,
        foreground: Color,
        background: Color,
        label: String
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$label contrast was ${"%.2f".format(ratio)}:1, expected >= $minimum:1",
            ratio >= minimum
        )
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val resolved = over(foreground, background)
        val first = luminance(resolved)
        val second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun over(foreground: Color, background: Color): Color {
        val alpha = foreground.alpha.toDouble()
        if (alpha >= 1.0) return foreground
        return Color(
            red = (foreground.red * alpha + background.red * (1.0 - alpha)).toFloat(),
            green = (foreground.green * alpha + background.green * (1.0 - alpha)).toFloat(),
            blue = (foreground.blue * alpha + background.blue * (1.0 - alpha)).toFloat(),
            alpha = 1f
        )
    }

    private fun luminance(color: Color): Double {
        val red = linear(color.red)
        val green = linear(color.green)
        val blue = linear(color.blue)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }
}
