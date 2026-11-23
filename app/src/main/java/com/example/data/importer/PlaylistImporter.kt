package com.example.data.importer

import com.example.domain.model.Track
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Where an external playlist came from. */
enum class PlaylistSource(val label: String) {
    SPOTIFY("Spotify"),
    APPLE_MUSIC("Apple Music"),
    PLAIN_TEXT("Pasted list")
}

/** One title/artist pair extracted from a playlist page before YouTube matching. */
data class ImportedTrackRef(
    val title: String,
    val artist: String = ""
)

data class ParsedPlaylist(
    val source: PlaylistSource,
    val name: String,
    val tracks: List<ImportedTrackRef>
)

/**
 * Reads public Spotify and Apple Music playlist pages without an API key.
 *
 * Both services expose a public *embed* page that contains the playlist's track list as
 * embedded JSON:
 *  - Spotify: the `__NEXT_DATA__` script on `open.spotify.com/embed/playlist/...`
 *  - Apple Music: the `serialized-server-data` script on `embed.music.apple.com/...`
 *
 * This is deliberately a best-effort reader. If a service changes its markup, the parser
 * returns a clear failure instead of silently importing the wrong songs. Users can always
 * paste a plain `Title - Artist` list as a fallback.
 */
class ExternalPlaylistParser(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun parse(input: String): Result<ParsedPlaylist> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Paste a playlist link first."))
        }

        val spotify = parseSpotifyReference(trimmed)
        if (spotify != null) {
            return@withContext runCatching { parseSpotify(spotify) }
                .recoverCatching { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // The link was definitely a Spotify link; do not silently treat it as text.
                    throw IllegalStateException(
                        throwable.message ?: "Could not read this Spotify playlist.",
                        throwable
                    )
                }
        }

        val appleUrl = toAppleEmbedUrl(trimmed)
        if (appleUrl != null) {
            return@withContext runCatching { parseAppleMusic(appleUrl) }
                .recoverCatching { throwable ->
                    if (throwable is CancellationException) throw throwable
                    throw IllegalStateException(
                        throwable.message ?: "Could not read this Apple Music playlist.",
                        throwable
                    )
                }
        }

        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("Only Spotify and Apple Music playlist links are supported.")
            )
        }

        parsePlainText(trimmed)
    }

    private fun parseSpotifyReference(input: String): SpotifyReference? {
        val uri = Regex("""spotify:(playlist|album):([A-Za-z0-9]{16,32})""")
            .find(input)
        if (uri != null) {
            return SpotifyReference(
                kind = uri.groupValues[1],
                id = uri.groupValues[2]
            )
        }
        val web = Regex(
            """(?:open\.spotify\.com/(?:intl-[a-z-]+/)?)(playlist|album)/([A-Za-z0-9]{16,32})""",
            RegexOption.IGNORE_CASE
        ).find(input) ?: return null
        return SpotifyReference(
            kind = web.groupValues[1].lowercase(Locale.US),
            id = web.groupValues[2]
        )
    }

    private fun parseSpotify(reference: SpotifyReference): ParsedPlaylist {
        val url = "https://open.spotify.com/embed/${reference.kind}/${reference.id}"
        val html = fetch(url)
        val nextData = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("Spotify did not return a readable playlist page.")

        val root = JSONObject(nextData)
        val tracks = linkedMapOf<String, ImportedTrackRef>()
        val name = findFirstString(root, listOf("name", "title"))
            ?: "Imported Spotify Playlist"

        // Preferred shape: props.pageProps.state.data.entity.trackList
        val trackList = findFirstArray(root, "trackList")
        if (trackList != null) {
            for (i in 0 until trackList.length()) {
                val item = trackList.optJSONObject(i) ?: continue
                addSpotifyTrack(item, tracks)
            }
        }

        // Fallback for album pages or markup changes: walk every object with a track URI.
        if (tracks.isEmpty()) {
            walkJson(root) { obj ->
                val uri = obj.optString("uri")
                if (uri.startsWith("spotify:track:")) addSpotifyTrack(obj, tracks)
            }
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException(
                "Spotify returned the playlist, but no public tracks were found. " +
                    "Make sure the playlist is public."
            )
        }
        return ParsedPlaylist(
            source = PlaylistSource.SPOTIFY,
            name = name.ifBlank { "Imported Spotify Playlist" },
            tracks = tracks.values.toList()
        )
    }

    private fun addSpotifyTrack(item: JSONObject, out: MutableMap<String, ImportedTrackRef>) {
        val title = firstNonBlank(
            item.optString("title"),
            item.optString("name"),
            item.optJSONObject("track")?.optString("name")
        )
        if (title.isBlank()) return

        val artist = firstNonBlank(
            item.optString("subtitle"),
            item.optString("artistName"),
            item.optJSONArray("artists")?.let { joinArtistArray(it) },
            item.optJSONObject("track")
                ?.optJSONArray("artists")
                ?.let { artists -> joinArtistArray(artists) }
        )
        val key = normalizeKey(title, artist)
        out.putIfAbsent(key, ImportedTrackRef(title = title.trim(), artist = artist.trim()))
    }

    private fun parseAppleMusic(embedUrl: String): ParsedPlaylist {
        val html = fetch(embedUrl)
        val jsonText = Regex(
            """<script[^>]*id=["']serialized-server-data["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("Apple Music did not return a readable playlist page.")

        val root = JSONObject(jsonText)
        val tracks = linkedMapOf<String, ImportedTrackRef>()
        var name = ""

        walkJson(root) { obj ->
            if (name.isBlank()) {
                val possibleName = obj.optString("title")
                if (possibleName.isNotBlank() &&
                    obj.has("sections") &&
                    obj.optString("subtitle").isBlank()
                ) {
                    name = possibleName
                }
            }

            val sections = obj.optJSONArray("sections")
            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue
                    val items = section.optJSONArray("items") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        addAppleTrack(item, tracks)
                    }
                }
            }

            // Some responses place song rows directly under a `data` array.
            if (obj.has("songName")) addAppleTrack(obj, tracks)
        }

        if (tracks.isEmpty()) {
            // Last resort: any object that carries both a title and artist links.
            walkJson(root) { obj ->
                if (obj.has("title") && (obj.has("subtitleLinks") || obj.has("artistName"))) {
                    addAppleTrack(obj, tracks)
                }
            }
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException(
                "Apple Music returned the playlist, but no public songs were found. " +
                    "Make sure the playlist is public."
            )
        }
        return ParsedPlaylist(
            source = PlaylistSource.APPLE_MUSIC,
            name = name.ifBlank { "Imported Apple Music Playlist" },
            tracks = tracks.values.toList()
        )
    }

    private fun addAppleTrack(item: JSONObject, out: MutableMap<String, ImportedTrackRef>) {
        val title = firstNonBlank(
            item.optString("title"),
            item.optString("songName"),
            item.optString("name")
        )
        if (title.isBlank()) return

        val subtitleLinks = item.optJSONArray("subtitleLinks")
        val artistFromLinks = if (subtitleLinks != null) {
            val names = mutableListOf<String>()
            for (i in 0 until subtitleLinks.length()) {
                val link = subtitleLinks.optJSONObject(i) ?: continue
                val value = firstNonBlank(link.optString("title"), link.optString("name"))
                if (value.isNotBlank()) names.add(value)
            }
            names.joinToString(", ")
        } else {
            ""
        }

        val artist = firstNonBlank(
            artistFromLinks,
            item.optString("artistName"),
            item.optString("subtitle"),
            item.optJSONObject("artist")?.optString("name")
        )

        // Apple often includes non-song rows (headers, "See All"). A row without an artist is
        // usually a section header, so keep it out of the import.
        if (artist.isBlank() && !item.has("songName")) return

        val key = normalizeKey(title, artist)
        out.putIfAbsent(key, ImportedTrackRef(title = title.trim(), artist = artist.trim()))
    }

    private fun parsePlainText(text: String): Result<ParsedPlaylist> {
        val tracks = linkedMapOf<String, ImportedTrackRef>()
        var name = "Imported Playlist"
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (lines.isEmpty()) {
            return Result.failure(IllegalArgumentException("Paste a playlist link first."))
        }

        // An optional first line "Playlist: My mix" names the result.
        lines.firstOrNull()?.let { first ->
            if (first.startsWith("playlist:", ignoreCase = true)) {
                name = first.substringAfter(':').trim().ifBlank { name }
            }
        }

        for (line in lines) {
            if (line.startsWith("playlist:", ignoreCase = true)) continue
            if (line.startsWith("http://", ignoreCase = true) ||
                line.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            val cleaned = line.removePrefix("- ").removePrefix("• ").trim()
            if (cleaned.isBlank()) continue

            val parts = when {
                cleaned.contains(" - ") -> cleaned.split(" - ", limit = 2)
                cleaned.contains(" – ") -> cleaned.split(" – ", limit = 2)
                cleaned.contains(" — ") -> cleaned.split(" — ", limit = 2)
                cleaned.contains(" | ") -> cleaned.split(" | ", limit = 2)
                cleaned.contains("\t") -> cleaned.split("\t", limit = 2)
                cleaned.contains(" by ", ignoreCase = true) ->
                    cleaned.split(Regex("""\s+by\s+""", RegexOption.IGNORE_CASE), limit = 2)
                else -> listOf(cleaned)
            }
            val title = parts.getOrNull(0)?.trim().orEmpty()
            val artist = parts.getOrNull(1)?.trim().orEmpty()
            if (title.isBlank()) continue
            val key = normalizeKey(title, artist)
            tracks.putIfAbsent(key, ImportedTrackRef(title, artist))
        }

        if (tracks.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "Paste a Spotify/Apple Music link, or one song per line as \"Title - Artist\"."
                )
            )
        }
        return Result.success(
            ParsedPlaylist(
                source = PlaylistSource.PLAIN_TEXT,
                name = name,
                tracks = tracks.values.toList()
            )
        )
    }

    private fun toAppleEmbedUrl(input: String): String? {
        if (!input.contains("music.apple.com", ignoreCase = true)) return null
        val withoutQuery = input.substringBefore('?').trimEnd('/')
        return when {
            withoutQuery.contains("embed.music.apple.com", ignoreCase = true) -> withoutQuery
            else -> withoutQuery.replace(
                "music.apple.com",
                "embed.music.apple.com",
                ignoreCase = true
            )
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Playlist page returned HTTP ${response.code}.")
            }
            return response.body?.string()
                ?: throw IllegalStateException("Playlist page was empty.")
        }
    }

    private fun findFirstString(root: JSONObject, keys: List<String>): String? {
        var found: String? = null
        walkJson(root) { obj ->
            if (found == null) {
                for (key in keys) {
                    val value = obj.optString(key)
                    if (value.isNotBlank()) {
                        found = value
                        break
                    }
                }
            }
        }
        return found
    }

    private fun findFirstArray(root: JSONObject, key: String): JSONArray? {
        var found: JSONArray? = null
        walkJson(root) { obj ->
            if (found == null) {
                val value = obj.optJSONArray(key)
                if (value != null && value.length() > 0) found = value
            }
        }
        return found
    }

    private fun walkJson(value: Any?, visit: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                visit(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    walkJson(value.opt(keys.next()), visit)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJson(value.opt(i), visit)
                }
            }
        }
    }

    private fun joinArtistArray(array: JSONArray): String {
        val names = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val value = firstNonBlank(item.optString("name"), item.optString("title"))
            if (value.isNotBlank()) names.add(value)
        }
        return names.joinToString(", ")
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun normalizeKey(title: String, artist: String): String =
        "${title.trim().lowercase(Locale.US)}|${artist.trim().lowercase(Locale.US)}"

    private data class SpotifyReference(val kind: String, val id: String)

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Resolves extracted titles against YouTube Music and writes the result into a normal Room
 * playlist. Every matched track goes through [MusicRepository], so playback, artwork, lyrics
 * and downloads keep working exactly like a manually created playlist.
 */
class PlaylistImporter(
    private val repository: MusicRepository,
    private val parser: ExternalPlaylistParser = ExternalPlaylistParser()
) {
    data class Progress(
        val phase: Phase,
        val processed: Int = 0,
        val total: Int = 0,
        val currentTitle: String = ""
    ) {
        enum class Phase { READING, MATCHING, SAVING }
    }

    data class Summary(
        val playlistId: String,
        val playlistName: String,
        val source: PlaylistSource,
        val imported: Int,
        val skipped: Int
    )

    suspend fun importPlaylist(
        input: String,
        onProgress: (Progress) -> Unit = {}
    ): Result<Summary> {
        return try {
            onProgress(Progress(Progress.Phase.READING))
            val parsed = parser.parse(input).getOrElse { error ->
                return Result.failure(error)
            }
            if (parsed.tracks.isEmpty()) {
                return Result.failure(
                    IllegalStateException("No songs were found in that playlist.")
                )
            }

            val refs = parsed.tracks.take(MAX_TRACKS)
            val playlistId = repository.createPlaylist(
                name = parsed.name,
                description = "Imported from ${parsed.source.label}"
            )

            var imported = 0
            var skipped = parsed.tracks.size - refs.size
            val seenTrackIds = mutableSetOf<String>()

            refs.forEachIndexed { index, ref ->
                onProgress(
                    Progress(
                        phase = Progress.Phase.MATCHING,
                        processed = index,
                        total = refs.size,
                        currentTitle = ref.title
                    )
                )
                val query = listOf(ref.title, ref.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                val match = repository.searchOnline(query)
                    .getOrNull()
                    ?.firstOrNull { candidate -> looksLikeMatch(ref, candidate) }
                if (match == null || !seenTrackIds.add(match.id)) {
                    skipped++
                } else {
                    repository.addTrackToPlaylist(playlistId, match)
                    imported++
                }
            }

            onProgress(
                Progress(
                    phase = Progress.Phase.SAVING,
                    processed = refs.size,
                    total = refs.size
                )
            )

            if (imported == 0) {
                // Never leave an empty playlist in the user's library.
                repository.deletePlaylist(playlistId)
                return Result.failure(
                    IllegalStateException(
                        "No tracks could be matched on YouTube Music. " +
                            "The playlist may be region-locked or private."
                    )
                )
            }

            Result.success(
                Summary(
                    playlistId = playlistId,
                    playlistName = parsed.name,
                    source = parsed.source,
                    imported = imported,
                    skipped = skipped
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * A light confidence gate. YouTube Music search is good, but an unavailable original can
     * return a completely unrelated upload; better to count that as skipped than to add noise.
     */
    private fun looksLikeMatch(ref: ImportedTrackRef, candidate: Track): Boolean {
        val wantedTitle = normalize(ref.title)
        val foundTitle = normalize(candidate.title)
        val wantedArtist = normalize(ref.artist)
        val foundArtist = normalize(candidate.artist)

        val titleTokens = wantedTitle.split(' ')
            .filter { it.length >= 3 && it !in STOP_WORDS }
        val titleHit = titleTokens.isEmpty() ||
            titleTokens.any { token -> foundTitle.contains(token) }
        val artistHit = wantedArtist.isBlank() ||
            foundArtist.contains(wantedArtist) ||
            wantedArtist.contains(foundArtist) ||
            wantedArtist.split(' ')
                .filter { it.length >= 3 }
                .any { token -> foundArtist.contains(token) }

        return titleHit || artistHit
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("""[\(\[\{].*?[\)\]\}]"""), " ")
        .replace(Regex("""[^a-z0-9\u00C0-\uFFFF]+"""), " ")
        .trim()

    private companion object {
        const val MAX_TRACKS = 100
        val STOP_WORDS = setOf(
            "the", "and", "official", "video", "audio", "music", "lyrics", "lyric",
            "feat", "ft", "with", "from", "remaster", "remastered", "version"
        )
    }
}
