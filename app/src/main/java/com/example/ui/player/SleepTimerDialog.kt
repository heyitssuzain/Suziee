package com.example.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.SuzieeTheme
import java.util.Locale

/**
 * Sleep timer picker. Shows the active countdown and lets the user switch it off.
 */
@Composable
fun SleepTimerDialog(
    playerManager: MusicPlayerManager,
    onDismiss: () -> Unit
) {
    val selectedMinutes by playerManager.sleepTimerMinutes.collectAsState()
    val remainingMs by playerManager.sleepTimerRemainingMs.collectAsState()
    val options = listOf(0, 15, 30, 45, 60, 90, 120)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SuzieeTheme.colors.surfaceCard,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "Sleep timer",
                    color = SuzieeTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                if (remainingMs > 0) {
                    Text(
                        text = "Stops in ${formatRemaining(remainingMs)}",
                        color = SuzieeTheme.colors.accentPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { minutes ->
                    val isSelected = minutes == selectedMinutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                playerManager.setSleepTimer(minutes)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (minutes == 0) "Off" else "$minutes minutes",
                            color = if (isSelected) {
                                SuzieeTheme.colors.accentPrimary
                            } else {
                                SuzieeTheme.colors.textPrimary
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = SuzieeTheme.colors.accentPrimary
                            )
                        }
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

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
