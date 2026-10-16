package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.Track

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val artworkUrl: String,
    val streamUrl: String?,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val lyricsPlain: String? = null,
    val lyricsSynced: String? = null,
    val lastAccessedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(isFavorite: Boolean = false): Track = Track(
        id = id,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        isDownloaded = isDownloaded,
        localFilePath = localFilePath,
        lyricsPlain = lyricsPlain,
        lyricsSynced = lyricsSynced,
        isFavorite = isFavorite
    )

    companion object {
        fun fromDomain(track: Track): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationSeconds = track.durationSeconds,
            artworkUrl = track.artworkUrl,
            streamUrl = track.streamUrl,
            isDownloaded = track.isDownloaded,
            localFilePath = track.localFilePath,
            lyricsPlain = track.lyricsPlain,
            lyricsSynced = track.lyricsSynced
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val coverArtworkUrl: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("playlistId"), Index("trackId")]
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val historyId: Long = 0,
    val trackId: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val status: String, // "QUEUED", "DOWNLOADING", "COMPLETED", "FAILED"
    val progressPercent: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
