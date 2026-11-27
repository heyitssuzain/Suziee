package com.example.couple

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * URL-safe Base64 with no padding.
 *
 * Implemented locally instead of relying on android.util.Base64 (which is unavailable in
 * plain JVM unit tests) or java.util.Base64 (API 26+, while Suziee supports API 24).
 */
object CoupleBase64 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val reverse = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            out.append(ALPHABET[(triple ushr 18) and 0x3F])
            out.append(ALPHABET[(triple ushr 12) and 0x3F])
            if (index + 1 < bytes.size) out.append(ALPHABET[(triple ushr 6) and 0x3F])
            if (index + 2 < bytes.size) out.append(ALPHABET[triple and 0x3F])
            index += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 4)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=' || ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue
            val code = ch.code
            if (code >= reverse.size) return null
            val value = reverse[code]
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

data class CoupleLink(
    val kind: String,
    val payloadJson: String
)

/**
 * Compact, deep-linkable representation of a pairing or signalling payload.
 *
 * The JSON is gzipped and Base64URL encoded because an SDP with its gathered ICE candidates
 * is far too large to fit comfortably into a QR code uncompressed. The same string works as
 * a QR code, a clipboard payload and a `suziee://couple/...` deep link.
 */
object CoupleCodec {
    const val PREFIX = "suziee://couple/v1/"

    const val KIND_INVITE = "invite"
    const val KIND_ACCEPT = "accept"
    const val KIND_CONFIRM = "confirm"
    const val KIND_OFFER = "offer"
    const val KIND_ANSWER = "answer"
    const val KIND_EVENT = "event"

    val kinds = setOf(KIND_INVITE, KIND_ACCEPT, KIND_CONFIRM, KIND_OFFER, KIND_ANSWER, KIND_EVENT)

    private const val MAX_LINK_CHARS = 24_000
    private const val MAX_DECOMPRESSED_BYTES = 256 * 1024
    private const val MAX_JSON_BYTES = 128 * 1024

    fun encodeLink(kind: String, payloadJson: String): String {
        require(kind in kinds) { "Unknown couple link kind: $kind" }
        val compressed = gzip(payloadJson.toByteArray(Charsets.UTF_8))
        return PREFIX + kind + "/" + CoupleBase64.encode(compressed)
    }

    fun decodeLink(raw: String): CoupleLink? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINK_CHARS) return null
        val body = if (trimmed.startsWith(PREFIX)) {
            trimmed.removePrefix(PREFIX)
        } else {
            trimmed
        }
        val separator = body.indexOfFirst { it == '/' || it == ':' }
        if (separator <= 0) return null
        val kind = body.substring(0, separator)
        if (kind !in kinds) return null
        val data = body.substring(separator + 1).trim()
        if (data.isEmpty()) return null

        val compressed = CoupleBase64.decode(data) ?: return null
        val jsonBytes = runCatching { gunzip(compressed) }.getOrNull() ?: return null
        if (jsonBytes.size > MAX_JSON_BYTES) return null
        return CoupleLink(kind = kind, payloadJson = jsonBytes.toString(Charsets.UTF_8))
    }

    fun isCoupleLink(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.startsWith(PREFIX)) return true
        val separator = trimmed.indexOfFirst { it == '/' || it == ':' }
        return separator > 0 && trimmed.substring(0, separator) in kinds
    }

    fun encodeEnvelope(envelope: CoupleEnvelope): String =
        encodeLink(KIND_EVENT, CoupleJson.encode(envelope))

    fun decodeEnvelope(raw: String): CoupleEnvelope? {
        val link = decodeLink(raw) ?: return null
        if (link.kind != KIND_EVENT) return null
        return CoupleJson.decode(link.payloadJson)
    }

    /**
     * Accepts a pasted deep link, a raw `kind:data` code, or the full QR text. Returns the
     * original trimmed input when it is already usable; otherwise null.
     */
    fun normalizeIncoming(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (isCoupleLink(trimmed)) return trimmed
        val marker = trimmed.indexOf("suziee://couple/")
        if (marker >= 0) {
            val rest = trimmed.substring(marker)
            // A link pasted inside a chat message is followed by prose or a newline; the code
            // itself never contains whitespace, so stop at the first whitespace character
            // instead of swallowing the rest of the message.
            val end = rest.indexOfFirst { it.isWhitespace() }
            val candidate = if (end >= 0) rest.substring(0, end) else rest
            if (isCoupleLink(candidate)) return candidate
        }
        return null
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(4_096)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw IllegalArgumentException("decompressed couple payload too large")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
