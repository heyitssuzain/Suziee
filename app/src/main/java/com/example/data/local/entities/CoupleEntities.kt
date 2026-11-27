package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent, trusted pairing record.
 *
 * Pairing is deliberately separate from the transient WebRTC connection: closing the app,
 * losing Wi-Fi or rebooting the phone must never require pairing again. The private half of
 * the identity lives in Android Keystore (never in this table); only the remote public key
 * is stored here.
 */
@Entity(tableName = "couple_peer")
data class CouplePeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val publicKey: String,
    val pairingId: String,
    val pairedAt: Long,
    val lastConnectedAt: Long? = null,
    val status: String = STATUS_PAIRED,
    val fingerprint: String = "",
    val inviteSignature: String = "",
    val acceptSignature: String = "",
    val confirmSignature: String = "",
    val lastRemoteChatSeq: Long = 0L,
    val lastRemotePlaylistSeq: Long = 0L,
    val localChatSeq: Long = 0L,
    val localPlaylistSeq: Long = 0L,
    val playlistOrderLamport: Long = 0L,
    val playlistOrderActor: String = "",
    val lastSeenAt: Long = 0L
) {
    companion object {
        const val STATUS_PAIRED = "PAIRED"
        const val STATUS_REVOKED = "REVOKED"
    }
}

/**
 * One chat message, inbound or outbound.
 *
 * Outbound rows are inserted as [DeliveryState.PENDING] before any network call. They become
 * [DeliveryState.SENT] only after the DataChannel accepted the bytes, and
 * [DeliveryState.DELIVERED] only after the peer acknowledged the event id. Saving a message
 * locally never marks it delivered.
 */
@Entity(
    tableName = "couple_message",
    indices = [Index("peerId"), Index("deliveryState")]
)
data class CoupleMessageEntity(
    @PrimaryKey val eventId: String,
    val peerId: String,
    val direction: String,
    val body: String,
    val createdAt: Long,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val deliveryState: String = DeliveryState.PENDING,
    val sequence: Long,
    val error: String? = null
) {
    companion object {
        const val DIRECTION_OUT = "OUT"
        const val DIRECTION_IN = "IN"
    }
}

object DeliveryState {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
}

/**
 * Shared queue item. This is intentionally a different table from the personal playlist
 * tables, so a Couple Space change can never corrupt a user's own library.
 */
@Entity(
    tableName = "couple_shared_track",
    primaryKeys = ["peerId", "trackId"],
    indices = [Index("peerId")]
)
data class CoupleSharedTrackEntity(
    val peerId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val artworkUrl: String,
    val position: Int,
    val lamport: Long,
    val updatedBy: String,
    val updatedAt: Long,
    val deleted: Boolean = false
)

/** A bounded, persisted replay-detection log for chat and playlist events. */
@Entity(
    tableName = "couple_event_log",
    indices = [Index("receivedAt")]
)
data class CoupleEventEntity(
    @PrimaryKey val eventId: String,
    val peerId: String,
    val kind: String,
    val receivedAt: Long
)

/**
 * Durable progress for a pairing that was interrupted by process death. It contains only
 * public keys and one-time nonces, never private key material.
 */
@Entity(tableName = "couple_pending_pairing")
data class CouplePendingPairingEntity(
    @PrimaryKey val id: Int = 1,
    val pairingId: String,
    val role: String,
    val localDisplayName: String,
    val remoteDeviceId: String,
    val remotePublicKey: String,
    val remoteDisplayName: String,
    val localNonce: String,
    val remoteNonce: String,
    val createdAt: Long,
    val stage: String
) {
    companion object {
        const val ROLE_INVITER = "INVITER"
        const val ROLE_JOINER = "JOINER"
        const val STAGE_INVITE_CREATED = "INVITE_CREATED"
        const val STAGE_ACCEPT_CREATED = "ACCEPT_CREATED"
        const val STAGE_ACCEPT_VERIFIED = "ACCEPT_VERIFIED"
    }
}
