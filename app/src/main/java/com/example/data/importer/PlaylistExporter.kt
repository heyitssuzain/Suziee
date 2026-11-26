package com.example.data.importer

import com.example.domain.model.Playlist
import com.example.domain.model.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports a Suziee playlist to the JSON shape the app's own importer understands (P2-5).
 *
 * The format is deliberately the *inverse* of what the paste-import path accepts, so an
 * exported playlist can always come back:
 *
 * ```json
 * {
 *   "schema": 1,
 *   "name": "My playlist",
 *   "description": "…",
 *   "tracks": [
 *     { "id": "…", "title": "…", "artist": "…", "artworkUrl": "…", "durationSeconds": 210 }
 *   ]
 * }
 * ```
 *
 * Everything is produced locally from the Room rows; no network call and no third-party
 * format is involved, which keeps the app account- and analytics-free.
 */
object PlaylistExporter {

    const val MIME_TYPE = "application/json"

    fun toJson(playlist: Playlist, tracks: List<Track>): String {
        val trackArray = JSONArray()
        tracks.forEach { track ->
            trackArray.put(trackToJson(track))
        }

        return JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("name", playlist.name)
            .put("description", playlist.description)
            .put("trackCount", tracks.size)
            .put("exportedAt", System.currentTimeMillis())
            .put("tracks", trackArray)
            .toString(2)
    }

    /** A one-line safe file name for the share sheet. */
    fun fileNameFor(playlist: Playlist): String {
        val base = playlist.name
            .replace(Regex("""[^A-Za-z0-9\u00C0-\uFFFF]+"""), "_")
            .trim('_')
            .take(48)
        val safe = if (base.isBlank()) "suziee_playlist" else base
        return "$safe.json"
    }

    /**
     * Reads the JSON produced by [toJson] back into a plain playlist. Tracks are only
     * re-resolved (title/artist) - the original video id is exported so an offline-capable
     * library can be matched later, but restoring always goes through the normal online path.
     */
    fun fromJson(json: String): ExportedPlaylist? {
        return try {
            val root = JSONObject(json)
            val tracks = root.optJSONArray("tracks") ?: return null
            val result = mutableListOf<ImportedTrackRef>()
            var ids = emptyList<String>()
            for (i in 0 until tracks.length()) {
                val item = tracks.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                result.add(ImportedTrackRef(title = title, artist = item.optString("artist").trim()))
                val id = item.optString("id")
                if (id.isNotBlank()) ids = ids + id
            }
            if (result.isEmpty()) return null
            ExportedPlaylist(
                name = root.optString("name").ifBlank { "Imported playlist" },
                description = root.optString("description"),
                tracks = result,
                sourceIds = ids
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun trackToJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artist)
        put("artworkUrl", track.artworkUrl)
        put("durationSeconds", track.durationSeconds)
        put("isDownloaded", track.isDownloaded)
    }

    data class ExportedPlaylist(
        val name: String,
        val description: String,
        val tracks: List<ImportedTrackRef>,
        /** Original Suziee track ids, kept so re-imports can prefer exact matches. */
        val sourceIds: List<String>
    )

    private const val SCHEMA_VERSION = 1
}