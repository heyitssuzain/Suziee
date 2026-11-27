package com.example.couple

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.security.SecureRandom
import java.util.UUID

/**
 * Couple Space wire protocol.
 *
 * The protocol is deliberately small and versioned. Every frame is a JSON envelope carrying
 * the version, a globally unique event id, the sender's stable device id, the event type, a
 * per-channel sequence number, a wall-clock timestamp and an opaque JSON payload. Payloads
 * are validated before any of them touch Room or the player.
 *
 * Version 1 is the only accepted version. A future version must be introduced with a new
 * constant and an explicit compatibility path; unknown versions are rejected instead of
 * being guessed at.
 */
object CoupleProtocol {
    const val VERSION = 1

    /** Hard cap on a single DataChannel frame, in UTF-8 bytes. */
    const val MAX_ENVELOPE_BYTES = 32 * 1024

    /** Hard cap on a chat message body, in characters. */
    const val MAX_CHAT_BODY = 4_000

    /** Hard cap on a remote queue snapshot. */
    const val MAX_QUEUE_ITEMS = 500

    /** Hard cap on the number of playlist items in one event. */
    const val MAX_PLAYLIST_ITEMS = 500

    /** A sync/control event older or newer than this is not trusted. */
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

    /** Queued chat may legitimately be days old; still bounded. */
    const val MAX_QUEUED_AGE_MS = 30L * 24 * 60 * 60 * 1000

    const val EVENT_AUTH = "auth.hello"
    const val EVENT_CHAT_MESSAGE = "chat.message"
    const val EVENT_CHAT_ACK = "chat.ack"
    const val EVENT_PRESENCE = "presence.state"
    const val EVENT_PLAYBACK = "playback.command"
    const val EVENT_PLAYLIST = "playlist.event"
    const val EVENT_CLOCK_PING = "clock.ping"
    const val EVENT_CLOCK_PONG = "clock.pong"

    val knownTypes = setOf(
        EVENT_AUTH,
        EVENT_CHAT_MESSAGE,
        EVENT_CHAT_ACK,
        EVENT_PRESENCE,
        EVENT_PLAYBACK,
        EVENT_PLAYLIST,
        EVENT_CLOCK_PING,
        EVENT_CLOCK_PONG
    )

    /** Types that are only meaningful inside the current authenticated session. */
    val sessionBoundTypes = setOf(
        EVENT_AUTH,
        EVENT_PRESENCE,
        EVENT_PLAYBACK,
        EVENT_CLOCK_PING,
        EVENT_CLOCK_PONG
    )

    private val eventIdRegex = Regex("^[A-Za-z0-9_-]{8,80}$")
    private val publicKeyBase64Regex = Regex("^[A-Za-z0-9_=-]{16,2048}$")

    fun isValidEventId(id: String): Boolean = eventIdRegex.matches(id)

    fun isValidPublicKeyBase64(key: String): Boolean = publicKeyBase64Regex.matches(key)
}

enum class CoupleChannel(
    val wireName: String,
    val ordered: Boolean,
    val reliable: Boolean
) {
    CONTROL("control", ordered = true, reliable = true),
    CHAT("chat", ordered = true, reliable = true),
    PLAYLIST("playlist", ordered = true, reliable = true),
    PRESENCE("presence", ordered = false, reliable = false),
    SYNC("sync", ordered = true, reliable = true);

    companion object {
        fun fromWireName(name: String): CoupleChannel? =
            entries.firstOrNull { it.wireName == name }
    }
}

data class CoupleEnvelope(
    val v: Int = CoupleProtocol.VERSION,
    val id: String = "",
    val sender: String = "",
    val type: String = "",
    val seq: Long = 0L,
    val ts: Long = 0L,
    val session: String? = null,
    val payload: String = ""
)

// ---------------------------------------------------------------------------------------------
// Payloads
// ---------------------------------------------------------------------------------------------

data class ChatPayload(
    val body: String = "",
    val sentAt: Long = 0L
)

data class ChatAckPayload(
    val ack: String = ""
)

data class AuthPayload(
    val deviceId: String = "",
    val publicKey: String = "",
    val pairingId: String = "",
    val session: String = "",
    val nonce: String = "",
    val signature: String = "",
    val displayName: String = ""
)

data class PresencePayload(
    val online: Boolean = false,
    val activity: String = "idle",
    val trackId: String? = null,
    val displayName: String = ""
)

data class RemoteTrackPayload(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val durationSeconds: Long = 0L,
    val artworkUrl: String = ""
)

