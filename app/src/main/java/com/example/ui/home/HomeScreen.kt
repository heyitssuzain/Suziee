package com.example.ui.home

import com.example.ui.theme.SectionHeader
import com.example.ui.theme.SuzieeTagChip
import com.example.ui.theme.SuzieeTheme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.preferences.HomeRailConfig
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.ThemedCard
import com.example.ui.theme.bouncyClickable
@Composable
fun HomeScreen(
    playerManager: MusicPlayerManager,
    onNavigateToSearch: (String?) -> Unit,
    onOpenVoiceSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTrackOptions: (Track) -> Unit,
    onOpenImportPlaylist: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onSmartDownloadTrack: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val reducedMotion = LocalReducedMotion.current
    val playbackState by playerManager.playbackState.collectAsState()
    val recentlyPlayed by playerManager.repository.getRecentlyPlayed(15).collectAsState(initial = emptyList())
    val playlists by playerManager.repository.getPlaylists().collectAsState(initial = emptyList())
    val downloadedTracks by playerManager.repository.getDownloadedTracks().collectAsState(initial = emptyList())
    val homeRails by playerManager.preferences.homeRails.collectAsState(initial = HomeRailConfig.defaults)
    val smartDownload by playerManager.preferences.isSmartDownloadEnabled.collectAsState(initial = false)
    val favorites by playerManager.repository.getFavorites().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var offlineBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(downloadedTracks) {
        offlineBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            downloadedTracks.sumOf { track ->
                val path = track.localFilePath
                if (path.isNullOrBlank()) 0L else java.io.File(path).length()
            }
        }
    }

    val popularSearchTags = listOf(
        "Anime Opening 2024",
        "Naruto Blue Bird",
        "Attack on Titan OST",
        "Yoasobi Idol",
        "Ado New Genesis",
        "Kenshi Yonezu Kick Back",
        "Lo-Fi Anime Chill",
        "Tokyo Ghoul Unravel",
        "Demon Slayer Gurenge"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeTheme.colors.bgPrimary)
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // 1. Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(SuzieeTheme.colors.accentPrimary, SuzieeTheme.colors.accentSecondary))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_suziee_icon),
                            contentDescription = "Suziee Logo",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SUZIEE",
                            color = SuzieeTheme.colors.highlight,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 3.sp
                            )
                        )
                        Text(
                            text = "Cybernetic Anime Audio",
                            color = SuzieeTheme.colors.accentSecondary,
                            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenVoiceSearch,
                        modifier = Modifier.testTag("home_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Search",
                            tint = SuzieeTheme.colors.accentSecondary
                        )
                    }
                    IconButton(
                        onClick = { onNavigateToSearch(null) },
                        modifier = Modifier.testTag("home_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = SuzieeTheme.colors.textPrimary
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = SuzieeTheme.colors.textSecondary
                        )
                    }
                }
            }
        }

        // 2. Hero: the cinematic Mikasa panel is exclusive to Aurora Glass. Clay and Minimal each
        // get their own hero so the three home pages read as three different experiences
        // (P3-8), instead of falling back to the generic trending showcase.
        item {
            when {
                SuzieeTheme.colors.isGlass -> AuroraHeroPanel(
                    playbackState = playbackState,
                    reducedMotion = reducedMotion,
                    onDiscover = { onNavigateToSearch("Anime Opening") }
                )

                SuzieeTheme.colors.isClay -> ClayHeroPanel(
                    playbackState = playbackState,
                    reducedMotion = reducedMotion,
                    onDiscover = { onNavigateToSearch("Anime Opening") }
                )

                else -> MinimalHeroPanel(
                    playbackState = playbackState,
                    reducedMotion = reducedMotion,
                    onDiscover = { onNavigateToSearch("Anime Opening") }
                )
            }
        }

        // 2a. Trending showcase: the anime rail is still the extra content air for the
        // non-glass themes, now *below* their own hero.
        item {
            if (!SuzieeTheme.colors.isGlass) {
                AnimeTrendingShowcase(
                    playerManager = playerManager,
                    onOpenFullSearch = onNavigateToSearch,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // 2b. Bento shortcuts: the four things a listener reaches for most.
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionHeader(
                    title = "Your Suziee",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BentoCard(
                        title = "Import",
                        subtitle = "Spotify / Apple",
                        icon = Icons.Default.CloudDownload,
                        accent = SuzieeTheme.colors.accentSecondary,
                        onClick = onOpenImportPlaylist,
                        modifier = Modifier.weight(1f)
                    )
                    BentoCard(
                        title = "Library",
                        subtitle = "${playlists.size} playlists",
                        icon = Icons.Default.LibraryMusic,
                        accent = SuzieeTheme.colors.accentPrimary,
                        onClick = onOpenLibrary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BentoCard(
                        title = "Downloads",
                        subtitle = "${downloadedTracks.size} offline",
                        icon = Icons.Default.Download,
                        accent = SuzieeTheme.colors.success,
                        onClick = onOpenDownloads,
                        modifier = Modifier.weight(1f)
                    )
                    BentoCard(
                        title = "Voice",
                        subtitle = "Find a song",
                        icon = Icons.Default.Mic,
                        accent = SuzieeTheme.colors.accentSecondary,
                        onClick = onOpenVoiceSearch,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Quick Popular Searches (Chips)
        item {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                SectionHeader(
                    title = "Popular Discovery",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    popularSearchTags.forEach { tag ->
                        SuzieeTagChip(
                            text = tag,
                            onClick = { onNavigateToSearch(tag) }
                        )
                    }
                }
            }
        }

        // 3b. Configurable discovery rails (P1-13). The list and order live in DataStore;
        // Settings owns the add/remove UI.
        items(homeRails, key = { it.title + "|" + it.query }) { rail ->
            DiscoveryRail(
                title = rail.title,
                subtitle = rail.subtitle,
                query = rail.query,
                playerManager = playerManager,
                onOpenFullSearch = onNavigateToSearch
            )
        }

        // 4. Continue Listening (P1-14): grouped by artist so a return visit reads as a
        // set of listening threads, not a flat list. The active track gets a progress ring.
        if (recentlyPlayed.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Continue Listening",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            val continueGroups = recentlyPlayed.take(8).groupBy { it.artist.ifBlank { "Unknown Artist" } }
            continueGroups.entries.take(3).forEach { entry ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = entry.key,
                            color = SuzieeTheme.colors.accentSecondary,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${entry.value.size} track${if (entry.value.size == 1) "" else "s"}",
                            color = SuzieeTheme.colors.textMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                items(entry.value) { track ->
                    val progress = if (playbackState.currentTrack?.id == track.id && playbackState.durationMs > 0) {
                        (playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = playbackState.currentTrack?.id == track.id,
                        isPlaying = playbackState.isPlaying,
                        progress = progress,
                        onClick = { playerManager.playTrack(track, recentlyPlayed) },
                        onFavoriteToggle = {
                            scope.launch {
                                playerManager.repository.toggleFavorite(track)
                            }
                        },
                        onAddToPlaylist = { onTrackOptions(track) },
                        onPlayNext = { playerManager.playNext(track) },
                        onAddToQueue = { playerManager.addToQueue(track) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        } else {
            // Empty state helper
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SuzieeTheme.colors.surfaceElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Your next favourite song is waiting.",
                            color = SuzieeTheme.colors.textPrimary,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = "Search online or tap a category above to start listening.",
                            color = SuzieeTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onNavigateToSearch("Top Anime Songs") },
                            colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
                        ) {
                            Text("Explore Music", color = SuzieeTheme.colors.bgPrimary)
                        }
                    }
                }
            }
        }

        // 5. Offline Downloads Quick Glance
        if (downloadedTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(
                    title = "Downloaded Music (${downloadedTracks.size})",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            items(downloadedTracks.take(4)) { track ->
                TrackRowItem(
                    track = track,
                    isCurrentTrack = playbackState.currentTrack?.id == track.id,
                    isPlaying = playbackState.isPlaying,
                    onClick = { playerManager.playTrack(track, downloadedTracks) },
                    onPlayNext = { playerManager.playNext(track) },
                    onAddToQueue = { playerManager.addToQueue(track) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }

        // 6. Offline Hub (P1-15): storage usage, one-tap offline playback and opt-in
        // smart download of saved favourites.
        item {
            OfflineHubCard(
                trackCount = downloadedTracks.size,
                bytes = offlineBytes,
                smartDownload = smartDownload,
                onToggleSmartDownload = { enabled ->
                    scope.launch {
                        playerManager.preferences.setSmartDownloadEnabled(enabled)
                        if (enabled) {
                            favorites
                                .filterNot { it.isDownloaded }
                                .take(10)
                                .forEach { favorite -> onSmartDownloadTrack?.invoke(favorite) }
                        }
                    }
                },
                onPlayAll = {
                    if (downloadedTracks.isNotEmpty()) {
                        playerManager.playTrack(downloadedTracks.first(), downloadedTracks)
                    } else {
                        onOpenDownloads()
                    }
                }
            )
        }
    }
}

@Composable
private fun OfflineHubCard(
    trackCount: Int,
    bytes: Long,
    smartDownload: Boolean,
    onToggleSmartDownload: (Boolean) -> Unit,
    onPlayAll: () -> Unit
) {
    val sizeText = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        SectionHeader(
            title = "Offline Hub",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
        ThemedCard(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "$trackCount offline track${if (trackCount == 1) "" else "s"}",
                            color = SuzieeTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$sizeText stored on this device",
                            color = SuzieeTheme.colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = onPlayAll,
                        colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
                    ) {
                        Text(
                            text = if (trackCount == 0) "Browse" else "Play all",
                            color = SuzieeTheme.colors.bgPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart Download",
                            color = SuzieeTheme.colors.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Keep your favourites available offline automatically",
                            color = SuzieeTheme.colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = smartDownload,
                        onCheckedChange = onToggleSmartDownload
                    )
                }
            }
        }
    }
}

@Composable
private fun BentoCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ThemedCard(
        modifier = modifier
            .height(112.dp)
            .bouncyClickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(15.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    color = SuzieeTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = subtitle,
                    color = SuzieeTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Load state for a discovery rail. Keeps loading / empty / error states explicit. */
private sealed interface DiscoveryState {
    data object Loading : DiscoveryState
    data class Ready(val tracks: List<Track>) : DiscoveryState
    data class Failure(val message: String) : DiscoveryState
}

/**
 * Process-lifetime cache of rail results. Home is recomposed every time the user returns to
 * it; without this each visit would re-run two network searches. "Retry" bypasses it.
 */
private val discoveryRailCache = object : android.util.LruCache<String, List<Track>>(20) {}

/**
 * A horizontally scrolling shelf of real, playable songs.
 *
 * The data comes from the same `searchOnline` backend the Search screen uses (no mock
 * data, no placeholders), and the section renders proper loading, empty and error states.
 * Picking a card plays it with the whole shelf as the queue, so Next/Previous work.
 */
@Composable
private fun DiscoveryRail(
    title: String,
    subtitle: String,
    query: String,
    playerManager: MusicPlayerManager,
    onOpenFullSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val playbackState by playerManager.playbackState.collectAsState()
    var state by remember { mutableStateOf<DiscoveryState>(DiscoveryState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    val shelfState = rememberLazyListState()

    LaunchedEffect(query, reloadKey) {
        // A plain revisit reuses the cached rail; an explicit Retry (reloadKey > 0) refetches.
        val cached = if (reloadKey == 0) discoveryRailCache[query] else null
        if (!cached.isNullOrEmpty()) {
            state = DiscoveryState.Ready(cached)
            return@LaunchedEffect
        }

        state = DiscoveryState.Loading
        val result = try {
            playerManager.repository.searchOnline(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        state = when {
            result.isFailure -> DiscoveryState.Failure("Couldn't load this section.")
            else -> {
                val tracks = result.getOrNull().orEmpty()
                if (tracks.isEmpty()) {
                    DiscoveryState.Failure("No songs available right now.")
                } else {
                    discoveryRailCache.put(query, tracks)
                    DiscoveryState.Ready(tracks)
                }
            }
        }
    }

    Column(modifier = modifier.padding(vertical = 10.dp)) {
        SectionHeader(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.padding(horizontal = 20.dp),
            trailing = {
                if (state is DiscoveryState.Ready) {
                    Text(
                        text = "See all",
                        color = SuzieeTheme.colors.accentSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenFullSearch(query) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (val current = state) {
            is DiscoveryState.Loading -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(4) { DiscoveryCardSkeleton() }
                }
            }

            is DiscoveryState.Failure -> {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    color = SuzieeTheme.colors.surfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = SuzieeTheme.colors.accentSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = current.message,
                            color = SuzieeTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        )
                        TextButton(onClick = { reloadKey++ }) {
                            Text("Retry", color = SuzieeTheme.colors.accentPrimary)
                        }
                    }
                }
            }

            is DiscoveryState.Ready -> {
                LazyRow(
                    state = shelfState,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = shelfState),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(current.tracks, key = { it.id }) { track ->
                        DiscoveryCard(
                            track = track,
                            isCurrent = playbackState.currentTrack?.id == track.id,
                            isPlaying = playbackState.isPlaying,
                            onClick = { playerManager.playTrack(track, current.tracks) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("discovery_card_${track.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(SuzieeTheme.colors.surfaceElevated)
        ) {
            if (track.artworkUrl.isNotBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SuzieeTheme.colors.textMuted,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(SuzieeTheme.colors.accentPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = SuzieeTheme.colors.bgPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            color = if (isCurrent) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = SuzieeTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiscoveryCardSkeleton() {
    Column(modifier = Modifier.width(148.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(SuzieeTheme.colors.surfaceElevated)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(10.dp)
                .clip(CircleShape)
                .background(SuzieeTheme.colors.surfaceElevated)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(9.dp)
                .clip(CircleShape)
                .background(SuzieeTheme.colors.surfaceElevated)
        )
    }
}
