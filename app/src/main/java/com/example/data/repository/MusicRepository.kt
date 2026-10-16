package com.example.data.repository

import com.example.data.local.SuzieeDatabase
import com.example.data.local.entities.FavoriteEntity
import com.example.data.local.entities.HistoryEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistTrackCrossRef
import com.example.data.local.entities.TrackEntity
import com.example.data.remote.fallback.DistributedFallbackStreamResolver
import com.example.data.remote.fallback.FallbackStreamResolver
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.remote.lyrics.LrclibApiClient
import com.example.data.remote.lyrics.LyricsResult
import com.example.domain.model.Playlist
import com.example.domain.model.StreamInfo
import com.example.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class MusicRepository(
    private val database: SuzieeDatabase,
    private val innerTubeApi: InnerTubeApiClient = InnerTubeApiClient(),
    private val fallbackResolver: FallbackStreamResolver = DistributedFallbackStreamResolver(),
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
            // Save search results to DB for offline / caching
            trackDao.insertTracks(tracks.map { TrackEntity.fromDomain(it) })
            return Result.success(tracks)
        }
        return result
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        return innerTubeApi.getSuggestions(query)
    }

    suspend fun resolveStreamUrl(track: Track): Result<StreamInfo> {
        // If downloaded locally, use the local file path!
        val localPath = track.localFilePath
        if (track.isDownloaded && !localPath.isNullOrBlank()) {
            return Result.success(StreamInfo(localPath))
        }

        return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            // 1. Try Primary InnerTube (tries several clients internally)
            val primary = innerTubeApi.resolveStream(track.id).getOrNull()
            if (primary != null && primary.url.isNotBlank()) {
                runCatching { trackDao.insertTrack(TrackEntity.fromDomain(track.copy(streamUrl = primary.url))) }
                return@withTimeoutOrNull Result.success(primary)
            }

            // 2. Try Fallback Stream Resolver (Piped / Invidious)
            val fallback = fallbackResolver.resolve(track).getOrNull()
            if (fallback != null && fallback.url.isNotBlank()) {
                runCatching { trackDao.insertTrack(TrackEntity.fromDomain(track.copy(streamUrl = fallback.url))) }
                return@withTimeoutOrNull Result.success(fallback)
            }

            Result.failure(Exception("Unable to resolve playable audio stream for ${track.title}"))
        } ?: Result.failure(Exception("Stream resolution timed out. Please try again."))
    }

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
        trackDao.insertTrack(TrackEntity.fromDomain(track))
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
        trackDao.insertTrack(TrackEntity.fromDomain(track))
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
        trackDao.insertTrack(TrackEntity.fromDomain(track))
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

    suspend fun markTrackDownloaded(trackId: String, localPath: String) {
        trackDao.updateDownloadStatus(trackId, isDownloaded = true, localPath = localPath)
    }

    suspend fun removeDownloadedTrack(trackId: String) {
        trackDao.updateDownloadStatus(trackId, isDownloaded = false, localPath = null)
        downloadDao.deleteDownload(trackId)
    }

    companion object {
        /** Upper bound for a full stream-resolution attempt (primary + fallbacks). */
        private const val RESOLVE_TIMEOUT_MS = 30_000L
    }
}
