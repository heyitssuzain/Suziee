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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
                    .clip(RoundedCornerShape(24.dp))
                    .testTag("home_hero_panel"),
                color = SuzieeSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder)
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.img_suziee_hero),
                        contentDescription = "Hero Artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        SuzieeBgPrimary.copy(alpha = 0.5f),
                                        SuzieeBgPrimary.copy(alpha = 0.95f)
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(18.dp)
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
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onNavigateToSearch("Anime Opening 2024") },
                                colors = ButtonDefaults.buttonColors(containerColor = SuzieeAccentPrimary),
                                shape = RoundedCornerShape(18.dp),
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
                                height = 28.dp,
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
