package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.zIndex
import com.example.domain.model.Track
import com.example.downloads.DownloadManager
import com.example.playback.MusicPlayerManager
import com.example.ui.components.AddToPlaylistDialog
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.VoiceSearchModal
import com.example.ui.downloads.DownloadsScreen
import com.example.ui.home.HomeScreen
import com.example.ui.importing.ImportPlaylistDialog
import com.example.ui.landing.LandingScreen
import com.example.ui.library.LibraryScreen
import com.example.ui.player.PlayerScreen
import com.example.ui.search.SearchScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.SuzieeThemeId
import com.example.ui.theme.TopSystemBarScrim
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
        // Draw behind both system bars from the very first frame. The scrims in the Compose
        // tree (not an opaque window colour) keep the icons legible on every theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        val app = application as SuzieeApplication
        playerManager = app.playerManager
        downloadManager = app.downloadManager
        voiceRecognizer = VoiceRecognizer(this)

        setContent {
            val themeKey by app.preferences.themeId.collectAsState(initial = "aurora_glass")
            val reducedMotion by app.preferences.isReducedMotion.collectAsState(initial = false)
            val themeId = remember(themeKey) { SuzieeThemeId.fromKey(themeKey) }

            SuzieeTheme(themeId = themeId) {
                CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                    SuzieeApp(
                        playerManager = playerManager,
                        downloadManager = downloadManager,
                        voiceRecognizer = voiceRecognizer
                    )
                }
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
    var showImportDialog by remember { mutableStateOf(false) }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SuzieeTheme.colors.bgPrimary)
        ) {
            // The settings content starts below the status bar, so the scrim stays visible only
            // in the inset strip behind the system icons and never dims the app bar.
            TopSystemBarScrim(modifier = Modifier.align(Alignment.TopCenter))
            SettingsScreen(
                preferences = app.preferences,
                repository = playerManager.repository,
                playerManager = playerManager,
                onBack = { showSettingsScreen = false },
                onReplayIntro = {
                    showSettingsScreen = false
                    showLanding = true
                },
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(SuzieeTheme.colors.bgPrimary),
            containerColor = SuzieeTheme.colors.bgPrimary,
            // The screens handle their own status-bar padding so the hero artwork can bleed
            // behind the clock; the Scaffold must not add a second, opaque inset layer.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(
                    modifier = Modifier
                        .background(SuzieeTheme.colors.bgSecondary)
                        .navigationBarsPadding()
                ) {
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
                        containerColor = SuzieeTheme.colors.bgSecondary,
                        contentColor = SuzieeTheme.colors.textPrimary,
                        tonalElevation = 8.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
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
                                        tint = if (selected) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textMuted
                                    )
                                },
                                label = {
                                    Text(
                                        text = destination.title,
                                        fontSize = 11.sp,
                                        color = if (selected) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textMuted
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = SuzieeTheme.colors.border
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
                            onTrackOptions = { trackForPlaylistDialog = it },
                            onOpenImportPlaylist = { showImportDialog = true },
                            onOpenLibrary = { currentDestination = MainDestination.LIBRARY },
                            onOpenDownloads = { currentDestination = MainDestination.DOWNLOADS }
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
                            onDownloadTrack = { track -> downloadManager.downloadTrack(track) },
                            onOpenImportPlaylist = { showImportDialog = true }
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
            }
        }

        // Scrim behind the status-bar icons, above the screens but below the full player.
        TopSystemBarScrim(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f),
            // The screens already start below the status bar; keep the scrim inside that strip
            // instead of washing out the top of the home header.
            extraHeight = 0.dp
        )

        // The full player lives at the root, not inside the Scaffold: it must cover the bottom
        // navigation and the system bars for the mini-player expansion to feel continuous.
        AnimatedVisibility(
            visible = showPlayerFullScreen,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                initialOffsetY = { it }
            ),
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) + slideOutVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                targetOffsetY = { it }
            ),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
        ) {
            PlayerScreen(
                playerManager = playerManager,
                onMinimize = { showPlayerFullScreen = false },
                onDownloadTrack = { track -> downloadManager.downloadTrack(track) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // External playlist importer (Spotify / Apple Music / plain track list)
    if (showImportDialog) {
        ImportPlaylistDialog(
            repository = playerManager.repository,
            onDismiss = { showImportDialog = false },
            onImported = { _ ->
                showImportDialog = false
                currentDestination = MainDestination.LIBRARY
            }
        )
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
