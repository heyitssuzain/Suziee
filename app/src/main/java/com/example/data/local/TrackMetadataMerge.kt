package com.example.data.local

import com.example.data.local.entities.TrackEntity

/**
 * Metadata upserts must never erase offline state.
 *
 * Search results, history writes and favourite toggles all carry a [TrackEntity] whose
 * `isDownloaded`/`localFilePath` fields describe only that screen's copy. Inserting it with
 * SQLite REPLACE used to flip a downloaded song back to "not downloaded" a day later (or as
 * soon as it was searched/played again), even though the audio file was still on disk.
 */
internal object TrackMetadataMerge {

    fun merge(existing: TrackEntity?, incoming: TrackEntity): TrackEntity {
        if (existing == null) return incoming
        return incoming.copy(
            // Download state is owned by DownloadManager, never by metadata refreshes.
            isDownloaded = existing.isDownloaded,
            localFilePath = existing.localFilePath,
            // A null lyrics value from a search result must not erase cached lyrics.
            lyricsPlain = incoming.lyricsPlain ?: existing.lyricsPlain,
            lyricsSynced = incoming.lyricsSynced ?: existing.lyricsSynced,
            lastAccessedAt = existing.lastAccessedAt
        )
    }
}
