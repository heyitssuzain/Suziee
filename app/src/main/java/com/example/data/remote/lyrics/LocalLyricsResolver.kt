package com.example.data.remote.lyrics

import android.util.Log
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Local and third-party lyric fallbacks (P3-5, P3-6, P3-7).
 *
 * Order matters and is enforced by [MusicRepository.getLyrics]:
 *   1. a `.lrc` file sitting next to the downloaded audio file (synced)
 *   2. an embedded ID3v2 `USLT` frame in the audio file (plain)
 *   3. LRCLIB (the primary online provider)
 *   4. a best-effort Genius page scrape when LRCLIB has nothing
 *
 * Everything here is optional: a failure returns null and playback continues.
 */
class LocalLyricsResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------------------------
    // P3-6 / P3-7: files on disk
    // ------------------------------------------------------------------------------------

    /**
     * Looks for sidecar LRC text and embedded ID3 lyrics for a local/down­loaded track.
     * Returns null when the track is streaming-only or has no local lyrics.
     */
    fun resolveLocal(track: Track, lrclib: LrclibApiClient): LyricsResult? {
        val audioPath = track.localFilePath
        if (audioPath.isNullOrBlank()) return null
        val audioFile = File(audioPath)
        if (!audioFile.exists()) return null

        // 1. Sidecar .lrc (same name, case-insensitive on all platforms).
        val sidecar = findSidecarLrc(audioFile)
        if (sidecar != null) {
            val raw = runCatching { sidecar.readText() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                val synced = lrclib.parseSyncedLyrics(raw)
                return LyricsResult(
                    plainLyrics = if (synced.isEmpty()) raw.trim() else null,
                    syncedLyrics = if (synced.isNotEmpty()) raw else null,
                    parsedLines = synced
                )
            }
        }

        // 2. Embedded ID3v2 USLT frame.
        val embedded = runCatching { readId3Uslt(audioFile) }.getOrNull()
        if (!embedded.isNullOrBlank()) {
            return LyricsResult(plainLyrics = embedded)
        }

        return null
    }

    private fun findSidecarLrc(audioFile: File): File? {
        val parent = audioFile.parentFile ?: return null
        val base = audioFile.nameWithoutExtension
        val direct = File(parent, "$base.lrc")
        if (direct.exists()) return direct
        return parent.listFiles()?.firstOrNull {
            it.isFile &&
                it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                it.extension.equals("lrc", ignoreCase = true)
        }
    }

    /**
     * Minimal ID3v2.3/v2.4 reader for the `USLT` (unsynchronised lyrics) frame.
     * SYLT is timing-table based and rare; USLT covers the vast majority of tagged files.
     */
    private fun readId3Uslt(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) < 10) return null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return null
            }
            val version = header[3].toInt() and 0xff
            if (version < 3) return null
            val flags = header[5].toInt() and 0xff
            var tagSize = syncSafeInt(header, 6)
            if (tagSize <= 0) return null
            // Unsynchronisation expands the tag; a conservative cap keeps a corrupt
            // header from allocating gigabytes.
            if (flags and 0x80 != 0) tagSize += tagSize / 4
            if (tagSize > 32 * 1024 * 1024) return null

            val tag = ByteArray(tagSize)
            var read = 0
            while (read < tagSize) {
                val count = raf.read(tag, read, tagSize - read)
                if (count <= 0) break
                read += count
            }
            if (read < 10) return null

            var pos = 0
            // v2.4 extended header.
            if (version == 4 && flags and 0x40 != 0 && pos + 4 <= read) {
                pos += 4 + syncSafeInt(tag, pos)
            }
            // v2.3 extended header is a normal big-endian size that excludes its own field.
            if (version == 3 && flags and 0x40 != 0 && pos + 4 <= read) {
                val extSize = bigEndianInt(tag, pos)
                pos += 4 + extSize
            }

            while (pos + 10 <= read) {
                if (tag[pos] == 0.toByte()) break
                val frameId = String(tag, pos, 4, Charsets.ISO_8859_1)
                val frameSize = if (version == 4) {
                    syncSafeInt(tag, pos + 4)
                } else {
                    bigEndianInt(tag, pos + 4)
                }
                if (frameSize <= 0 || pos + 10 + frameSize > read) break
                if (frameId == "USLT") {
                    return decodeUslt(tag, pos + 10, frameSize)
                }
                pos += 10 + frameSize
            }
        }
        return null
    }

    private fun decodeUslt(tag: ByteArray, offset: Int, size: Int): String? {
        if (size < 4 || offset + size > tag.size) return null
        val encoding = tag[offset].toInt() and 0xff
        var cursor = offset + 4 // encoding byte + 3-byte language
        val end = offset + size

        // Skip the content descriptor (terminated by one or two NUL bytes).
        if (encoding == 1 || encoding == 2) {
            while (cursor + 1 < end) {
                if (tag[cursor] == 0.toByte() && tag[cursor + 1] == 0.toByte()) {
                    cursor += 2
                    break
                }
                cursor += 2
            }
        } else {
            while (cursor < end) {
                if (tag[cursor] == 0.toByte()) {
                    cursor += 1
                    break
                }
                cursor += 1
            }
        }
        if (cursor >= end) return null
        return decodeText(tag, cursor, end, encoding)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun decodeText(bytes: ByteArray, start: Int, end: Int, encoding: Int): String? {
        if (start >= end) return null
        val slice = bytes.copyOfRange(start, end)
        return try {
            when (encoding) {
                0 -> String(slice, Charsets.ISO_8859_1)
                1 -> {
                    // UTF-16 with BOM.
                    if (slice.size >= 2 && slice[0] == 0xFF.toByte() && slice[1] == 0xFE.toByte()) {
                        String(slice, 2, slice.size - 2, Charsets.UTF_16LE)
                    } else if (slice.size >= 2 && slice[0] == 0xFE.toByte() && slice[1] == 0xFF.toByte()) {
                        String(slice, 2, slice.size - 2, Charsets.UTF_16BE)
                    } else {
                        String(slice, Charsets.UTF_16LE)
                    }
                }
                2 -> String(slice, Charsets.UTF_16BE)
                else -> String(slice, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)
    }

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    // ------------------------------------------------------------------------------------
    // P3-5: Genius fallback (opt-in path; only called after LRCLIB fails)
    // ------------------------------------------------------------------------------------

    suspend fun fetchGeniusLyrics(track: Track): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(
                cleanTerm("${track.title} ${track.artist}"),
                "UTF-8"
            )
            val searchRequest = Request.Builder()
                .url("https://genius.com/api/search/multi?per_page=5&q=$query")
                .addHeader("User-Agent", USER_AGENT)
                .build()

            val searchBody = client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            } ?: return@withContext null

            val json = JSONObject(searchBody)
            val sections = json.optJSONObject("response")?.optJSONArray("sections")
                ?: return@withContext null

            var songUrl: String? = null
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                if (section.optString("type") != "song") continue
                val hits = section.optJSONArray("hits") ?: continue
                if (hits.length() == 0) continue
                val result = hits.optJSONObject(0)?.optJSONObject("result") ?: continue
                val url = result.optString("url")
                if (url.startsWith("https://genius.com/")) {
                    songUrl = url
                    break
                }
            }
            if (songUrl == null) return@withContext null

            val pageRequest = Request.Builder()
                .url(songUrl)
                .addHeader("User-Agent", USER_AGENT)
                .build()
            val html = client.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            } ?: return@withContext null

            val containerPattern = Regex(
                """data-lyrics-container="true"[^>]*>(.*?)</div>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val raw = containerPattern.findAll(html).joinToString("\n") { it.groupValues[1] }
            if (raw.isBlank()) return@withContext null

            raw
                .replace(Regex("""<br\s*/?>"""), "\n")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&amp;", "&")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Genius fallback failed: ${e.message}")
            null
        }
    }

    private fun cleanTerm(term: String): String = term
        .replace(Regex("(?i)\\[.*?]|\\(.*?\\)"), "")
        .replace(Regex("(?i)official|audio|video|lyrics|mv"), "")
        .trim()

    companion object {
        private const val TAG = "LocalLyricsResolver"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
