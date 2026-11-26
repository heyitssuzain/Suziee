package com.example.ui.settings

import com.example.ui.theme.SuzieeTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.example.data.preferences.SuzieePreferences
import com.example.data.preferences.HomeRailConfig
import com.example.data.repository.MusicRepository
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.SuzieeThemeId
import com.example.ui.theme.ThemedCard
import com.example.ui.theme.suzieeColorsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: SuzieePreferences,
    repository: MusicRepository,
    playerManager: MusicPlayerManager,
    onBack: () -> Unit,
    onReplayIntro: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isReducedMotion by preferences.isReducedMotion.collectAsState(initial = false)
    val isAutoplay by preferences.isAutoplayEnabled.collectAsState(initial = true)
    val voiceLang by preferences.voiceLanguage.collectAsState(initial = "en-US")
    val themeKey by preferences.themeId.collectAsState(initial = "aurora_glass")
    val lyricsOffset by preferences.lyricsOffsetMs.collectAsState(initial = 0)
    val activeTheme = SuzieeThemeId.fromKey(themeKey)
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var cacheSummary by remember { mutableStateOf("Calculating…") }
    var cacheRefresh by remember { mutableIntStateOf(0) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showAddRail by remember { mutableStateOf(false) }
    var newRailTitle by remember { mutableStateOf("") }
    var newRailQuery by remember { mutableStateOf("") }
    val homeRails by preferences.homeRails.collectAsState(initial = HomeRailConfig.defaults)
    val downloadQuality by preferences.downloadQuality.collectAsState(initial = "high")
    val lastFmEnabled by preferences.isLastFmEnabled.collectAsState(initial = false)
    val lastFmApiKey by preferences.lastFmApiKey.collectAsState(initial = "")
    val lastFmUsername by preferences.lastFmUsername.collectAsState(initial = "")
    val lastFmSessionKey by preferences.lastFmSessionKey.collectAsState(initial = "")

    // PR-1: the hidden dedication. Ephemeral by design — SecretPrefs.reset() runs in the
    // DisposableEffect below, so leaving Settings hides the entry again.
    var isSecretRevealed by remember {
        mutableStateOf(com.example.data.preferences.SecretPrefs.isSecretRevealed)
    }
    var showSecretPassword by remember { mutableStateOf(false) }
    var showSecretRomantic by remember { mutableStateOf(false) }
    val unlockSecret: () -> Unit = {
        // PR-1 asks for a toast-style confirmation on a successful activation.
        com.example.data.preferences.SecretPrefs.reveal()
        isSecretRevealed = true
    }
    DisposableEffect(Unit) {
        onDispose { com.example.data.preferences.SecretPrefs.reset() }
    }

    LaunchedEffect(cacheRefresh) {
        cacheSummary = withContext(Dispatchers.IO) {
            val loader = context.imageLoader
            val diskBytes = loader.diskCache?.size ?: 0L
            val memoryBytes = (loader.memoryCache?.size ?: 0).toLong()
            "Disk ${formatBytes(diskBytes)} · Memory ${formatBytes(memoryBytes)}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeTheme.colors.bgPrimary)
            .testTag("settings_screen")
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings",
                    color = SuzieeTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SuzieeTheme.colors.textPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SuzieeTheme.colors.bgPrimary)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Theme engine
            item {
                SettingsSectionHeader("THEME")
                SettingsCard {
                    Column(modifier = Modifier.padding(10.dp)) {
                        SuzieeThemeId.entries.forEach { id ->
                            val palette = suzieeColorsFor(id)
                            val selected = id == activeTheme
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (selected) palette.accentPrimary.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        scope.launch { preferences.setThemeId(id.storageKey) }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                                    .testTag("theme_${id.storageKey}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    palette.accentPrimary,
                                                    palette.accentSecondary
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(palette.bgPrimary.copy(alpha = 0.8f))
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = id.label,
                                        color = SuzieeTheme.colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = id.tagline,
                                        color = SuzieeTheme.colors.textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected theme",
                                        tint = palette.accentPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Appearance section
            item {
                Spacer(modifier = Modifier.height(16.dp))
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
                                Icon(Icons.Default.Animation, contentDescription = null, tint = SuzieeTheme.colors.accentSecondary)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text("Reduced Motion", color = SuzieeTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
                                    Text("Skip intro animations and reduce dynamic motion", color = SuzieeTheme.colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                            Switch(
                                checked = isReducedMotion,
                                onCheckedChange = { scope.launch { preferences.setReducedMotion(it) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuzieeTheme.colors.bgPrimary, checkedTrackColor = SuzieeTheme.colors.accentPrimary)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onReplayIntro() }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = SuzieeTheme.colors.accentPrimary)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text("Replay Cinematic Intro", color = SuzieeTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
                                Text("Watch the animated Suziee opening again", color = SuzieeTheme.colors.textSecondary, fontSize = 12.sp)
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
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = SuzieeTheme.colors.accentPrimary)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text("Autoplay Next Track", color = SuzieeTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
                                    Text("Automatically play subsequent songs in queue", color = SuzieeTheme.colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                            Switch(
                                checked = isAutoplay,
                                onCheckedChange = { scope.launch { preferences.setAutoplayEnabled(it) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = SuzieeTheme.colors.bgPrimary, checkedTrackColor = SuzieeTheme.colors.accentPrimary)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showEqualizer = true }
                                .padding(16.dp)
                                .testTag("open_equalizer_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = null,
                                tint = SuzieeTheme.colors.accentSecondary
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    "Equalizer",
                                    color = SuzieeTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Tune bass, mids and treble for the active audio session",
                                    color = SuzieeTheme.colors.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .testTag("lyrics_offset_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                tint = SuzieeTheme.colors.accentSecondary
                            )
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    "Lyrics Sync",
                                    color = SuzieeTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Shift lyric timing when lyrics run fast or slow",
                                    color = SuzieeTheme.colors.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        preferences.setLyricsOffsetMs((lyricsOffset - 500).coerceAtLeast(-5000))
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("−0.5s", color = SuzieeTheme.colors.accentPrimary)
                            }
                            Text(
                                text = formatOffset(lyricsOffset),
                                color = SuzieeTheme.colors.textPrimary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        preferences.setLyricsOffsetMs((lyricsOffset + 500).coerceAtMost(5000))
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("+0.5s", color = SuzieeTheme.colors.accentPrimary)
                            }
                        }
                    }
                }
            }

            // Home rails (P1-13)
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("HOME RAILS")
                SettingsCard {
                    Column {
                        homeRails.forEach { rail ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = SuzieeTheme.colors.accentPrimary
                                )
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        rail.title,
                                        color = SuzieeTheme.colors.textPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "Search: ${rail.query}",
                                        color = SuzieeTheme.colors.textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(
                                    onClick = { scope.launch { preferences.removeHomeRail(rail.query) } }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove ${rail.title}",
                                        tint = SuzieeTheme.colors.textMuted
                                    )
                                }
                            }
                        }

                        if (homeRails.isEmpty()) {
                            Text(
                                "All discovery rails are hidden. Add one below or reset the defaults.",
                                color = SuzieeTheme.colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    newRailTitle = ""
                                    newRailQuery = ""
                                    showAddRail = true
                                }
                                .padding(16.dp)
                                .testTag("add_home_rail_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = SuzieeTheme.colors.accentSecondary
                            )
                            Text(
                                "Add discovery rail",
                                color = SuzieeTheme.colors.textPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                        TextButton(
                            onClick = { scope.launch { preferences.resetHomeRails() } },
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        ) {
                            Text("Reset defaults", color = SuzieeTheme.colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Download quality (P2-10)
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("DOWNLOADS")
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Download Quality",
                            color = SuzieeTheme.colors.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Preferred audio bitrate for new offline saves",
                            color = SuzieeTheme.colors.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "high" to "High · 160k",
                                "medium" to "Medium · 128k",
                                "low" to "Data saver · 70k"
                            ).forEach { (key, label) ->
                                val selected = downloadQuality == key
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) SuzieeTheme.colors.accentPrimary else SuzieeTheme.colors.bgPrimary,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) SuzieeTheme.colors.accentPrimary else SuzieeTheme.colors.border
                                    ),
                                    modifier = Modifier.clickable {
                                        scope.launch { preferences.setDownloadQuality(key) }
                                    }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) SuzieeTheme.colors.bgPrimary else SuzieeTheme.colors.textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Last.fm scrobbling (P3-4) — opt-in, credentials stay on-device.
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("LAST.FM SCROBBLING")
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Scrobble to Last.fm",
                                    color = SuzieeTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Optional. Nothing leaves the device until you enable this.",
                                    color = SuzieeTheme.colors.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = lastFmEnabled,
                                onCheckedChange = { scope.launch { preferences.setLastFmEnabled(it) } }
                            )
                        }
                        if (lastFmEnabled) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = lastFmApiKey,
                                onValueChange = { scope.launch { preferences.setLastFmApiKey(it) } },
                                label = { Text("API key") },
                                singleLine = true,
                                colors = settingsTextFieldColors(),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = lastFmUsername,
                                onValueChange = { scope.launch { preferences.setLastFmUsername(it) } },
                                label = { Text("Username") },
                                singleLine = true,
                                colors = settingsTextFieldColors(),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = lastFmSessionKey,
                                onValueChange = { scope.launch { preferences.setLastFmSessionKey(it) } },
                                label = { Text("Session key") },
                                singleLine = true,
                                colors = settingsTextFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Get these from your Last.fm API account. Scrobbles are sent only while the app plays a track.",
                                color = SuzieeTheme.colors.textMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
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
                            Icon(Icons.Default.Language, contentDescription = null, tint = SuzieeTheme.colors.accentSecondary)
                            Text(
                                text = "Preferred Voice Language",
                                color = SuzieeTheme.colors.textPrimary,
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
                                    color = if (selected) SuzieeTheme.colors.accentPrimary else SuzieeTheme.colors.bgPrimary,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) SuzieeTheme.colors.accentPrimary else SuzieeTheme.colors.border),
                                    modifier = Modifier.clickable { scope.launch { preferences.setVoiceLanguage(code) } }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) SuzieeTheme.colors.bgPrimary else SuzieeTheme.colors.textPrimary,
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
                            Icon(Icons.Default.CleaningServices, contentDescription = null, tint = SuzieeTheme.colors.accentPrimary)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text("Clear Playback History", color = SuzieeTheme.colors.textPrimary, fontWeight = FontWeight.Medium)
                                Text("Delete recent playback logs from local device", color = SuzieeTheme.colors.textSecondary, fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            context.imageLoader.diskCache?.clear()
                                            context.imageLoader.memoryCache?.clear()
                                        }
                                        cacheRefresh++
                                    }
                                }
                                .padding(16.dp)
                                .testTag("clear_image_cache_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CleaningServices,
                                contentDescription = null,
                                tint = SuzieeTheme.colors.accentSecondary
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    "Clear Image Cache",
                                    color = SuzieeTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    cacheSummary,
                                    color = SuzieeTheme.colors.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // About section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader("ABOUT SUZIEE")
                SettingsCard(
                    // PR-1: the dedication unlocks here. A two-finger two-second hold on
                    // this exact card — and nothing else in the app — reveals the hidden
                    // entry at the very bottom of Settings. No UI hint exists on purpose,
                    // and the gesture is intentionally awkward to hit by accident.
                    modifier = Modifier.twoFingerLongPress(holdDurationMs = 2_000L) {
                        unlockSecret()
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = SuzieeTheme.colors.accentSecondary)
                            Text(
                                text = "Suziee Music Player v1.0",
                                color = SuzieeTheme.colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                // The original spec also accepts a triple-tap on the version
                                // text. It lives here, on its own element, so the two
                                // gesture detectors never compete for the same pointer
                                // events and the two-finger hold stays reliable.
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .tripleTapWithin { unlockSecret() }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Premium anime music player built with Kotlin, Jetpack Compose, Media3 ExoPlayer, InnerTubeX architecture, Room database, and LRCLIB synchronized lyrics.",
                            color = SuzieeTheme.colors.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No tracking, no mandatory accounts, pure anime music experience.",
                            color = SuzieeTheme.colors.textMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))

                // PR-1: the hidden entry. Only rendered once the gesture has unlocked it,
                // and only while this Settings screen is open.
                item {
                    SecretEntryCard(
                        visible = isSecretRevealed,
                        onClick = { showSecretPassword = true }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        if (showEqualizer) {
            EqualizerDialog(
                playerManager = playerManager,
                onDismiss = { showEqualizer = false }
            )
        }

        // PR-2: the password gate, only reachable once the hidden entry exists.
        if (showSecretPassword) {
            PasswordDialog(
                onDismiss = { showSecretPassword = false },
                onAuthenticated = {
                    showSecretPassword = false
                    showSecretRomantic = true
                }
            )
        }

        // PR-3: the dedication itself, as an overlay so the back stack and playback are
        // both untouched.
        if (showSecretRomantic) {
            SecretEasterEgg(
                onDismiss = { showSecretRomantic = false }
            )
        }

        if (showAddRail) {
            AlertDialog(
                onDismissRequest = { showAddRail = false },
                title = {
                    Text(
                        text = "Add discovery rail",
                        color = SuzieeTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newRailTitle,
                            onValueChange = { newRailTitle = it },
                            label = { Text("Title") },
                            placeholder = { Text("Late Night J-Pop") },
                            singleLine = true,
                            colors = settingsTextFieldColors(),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = newRailQuery,
                            onValueChange = { newRailQuery = it },
                            label = { Text("Search query") },
                            placeholder = { Text("City Pop") },
                            singleLine = true,
                            colors = settingsTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                preferences.addHomeRail(newRailTitle, newRailQuery)
                            }
                            showAddRail = false
                        },
                        enabled = newRailTitle.isNotBlank() && newRailQuery.isNotBlank()
                    ) {
                        Text("Add", color = SuzieeTheme.colors.accentPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddRail = false }) {
                        Text("Cancel", color = SuzieeTheme.colors.textSecondary)
                    }
                }
            )
        }
    }
}

@Composable
private fun settingsTextFieldColors(): androidx.compose.material3.TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = SuzieeTheme.colors.textPrimary,
        unfocusedTextColor = SuzieeTheme.colors.textPrimary,
        focusedBorderColor = SuzieeTheme.colors.accentSecondary,
        unfocusedBorderColor = SuzieeTheme.colors.border,
        focusedLabelColor = SuzieeTheme.colors.accentSecondary,
        unfocusedLabelColor = SuzieeTheme.colors.textSecondary,
        cursorColor = SuzieeTheme.colors.accentPrimary
    )

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = SuzieeTheme.colors.accentSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    ThemedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        content()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatOffset(ms: Int): String {
    val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
    val abs = kotlin.math.abs(ms)
    return String.format(Locale.US, "%s%.1fs", sign, abs / 1000.0)
}
