package com.example.ui.importing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.importer.PlaylistImporter
import com.example.data.repository.MusicRepository
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard
import kotlinx.coroutines.launch

private sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Running(
        val phase: PlaylistImporter.Progress.Phase,
        val processed: Int,
        val total: Int,
        val currentTitle: String
    ) : ImportUiState

    data class Success(val summary: PlaylistImporter.Summary) : ImportUiState
    data class Failure(val message: String) : ImportUiState
}

/**
 * Paste a public Spotify / Apple Music playlist link (or a plain Title - Artist list) and turn
 * it into a normal Suziee playlist. Matching happens through the existing YouTube Music search
 * pipeline, so every imported row is immediately playable and download-capable.
 */
@Composable
fun ImportPlaylistDialog(
    repository: MusicRepository,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit
) {
    val colors = SuzieeTheme.colors
    val scope = rememberCoroutineScope()
    val importer = remember(repository) { PlaylistImporter(repository) }
    var input by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ImportUiState>(ImportUiState.Idle) }

    val running = state is ImportUiState.Running

    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            ThemedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .testTag("import_playlist_dialog"),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.accentPrimary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = colors.accentPrimary
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = "Import a playlist",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Spotify, Apple Music, or a pasted song list",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { if (!running) onDismiss() },
                        enabled = !running
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                when (val current = state) {
                    is ImportUiState.Success -> SuccessBody(
                        summary = current.summary,
                        onOpenLibrary = {
                            onImported(current.summary.playlistId)
                            onDismiss()
                        },
                        onImportAnother = {
                            input = ""
                            state = ImportUiState.Idle
                        }
                    )

                    else -> {
                        Text(
                            text = "Playlist link",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_playlist_url_field"),
                            enabled = !running,
                            minLines = 2,
                            maxLines = 5,
                            shape = RoundedCornerShape(16.dp),
                            placeholder = {
                                Text(
                                    text = "https://open.spotify.com/playlist/…\n" +
                                        "or one song per line: Title - Artist",
                                    color = colors.textMuted,
                                    fontSize = 13.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = colors.textMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentSecondary,
                                unfocusedBorderColor = colors.border,
                                disabledBorderColor = colors.border,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                disabledTextColor = colors.textSecondary,
                                cursorColor = colors.accentSecondary,
                                focusedContainerColor = colors.surfaceElevated.copy(alpha = 0.4f),
                                unfocusedContainerColor = colors.surfaceElevated.copy(alpha = 0.25f)
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceChip("Spotify")
                            SourceChip("Apple Music")
                            SourceChip("Title - Artist")
                        }

                        if (state is ImportUiState.Failure) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.error.copy(alpha = 0.12f))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = colors.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = (state as ImportUiState.Failure).message,
                                    color = colors.error,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        if (state is ImportUiState.Running) {
                            Spacer(modifier = Modifier.height(14.dp))
                            RunningBody(state as ImportUiState.Running)
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                enabled = !running
                            ) {
                                Text("Cancel", color = colors.textSecondary)
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            Button(
                                onClick = {
                                    if (input.isNotBlank()) {
                                        state = ImportUiState.Running(
                                            phase = PlaylistImporter.Progress.Phase.READING,
                                            processed = 0,
                                            total = 0,
                                            currentTitle = ""
                                        )
                                        scope.launch {
                                            val result = importer.importPlaylist(input) { progress ->
                                                state = ImportUiState.Running(
                                                    phase = progress.phase,
                                                    processed = progress.processed,
                                                    total = progress.total,
                                                    currentTitle = progress.currentTitle
                                                )
                                            }
                                            state = result.fold(
                                                onSuccess = { ImportUiState.Success(it) },
                                                onFailure = {
                                                    ImportUiState.Failure(
                                                        it.message ?: "Something went wrong."
                                                    )
                                                }
                                            )
                                        }
                                    }
                                },
                                enabled = input.isNotBlank() && !running,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentPrimary,
                                    contentColor = if (colors.isDark) colors.bgPrimary else colors.bgSecondary,
                                    disabledContainerColor = colors.surfaceElevated,
                                    disabledContentColor = colors.textMuted
                                )
                            ) {
                                Text(
                                    text = if (running) "Importing…" else "Import",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun RunningBody(state: ImportUiState.Running) {
    val colors = SuzieeTheme.colors
    val label = when (state.phase) {
        PlaylistImporter.Progress.Phase.READING -> "Reading the playlist…"
        PlaylistImporter.Progress.Phase.MATCHING ->
            if (state.total > 0) {
                "Matching track ${state.processed + 1} of ${state.total}…"
            } else {
                "Matching songs on YouTube Music…"
            }
        PlaylistImporter.Progress.Phase.SAVING -> "Saving to your library…"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceElevated.copy(alpha = 0.55f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.accentSecondary
            )
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (state.currentTitle.isNotBlank() &&
            state.phase == PlaylistImporter.Progress.Phase.MATCHING
        ) {
            Text(
                text = state.currentTitle,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 28.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = colors.accentPrimary,
            trackColor = colors.border
        )
    }
}

@Composable
private fun SuccessBody(
    summary: PlaylistImporter.Summary,
    onOpenLibrary: () -> Unit,
    onImportAnother: () -> Unit
) {
    val colors = SuzieeTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = "${summary.imported} tracks imported, ${summary.skipped} skipped",
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = summary.playlistName,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Saved as a normal Suziee playlist. Matched songs stream and download " +
                "through the same pipeline as the rest of your library.",
            color = colors.textSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onImportAnother) {
                Text("Import another", color = colors.textSecondary)
            }
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                onClick = onOpenLibrary,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = if (colors.isDark) colors.bgPrimary else colors.bgSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Open Library",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SourceChip(label: String) {
    val colors = SuzieeTheme.colors
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfaceElevated.copy(alpha = 0.7f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