data class PlaybackPayload(
    val action: String = ACTION_PLAY,
    val track: RemoteTrackPayload? = null,
    val queue: List<RemoteTrackPayload> = emptyList(),
    val positionMs: Long = 0L,
    val playing: Boolean = true,
    val issuedAt: Long = 0L,
    val speed: Float = 1f
) {
    companion object {
        const val ACTION_TRACK = "TRACK"
        const val ACTION_PLAY = "PLAY"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_SEEK = "SEEK"
        const val ACTION_NEXT = "NEXT"
        const val ACTION_PREVIOUS = "PREVIOUS"
        const val ACTION_QUEUE = "QUEUE"
        const val ACTION_RATE = "RATE"

        val actions = setOf(
            ACTION_TRACK, ACTION_PLAY, ACTION_PAUSE, ACTION_RESUME,
            ACTION_SEEK, ACTION_NEXT, ACTION_PREVIOUS, ACTION_QUEUE, ACTION_RATE
        )
    }
}

data class PlaylistItemPayload(
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val durationSeconds: Long = 0L,
    val artworkUrl: String = "",
    val position: Int = 0,
    val lamport: Long = 0L,
    val updatedBy: String = "",
    val deleted: Boolean = false
)

data class PlaylistPayload(
    val op: String = OP_ADD,
    val track: PlaylistItemPayload? = null,
    val trackId: String = "",
    val order: List<String> = emptyList(),
    val items: List<PlaylistItemPayload> = emptyList(),
    val lamport: Long = 0L,
    val actor: String = ""
) {
    companion object {
        const val OP_ADD = "ADD"
        const val OP_REMOVE = "REMOVE"
        const val OP_MOVE = "MOVE"
        const val OP_SNAPSHOT = "SNAPSHOT"

        val ops = setOf(OP_ADD, OP_REMOVE, OP_MOVE, OP_SNAPSHOT)
    }
}

data class ClockPayload(
    val kind: String = KIND_PING,
    val t0: Long = 0L,
    val t1: Long = 0L,
    val t2: Long = 0L
) {
    companion object {
        const val KIND_PING = "PING"
        const val KIND_PONG = "PONG"
    }
}

// ---------------------------------------------------------------------------------------------
// JSON
// ---------------------------------------------------------------------------------------------

object CoupleJson {
    @PublishedApi
    internal val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    inline fun <reified T : Any> encode(value: T): String =
        moshi.adapter(T::class.java).toJson(value)

    inline fun <reified T : Any> decode(json: String): T? =
        runCatching { moshi.adapter(T::class.java).fromJson(json) }.getOrNull()
}

// ---------------------------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------------------------

sealed interface CoupleValidation {
    data class Valid(val envelope: CoupleEnvelope) : CoupleValidation
    data class Invalid(val reason: String) : CoupleValidation
}

/**
 * Validates an incoming frame against the protocol, the expected peer and the current clock.
 *
 * Nothing is trusted: malformed JSON, unknown versions, wrong senders, oversized bodies,
 * replayed sequence numbers and stale session-bound events are all rejected before the
 * controller or the database sees them.
 */
object CoupleValidator {

