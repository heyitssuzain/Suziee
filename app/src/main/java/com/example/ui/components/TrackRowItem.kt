package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.model.Track
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeSurfaceElevated
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary

@Composable
fun TrackRowItem(
    track: Track,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    /**
     * Shown as "Remove from Playlist" in the overflow menu. Only supplied by playlist
     * detail views, so removing a song here never touches the shared library entry.
     */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrentTrack) SuzieeSurfaceElevated else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("track_row_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with indicator
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SuzieeSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (track.artworkUrl.isNotBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = "Artwork for ${track.title}",
                    modifier = Modifier.size(52.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SuzieeTextMuted,
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(SuzieeAccentPrimary.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Playing",
                        tint = SuzieeAccentSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.title,
                color = if (isCurrentTrack) SuzieeAccentSecondary else SuzieeTextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.artist,
                    color = SuzieeTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.durationSeconds > 0) {
                    Text(
                        text = " • ${track.formattedDuration}",
                        color = SuzieeTextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Favorite Toggle
        if (onFavoriteToggle != null) {
            IconButton(
                onClick = onFavoriteToggle,
                modifier = Modifier.testTag("fav_button_${track.id}")
            ) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (track.isFavorite) SuzieeAccentPrimary else SuzieeTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // More Menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.testTag("more_button_${track.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Track options",
                    tint = SuzieeTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (onAddToQueue != null) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onAddToQueue()
                        }
                    )
                }
                if (onAddToPlaylist != null) {
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onAddToPlaylist()
                        }
                    )
                }
                if (onDownload != null) {
                    DropdownMenuItem(
                        text = { Text(if (track.isDownloaded) "Downloaded" else "Download Offline") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onDownload()
                        }
                    )
                }
                if (onRemoveFromPlaylist != null) {
                    DropdownMenuItem(
                        text = { Text("Remove from Playlist") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRemoveFromPlaylist()
                        },
                        modifier = Modifier.testTag("remove_from_playlist_${track.id}")
                    )
                }
            }
        }
    }
}
