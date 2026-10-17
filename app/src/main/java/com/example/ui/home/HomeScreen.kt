package com.example.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.ui.components.TrackRowItem
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeHighlight
import com.example.ui.theme.SuzieeSurfaceCard
import com.example.ui.theme.SuzieeSurfaceElevated
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary

@Composable
fun HomeScreen(
    playerManager: MusicPlayerManager,
    onNavigateToSearch: (String?) -> Unit,
    onOpenVoiceSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTrackOptions: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val playbackState by playerManager.playbackState.collectAsState()
    val recentlyPlayed by playerManager.repository.getRecentlyPlayed(15).collectAsState(initial = emptyList())
    val playlists by playerManager.repository.getPlaylists().collectAsState(initial = emptyList())
    val downloadedTracks by playerManager.repository.getDownloadedTracks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

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
            .background(SuzieeBgPrimary)
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
                                Brush.linearGradient(listOf(SuzieeAccentPrimary, SuzieeAccentSecondary))
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
                            color = SuzieeHighlight,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 3.sp
                            )
                        )
                        Text(
                            text = "Cybernetic Anime Audio",
                            color = SuzieeAccentSecondary,
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
                            tint = SuzieeAccentSecondary
                        )
                    }
                    IconButton(
                        onClick = { onNavigateToSearch(null) },
                        modifier = Modifier.testTag("home_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = SuzieeTextPrimary
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = SuzieeTextSecondary
                        )
                    }
                }
            }
        }

        // 2. Cinematic Hero Panel
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .testTag("home_hero_panel"),
                color = SuzieeSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder)
            ) {
                // A taller frame plus an upward-biased crop keeps the character's face
                // fully in view (the artwork is portrait; a centre crop used to slice it).
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.img_suziee_hero),
                        contentDescription = "Hero Artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment(0.5f, 0.24f)
                    )

                    // Cinematic colour grade: cool wash from the top, deep fade to the base.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        SuzieeBgPrimary.copy(alpha = 0.55f),
                                        Color.Transparent,
                                        SuzieeBgPrimary.copy(alpha = 0.55f),
                                        SuzieeBgPrimary
                                    )
                                )
                            )
                    )

                    // Neon bloom from the lower-left corner.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        SuzieeAccentPrimary.copy(alpha = 0.28f),
                                        Color.Transparent
                                    ),
                                    center = androidx.compose.ui.geometry.Offset(0f, 1400f),
                                    radius = 1100f
                                )
                            )
                    )

                    // Live badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.35f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (playbackState.isPlaying) SuzieeAccentSecondary else SuzieeTextMuted)
                            )
                            Text(
                                text = if (playbackState.isPlaying) "NOW PLAYING" else "SUZIEE ORIGINAL",
                                color = SuzieeHighlight,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "IMMERSIVE ANIME SOUND",
                            color = SuzieeAccentSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "Explore Trending Anime & J-Music",
                            color = SuzieeTextPrimary,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { onNavigateToSearch("Anime Opening") },
                                colors = ButtonDefaults.buttonColors(containerColor = SuzieeAccentPrimary),
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.testTag("hero_explore_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = SuzieeBgPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Discover Hits",
                                    color = SuzieeBgPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            WaveformVisualizer(
                                amplitudes = playbackState.visualizerAmplitudes,
                                isPlaying = playbackState.isPlaying,
                                height = 30.dp,
                                modifier = Modifier.width(110.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. Quick Popular Searches (Chips)
        item {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "POPULAR DISCOVERY",
                    color = SuzieeTextSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    popularSearchTags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SuzieeSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder),
                            modifier = Modifier.clickable { onNavigateToSearch(tag) }
                        ) {
                            Text(
                                text = tag,
                                color = SuzieeTextPrimary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3b. Trending Now - real, playable tracks pulled from the search backend
        item {
            DiscoveryRail(
                title = "Trending Now",
                subtitle = "What people are playing today",
                query = "Anime Opening",
                playerManager = playerManager,
                onOpenFullSearch = onNavigateToSearch
            )
        }

        // 3c. Popular / recommended picks
        item {
            DiscoveryRail(
                title = "Popular This Week",
                subtitle = "Hand-picked J-Music favourites",
                query = "J-Pop Hits",
                playerManager = playerManager,
                onOpenFullSearch = onNavigateToSearch
            )
        }

        // 4. Recently Played / Continue Listening
        if (recentlyPlayed.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recently Played",
                        color = SuzieeTextPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            items(recentlyPlayed.take(5)) { track ->
                TrackRowItem(
                    track = track,
                    isCurrentTrack = playbackState.currentTrack?.id == track.id,
                    isPlaying = playbackState.isPlaying,
                    onClick = { playerManager.playTrack(track, recentlyPlayed) },
                    onFavoriteToggle = {
                        scope.launch {
                            playerManager.repository.toggleFavorite(track)
                        }
                    },
                    onAddToPlaylist = { onTrackOptions(track) },
                    onAddToQueue = { playerManager.addToQueue(track) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        } else {
            // Empty state helper
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SuzieeSurfaceElevated,
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
                            color = SuzieeTextPrimary,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = "Search online or tap a category above to start listening.",
                            color = SuzieeTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onNavigateToSearch("Top Anime Songs") },
                            colors = ButtonDefaults.buttonColors(containerColor = SuzieeAccentPrimary)
                        ) {
                            Text("Explore Music", color = SuzieeBgPrimary)
                        }
                    }
                }
            }
        }

        // 5. Offline Downloads Quick Glance
        if (downloadedTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Downloaded Music (${downloadedTracks.size})",
                    color = SuzieeTextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            items(downloadedTracks.take(4)) { track ->
                TrackRowItem(
                    track = track,
                    isCurrentTrack = playbackState.currentTrack?.id == track.id,
                    isPlaying = playbackState.isPlaying,
                    onClick = { playerManager.playTrack(track, downloadedTracks) },
                    onAddToQueue = { playerManager.addToQueue(track) },
                    modifier = Modifier.padding(horizontal = 12.dp)
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
private val discoveryRailCache = mutableMapOf<String, List<Track>>()

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
                    discoveryRailCache[query] = tracks
                    DiscoveryState.Ready(tracks)
                }
            }
        }
    }

    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = SuzieeTextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(text = subtitle, color = SuzieeTextMuted, fontSize = 12.sp)
            }
            if (state is DiscoveryState.Ready) {
                Text(
                    text = "See all",
                    color = SuzieeAccentSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenFullSearch(query) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

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
                    color = SuzieeSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder)
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
                            tint = SuzieeAccentSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = current.message,
                            color = SuzieeTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        )
                        TextButton(onClick = { reloadKey++ }) {
                            Text("Retry", color = SuzieeAccentPrimary)
                        }
                    }
                }
            }

            is DiscoveryState.Ready -> {
                LazyRow(
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
                .background(SuzieeSurfaceElevated)
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
                    tint = SuzieeTextMuted,
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
                    .background(SuzieeAccentPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = SuzieeBgPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            color = if (isCurrent) SuzieeAccentSecondary else SuzieeTextPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = SuzieeTextSecondary,
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
                .background(SuzieeSurfaceElevated)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(10.dp)
                .clip(CircleShape)
                .background(SuzieeSurfaceElevated)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(9.dp)
                .clip(CircleShape)
                .background(SuzieeSurfaceElevated)
        )
    }
}
