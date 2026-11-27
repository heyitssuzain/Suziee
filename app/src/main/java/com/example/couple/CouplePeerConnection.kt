package com.example.couple

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional TURN relay. Supplied by the caller; no credentials are bundled with the app. */
data class CoupleTurnConfig(
    val urls: List<String>,
    val username: String,
    val password: String
)

/**
 * A single, data-only WebRTC connection between the two paired phones.
 *
 * There is deliberately no audio/video track: the phones keep using Suziee's own player and
 * only exchange small control frames. WebRTC gives us DTLS-encrypted, congestion-controlled
 * SCTP DataChannels, while the app-level signatures in [CoupleCrypto] bind the connection to
 * the pairing identity.
 *
 * ICE is non-trickle on purpose. The whole offer/answer (including candidates) is signed and
 * exchanged as one code, so the signalling path can be a QR code or a deep link instead of a
 * server. Gathering is bounded by [ICE_GATHERING_TIMEOUT_MS]; on a slow network the best
 * gathered candidates are still used rather than hanging forever.
 */
class CouplePeerConnection(
    context: Context,
    private val listener: Listener,
    private val turn: CoupleTurnConfig? = null
) {

    interface Listener {
        fun onTransportConnected()
        fun onTransportDisconnected()
        fun onTransportFailed(reason: String)
        fun onTransportClosed()
        fun onChannelOpen(channel: CoupleChannel)
        fun onMessage(channel: CoupleChannel, text: String)
    }

    private val factory: PeerConnectionFactory = sharedFactory(context.applicationContext)
    private val channels = ConcurrentHashMap<CoupleChannel, DataChannel>()
    private val closed = AtomicBoolean(false)
    private var iceGathering: CompletableDeferred<Unit>? = null

    private val peerConnection: PeerConnection

    // ------------------------------------------------------------------------------------
    // Offer / answer
    // ------------------------------------------------------------------------------------

    /**
     * Creates the five DataChannels and returns a complete offer SDP. The caller signs and
     * encodes this string; it is not sent anywhere by this class.
     */
    suspend fun createOffer(): String {
        createChannels()
        iceGathering = CompletableDeferred()
        val offer = awaitCreateOffer()
        awaitSetLocalDescription(offer)
        awaitIceGathering()
        return localDescription() ?: throw IllegalStateException("Offer SDP was empty")
    }

    /**
     * Applies a remote offer and returns the complete answer SDP. The remote offer creates
     * the DataChannels on this side through [PeerConnection.Observer.onDataChannel].
     */
    suspend fun createAnswer(offerSdp: String): String {
        iceGathering = CompletableDeferred()
        awaitSetRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        val answer = awaitCreateAnswer()
        awaitSetLocalDescription(answer)
        awaitIceGathering()
        return localDescription() ?: throw IllegalStateException("Answer SDP was empty")
    }

    /** Applies the answer that the peer produced from [createOffer]. */
    suspend fun applyAnswer(answerSdp: String) {
        awaitSetRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    /** Best-effort ICE restart, used when the network changes but the session is still valid. */
    fun restartIce() {
        if (closed.get()) return
        runCatching { peerConnection.restartIce() }
            .onFailure { Log.w(TAG, "ICE restart failed: ${it.message}") }
    }

    // ------------------------------------------------------------------------------------
    // DataChannels
    // ------------------------------------------------------------------------------------

    fun isOpen(channel: CoupleChannel): Boolean =
        channels[channel]?.state() == DataChannel.State.OPEN

    fun send(channel: CoupleChannel, text: String): Boolean {
        if (closed.get()) return false
        val dataChannel = channels[channel] ?: return false
        if (dataChannel.state() != DataChannel.State.OPEN) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > CoupleProtocol.MAX_ENVELOPE_BYTES) {
            Log.w(TAG, "Refusing to send an oversized ${channel.wireName} frame")
            return false
        }
        return runCatching {
            dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        }.getOrDefault(false)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        channels.values.forEach { channel ->
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
            runCatching { channel.dispose() }
        }
        channels.clear()
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
    }

    private fun createChannels() {
        CoupleChannel.entries.forEach { channel ->
            val init = DataChannel.Init().apply {
                ordered = channel.ordered
                // Unreliable channels are for presence/clock samples only. Dropping one is
                // harmless and is far better than head-of-line blocking behind stale data.
                if (!channel.reliable) maxRetransmits = 0
            }
            val dataChannel = peerConnection.createDataChannel(channel.wireName, init) ?: return@forEach
            registerChannel(channel, dataChannel)
        }
    }

    private fun registerChannel(channel: CoupleChannel, dataChannel: DataChannel) {
        channels[channel] = dataChannel
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (dataChannel.state() == DataChannel.State.OPEN) {
                    listener.onChannelOpen(channel)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val remaining = buffer.data.remaining()
                if (remaining <= 0 || remaining > CoupleProtocol.MAX_ENVELOPE_BYTES) return
                val bytes = ByteArray(remaining)
                buffer.data.get(bytes)
                listener.onMessage(channel, bytes.toString(Charsets.UTF_8))
            }
        })
    }

    // ------------------------------------------------------------------------------------
    // SDP helpers
    // ------------------------------------------------------------------------------------

    private suspend fun awaitCreateOffer(): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Offer failed: $error"))
                    }
                }

                override fun onSetFailure(error: String) = Unit
            }, MediaConstraints())
        }

    private suspend fun awaitCreateAnswer(): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            peerConnection.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Answer failed: $error"))
                    }
                }

                override fun onSetFailure(error: String) = Unit
            }, MediaConstraints())
        }

    private suspend fun awaitSetLocalDescription(sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("setLocalDescription failed: $error")
                        )
                    }
                }
            }, sdp)
        }

    private suspend fun awaitSetRemoteDescription(sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("setRemoteDescription failed: $error")
                        )
                    }
                }
            }, sdp)
        }

    private suspend fun awaitIceGathering() {
        val deferred = iceGathering ?: return
        if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            deferred.complete(Unit)
        }
        val completed = withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) { deferred.await() }
        if (completed == null) {
            Log.w(TAG, "ICE gathering timed out; using the candidates gathered so far")
        }
        iceGathering = null
    }

    private fun localDescription(): String? =
        runCatching { peerConnection.localDescription?.description }.getOrNull()

    private fun iceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        turn?.let { config ->
            if (config.urls.isNotEmpty() && config.username.isNotBlank()) {
                servers += PeerConnection.IceServer.builder(config.urls)
                    .setUsername(config.username)
                    .setPassword(config.password)
                    .createIceServer()
            }
        }
        return servers
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> listener.onTransportConnected()

                PeerConnection.IceConnectionState.DISCONNECTED -> listener.onTransportDisconnected()
                PeerConnection.IceConnectionState.FAILED ->
                    listener.onTransportFailed("ICE connection failed")

                PeerConnection.IceConnectionState.CLOSED -> listener.onTransportClosed()
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener.onTransportConnected()
                PeerConnection.PeerConnectionState.DISCONNECTED -> listener.onTransportDisconnected()
                PeerConnection.PeerConnectionState.FAILED ->
                    listener.onTransportFailed("Peer connection failed")

                PeerConnection.PeerConnectionState.CLOSED -> listener.onTransportClosed()
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                iceGathering?.complete(Unit)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(dataChannel: DataChannel) {
            val channel = CoupleChannel.fromWireName(dataChannel.label()) ?: return
            registerChannel(channel, dataChannel)
        }

        override fun onRenegotiationNeeded() = Unit
    }

    init {
        val configuration = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            enableImplicitRollback = true
        }
        peerConnection = factory.createPeerConnection(configuration, observer)
            ?: throw IllegalStateException("WebRTC could not create a peer connection")
    }

    companion object {
        private const val TAG = "CouplePeerConnection"
        private const val ICE_GATHERING_TIMEOUT_MS = 12_000L

        @Volatile
        private var sharedFactory: PeerConnectionFactory? = null

        /**
         * One process-wide factory. WebRTC's initialization is process-scoped, so creating a
         * second factory per connection would waste native threads and risk double-init.
         */
        @Synchronized
        private fun sharedFactory(context: Context): PeerConnectionFactory {
            sharedFactory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
            sharedFactory = created
            return created
        }
    }
}
