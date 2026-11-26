package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.SuzieeTheme

/**
 * Real device equalizer: preset chips plus one slider per band the hardware exposes.
 *
 * The dialog reads the effect's actual band count and level range, so it adapts to 5-band
 * and 10-band devices instead of assuming a fixed layout.
 */
@Composable
fun EqualizerDialog(
    playerManager: MusicPlayerManager,
    onDismiss: () -> Unit
) {
    val equalizer = playerManager.equalizer
    var bands by remember { mutableStateOf<List<Short>>(emptyList()) }
    var levels by remember { mutableStateOf<List<Short>>(emptyList()) }
    var minLevel by remember { mutableStateOf(0) }
    var maxLevel by remember { mutableStateOf(0) }
    var presets by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPreset by remember { mutableIntStateOf(-1) }
    var enabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        bands = List(equalizer.bandCount()) { it.toShort() }
        levels = equalizer.bandLevels()
        equalizer.bandLevelRange()?.let { range ->
            minLevel = range.first.toInt()
            maxLevel = range.second.toInt()
        }
        presets = equalizer.presetNames()
    }

    val sliderStart = if (maxLevel > minLevel) minLevel.toFloat() else -1500f
    val sliderEnd = if (maxLevel > minLevel) maxLevel.toFloat() else 1500f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SuzieeTheme.colors.surfaceCard,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Equalizer",
                    color = SuzieeTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        equalizer.setEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SuzieeTheme.colors.bgPrimary,
                        checkedTrackColor = SuzieeTheme.colors.accentPrimary
                    )
                )
            }
        },
        text = {
            if (bands.isEmpty()) {
                Text(
                    text = "Start a track, then reopen the equalizer to tune it.",
                    color = SuzieeTheme.colors.textSecondary
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEachIndexed { index, name ->
                            val selected = index == selectedPreset
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    SuzieeTheme.colors.accentPrimary
                                } else {
                                    SuzieeTheme.colors.bgPrimary
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) {
                                        SuzieeTheme.colors.accentPrimary
                                    } else {
                                        SuzieeTheme.colors.border
                                    }
                                ),
                                modifier = Modifier.clickable {
                                    selectedPreset = index
                                    equalizer.applyPreset(index.toShort())
                                    levels = equalizer.bandLevels()
                                }
                            ) {
                                Text(
                                    text = name,
                                    color = if (selected) {
                                        SuzieeTheme.colors.bgPrimary
                                    } else {
                                        SuzieeTheme.colors.textPrimary
                                    },
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    bands.forEachIndexed { index, band ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${equalizer.centerFreq(band)} Hz",
                                color = SuzieeTheme.colors.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.width(64.dp)
                            )
                            Slider(
                                value = (levels.getOrNull(index) ?: 0).toFloat(),
                                onValueChange = { value ->
                                    val level = value.toInt().toShort()
                                    equalizer.setBandLevel(band, level)
                                    levels = levels.toMutableList().also {
                                        if (index < it.size) it[index] = level
                                    }
                                    selectedPreset = -1
                                },
                                valueRange = sliderStart..sliderEnd,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            bands.forEach { equalizer.setBandLevel(it, 0) }
                            levels = List<Short>(bands.size) { 0 }
                            selectedPreset = -1
                        }
                    ) {
                        Text("Flat", color = SuzieeTheme.colors.accentSecondary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = SuzieeTheme.colors.accentPrimary)
            }
        }
    )
}
