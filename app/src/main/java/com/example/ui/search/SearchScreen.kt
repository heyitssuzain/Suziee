package com.example.ui.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.ui.components.TrackRowItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    playerManager: MusicPlayerManager,
    initialQuery: String? = null,
    onOpenVoiceSearch: () -> Unit,
    onTrackOptions: (Track) -> Unit,
    onDownloadTrack: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf(initialQuery ?: "") }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val playbackState by playerManager.playbackState.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        keyboardController?.hide()
        isSearching = true
        errorMessage = null
        suggestions = emptyList()

        searchJob?.cancel()
        searchJob = scope.launch {
            val result = playerManager.repository.searchOnline(trimmed)
            isSearching = false
            if (result.isSuccess) {
                searchResults = result.getOrNull().orEmpty()
                if (searchResults.isEmpty()) {
                    errorMessage = "No matching songs found. Try a different title or artist."
                }
            } else {
                errorMessage = "Couldn't reach the music service. Check your connection and try again."
            }
        }
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            searchQuery = initialQuery
            executeSearch(initialQuery)
        }
    }

    // Suggestions debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank() && searchResults.isEmpty()) {
            delay(300)
            val fetchedSuggestions = playerManager.repository.getSearchSuggestions(searchQuery)
            suggestions = fetchedSuggestions
        } else {
            suggestions = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SuzieeTheme.colors.bgPrimary)
            .testTag("search_screen")
    ) {
        // Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    if (it.isBlank()) {
                        searchResults = emptyList()
                        errorMessage = null
                    }
                },
                placeholder = {
                    Text(
                        text = "Search song, anime opening, artist...",
                        color = SuzieeTheme.colors.textMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search icon",
                        tint = SuzieeTheme.colors.accentSecondary
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                searchResults = emptyList()
                                errorMessage = null
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = SuzieeTheme.colors.textSecondary)
                            }
                        }
                        IconButton(
                            onClick = onOpenVoiceSearch,
                            modifier = Modifier.testTag("search_mic_button")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice Search", tint = SuzieeTheme.colors.accentPrimary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SuzieeTheme.colors.accentSecondary,
                    unfocusedBorderColor = SuzieeTheme.colors.border,
                    focusedTextColor = SuzieeTheme.colors.textPrimary,
                    unfocusedTextColor = SuzieeTheme.colors.textPrimary,
                    focusedContainerColor = SuzieeTheme.colors.surfaceCard,
                    unfocusedContainerColor = SuzieeTheme.colors.surfaceCard
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch(searchQuery) }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("search_input_field")
            )
        }

        // Suggestions Dropdown List
        if (suggestions.isNotEmpty() && searchResults.isEmpty() && !isSearching) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                items(suggestions) { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                searchQuery = suggestion
                                executeSearch(suggestion)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = SuzieeTheme.colors.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = suggestion,
                            color = SuzieeTheme.colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }

        // Content Area: Loading, Error, or Results
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when {
                isSearching -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = SuzieeTheme.colors.accentSecondary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Searching online music library...",
                            color = SuzieeTheme.colors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = SuzieeTheme.colors.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { executeSearch(searchQuery) },
                            colors = ButtonDefaults.buttonColors(containerColor = SuzieeTheme.colors.accentPrimary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = SuzieeTheme.colors.bgPrimary)
                            Text("Retry Search", color = SuzieeTheme.colors.bgPrimary, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                searchResults.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp, top = 4.dp)
                    ) {
                        item {
                            Text(
                                text = "Results (${searchResults.size})",
                                color = SuzieeTheme.colors.textSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }

                        items(searchResults) { track ->
                            TrackRowItem(
                                track = track,
                                isCurrentTrack = playbackState.currentTrack?.id == track.id,
                                isPlaying = playbackState.isPlaying,
                                onClick = {
                                    playerManager.playTrack(track, searchResults)
                                },
                                onFavoriteToggle = {
                                    scope.launch {
                                        playerManager.repository.toggleFavorite(track)
                                    }
                                },
                                onAddToPlaylist = { onTrackOptions(track) },
                                onPlayNext = { playerManager.playNext(track) },
                                onAddToQueue = { playerManager.addToQueue(track) },
                                onDownload = { onDownloadTrack(track) }
                            )
                        }
                    }
                }

                else -> {
                    // Initial search empty state with anime ideas
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Discover Any Song",
                            color = SuzieeTheme.colors.textPrimary,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Search by song title, anime opening, artist, or use your voice to find any track in the world.",
                            color = SuzieeTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SuzieeTheme.colors.surfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border),
                                modifier = Modifier.clickable {
                                    searchQuery = "Ado"
                                    executeSearch("Ado")
                                }
                            ) {
                                Text("Ado", color = SuzieeTheme.colors.accentSecondary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SuzieeTheme.colors.surfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border),
                                modifier = Modifier.clickable {
                                    searchQuery = "Yoasobi"
                                    executeSearch("Yoasobi")
                                }
                            ) {
                                Text("Yoasobi", color = SuzieeTheme.colors.accentSecondary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SuzieeTheme.colors.surfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, SuzieeTheme.colors.border),
                                modifier = Modifier.clickable {
                                    searchQuery = "Naruto"
                                    executeSearch("Naruto")
                                }
                            ) {
                                Text("Naruto", color = SuzieeTheme.colors.accentSecondary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