    fun validate(
        raw: String,
        expectedPeerId: String,
        now: Long
    ): CoupleValidation {
        if (raw.isBlank()) return CoupleValidation.Invalid("empty frame")
        if (raw.toByteArray(Charsets.UTF_8).size > CoupleProtocol.MAX_ENVELOPE_BYTES) {
            return CoupleValidation.Invalid("frame too large")
        }
        val envelope = CoupleJson.decode<CoupleEnvelope>(raw)
            ?: return CoupleValidation.Invalid("malformed envelope")

        if (envelope.v != CoupleProtocol.VERSION) {
            return CoupleValidation.Invalid("unsupported protocol version")
        }
        if (!CoupleProtocol.isValidEventId(envelope.id)) {
            return CoupleValidation.Invalid("invalid event id")
        }
        if (envelope.sender.isBlank() || envelope.sender != expectedPeerId) {
            return CoupleValidation.Invalid("unexpected sender")
        }
        if (envelope.type !in CoupleProtocol.knownTypes) {
            return CoupleValidation.Invalid("unknown event type")
        }
        if (envelope.seq <= 0L) {
            return CoupleValidation.Invalid("invalid sequence")
        }
        if (envelope.ts <= 0L) {
            return CoupleValidation.Invalid("invalid timestamp")
        }
        if (envelope.payload.length > CoupleProtocol.MAX_ENVELOPE_BYTES) {
            return CoupleValidation.Invalid("payload too large")
        }

        val age = now - envelope.ts
        if (envelope.type in CoupleProtocol.sessionBoundTypes) {
            if (age > CoupleProtocol.MAX_CLOCK_SKEW_MS || age < -CoupleProtocol.MAX_CLOCK_SKEW_MS) {
                return CoupleValidation.Invalid("stale or future event")
            }
            if (envelope.session.isNullOrBlank()) {
                return CoupleValidation.Invalid("missing session")
            }
        } else {
            if (age > CoupleProtocol.MAX_QUEUED_AGE_MS || age < -CoupleProtocol.MAX_CLOCK_SKEW_MS) {
                return CoupleValidation.Invalid("event outside the accepted window")
            }
        }

        return when (envelope.type) {
            CoupleProtocol.EVENT_CHAT_MESSAGE -> {
                val payload = CoupleJson.decode<ChatPayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed chat payload")
                val body = sanitizeChatBody(payload.body)
                if (body.isBlank()) return CoupleValidation.Invalid("empty chat body")
                if (body.length > CoupleProtocol.MAX_CHAT_BODY) {
                    return CoupleValidation.Invalid("chat body too long")
                }
                CoupleValidation.Valid(envelope)
            }

            CoupleProtocol.EVENT_CHAT_ACK -> {
                val payload = CoupleJson.decode<ChatAckPayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed ack payload")
                if (!CoupleProtocol.isValidEventId(payload.ack)) {
                    return CoupleValidation.Invalid("invalid ack id")
                }
                CoupleValidation.Valid(envelope)
            }

            CoupleProtocol.EVENT_AUTH -> {
                val payload = CoupleJson.decode<AuthPayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed auth payload")
                if (payload.deviceId != expectedPeerId) {
                    return CoupleValidation.Invalid("auth device mismatch")
                }
                if (!CoupleProtocol.isValidPublicKeyBase64(payload.publicKey)) {
                    return CoupleValidation.Invalid("invalid public key")
                }
                if (payload.pairingId.isBlank() || payload.session.isBlank() ||
                    payload.nonce.isBlank() || payload.signature.isBlank()
                ) {
                    return CoupleValidation.Invalid("incomplete auth payload")
                }
                if (payload.session != envelope.session) {
                    return CoupleValidation.Invalid("auth session mismatch")
                }
                CoupleValidation.Valid(envelope)
            }

            CoupleProtocol.EVENT_PLAYBACK -> {
                val payload = CoupleJson.decode<PlaybackPayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed playback payload")
                if (payload.action !in PlaybackPayload.actions) {
                    return CoupleValidation.Invalid("unknown playback action")
                }
                if (payload.positionMs < 0L || payload.positionMs > 24L * 60 * 60 * 1000) {
                    return CoupleValidation.Invalid("invalid playback position")
                }
                if (payload.queue.size > CoupleProtocol.MAX_QUEUE_ITEMS) {
                    return CoupleValidation.Invalid("queue too large")
                }
                if (payload.speed < 0.5f || payload.speed > 2.0f) {
                    return CoupleValidation.Invalid("invalid playback speed")
                }
                if (payload.track != null && !isValidRemoteTrack(payload.track)) {
                    return CoupleValidation.Invalid("invalid remote track")
                }
                if (payload.queue.any { !isValidRemoteTrack(it) }) {
                    return CoupleValidation.Invalid("invalid queue item")
                }
                CoupleValidation.Valid(envelope)
            }

            CoupleProtocol.EVENT_PLAYLIST -> {
                val payload = CoupleJson.decode<PlaylistPayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed playlist payload")
                if (payload.op !in PlaylistPayload.ops) {
                    return CoupleValidation.Invalid("unknown playlist operation")
                }
                if (payload.lamport < 0L) {
                    return CoupleValidation.Invalid("invalid playlist clock")
                }
                if (payload.actor.isBlank() || payload.actor.length > 80) {
                    return CoupleValidation.Invalid("invalid playlist actor")
                }
                if (payload.track != null && !isValidPlaylistItem(payload.track)) {
                    return CoupleValidation.Invalid("invalid playlist track")
                }
                if (payload.items.size > CoupleProtocol.MAX_PLAYLIST_ITEMS ||
                    payload.order.size > CoupleProtocol.MAX_PLAYLIST_ITEMS
                ) {
                    return CoupleValidation.Invalid("playlist event too large")
                }
                if (payload.items.any { !isValidPlaylistItem(it) }) {
                    return CoupleValidation.Invalid("invalid playlist item")
                }
                CoupleValidation.Valid(envelope)
            }

            CoupleProtocol.EVENT_PRESENCE -> {
                val payload = CoupleJson.decode<PresencePayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed presence payload")
                if (payload.activity.length > 40) {
                    return CoupleValidation.Invalid("invalid activity")
                }
                if (payload.displayName.length > 40) {
                    return CoupleValidation.Invalid("invalid display name")
                }
                CoupleValidation.Valid(envelope)
            }

            CoupleProtocol.EVENT_CLOCK_PING, CoupleProtocol.EVENT_CLOCK_PONG -> {
                val payload = CoupleJson.decode<ClockPayload>(envelope.payload)
                    ?: return CoupleValidation.Invalid("malformed clock payload")
                if (payload.t0 < 0L || payload.t1 < 0L || payload.t2 < 0L) {
                    return CoupleValidation.Invalid("invalid clock sample")
                }
                CoupleValidation.Valid(envelope)
            }

            else -> CoupleValidation.Invalid("unhandled event type")
        }
    }

