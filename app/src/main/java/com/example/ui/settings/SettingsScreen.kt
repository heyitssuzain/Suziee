package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.SuzieePreferences
import com.example.data.repository.MusicRepository
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeSurfaceCard
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: SuzieePreferences,
    repository: MusicRepository,
    onBack: () -> Unit,
    onReplayIntro: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isReducedMotion by preferences.isReducedMotion.collectAsState(initial = false)
    val isAutoplay by preferences.isAutoplayEnabled.collectAsState(initial = true)
    val voiceLang by preferences.voiceLanguage.collectAsState(initial = "en-US")
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeBgPrimary)
            .testTag("settings_screen")
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings",
                    color = SuzieeTextPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SuzieeTextPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SuzieeBgPrimary)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Appearance section
            item {
                SettingsSectionHeader("APPEARANCE")
                SettingsCard {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Animation, contentDescription = null, tint = SuzieeAccentSecondary)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text("Reduced Motion", color = SuzieeTextPrimary, fontWeight = FontWeight.Medium)
                                    Text("Skip intro animations and reduce dynamic motion", color = SuzieeTextSecondary, fontSize = 12.sp)
                                }
                            }
                            Switch(
                                checked = isReducedMotion,
                                onCheckedChange = { scope.launch { preferences.setReducedMotion(it) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuzieeBgPrimary, checkedTrackColor = SuzieeAccentPrimary)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onReplayIntro() }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = SuzieeAccentPrimary)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text("Replay Cinematic Intro", color = SuzieeTextPrimary, fontWeight = FontWeight.Medium)
                                Text("Watch the animated Suziee opening again", color = SuzieeTextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Playback section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("PLAYBACK")
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = SuzieeAccentPrimary)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text("Autoplay Next Track", color = SuzieeTextPrimary, fontWeight = FontWeight.Medium)
                                Text("Automatically play subsequent songs in queue", color = SuzieeTextSecondary, fontSize = 12.sp)
                            }
                        }
                        Switch(
                            checked = isAutoplay,
                            onCheckedChange = { scope.launch { preferences.setAutoplayEnabled(it) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = SuzieeBgPrimary, checkedTrackColor = SuzieeAccentPrimary)
                        )
                    }
                }
            }

            // Voice Language section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("VOICE COMMANDS")
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = SuzieeAccentSecondary)
                            Text(
                                text = "Preferred Voice Language",
                                color = SuzieeTextPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("en-US" to "English (US)", "en-IN" to "English (India)", "hi-IN" to "Hindi").forEach { (code, label) ->
                                val selected = voiceLang == code
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) SuzieeAccentPrimary else SuzieeBgPrimary,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) SuzieeAccentPrimary else SuzieeBorder),
                                    modifier = Modifier.clickable { scope.launch { preferences.setVoiceLanguage(code) } }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) SuzieeBgPrimary else SuzieeTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Privacy & Data
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("PRIVACY & STORAGE")
                SettingsCard {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { scope.launch { repository.clearHistory() } }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, tint = SuzieeAccentPrimary)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text("Clear Playback History", color = SuzieeTextPrimary, fontWeight = FontWeight.Medium)
                                Text("Delete recent playback logs from local device", color = SuzieeTextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // About section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("ABOUT SUZIEE")
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = SuzieeAccentSecondary)
                            Text(
                                text = "Suziee Music Player v1.0",
                                color = SuzieeTextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Premium anime music player built with Kotlin, Jetpack Compose, Media3 ExoPlayer, InnerTubeX architecture, Room database, and LRCLIB synchronized lyrics.",
                            color = SuzieeTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No tracking, no mandatory accounts, pure anime music experience.",
                            color = SuzieeTextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = SuzieeAccentSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = SuzieeSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder),
        content = content
    )
}
