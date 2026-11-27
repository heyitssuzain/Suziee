package com.example.couple

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleCryptoSignalingTest {

    private val now = System.currentTimeMillis()

    private fun sign(store: InMemoryIdentityStore, transcript: ByteArray): String =
        CoupleBase64.encode(store.sign(transcript))

    @Test
    fun inMemoryIdentityMatchesTheRealEcPrimitives() {
        val store = InMemoryIdentityStore()
        val identity = store.getOrCreate()
        assertEquals(32, identity.deviceId.length)
        assertTrue(identity.deviceId.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(4, identity.fingerprint.split(" ").size)

        val data = "suziee".toByteArray()
        val signature = store.sign(data)
        assertTrue(CoupleCrypto.verify(identity.publicKey, data, signature))
        assertFalse(CoupleCrypto.verify(identity.publicKey, "tampered".toByteArray(), signature))
        assertArrayEquals(
            identity.publicKey,
            CoupleCrypto.publicKeyFromBase64(identity.publicKeyBase64())
        )
    }

    @Test
    fun inviteRoundTripsAndVerifies() {
        val inviterStore = InMemoryIdentityStore()
        val inviter = inviterStore.getOrCreate()
        val pairingId = CoupleEventIds.newPairingId()
        val nonce = CoupleEventIds.newNonce()
        val invite = InvitePayload(
            pairingId = pairingId,
            displayName = "Suziee",
            deviceId = inviter.deviceId,
            publicKey = CoupleBase64.encode(inviter.publicKey),
            nonce = nonce,
            createdAt = now,
            signature = sign(
                inviterStore,
                CoupleTranscripts.invite(
                    pairingId = pairingId,
                    deviceId = inviter.deviceId,
                    publicKeyBase64 = CoupleBase64.encode(inviter.publicKey),
                    nonce = nonce,
                    createdAt = now,
                    displayName = "Suziee"
                )
            )
        )

        assertNull(CoupleSignaling.verifyInvite(invite, now))
        val decoded = CoupleSignaling.decodeInvite(CoupleSignaling.encodeInvite(invite))
        assertEquals(invite, decoded)

        val tampered = invite.copy(displayName = "Mallory")
        assertNotNull(CoupleSignaling.verifyInvite(tampered, now))
        assertNotNull(CoupleSignaling.verifyInvite(invite, now + CoupleSignaling.PAIRING_WINDOW_MS + 1))
    }

    @Test
    fun acceptIsBoundToTheInvitation() {
        val inviterStore = InMemoryIdentityStore()
        val inviter = inviterStore.getOrCreate()
        val joinerStore = InMemoryIdentityStore()
        val joiner = joinerStore.getOrCreate()
        val pairingId = CoupleEventIds.newPairingId()
        val inviterNonce = CoupleEventIds.newNonce()
        val joinerNonce = CoupleEventIds.newNonce()
        val inviterKey = CoupleBase64.encode(inviter.publicKey)
        val joinerKey = CoupleBase64.encode(joiner.publicKey)

        val accept = AcceptPayload(
            pairingId = pairingId,
            displayName = "Joiner",
            deviceId = joiner.deviceId,
            publicKey = joinerKey,
            inviterDeviceId = inviter.deviceId,
            inviterPublicKey = inviterKey,
            inviterNonce = inviterNonce,
            joinerNonce = joinerNonce,
            createdAt = now,
            signature = sign(
                joinerStore,
                CoupleTranscripts.accept(
                    pairingId = pairingId,
                    inviterDeviceId = inviter.deviceId,
                    inviterPublicKeyBase64 = inviterKey,
                    joinerDeviceId = joiner.deviceId,
                    joinerPublicKeyBase64 = joinerKey,
                    inviterNonce = inviterNonce,
                    joinerNonce = joinerNonce,
                    createdAt = now
                )
            )
        )

        assertNull(
            CoupleSignaling.verifyAccept(
                payload = accept,
                expectedPairingId = pairingId,
                expectedInviterDeviceId = inviter.deviceId,
                expectedInviterPublicKey = inviterKey,
                expectedInviterNonce = inviterNonce,
                now = now
            )
        )
        assertNotNull(
            CoupleSignaling.verifyAccept(
                payload = accept,
                expectedPairingId = pairingId,
                expectedInviterDeviceId = inviter.deviceId,
                expectedInviterPublicKey = inviterKey,
                expectedInviterNonce = "different-nonce",
                now = now
            )
        )
        assertNotNull(
            CoupleSignaling.verifyAccept(
                payload = accept.copy(joinerNonce = ""),
                expectedPairingId = pairingId,
                expectedInviterDeviceId = inviter.deviceId,
                expectedInviterPublicKey = inviterKey,
                expectedInviterNonce = inviterNonce,
                now = now
            )
        )
    }

    @Test
    fun confirmBindsBothIdentities() {
        val inviterStore = InMemoryIdentityStore()
        val inviter = inviterStore.getOrCreate()
        val joinerStore = InMemoryIdentityStore()
        val joiner = joinerStore.getOrCreate()
        val pairingId = CoupleEventIds.newPairingId()
        val joinerNonce = CoupleEventIds.newNonce()
        val inviterKey = CoupleBase64.encode(inviter.publicKey)
        val joinerKey = CoupleBase64.encode(joiner.publicKey)

        val confirm = ConfirmPayload(
            pairingId = pairingId,
            displayName = "Suziee",
            deviceId = inviter.deviceId,
            publicKey = inviterKey,
            joinerDeviceId = joiner.deviceId,
            joinerPublicKey = joinerKey,
            joinerNonce = joinerNonce,
            createdAt = now,
            signature = sign(
                inviterStore,
                CoupleTranscripts.confirm(
                    pairingId = pairingId,
                    inviterDeviceId = inviter.deviceId,
                    inviterPublicKeyBase64 = inviterKey,
                    joinerDeviceId = joiner.deviceId,
                    joinerPublicKeyBase64 = joinerKey,
                    joinerNonce = joinerNonce,
                    createdAt = now
                )
            )
        )

        assertNull(
            CoupleSignaling.verifyConfirm(
                payload = confirm,
                expectedPairingId = pairingId,
                expectedInviterDeviceId = inviter.deviceId,
                expectedInviterPublicKey = inviterKey,
                expectedJoinerDeviceId = joiner.deviceId,
                expectedJoinerPublicKey = joinerKey,
                expectedJoinerNonce = joinerNonce,
                now = now
            )
        )
        assertNotNull(
            CoupleSignaling.verifyConfirm(
                payload = confirm,
                expectedPairingId = pairingId,
                expectedInviterDeviceId = inviter.deviceId,
                expectedInviterPublicKey = inviterKey,
                expectedJoinerDeviceId = joiner.deviceId,
                expectedJoinerPublicKey = joinerKey,
                expectedJoinerNonce = "stale",
                now = now
            )
        )
    }

    @Test
    fun connectionCodeBindsSdpSessionAndPeer() {
        val inviterStore = InMemoryIdentityStore()
        val inviter = inviterStore.getOrCreate()
        val pairingId = CoupleEventIds.newPairingId()
        val session = CoupleEventIds.newSessionId()
        val sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
        val publicKey = CoupleBase64.encode(inviter.publicKey)

        val offer = ConnectionPayload(
            kind = CoupleCodec.KIND_OFFER,
            pairingId = pairingId,
            session = session,
            from = inviter.deviceId,
            publicKey = publicKey,
            sdp = sdp,
            createdAt = now,
            signature = sign(
                inviterStore,
                CoupleTranscripts.signal(
                    kind = CoupleCodec.KIND_OFFER,
                    pairingId = pairingId,
                    session = session,
                    from = inviter.deviceId,
                    sdp = sdp
                )
            )
        )

        assertNull(
            CoupleSignaling.verifyConnection(
                payload = offer,
                expectedPairingId = pairingId,
                expectedPeerId = inviter.deviceId,
                expectedPeerPublicKey = publicKey,
                expectedSession = null,
                now = now
            )
        )
        assertNotNull(
            CoupleSignaling.verifyConnection(
                payload = offer.copy(sdp = sdp + "tampered"),
                expectedPairingId = pairingId,
                expectedPeerId = inviter.deviceId,
                expectedPeerPublicKey = publicKey,
                expectedSession = null,
                now = now
            )
        )
        assertNotNull(
            CoupleSignaling.verifyConnection(
                payload = offer,
                expectedPairingId = pairingId,
                expectedPeerId = inviter.deviceId,
                expectedPeerPublicKey = publicKey,
                expectedSession = "another-session",
                now = now
            )
        )
        assertNotNull(
            CoupleSignaling.verifyConnection(
                payload = offer,
                expectedPairingId = pairingId,
                expectedPeerId = "another-peer",
                expectedPeerPublicKey = publicKey,
                expectedSession = null,
                now = now
            )
        )
    }

    private fun CoupleIdentity.publicKeyBase64(): String = CoupleBase64.encode(publicKey)
}
