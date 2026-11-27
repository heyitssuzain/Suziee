package com.example.couple

import kotlin.math.abs

/**
 * Out-of-band pairing / signalling payloads.
 *
 * These are exchanged as QR codes, clipboard text or `suziee://couple/...` deep links. The
 * payloads are authenticated with the sender's Keystore identity, so a QR code alone cannot
 * silently substitute another device's public key.
 */
data class InvitePayload(
    val v: Int = 1,
    val kind: String = CoupleCodec.KIND_INVITE,
    val pairingId: String = "",
    val displayName: String = "",
    val deviceId: String = "",
    val publicKey: String = "",
    val nonce: String = "",
    val createdAt: Long = 0L,
    val signature: String = ""
)

data class AcceptPayload(
    val v: Int = 1,
    val kind: String = CoupleCodec.KIND_ACCEPT,
    val pairingId: String = "",
    val displayName: String = "",
    val deviceId: String = "",
    val publicKey: String = "",
    val inviterDeviceId: String = "",
    val inviterPublicKey: String = "",
    val inviterNonce: String = "",
    val joinerNonce: String = "",
    val createdAt: Long = 0L,
    val signature: String = ""
)

data class ConfirmPayload(
    val v: Int = 1,
    val kind: String = CoupleCodec.KIND_CONFIRM,
    val pairingId: String = "",
    val displayName: String = "",
    val deviceId: String = "",
    val publicKey: String = "",
    val joinerDeviceId: String = "",
    val joinerPublicKey: String = "",
    val joinerNonce: String = "",
    val createdAt: Long = 0L,
    val signature: String = ""
)

data class ConnectionPayload(
    val v: Int = 1,
    val kind: String = CoupleCodec.KIND_OFFER,
    val pairingId: String = "",
    val session: String = "",
    val from: String = "",
    val publicKey: String = "",
    val sdp: String = "",
    val createdAt: Long = 0L,
    val signature: String = ""
)

object CoupleSignaling {
    const val PAIRING_WINDOW_MS = 15L * 60 * 1000
    const val SIGNAL_WINDOW_MS = 15L * 60 * 1000
    const val MAX_SDP_CHARS = 64 * 1024

    fun encodeInvite(payload: InvitePayload): String =
        CoupleCodec.encodeLink(CoupleCodec.KIND_INVITE, CoupleJson.encode(payload))

    fun encodeAccept(payload: AcceptPayload): String =
        CoupleCodec.encodeLink(CoupleCodec.KIND_ACCEPT, CoupleJson.encode(payload))

    fun encodeConfirm(payload: ConfirmPayload): String =
        CoupleCodec.encodeLink(CoupleCodec.KIND_CONFIRM, CoupleJson.encode(payload))

    fun encodeConnection(payload: ConnectionPayload): String =
        CoupleCodec.encodeLink(payload.kind, CoupleJson.encode(payload))

    fun decodeInvite(raw: String): InvitePayload? = decode(raw, CoupleCodec.KIND_INVITE)

    fun decodeAccept(raw: String): AcceptPayload? = decode(raw, CoupleCodec.KIND_ACCEPT)

    fun decodeConfirm(raw: String): ConfirmPayload? = decode(raw, CoupleCodec.KIND_CONFIRM)

    fun decodeConnection(raw: String): ConnectionPayload? {
        val link = CoupleCodec.decodeLink(raw) ?: return null
        if (link.kind != CoupleCodec.KIND_OFFER && link.kind != CoupleCodec.KIND_ANSWER) {
            return null
        }
        return CoupleJson.decode(link.payloadJson)
    }

    private inline fun <reified T : Any> decode(raw: String, kind: String): T? {
        val link = CoupleCodec.decodeLink(raw) ?: return null
        if (link.kind != kind) return null
        return CoupleJson.decode(link.payloadJson)
    }

    /** @return null when valid, otherwise a user-safe reason. */
    fun verifyInvite(payload: InvitePayload, now: Long): String? {
        if (payload.v != 1 || payload.kind != CoupleCodec.KIND_INVITE) return "Unsupported invitation"
        if (payload.pairingId.isBlank() || payload.nonce.isBlank()) return "Incomplete invitation"
        if (payload.createdAt <= 0L || abs(now - payload.createdAt) > PAIRING_WINDOW_MS) {
            return "This invitation has expired. Create a fresh one."
        }
        val key = CoupleCrypto.publicKeyFromBase64(payload.publicKey)
            ?: return "Invalid identity key"
        if (CoupleCrypto.deviceId(key) != payload.deviceId) return "Identity does not match its key"
        val signature = CoupleBase64.decode(payload.signature) ?: return "Invalid signature"
        val transcript = CoupleTranscripts.invite(
            pairingId = payload.pairingId,
            deviceId = payload.deviceId,
            publicKeyBase64 = payload.publicKey,
            nonce = payload.nonce,
            createdAt = payload.createdAt,
            displayName = payload.displayName
        )
        if (!CoupleCrypto.verify(key, transcript, signature)) return "Invitation signature rejected"
        return null
    }

