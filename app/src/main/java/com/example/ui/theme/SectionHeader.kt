package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The section heading used across the app. The same call renders three different artefacts so the
 * three identities read as separate products rather than one palette swap:
 *
 *  - Pink Claymorphism: a puffy pastel pill with a gradient candy dot and a soft shadow.
 *  - Minimal Slate: a square ink tick, an uppercase letter-spaced label and a hairline rule.
 *  - Aurora Glass: a glowing gradient dot beside an uppercase tracked label.
 *
 * [trailing] hosts the optional end-aligned action (e.g. a "See all" affordance).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = SuzieeTheme.colors
    when {
        colors.isClay -> ClaySectionHeader(title, subtitle, trailing, modifier)
        colors.isGlass -> AuroraSectionHeader(title, subtitle, trailing, modifier)
        else -> MinimalSectionHeader(title, subtitle, trailing, modifier)
    }
}

@Composable
private fun ClaySectionHeader(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Surface(
                shape = shape,
                color = colors.accentPrimary.copy(alpha = 0.12f),
                modifier = Modifier.shadow(
                    elevation = 6.dp,
                    shape = shape,
                    ambientColor = colors.accentPrimary.copy(alpha = 0.45f),
                    spotColor = colors.accentPrimary.copy(alpha = 0.45f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(colors.accentGradient))
                    )
                }
            }
            subtitle?.let {
                Text(
                    text = it,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 5.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun MinimalSectionHeader(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .background(colors.accentPrimary)
                )
                Text(
                    text = title.uppercase(),
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    ),
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            trailing?.invoke()
        }
        subtitle?.let {
            Text(
                text = it,
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, top = 3.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = colors.border, thickness = 1.dp)
    }
}

@Composable
private fun AuroraSectionHeader(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(colors.accentGradient))
                )
                Text(
                    text = title.uppercase(),
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp
                    ),
                    modifier = Modifier.padding(start = 9.dp)
                )
            }
            subtitle?.let {
                Text(
                    text = it,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 17.dp, top = 3.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A tappable keyword chip. Clay gets a soft, puffy candy pill; Minimal an editorial hairline
 * outline; Aurora a translucent tinted glass capsule.
 */
@Composable
fun SuzieeTagChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val shape = RoundedCornerShape(50)
    when {
        colors.isClay -> Box(
            modifier = modifier
                .shadow(
                    elevation = 5.dp,
                    shape = shape,
                    ambientColor = colors.accentPrimary.copy(alpha = 0.32f),
                    spotColor = colors.accentPrimary.copy(alpha = 0.32f)
                )
                .clip(shape)
                .background(
                    Brush.linearGradient(listOf(colors.surfaceCard, colors.surfaceElevated))
                )
                .border(1.dp, Color.White.copy(alpha = 0.8f), shape)
                .bouncyClickable(onClick = onClick)
        ) {
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }

        colors.isGlass -> Surface(
            shape = shape,
            color = colors.accentPrimary.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, colors.accentSecondary.copy(alpha = 0.40f)),
            modifier = modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                color = colors.highlight,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp)
            )
        }

        else -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, colors.border),
            modifier = modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}
