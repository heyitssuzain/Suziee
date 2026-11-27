package com.example.couple

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * The local, non-exportable Couple Space identity.
 *
 * The private key never leaves Android Keystore. [publicKey] is the X.509/SPKI encoding that
 * is safe to exchange over a QR code or DataChannel.
 */
data class CoupleIdentity(
    val deviceId: String,
    val publicKey: ByteArray,
    val fingerprint: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoupleIdentity) return false
        return deviceId == other.deviceId && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * deviceId.hashCode() + publicKey.contentHashCode()
}

interface CoupleIdentityStore {
    fun getOrCreate(): CoupleIdentity
    fun sign(data: ByteArray): ByteArray
    fun reset()
}

/**
 * Android Keystore-backed identity: an EC P-256 key with SHA-256 ECDSA signatures.
 *
 * Keystore keys are non-exportable and hardware-backed where the device supports it. The
 * alias is stable across app restarts, so pairing survives process death and reboots.
 */
class AndroidKeystoreIdentityStore(
    @Suppress("unused") private val context: Context
) : CoupleIdentityStore {

    @Synchronized
    override fun getOrCreate(): CoupleIdentity {
        val keyPair = ensureKeyPair()
        val encoded = keyPair.public.encoded
        return CoupleIdentity(
            deviceId = CoupleCrypto.deviceId(encoded),
            publicKey = encoded,
            fingerprint = CoupleCrypto.fingerprint(encoded)
        )
    }

    @Synchronized
    override fun sign(data: ByteArray): ByteArray {
        val privateKey = ensureKeyPair().private
        return CoupleCrypto.sign(privateKey, data)
    }

    @Synchronized
    override fun reset() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    @Synchronized
    private fun ensureKeyPair(): KeyPair {
        val store = keyStore()
        val existing = store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "suziee.couple.identity.v1"
    }
}

/**
 * A non-Android identity used by unit tests and previews. It uses the same EC P-256 /
 * SHA256withECDSA primitives as the Keystore implementation, so protocol tests exercise the
 * real signature format rather than a stub.
 */
class InMemoryIdentityStore : CoupleIdentityStore {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    override fun getOrCreate(): CoupleIdentity {
        val encoded = keyPair.public.encoded
        return CoupleIdentity(
            deviceId = CoupleCrypto.deviceId(encoded),
            publicKey = encoded,
            fingerprint = CoupleCrypto.fingerprint(encoded)
        )
    }

    override fun sign(data: ByteArray): ByteArray = CoupleCrypto.sign(keyPair.private, data)

    override fun reset() = Unit
}

object CoupleCrypto {

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(text: String): String = hex(sha256(text.toByteArray(Charsets.UTF_8)))

    /** Stable 128-bit device identifier derived from the public key. */
    fun deviceId(publicKey: ByteArray): String =
        hex(sha256(publicKey).copyOf(16))

    /** Human-comparable fingerprint; 8 bytes rendered as four groups of four hex digits. */
    fun fingerprint(publicKey: ByteArray): String {
        val digest = sha256(publicKey).copyOf(8)
        val full = hex(digest).uppercase()
        return full.chunked(4).joinToString(" ")
    }

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val key: PublicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

    fun publicKeyFromBase64(encoded: String): ByteArray? =
        CoupleBase64.decode(encoded)?.takeIf { it.size in 32..2_048 }

    fun publicKeyBase64(publicKey: ByteArray): String = CoupleBase64.encode(publicKey)

    fun sanitizeDisplayName(raw: String): String =
        raw.replace('\u001F', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(40)

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Canonical byte strings that are signed. Separators are unit separators, and every field is
 * either base64/hex/numeric or a display name that has had separators stripped, so two
 * different pairings cannot produce the same transcript.
 */
object CoupleTranscripts {
    private const val SEP = "\u001F"

    fun invite(
        pairingId: String,
        deviceId: String,
        publicKeyBase64: String,
        nonce: String,
        createdAt: Long,
        displayName: String
    ): ByteArray = listOf(
        "suziee-couple-invite-v1",
        pairingId,
        deviceId,
        publicKeyBase64,
        nonce,
        createdAt.toString(),
        CoupleCrypto.sanitizeDisplayName(displayName)
    ).joinToString(SEP).toByteArray(Charsets.UTF_8)

    fun accept(
        pairingId: String,
        inviterDeviceId: String,
        inviterPublicKeyBase64: String,
        joinerDeviceId: String,
        joinerPublicKeyBase64: String,
        inviterNonce: String,
        joinerNonce: String,
        createdAt: Long
    ): ByteArray = listOf(
        "suziee-couple-accept-v1",
        pairingId,
        inviterDeviceId,
        inviterPublicKeyBase64,
        joinerDeviceId,
        joinerPublicKeyBase64,
        inviterNonce,
        joinerNonce,
        createdAt.toString()
    ).joinToString(SEP).toByteArray(Charsets.UTF_8)

    fun confirm(
        pairingId: String,
        inviterDeviceId: String,
        inviterPublicKeyBase64: String,
        joinerDeviceId: String,
        joinerPublicKeyBase64: String,
        joinerNonce: String,
        createdAt: Long
    ): ByteArray = listOf(
        "suziee-couple-confirm-v1",
        pairingId,
        inviterDeviceId,
        inviterPublicKeyBase64,
        joinerDeviceId,
        joinerPublicKeyBase64,
        joinerNonce,
        createdAt.toString()
    ).joinToString(SEP).toByteArray(Charsets.UTF_8)

    fun auth(
        pairingId: String,
        session: String,
        deviceId: String,
        publicKeyBase64: String,
        nonce: String
    ): ByteArray = listOf(
        "suziee-couple-auth-v1",
        pairingId,
        session,
        deviceId,
        publicKeyBase64,
        nonce
    ).joinToString(SEP).toByteArray(Charsets.UTF_8)

    /**
     * SDP is hashed rather than embedded: SDP is large and contains newlines, while the hash
     * still binds the exact offer/answer to the pairing and session.
     */
    fun signal(
        kind: String,
        pairingId: String,
        session: String,
        from: String,
        sdp: String
    ): ByteArray = listOf(
        "suziee-couple-signal-v1",
        kind,
        pairingId,
        session,
        from,
        CoupleCrypto.sha256Hex(sdp)
    ).joinToString(SEP).toByteArray(Charsets.UTF_8)
}