    fun sanitizeChatBody(raw: String): String =
        raw
            .replace("\r\n", "\n")
            .filter { ch ->
                ch == '\n' || ch == '\t' || (ch.code in 32..0x10FFFF)
            }
            .trim()
            .take(CoupleProtocol.MAX_CHAT_BODY)

    private fun isValidRemoteTrack(track: RemoteTrackPayload): Boolean =
        track.id.isNotBlank() &&
            track.id.length <= 160 &&
            track.title.length <= 300 &&
            track.artist.length <= 300 &&
            track.artworkUrl.length <= 2_000 &&
            track.durationSeconds in 0..86_400L

    private fun isValidPlaylistItem(item: PlaylistItemPayload): Boolean =
        item.trackId.isNotBlank() &&
            item.trackId.length <= 160 &&
            item.title.length <= 300 &&
            item.artist.length <= 300 &&
            item.artworkUrl.length <= 2_000 &&
            item.durationSeconds in 0..86_400L &&
            item.position in -1..CoupleProtocol.MAX_PLAYLIST_ITEMS &&
            item.lamport >= 0L &&
            item.updatedBy.length <= 80
}

// ---------------------------------------------------------------------------------------------
// Replay / duplicate detection
// ---------------------------------------------------------------------------------------------

/**
 * Per-channel sequence tracking plus a bounded LRU of seen event ids.
 *
 * Chat and playlist sequence numbers are seeded from the persisted peer record, so a
 * reconnect cannot replay an old message. Session-bound channels start at zero for every
 * new authenticated WebRTC session.
 */
class CoupleSequenceTracker {
    private val lastSequence = mutableMapOf<String, Long>()
    private val seen = object : LinkedHashMap<String, Unit>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_SEEN_IDS
    }

    fun seed(channel: String, sequence: Long) {
        if (sequence > 0L) {
            lastSequence[channel] = maxOf(lastSequence[channel] ?: 0L, sequence)
        }
    }

    fun lastAccepted(channel: String): Long = lastSequence[channel] ?: 0L

    fun isDuplicate(eventId: String): Boolean = seen.containsKey(eventId)

    /**
     * @return true when the frame is new and in order; false for a duplicate or a replay.
     */
    fun accept(channel: String, sequence: Long, eventId: String): Boolean {
        if (seen.containsKey(eventId)) return false
        val last = lastSequence[channel] ?: 0L
        if (sequence <= last) return false
        lastSequence[channel] = sequence
        seen[eventId] = Unit
        return true
    }

    fun remember(eventId: String) {
        seen[eventId] = Unit
    }

    fun resetSessionChannels() {
        lastSequence.remove(CoupleChannel.CONTROL.wireName)
        lastSequence.remove(CoupleChannel.PRESENCE.wireName)
        lastSequence.remove(CoupleChannel.SYNC.wireName)
    }

    fun reset() {
        lastSequence.clear()
        seen.clear()
    }

    private companion object {
        const val MAX_SEEN_IDS = 1_024
    }
}

object CoupleEventIds {
    private val random = SecureRandom()

    fun newEventId(): String = UUID.randomUUID().toString()

    fun newNonce(): String = CoupleBase64.encode(ByteArray(16).also(random::nextBytes))

    fun newPairingId(): String = CoupleBase64.encode(ByteArray(16).also(random::nextBytes))

    fun newSessionId(): String = CoupleBase64.encode(ByteArray(16).also(random::nextBytes))
}
