package com.example.couple

import com.example.data.local.entities.CoupleMessageEntity
import com.example.data.local.entities.CouplePeerEntity
import com.example.data.local.entities.CoupleSharedTrackEntity
import com.example.domain.model.Track

enum class CoupleConnectionState {
    OFFLINE,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

data class CouplePeer(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val pairingId: String,
    val pairedAt: Long,
    val lastConnectedAt: Long?,
    val lastSeenAt: Long,
    val status: String
) {
    val isPaired: Boolean get() = status == CouplePeerEntity.STATUS_PAIRED
}

data class CoupleChatMessage(
    val eventId: String,
    val body: String,
    val outgoing: Boolean,
    val createdAt: Long,
    val deliveryState: String,
    val sequence: Long
)

data class SharedTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val artworkUrl: String,
    val position: Int,
    val lamport: Long,
    val updatedBy: String,
    val deleted: Boolean
) {
    fun toTrack(): Track = Track(
        id = trackId,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        artworkUrl = artworkUrl
    )
}

data class CoupleNowPlaying(
    val track: Track,
    val positionMs: Long,
    val isPlaying: Boolean,
    val synced: Boolean,
    val peerListening: Boolean,
    val unavailableReason: String? = null
)

sealed interface CouplePairingState {
    data object Idle : CouplePairingState

    data class InviteCreated(
        val code: String,
        val displayName: String
    ) : CouplePairingState

    /** The joiner scanned an invitation and now needs to send the reply code back. */
    data class InviteScanned(
        val replyCode: String,
        val peerName: String,
        val peerFingerprint: String
    ) : CouplePairingState

    /** The inviter scanned a reply and now needs to send the confirmation code back. */
    data class ReplyScanned(
        val confirmationCode: String,
        val peerName: String,
        val peerFingerprint: String
    ) : CouplePairingState

    data class Completed(val peer: CouplePeer) : CouplePairingState

    data class Failed(val message: String) : CouplePairingState
}

data class CoupleSignalState(
    val localCode: String? = null,
    val localKind: String? = null,
    val waitingForPeer: Boolean = false,
    val hint: String? = null
)

fun CouplePeerEntity.toDomain(): CouplePeer = CouplePeer(
    deviceId = peerId,
    displayName = displayName,
    fingerprint = fingerprint,
    pairingId = pairingId,
    pairedAt = pairedAt,
    lastConnectedAt = lastConnectedAt,
    lastSeenAt = lastSeenAt,
    status = status
)

fun CoupleMessageEntity.toDomain(): CoupleChatMessage = CoupleChatMessage(
    eventId = eventId,
    body = body,
    outgoing = direction == CoupleMessageEntity.DIRECTION_OUT,
    createdAt = createdAt,
    deliveryState = deliveryState,
    sequence = sequence
)

fun CoupleSharedTrackEntity.toDomain(): SharedTrack = SharedTrack(
    trackId = trackId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    artworkUrl = artworkUrl,
    position = position,
    lamport = lamport,
    updatedBy = updatedBy,
    deleted = deleted
)
