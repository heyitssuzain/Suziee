package com.example.data.repository

import com.example.data.local.SuzieeDatabase
import com.example.data.local.entities.FavoriteEntity
import com.example.data.local.entities.HistoryEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistTrackCrossRef
import com.example.data.local.entities.TrackEntity
import com.example.data.remote.innertube.InnerTubeApiClient
import com.example.data.remote.lyrics.LrclibApiClient
import com.example.data.remote.lyrics.LyricsResult
import com.example.data.remote.soundcloud.SoundCloudApiClient
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
    private val soundCloudApi: SoundCloudApiClient = SoundCloudApiClient(),
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

    /**
     * Resolves a directly playable audio URL for [track] from the SoundCloud source.
     *
     * This used to try the YouTube InnerTube player API and then Piped/Invidious
     * mirrors. All of those were removed: every one of them answers LOGIN_REQUIRED,
     * UNPLAYABLE or HTTP 400/404 for anonymous requests, so they only burned time and
     * bandwidth. YouTube tracks are still played - that resolution happens one layer up
     * in `MusicPlayerManager`, which first drives the headless-WebView sniffer (a real
     * YouTube browser session) and only calls down to this SoundCloud path when the
     * sniffer fails.
     *
     * @param allowHls when false only a single progressive file is accepted. The
     * downloader sets this because an HLS playlist cannot be saved as one audio file.
     */
    suspend fun resolveStreamUrl(track: Track, allowHls: Boolean = true): Result<StreamInfo> {
        // If downloaded locally, use the local file path!
        val localPath = track.localFilePath
        if (track.isDownloaded && !localPath.isNullOrBlank()) {
            return Result.success(StreamInfo(localPath))
        }

        return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            val soundCloud = soundCloudApi.resolve(track, allowHls = allowHls)
            if (soundCloud != null && soundCloud.url.isNotBlank()) {
                runCatching {
                    trackDao.insertTrack(TrackEntity.fromDomain(track.copy(streamUrl = soundCloud.url)))
                }
                return@withTimeoutOrNull Result.success(soundCloud)
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
