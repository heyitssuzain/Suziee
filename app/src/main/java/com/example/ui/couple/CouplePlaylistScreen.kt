package com.example.ui.couple

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.couple.SharedTrack
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard

@Composable
fun CouplePlaylistScreen(
    controller: CoupleSpaceController,
    playerManager: MusicPlayerManager,
    state: CoupleUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val playback by playerManager.playbackState.collectAsState()
    val currentTrack = playback.currentTrack
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            CoupleHeader(
                title = "Shared queue",
                subtitle = "${state.sharedTracks.size} tracks · separate from your personal playlists",
                onBack = onBack
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CouplePrimaryButton(
                    text = if (currentTrack == null) "Nothing playing" else "Add current track",
                    onClick = { currentTrack?.let(controller::addToSharedPlaylist) },
                    enabled = currentTrack != null,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Add
                )
                CoupleSecondaryButton(
                    text = "Clear",
                    onClick = { showClearConfirm = true },
                    enabled = state.sharedTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Delete
                )
            }
            if (!state.authenticated) {
                Text(
                    text = "Offline edits are saved locally and merged deterministically the next time " +
                        "the encrypted session opens.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.sharedTracks.isEmpty()) {
                item {
                    CoupleEmptyState(
                        icon = Icons.Filled.QueueMusic,
                        title = "The shared queue is empty",
                        body = "Play something, then add it here. Your personal playlists are never modified."
                    )
                }
            }
            itemsIndexed(
                items = state.sharedTracks,
                key = { _, track -> track.trackId }
            ) { index, track ->
                SharedTrackRow(
                    track = track,
                    isFirst = index == 0,
                    isLast = index == state.sharedTracks.lastIndex,
                    onPlay = { controller.playSharedTrack(track.toTrack()) },
                    onMoveUp = { controller.moveSharedTrack(track.trackId, index - 1) },
                    onMoveDown = { controller.moveSharedTrack(track.trackId, index + 1) },
                    onRemove = { controller.removeFromSharedPlaylist(track.trackId) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        Text(
            text = "Every add, remove and reorder is an event with a logical clock. Concurrent edits " +
                "resolve by (lamport, device id), so both phones converge on the same queue.",
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear the shared queue?", color = colors.textPrimary) },
            text = {
                Text(
                    text = "This removes every shared track on both phones. Your personal playlists " +
                        "and downloads are untouched.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.clearSharedPlaylist()
                        showClearConfirm = false
                    }
                ) {
                    Text("Clear", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun SharedTrackRow(
    track: SharedTrack,
    isFirst: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = SuzieeTheme.colors
    ThemedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                if (track.artworkUrl.isNotBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist.ifBlank { "Unknown artist" },
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move up",
                    tint = if (isFirst) colors.textMuted else colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move down",
                    tint = if (isLast) colors.textMuted else colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove",
                    tint = colors.error,
                    modifier = Modifier.size(17.dp)
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = coupleAccentText(),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
