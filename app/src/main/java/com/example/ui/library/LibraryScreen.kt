package com.example.ui.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.ui.components.TrackRowItem
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    playerManager: MusicPlayerManager,
    onTrackOptions: (Track) -> Unit,
    onDownloadTrack: (Track) -> Unit,
    onOpenImportPlaylist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Favorites", "History")

    val playbackState by playerManager.playbackState.collectAsState()
    val playlists by playerManager.repository.getPlaylists().collectAsState(initial = emptyList())
    val favorites by playerManager.repository.getFavorites().collectAsState(initial = emptyList())
    val history by playerManager.repository.getRecentlyPlayed(40).collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var selectedPlaylistForDetail by remember { mutableStateOf<Playlist?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeTheme.colors.bgPrimary)
            .testTag("library_screen")
    ) {
        // Library Title & New Playlist button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Library",
                color = SuzieeTheme.colors.textPrimary,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )

            if (selectedPlaylistForDetail == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenImportPlaylist,
                        modifier = Modifier.testTag("library_import_playlist_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlaylistAdd,
                            contentDescription = "Import playlist",
                            tint = SuzieeTheme.colors.accentPrimary
                        )
                    }
                    if (selectedTab == 0) {
                        IconButton(
                            onClick = { showCreatePlaylistDialog = true },
                            modifier = Modifier.testTag("new_playlist_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create Playlist",
                                tint = SuzieeTheme.colors.accentSecondary
                            )
                        }
                    }
                }
            }
        }

        // Tab Row (only when not in playlist details)
        if (selectedPlaylistForDetail == null) {
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = SuzieeTheme.colors.bgPrimary,
                contentColor = SuzieeTheme.colors.accentSecondary,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTab),
                        color = SuzieeTheme.colors.accentSecondary
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textSecondary,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        // Body Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            if (selectedPlaylistForDetail != null) {
                val pl = selectedPlaylistForDetail!!
                val playlistTracks by playerManager.repository.getTracksForPlaylist(pl.id).collectAsState(initial = emptyList())

                PlaylistDetailView(
                    playlist = pl,
                    tracks = playlistTracks,
                    currentTrackId = playbackState.currentTrack?.id,
                    isPlaying = playbackState.isPlaying,
                    onBack = { selectedPlaylistForDetail = null },
                    onPlayAll = {
                        if (playlistTracks.isNotEmpty()) {
                            playerManager.playTrack(playlistTracks.first(), playlistTracks)
                        }
                    },
                    onShuffle = {
                        if (playlistTracks.isNotEmpty()) {
                            if (!playbackState.isShuffle) playerManager.toggleShuffle()
                            val shuffled = playlistTracks.shuffled()
                            playerManager.playTrack(shuffled.first(), shuffled)
                        }
                    },
                    onTrackClick = { t -> playerManager.playTrack(t, playlistTracks) },
                    onPlayNext = { t -> playerManager.playNext(t) },
                    onAddToQueue = { t -> playerManager.addToQueue(t) },
                    onRemoveTrack = { t ->
                        scope.launch {
                            playerManager.repository.removeTrackFromPlaylist(pl.id, t.id)
                        }
                    },
                    onDeletePlaylist = {
                        scope.launch {
                            playerManager.repository.deletePlaylist(pl.id)
                            selectedPlaylistForDetail = null
                        }
                    },
                    onTrackOptions = onTrackOptions
                )
            } else {
                when (selectedTab) {
                    0 -> {
                        // Playlists Tab
                        if (playlists.isEmpty()) {
                            EmptyLibraryState(
                                message = "No playlists created yet.",
                                buttonText = "Create Playlist",
                                onAction = { showCreatePlaylistDialog = true }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                items(playlists) { playlist ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SuzieeTheme.colors.surfaceElevated)
                                            .clickable { selectedPlaylistForDetail = playlist }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(SuzieeTheme.colors.border),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlaylistPlay,
                                                contentDescription = null,
                                                tint = SuzieeTheme.colors.accentPrimary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                                            Text(
                                                text = playlist.name,
                                                color = SuzieeTheme.colors.textPrimary,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Text(
                                                text = "Playlist",
                                                color = SuzieeTheme.colors.textSecondary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Favorites Tab
                        if (favorites.isEmpty()) {
                            EmptyLibraryState(
                                message = "No favorite songs yet.",
                                buttonText = "Discover Songs",
                                onAction = {}
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { playerManager.playTrack(favorites.first(), favorites) },
                                            colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SuzieeTheme.colors.bgPrimary)
                                            Text("Play All (${favorites.size})", color = SuzieeTheme.colors.bgPrimary)
                                        }
                                    }
                                }

                                items(favorites) { track ->
                                    TrackRowItem(
                                        track = track,
                                        isCurrentTrack = playbackState.currentTrack?.id == track.id,
                                        isPlaying = playbackState.isPlaying,
                                        onClick = { playerManager.playTrack(track, favorites) },
                                        onFavoriteToggle = {
                                            scope.launch { playerManager.repository.toggleFavorite(track) }
                                        },
                                        onAddToPlaylist = { onTrackOptions(track) },
                                        onPlayNext = { playerManager.playNext(track) },
                                        onAddToQueue = { playerManager.addToQueue(track) },
                                        onDownload = { onDownloadTrack(track) }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        // History Tab
                        if (history.isEmpty()) {
                            EmptyLibraryState(
                                message = "No playback history yet.",
                                buttonText = "Start Listening",
                                onAction = {}
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Recent Tracks",
                                            color = SuzieeTheme.colors.textSecondary,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        TextButton(onClick = { scope.launch { playerManager.repository.clearHistory() } }) {
                                            Text("Clear History", color = SuzieeTheme.colors.textMuted)
                                        }
                                    }
                                }

                                items(history) { track ->
                                    TrackRowItem(
                                        track = track,
                                        isCurrentTrack = playbackState.currentTrack?.id == track.id,
                                        isPlaying = playbackState.isPlaying,
                                        onClick = { playerManager.playTrack(track, history) },
                                        onAddToPlaylist = { onTrackOptions(track) },
                                        onPlayNext = { playerManager.playNext(track) },
                                        onAddToQueue = { playerManager.addToQueue(track) },
                                        onDownload = { onDownloadTrack(track) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        var name by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showCreatePlaylistDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SuzieeTheme.colors.surfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("New Playlist", color = SuzieeTheme.colors.textPrimary, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Playlist name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SuzieeTheme.colors.accentSecondary,
                            unfocusedBorderColor = SuzieeTheme.colors.border,
                            focusedTextColor = SuzieeTheme.colors.textPrimary,
                            unfocusedTextColor = SuzieeTheme.colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCreatePlaylistDialog = false }) {
                            Text("Cancel", color = SuzieeTheme.colors.textSecondary)
                        }
                        Button(
                            onClick = {
                                if (name.isNotBlank()) {
                                    scope.launch {
                                        playerManager.repository.createPlaylist(name.trim())
                                        showCreatePlaylistDialog = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
                        ) {
                            Text("Create", color = SuzieeTheme.colors.bgPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailView(
    playlist: Playlist,
    tracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onRemoveTrack: (Track) -> Unit,
    onDeletePlaylist: () -> Unit,
    onTrackOptions: (Track) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← All Playlists", color = SuzieeTheme.colors.accentSecondary)
                }
                IconButton(onClick = onDeletePlaylist) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Playlist", tint = SuzieeTheme.colors.textMuted)
                }
            }

            Text(
                text = playlist.name,
                color = SuzieeTheme.colors.textPrimary,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "${tracks.size} tracks",
                color = SuzieeTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (tracks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPlayAll,
                        colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SuzieeTheme.colors.bgPrimary)
                        Text("Play", color = SuzieeTheme.colors.bgPrimary, modifier = Modifier.padding(start = 4.dp))
                    }
                    Button(
                        onClick = onShuffle,
                        colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.surfaceElevated)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = SuzieeTheme.colors.textPrimary)
                        Text("Shuffle", color = SuzieeTheme.colors.textPrimary, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    text = "No tracks in this playlist yet. Search online and tap 'Add to Playlist'!",
                    color = SuzieeTheme.colors.textMuted,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        } else {
            items(tracks) { track ->
                TrackRowItem(
                    track = track,
                    isCurrentTrack = currentTrackId == track.id,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(track) },
                    onAddToPlaylist = { onTrackOptions(track) },
                    onPlayNext = { onPlayNext(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onRemoveFromPlaylist = { onRemoveTrack(track) }
                )
            }
        }
    }
}

@Composable
fun EmptyLibraryState(
    message: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            color = SuzieeTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodyLarge
        )
        if (buttonText.isNotBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
            ) {
                Text(buttonText, color = SuzieeTheme.colors.bgPrimary)
            }
        }
    }
}
