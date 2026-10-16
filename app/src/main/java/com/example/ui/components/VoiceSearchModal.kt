package com.example.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeError
import com.example.ui.theme.SuzieeSurfaceCard
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary
import com.example.voice.VoiceCommand
import com.example.voice.VoiceCommandParser
import com.example.voice.VoiceRecognizer
import com.example.voice.VoiceState

@Composable
fun VoiceSearchModal(
    voiceRecognizer: VoiceRecognizer,
    voiceState: VoiceState,
    onDismiss: () -> Unit,
    onCommandParsed: (VoiceCommand, String) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            voiceRecognizer.startListening()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            voiceRecognizer.startListening()
        }
    }

    // Automatically parse command on success
    LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Success) {
            val command = VoiceCommandParser.parse(voiceState.spokenText)
            onCommandParsed(command, voiceState.spokenText)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_rings")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Dialog(onDismissRequest = {
        voiceRecognizer.reset()
        onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SuzieeSurfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, SuzieeBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("voice_search_modal")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SUZIEE VOICE",
                        color = SuzieeAccentSecondary,
                        style = MaterialTheme.typography.labelLarge.copy(
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    IconButton(
                        onClick = {
                            voiceRecognizer.reset()
                            onDismiss()
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SuzieeTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Microphone animated circle
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(130.dp)
                ) {
                    if (voiceState is VoiceState.Listening) {
                        // Outer neon aura ring
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .scale(pulseScale)
                                .border(
                                    2.dp,
                                    Brush.radialGradient(
                                        listOf(SuzieeAccentSecondary.copy(alpha = 0.6f), androidx.compose.ui.graphics.Color.Transparent)
                                    ),
                                    CircleShape
                                )
                        )
                    }

                    // Main mic button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(SuzieeAccentPrimary, SuzieeAccentSecondary)),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (voiceState is VoiceState.Processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = SuzieeBgPrimary,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Microphone",
                                tint = SuzieeBgPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // State message
                when (voiceState) {
                    is VoiceState.Listening -> {
                        Text(
                            text = "Listening...",
                            color = SuzieeTextPrimary,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Say \"Play Believer\", \"Next song\", \"Pause\", or any title",
                            color = SuzieeTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    is VoiceState.Processing -> {
                        Text(
                            text = "Interpreting audio command...",
                            color = SuzieeAccentSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is VoiceState.Success -> {
                        Text(
                            text = "\"${voiceState.spokenText}\"",
                            color = SuzieeAccentSecondary,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                    }
                    is VoiceState.Error -> {
                        Text(
                            text = voiceState.message,
                            color = SuzieeError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (hasPermission) {
                                    voiceRecognizer.startListening()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuzieeAccentPrimary)
                        ) {
                            Text("Try Again", color = SuzieeBgPrimary)
                        }
                    }
                    VoiceState.Idle -> {
                        Text(
                            text = "Tap to speak",
                            color = SuzieeTextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