    fun verifyAccept(
        payload: AcceptPayload,
        expectedPairingId: String,
        expectedInviterDeviceId: String,
        expectedInviterPublicKey: String,
        expectedInviterNonce: String,
        now: Long
    ): String? {
        if (payload.v != 1 || payload.kind != CoupleCodec.KIND_ACCEPT) return "Unsupported reply"
        if (payload.pairingId != expectedPairingId) return "Reply belongs to another invitation"
        if (payload.inviterDeviceId != expectedInviterDeviceId) return "Reply targets another device"
        if (payload.inviterPublicKey != expectedInviterPublicKey) return "Reply targets another identity"
        if (payload.inviterNonce != expectedInviterNonce) return "Reply reuses an old invitation"
        if (payload.joinerNonce.isBlank()) return "Incomplete reply"
        if (payload.createdAt <= 0L || abs(now - payload.createdAt) > PAIRING_WINDOW_MS) {
            return "This reply has expired. Scan a fresh invitation."
        }
        val key = CoupleCrypto.publicKeyFromBase64(payload.publicKey)
            ?: return "Invalid identity key"
        if (CoupleCrypto.deviceId(key) != payload.deviceId) return "Identity does not match its key"
        val signature = CoupleBase64.decode(payload.signature) ?: return "Invalid signature"
        val transcript = CoupleTranscripts.accept(
            pairingId = payload.pairingId,
            inviterDeviceId = payload.inviterDeviceId,
            inviterPublicKeyBase64 = payload.inviterPublicKey,
            joinerDeviceId = payload.deviceId,
            joinerPublicKeyBase64 = payload.publicKey,
            inviterNonce = payload.inviterNonce,
            joinerNonce = payload.joinerNonce,
            createdAt = payload.createdAt
        )
        if (!CoupleCrypto.verify(key, transcript, signature)) return "Reply signature rejected"
        return null
    }

    fun verifyConfirm(
        payload: ConfirmPayload,
        expectedPairingId: String,
        expectedInviterDeviceId: String,
        expectedInviterPublicKey: String,
        expectedJoinerDeviceId: String,
        expectedJoinerPublicKey: String,
        expectedJoinerNonce: String,
        now: Long
    ): String? {
        if (payload.v != 1 || payload.kind != CoupleCodec.KIND_CONFIRM) return "Unsupported confirmation"
        if (payload.pairingId != expectedPairingId) return "Confirmation belongs to another pairing"
        if (payload.deviceId != expectedInviterDeviceId) return "Confirmation came from another device"
        if (payload.publicKey != expectedInviterPublicKey) return "Confirmation identity mismatch"
        if (payload.joinerDeviceId != expectedJoinerDeviceId) return "Confirmation targets another device"
        if (payload.joinerPublicKey != expectedJoinerPublicKey) return "Confirmation targets another identity"
        if (payload.joinerNonce != expectedJoinerNonce) return "Confirmation reuses an old reply"
        if (payload.createdAt <= 0L || abs(now - payload.createdAt) > PAIRING_WINDOW_MS) {
            return "This confirmation has expired. Pair again."
        }
        val key = CoupleCrypto.publicKeyFromBase64(payload.publicKey)
            ?: return "Invalid identity key"
        if (CoupleCrypto.deviceId(key) != payload.deviceId) return "Identity does not match its key"
        val signature = CoupleBase64.decode(payload.signature) ?: return "Invalid signature"
        val transcript = CoupleTranscripts.confirm(
            pairingId = payload.pairingId,
            inviterDeviceId = payload.deviceId,
            inviterPublicKeyBase64 = payload.publicKey,
            joinerDeviceId = payload.joinerDeviceId,
            joinerPublicKeyBase64 = payload.joinerPublicKey,
            joinerNonce = payload.joinerNonce,
            createdAt = payload.createdAt
        )
        if (!CoupleCrypto.verify(key, transcript, signature)) return "Confirmation signature rejected"
        return null
    }

    fun verifyConnection(
        payload: ConnectionPayload,
        expectedPairingId: String,
        expectedPeerId: String,
        expectedPeerPublicKey: String,
        expectedSession: String?,
        now: Long
    ): String? {
        if (payload.v != 1) return "Unsupported signalling version"
        if (payload.kind != CoupleCodec.KIND_OFFER && payload.kind != CoupleCodec.KIND_ANSWER) {
            return "Unsupported signalling kind"
        }
        if (payload.pairingId != expectedPairingId) return "Signalling belongs to another pairing"
        if (payload.from != expectedPeerId) return "Signalling came from another device"
        if (payload.publicKey != expectedPeerPublicKey) return "Signalling identity mismatch"
        if (expectedSession != null && payload.session != expectedSession) {
            return "Signalling belongs to another session"
        }
        if (payload.session.isBlank()) return "Missing signalling session"
        if (payload.sdp.isBlank() || payload.sdp.length > MAX_SDP_CHARS) return "Invalid SDP"
        if (payload.createdAt <= 0L || abs(now - payload.createdAt) > SIGNAL_WINDOW_MS) {
            return "This signalling code has expired. Create a fresh one."
        }
        val key = CoupleCrypto.publicKeyFromBase64(payload.publicKey)
            ?: return "Invalid identity key"
        if (CoupleCrypto.deviceId(key) != payload.from) return "Identity does not match its key"
        val signature = CoupleBase64.decode(payload.signature) ?: return "Invalid signature"
        val transcript = CoupleTranscripts.signal(
            kind = payload.kind,
            pairingId = payload.pairingId,
            session = payload.session,
            from = payload.from,
            sdp = payload.sdp
        )
        if (!CoupleCrypto.verify(key, transcript, signature)) return "Signalling signature rejected"
        return null
    }
}
