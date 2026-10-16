package com.example.ui.downloads

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.downloads.DownloadManager
import com.example.playback.MusicPlayerManager
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.SuzieeAccentPrimary
import com.example.ui.theme.SuzieeAccentSecondary
import com.example.ui.theme.SuzieeBgPrimary
import com.example.ui.theme.SuzieeBorder
import com.example.ui.theme.SuzieeSurfaceCard
import com.example.ui.theme.SuzieeTextMuted
import com.example.ui.theme.SuzieeTextPrimary
import com.example.ui.theme.SuzieeTextSecondary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DownloadsScreen(
    playerManager: MusicPlayerManager,
    downloadManager: DownloadManager,
    onNavigateToSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloadedTracks by playerManager.repository.getDownloadedTracks().collectAsState(initial = emptyList())
    val activeDownloads by downloadManager.downloadsFlow.collectAsState(initial = emptyList())
    val playbackState by playerManager.playbackState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Calculate total downloaded storage size
    val totalSizeMb = rememberCalculatedStorageSize(context)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeBgPrimary)
            .testTag("downloads_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Header & Storage Info Card
        item {
            Text(
                text = "Offline Downloads",
                color = SuzieeTextPrimary,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                color = SuzieeSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DownloadDone,
                                contentDescription = null,
                                tint = SuzieeAccentSecondary
                            )
                            Text(
                                text = "Device Storage",
                                color = SuzieeTextPrimary,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Text(
                            text = String.format("%.1f MB used", totalSizeMb),
                            color = SuzieeAccentSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Downloaded songs can be enjoyed anywhere without Wi-Fi or cellular data.",
                        color = SuzieeTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Play all downloaded button
            if (downloadedTracks.isNotEmpty()) {
                Button(
                    onClick = {
                        playerManager.playTrack(downloadedTracks.first(), downloadedTracks)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuzieeAccentPrimary),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SuzieeBgPrimary)
                    Text("Play All Downloaded (${downloadedTracks.size})", color = SuzieeBgPrimary)
                }
            }
        }

        // Active downloading progress items
        val pendingDownloads = activeDownloads.filter { it.status == "DOWNLOADING" || it.status == "RESOLVING" }
        if (pendingDownloads.isNotEmpty()) {
            item {
                Text(
                    text = "ACTIVE DOWNLOADS",
                    color = SuzieeAccentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            items(pendingDownloads) { d ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    color = SuzieeSurfaceCard
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Downloading track...", color = SuzieeTextPrimary, fontSize = 13.sp)
                            Text(text = "${d.progressPercent}%", color = SuzieeAccentSecondary, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { d.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = SuzieeAccentSecondary,
                            trackColor = SuzieeBorder
                        )
                    }
                }
            }
        }

        // Downloaded tracks list
        if (downloadedTracks.isEmpty() && pendingDownloads.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No downloaded tracks yet.",
                        color = SuzieeTextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Search for songs online and choose 'Download' from the track options.",
                        color = SuzieeTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                    Button(
                        onClick = onNavigateToSearch,
                        colors = ButtonDefaults.buttonColors(containerColor = SuzieeAccentPrimary)
                    ) {
                        Text("Search Music", color = SuzieeBgPrimary)
                    }
                }
            }
        } else {
            items(downloadedTracks) { track ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TrackRowItem(
                            track = track,
                            isCurrentTrack = playbackState.currentTrack?.id == track.id,
                            isPlaying = playbackState.isPlaying,
                            onClick = {
                                playerManager.playTrack(track, downloadedTracks)
                            }
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                downloadManager.deleteDownload(track.id)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete download",
                            tint = SuzieeTextMuted
                        )
                    }
                }
            }
        }
    }
}

private fun rememberCalculatedStorageSize(context: android.content.Context): Float {
    return try {
        val dir = File(context.filesDir, "downloads")
        if (dir.exists()) {
            val totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
            totalBytes.toFloat() / (1024f * 1024f)
        } else {
            0f
        }
    } catch (e: Exception) {
        0f
    }
}
