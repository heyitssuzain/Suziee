package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.SuzieeDatabase
import com.example.data.local.TrackMetadataMerge
import com.example.data.local.entities.FavoriteEntity
import com.example.data.local.entities.HistoryEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistTrackCrossRef
import com.example.data.local.entities.TrackEntity
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.remote.lyrics.LrclibApiClient
import com.example.data.remote.lyrics.LyricsResult
import com.example.domain.model.Playlist
import com.example.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class MusicRepository(
    private val database: SuzieeDatabase,
    private val innerTubeApi: InnerTubeApiClient = InnerTubeApiClient(),
    private val lrclibApi: LrclibApiClient = LrclibApiClient()
) {
    private val trackDao = database.trackDao()
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val historyDao = database.historyDao()
    private val downloadDao = database.downloadDao()

    suspend fun searchOnline(query: String): Result<List<Track>> {
        val result = innerTubeApi.search(query)
        if (result.isSuccess) {
            val tracks = result.getOrNull().orEmpty()
            // Save search results for offline / caching. This is a metadata refresh, so it
            // must preserve an existing download flag/path and cached lyrics.
            database.withTransaction {
                tracks.forEach { upsertMetadataInTransaction(it) }
            }
            return Result.success(tracks)
        }
        return result
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        return innerTubeApi.getSuggestions(query)
    }

    // Online stream resolution lives in OnlineStreamResolver (the native YouTube player
    // API), shared by playback and downloads. Keeping it out of the repository avoids a
    // second, divergent provider path here.

    suspend fun getLyrics(track: Track): Result<LyricsResult> {
        // Check if cached in DB
        val cached = trackDao.getTrackById(track.id)
        if (cached != null && (!cached.lyricsPlain.isNullOrBlank() || !cached.lyricsSynced.isNullOrBlank())) {
            val parsed = cached.lyricsSynced?.let { lrclibApi.parseSyncedLyrics(it) } ?: emptyList()
            return Result.success(LyricsResult(cached.lyricsPlain, cached.lyricsSynced, parsed))
        }

        val result = lrclibApi.getLyrics(track.title, track.artist, track.durationSeconds)
        if (result.isSuccess) {
            val lyrics = result.getOrThrow()
            trackDao.updateLyrics(track.id, lyrics.plainLyrics, lyrics.syncedLyrics)
        }
        return result
    }

    // Favorites
    fun isFavorite(trackId: String): Flow<Boolean> = favoriteDao.isFavorite(trackId)

    suspend fun toggleFavorite(track: Track) {
        val fav = favoriteDao.isFavorite(track.id).first()
        upsertMetadata(track)
        if (fav) {
            favoriteDao.removeFavorite(track.id)
        } else {
            favoriteDao.addFavorite(FavoriteEntity(track.id))
        }
    }

    fun getFavorites(): Flow<List<Track>> = favoriteDao.getFavoriteTracks().map { list ->
        list.map { it.toDomain(isFavorite = true) }
    }

    // Playlists
    fun getPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists().map { list ->
        list.map {
            Playlist(
                id = it.id,
                name = it.name,
                description = it.description,
                coverArtworkUrl = it.coverArtworkUrl,
                createdAt = it.createdAt
            )
        }
    }

    suspend fun createPlaylist(name: String, description: String = ""): String {
        val id = UUID.randomUUID().toString()
        playlistDao.insertPlaylist(PlaylistEntity(id = id, name = name, description = description))
        return id
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: Track) {
        upsertMetadata(track)
        playlistDao.insertTrackToPlaylist(
            PlaylistTrackCrossRef(playlistId = playlistId, trackId = track.id)
        )
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun getTracksForPlaylist(playlistId: String): Flow<List<Track>> =
        playlistDao.getTracksForPlaylist(playlistId).map { list ->
            list.map { it.toDomain() }
        }

    // History
    suspend fun recordPlayed(track: Track) {
        upsertMetadata(track)
        historyDao.insertHistory(HistoryEntity(trackId = track.id))
    }

    fun getRecentlyPlayed(limit: Int = 30): Flow<List<Track>> =
        historyDao.getRecentlyPlayedTracks(limit).map { list ->
            list.map { it.toDomain() }
        }

    suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    // Downloads
    fun getDownloadedTracks(): Flow<List<Track>> =
        trackDao.getDownloadedTracks().map { list -> list.map { it.toDomain() } }

    /** Saves a track row without changing its existing download state (see [upsertMetadata]). */
    suspend fun rememberTrack(track: Track) {
        upsertMetadata(track)
    }

    suspend fun markTrackDownloaded(trackId: String, localPath: String) {
        trackDao.updateDownloadStatus(trackId, isDownloaded = true, localPath = localPath)
    }

    /**
     * A completed file disappeared. Keep the track metadata, but stop claiming it is
     * downloadable offline; the Downloads screen keeps the row and offers Retry.
     */
    suspend fun markTrackDownloadMissing(trackId: String) {
        trackDao.updateDownloadStatus(trackId, isDownloaded = false, localPath = null)
    }

    suspend fun removeDownloadedTrack(trackId: String) {
        trackDao.updateDownloadStatus(trackId, isDownloaded = false, localPath = null)
        downloadDao.deleteDownload(trackId)
    }

    /**
     * Metadata-only upsert. REPLACE would delete the whole row first, which is how search
     * results and history writes used to erase `isDownloaded`/`localFilePath`. Preserve
     * those fields explicitly and leave download rows (and files) untouched.
     */
    private suspend fun upsertMetadata(track: Track) = database.withTransaction {
        upsertMetadataInTransaction(track)
    }

    private suspend fun upsertMetadataInTransaction(track: Track) {
        val incoming = TrackEntity.fromDomain(track)
        val existing = trackDao.getTrackById(track.id)
        trackDao.insertTrack(TrackMetadataMerge.merge(existing, incoming))
    }
}
