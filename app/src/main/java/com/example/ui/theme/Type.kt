package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Bundled Google Fonts.
 *
 * The static TTFs live in `res/font` (downloaded from Google Fonts under the OFL), so the
 * typography is available offline on first launch. Manrope carries the expressive display /
 * headline voice; Inter keeps body copy neutral and highly legible.
 */
val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
    Font(R.font.manrope_800, FontWeight.ExtraBold)
)

val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold)
)

private fun manrope(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Float = -0.4f
) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp
)

private fun inter(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Float = 0f
) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp
)

/**
 * Material 3 type scale. Headlines and titles use Manrope; body and label text use Inter.
 * Line heights are deliberately generous for long, scannable lyrics and track metadata.
 */
val SuzieeTypography = Typography(
    displayLarge = manrope(56, 62, FontWeight.ExtraBold, -1.2f),
    displayMedium = manrope(46, 52, FontWeight.ExtraBold, -1.0f),
    displaySmall = manrope(38, 44, FontWeight.Bold, -0.8f),

    headlineLarge = manrope(34, 40, FontWeight.ExtraBold, -0.7f),
    headlineMedium = manrope(28, 34, FontWeight.Bold, -0.5f),
    headlineSmall = manrope(24, 30, FontWeight.Bold, -0.4f),

    titleLarge = manrope(22, 28, FontWeight.Bold, -0.3f),
    titleMedium = manrope(17, 23, FontWeight.SemiBold, -0.2f),
    titleSmall = manrope(15, 21, FontWeight.SemiBold, -0.1f),

    bodyLarge = inter(16, 25, FontWeight.Normal),
    bodyMedium = inter(14, 22, FontWeight.Normal),
    bodySmall = inter(12, 18, FontWeight.Normal),

    labelLarge = inter(14, 19, FontWeight.SemiBold, 0.1f),
    labelMedium = inter(12, 16, FontWeight.Medium, 0.4f),
    labelSmall = inter(11, 15, FontWeight.Medium, 0.5f)
)
