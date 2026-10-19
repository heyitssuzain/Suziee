package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.Track
import com.example.downloads.DownloadManager
import com.example.playback.ExtractionService
import com.example.playback.MusicPlayerManager
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.VoiceSearchModal
import com.example.ui.downloads.DownloadsScreen
import com.example.ui.home.HomeScreen
import com.example.ui.landing.LandingScreen
import com.example.ui.library.LibraryScreen
import com.example.ui.player.PlayerScreen
import com.example.ui.search.SearchScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeBgSecondary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTheme
import com.example.voice.VoiceCommand
import com.example.voice.VoiceRecognizer
import kotlinx.coroutines.launch

enum class MainDestination(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    SEARCH("Search", Icons.Filled.Search, Icons.Outlined.Search, "nav_search"),
    LIBRARY("Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, "nav_library"),
    DOWNLOADS("Downloads", Icons.Filled.Download, Icons.Outlined.Download, "nav_downloads")
}

class MainActivity : ComponentActivity() {

    private lateinit var playerManager: MusicPlayerManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var voiceRecognizer: VoiceRecognizer

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS request result: granted=$granted")
            logNotificationSurfaceState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SuzieeApplication
        playerManager = app.playerManager
        downloadManager = app.downloadManager
        voiceRecognizer = VoiceRecognizer(this)

        setContent {
            SuzieeTheme {
                SuzieeApp(
                    playerManager = playerManager,
                    downloadManager = downloadManager,
                    voiceRecognizer = voiceRecognizer
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Requested here rather than in onCreate on purpose: a permission request launched
        // while the activity is still being created can be dropped by some OEM builds,
        // which silently left the app without POST_NOTIFICATIONS - and therefore without
        // the media notification / lock-screen controls. This runs once the activity is
        // actually started, and the previous failure path (a swallowed exception) is logged.
        requestNotificationPermissionIfNeeded()
        // Pre-warm the headless extraction engine while the user is browsing, so tapping a
        // song does not pay for Chromium instantiation on the critical path. Optional: the
        // extractor works (cold) even if this service cannot start.
        ExtractionService.ensureStarted(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            logNotificationSurfaceState()
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            logNotificationSurfaceState()
            return
        }
        runCatching {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure {
            Log.w(TAG, "Could not request POST_NOTIFICATIONS: ${it.message}")
        }
    }

    /**
     * Diagnostic line for the media-notification surfaces: post Android 13 a denied
     * permission (or a disabled channel) hides the notification even though the
     * MediaSession and the foreground service are both healthy.
     */
    private fun logNotificationSurfaceState() {
        val enabled = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        Log.i(TAG, "Notification surfaces: notificationsEnabled=$enabled")
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecognizer.stopListening()
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun SuzieeApp(
    playerManager: MusicPlayerManager,
    downloadManager: DownloadManager,
    voiceRecognizer: VoiceRecognizer
) {
    val app = SuzieeApplication.instance
    val isFirstLaunchCompleted by app.preferences.isFirstLaunchCompleted.collectAsState(initial = false)
    val isReducedMotion by app.preferences.isReducedMotion.collectAsState(initial = false)

    var showLanding by remember(isFirstLaunchCompleted) { mutableStateOf(!isFirstLaunchCompleted) }
    var currentDestination by remember { mutableStateOf(MainDestination.HOME) }
    var searchQueryOverride by remember { mutableStateOf<String?>(null) }
    var showPlayerFullScreen by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showVoiceModal by remember { mutableStateOf(false) }
    var trackForPlaylistDialog by remember { mutableStateOf<Track?>(null) }

    val playbackState by playerManager.playbackState.collectAsState()
    val voiceState by voiceRecognizer.voiceState.collectAsState()
    val playlists by playerManager.repository.getPlaylists().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    if (showLanding) {
        LandingScreen(
            onEnterApp = {
                showLanding = false
                scope.launch {
                    app.preferences.setFirstLaunchCompleted(true)
                }
            },
            reducedMotion = isReducedMotion
        )
        return
    }

    if (showSettingsScreen) {
        SettingsScreen(
            preferences = app.preferences,
            repository = playerManager.repository,
            onBack = { showSettingsScreen = false },
            onReplayIntro = {
                showSettingsScreen = false
                showLanding = true
            },
            modifier = Modifier.statusBarsPadding()
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(SuzieeBgPrimary),
        containerColor = SuzieeBgPrimary,
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                // Mini Player Bar above Navigation
                if (playbackState.currentTrack != null && !showPlayerFullScreen) {
                    MiniPlayerBar(
                        state = playbackState,
                        onExpandPlayer = { showPlayerFullScreen = true },
                        onTogglePlayPause = { playerManager.togglePlayPause() },
                        onSkipNext = { playerManager.playNext() },
                        onSkipPrevious = { playerManager.playPrevious() },
                        onRetry = { playerManager.retryCurrent() }
                    )
                }

                // Bottom Navigation
                NavigationBar(
                    containerColor = SuzieeBgSecondary,
                    contentColor = SuzieeTextPrimary,
                    tonalElevation = 8.dp,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    MainDestination.entries.forEach { destination ->
                        val selected = currentDestination == destination
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (destination == MainDestination.SEARCH && currentDestination == MainDestination.SEARCH) {
                                    // already here
                                }
                                currentDestination = destination
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.title,
                                    tint = if (selected) SuzieeAccentSecondary else SuzieeTextMuted
                                )
                            },
                            label = {
                                Text(
                                    text = destination.title,
                                    fontSize = 11.sp,
                                    color = if (selected) SuzieeAccentSecondary else SuzieeTextMuted
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = SuzieeBorder
                            ),
                            modifier = Modifier.testTag(destination.tag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            when (currentDestination) {
                MainDestination.HOME -> {
                    HomeScreen(
                        playerManager = playerManager,
                        onNavigateToSearch = { query ->
                            searchQueryOverride = query
                            currentDestination = MainDestination.SEARCH
                        },
                        onOpenVoiceSearch = { showVoiceModal = true },
                        onNavigateToSettings = { showSettingsScreen = true },
                        onTrackOptions = { trackForPlaylistDialog = it }
                    )
                }
                MainDestination.SEARCH -> {
                    SearchScreen(
                        playerManager = playerManager,
                        initialQuery = searchQueryOverride,
                        onOpenVoiceSearch = { showVoiceModal = true },
                        onTrackOptions = { trackForPlaylistDialog = it },
                        onDownloadTrack = { track -> downloadManager.downloadTrack(track) }
                    )
                }
                MainDestination.LIBRARY -> {
                    LibraryScreen(
                        playerManager = playerManager,
                        onTrackOptions = { trackForPlaylistDialog = it },
                        onDownloadTrack = { track -> downloadManager.downloadTrack(track) }
                    )
                }
                MainDestination.DOWNLOADS -> {
                    DownloadsScreen(
                        playerManager = playerManager,
                        downloadManager = downloadManager,
                        onNavigateToSearch = { currentDestination = MainDestination.SEARCH }
                    )
                }
            }

            // Full Player Screen as animated overlay
            AnimatedVisibility(
                visible = showPlayerFullScreen,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                PlayerScreen(
                    playerManager = playerManager,
                    onMinimize = { showPlayerFullScreen = false },
                    onDownloadTrack = { track -> downloadManager.downloadTrack(track) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Voice Search Modal
    if (showVoiceModal) {
        VoiceSearchModal(
            voiceRecognizer = voiceRecognizer,
            voiceState = voiceState,
            onDismiss = { showVoiceModal = false },
            onCommandParsed = { command, text ->
                showVoiceModal = false
                when (command) {
                    is VoiceCommand.PlaySearch -> {
                        searchQueryOverride = command.query
                        currentDestination = MainDestination.SEARCH
                        scope.launch {
                            val res = playerManager.repository.searchOnline(command.query)
                            val first = res.getOrNull()?.firstOrNull()
                            if (first != null) {
                                playerManager.playTrack(first, res.getOrNull().orEmpty())
                            }
                        }
                    }
                    is VoiceCommand.SearchOnly -> {
                        searchQueryOverride = command.query
                        currentDestination = MainDestination.SEARCH
                    }
                    VoiceCommand.Pause -> playerManager.exoPlayer.pause()
                    VoiceCommand.Resume -> playerManager.exoPlayer.play()
                    VoiceCommand.Next -> playerManager.playNext()
                    VoiceCommand.Previous -> playerManager.playPrevious()
                    VoiceCommand.Shuffle -> playerManager.toggleShuffle()
                    VoiceCommand.Repeat -> playerManager.toggleRepeat()
                    VoiceCommand.OpenLyrics -> {
                        if (playbackState.currentTrack != null) {
                            showPlayerFullScreen = true
                        }
                    }
                    VoiceCommand.OpenDownloads -> currentDestination = MainDestination.DOWNLOADS
                    VoiceCommand.GoBack -> {
                        if (showPlayerFullScreen) showPlayerFullScreen = false
                        else if (currentDestination != MainDestination.HOME) currentDestination = MainDestination.HOME
                    }
                }
            }
        )
    }

    // Add to Playlist Dialog
    if (trackForPlaylistDialog != null) {
        val targetTrack = trackForPlaylistDialog!!
        AddToPlaylistDialog(
            track = targetTrack,
            playlists = playlists,
            onDismiss = { trackForPlaylistDialog = null },
            onPlaylistSelected = { playlistId ->
                scope.launch {
                    playerManager.repository.addTrackToPlaylist(playlistId, targetTrack)
                    trackForPlaylistDialog = null
                }
            },
            onCreateNewPlaylist = { name ->
                scope.launch {
                    val id = playerManager.repository.createPlaylist(name)
                    playerManager.repository.addTrackToPlaylist(id, targetTrack)
                    trackForPlaylistDialog = null
                }
            }
        )
    }
}
