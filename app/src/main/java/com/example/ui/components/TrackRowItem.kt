package com.example.ui.components

import com.example.ui.theme.SuzieeTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.SuzieeApplication
import com.example.domain.model.Track
@Composable
fun TrackRowItem(
    track: Track,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    /**
     * Shown as "Remove from Playlist" in the overflow menu. Only supplied by playlist
     * detail views, so removing a song here never touches the shared library entry.
     */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** 0f..1f renders a progress ring around the artwork (used by Continue Listening). */
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val downloadStatus = rememberDownloadUiStatus(track.id)
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val artworkRequest = remember(track.artworkUrl, context) {
        ImageRequest.Builder(context)
            .data(track.artworkUrl)
            .crossfade(220)
            .build()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrentTrack) SuzieeTheme.colors.surfaceElevated else androidx.compose.ui.graphics.Color.Transparent)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("track_row_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with indicator
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SuzieeTheme.colors.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (track.artworkUrl.isNotBlank()) {
                AsyncImage(
                    model = artworkRequest,
                    contentDescription = "Artwork for ${track.title}",
                    modifier = Modifier.size(52.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SuzieeTheme.colors.textMuted,
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(SuzieeTheme.colors.accentPrimary.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Playing",
                        tint = SuzieeTheme.colors.accentSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (progress != null) {
                Canvas(modifier = Modifier.matchParentSize().padding(2.dp)) {
                    drawArc(
                        color = SuzieeTheme.colors.accentPrimary,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx())
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
                color = if (isCurrentTrack) SuzieeTheme.colors.accentSecondary else SuzieeTheme.colors.textPrimary,
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
                    color = SuzieeTheme.colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.durationSeconds > 0) {
                    Text(
                        text = " • ${track.formattedDuration}",
                        color = SuzieeTheme.colors.textMuted,
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
                    tint = if (track.isFavorite) SuzieeTheme.colors.accentPrimary else SuzieeTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Live download feedback - appears the instant a download starts, wherever this
        // track is listed (Home, Library, Search), not only on the Downloads screen.
        if (downloadStatus.active) {
            if (downloadStatus.percent < 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                CircularProgressIndicator(
                    progress = { downloadStatus.percent / 100f },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "${downloadStatus.percent}%",
                    color = SuzieeTheme.colors.accentSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 2.dp)
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
                    tint = SuzieeTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (onPlayNext != null) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onPlayNext()
                        }
                    )
                }
                if (onAddToQueue != null) {
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
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
                if (onDownload != null && !downloadStatus.active) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    downloadStatus.active && downloadStatus.percent < 0 -> "Starting download…"
                                    downloadStatus.active -> "Downloading… ${downloadStatus.percent}%"
                                    track.isDownloaded -> "Downloaded"
                                    else -> "Download Offline"
                                }
                            )
                        },
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

/** Where a track's download stands right now, for inline UI feedback. */
data class DownloadUiStatus(
    val active: Boolean = false,

    /** -1 means "started, size unknown" (indeterminate); 0..99 is a real percentage. */
    val percent: Int = -1
)

/**
 * Observes the shared downloads table for one track id so any row or button showing a
 * download action can reflect live progress immediately - not just the Downloads screen.
 */
@Composable
fun rememberDownloadUiStatus(trackId: String): DownloadUiStatus {
    val downloads by remember {
        SuzieeApplication.instance.downloadManager.downloadsFlow
    }.collectAsState(initial = emptyList())

    val entity = downloads.firstOrNull { it.trackId == trackId }
        ?: return DownloadUiStatus()

    return when (entity.status) {
        "RESOLVING" -> DownloadUiStatus(active = true, percent = -1)
        "DOWNLOADING" -> DownloadUiStatus(active = true, percent = entity.progressPercent)
        else -> DownloadUiStatus()
    }
}
