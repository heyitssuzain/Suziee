package com.example.couple

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Best-effort, same-network signalling for the opt-in "reconnect on this Wi-Fi" path.
 *
 * There is no rendezvous server here. The offerer broadcasts a tiny UDP discovery datagram,
 * the answerer connects back over TCP, and the two sides exchange the *already signed*
 * connection codes produced by [CoupleSpaceController]. Those codes are verified against the
 * persisted peer key, so LAN discovery is only a transport: it cannot pair a stranger or
 * substitute another identity.
 *
 * The path is deliberately narrow:
 *  - it only works while both phones are on the same L2 network and the app is foregrounded;
 *  - it is opt-in (the user must enable Wi-Fi reconnect);
 *  - it is bounded by short timeouts and never becomes a background listener or a cloud
 *    service.
 *
 * Global connectivity still requires the manual QR / deep-link code exchange. That
 * limitation is intentional: the project has no central signalling backend.
 */
class CoupleLanSignaling {

    suspend fun offer(
        pairingId: String,
        localDeviceId: String,
        expectedPeerDeviceId: String,
        createOffer: suspend () -> String,
        onAnswer: suspend (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var server: ServerSocket? = null
        var socket: Socket? = null
        try {
            server = ServerSocket(0).apply {
                reuseAddress = true
                soTimeout = DISCOVERY_POLL_MS
            }
            val localPort = server.localPort
            socket = awaitPeerConnection(
                role = ROLE_OFFER,
                pairingId = pairingId,
                localDeviceId = localDeviceId,
                localPort = localPort,
                server = server
            ) ?: return@withContext false

            socket.soTimeout = EXCHANGE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val hello = reader.readLine() ?: return@withContext false
            if (!isExpectedHello(hello, pairingId, expectedPeerDeviceId)) {
                Log.w(TAG, "LAN offer rejected an unexpected peer handshake")
                return@withContext false
            }
            writeLine(writer, "OK|$pairingId|$localDeviceId")

            // Creating the offer gathers ICE candidates and can take several seconds.
            val offerCode = createOffer()
            writeLine(writer, offerCode)

            val answerCode = reader.readLine() ?: return@withContext false
            if (answerCode.isBlank() || answerCode.length > MAX_CODE_CHARS) {
                return@withContext false
            }
            onAnswer(answerCode)
            true
        } catch (error: Throwable) {
            // Log only the failure class: the codes and the pairing id are not secret, but
            // there is no reason to put signalling material in logcat either.
            Log.w(TAG, "LAN offer failed: ${error.javaClass.simpleName}")
            false
        } finally {
            runCatching { socket?.close() }
            runCatching { server?.close() }
        }
    }

    suspend fun answer(
        pairingId: String,
        localDeviceId: String,
        expectedPeerDeviceId: String,
        onOffer: suspend (String) -> String
    ): Boolean = withContext(Dispatchers.IO) {
        var udp: DatagramSocket? = null
        var socket: Socket? = null
        try {
            udp = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = DISCOVERY_POLL_MS
            }
            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(MAX_DISCOVERY_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching { udp.receive(packet) }.getOrNull() ?: continue
                if (received.length <= 0 || received.length > MAX_DISCOVERY_BYTES) continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val discovery = parseDiscovery(text) ?: continue
                if (discovery.role != ROLE_OFFER) continue
                if (discovery.pairingId != pairingId) continue
                if (discovery.deviceId != expectedPeerDeviceId) continue
                if (discovery.port !in 1..65535) continue

                socket = Socket()
                socket.connect(InetSocketAddress(packet.address, discovery.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = EXCHANGE_TIMEOUT_MS
                socket.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                writeLine(writer, "HELLO|$pairingId|$localDeviceId")
                val acknowledgement = reader.readLine() ?: return@withContext false
                if (acknowledgement != "OK|$pairingId|$expectedPeerDeviceId") {
                    Log.w(TAG, "LAN answer rejected an unexpected peer acknowledgement")
                    return@withContext false
                }
                val offerCode = reader.readLine() ?: return@withContext false
                if (offerCode.isBlank() || offerCode.length > MAX_CODE_CHARS) {
                    return@withContext false
                }
                val answerCode = onOffer(offerCode)
                writeLine(writer, answerCode)
                return@withContext true
            }
            false
        } catch (error: Throwable) {
            Log.w(TAG, "LAN answer failed: ${error.javaClass.simpleName}")
            false
        } finally {
            runCatching { socket?.close() }
            runCatching { udp?.close() }
        }
    }

    private fun awaitPeerConnection(
        role: String,
        pairingId: String,
        localDeviceId: String,
        localPort: Int,
        server: ServerSocket
    ): Socket? {
        val discovery = buildDiscovery(role, pairingId, localDeviceId, localPort)
        val broadcastAddress = InetAddress.getByName(BROADCAST_ADDRESS)
        DatagramSocket().use { udp ->
            udp.broadcast = true
            val packet = DatagramPacket(
                discovery,
                discovery.size,
                broadcastAddress,
                DISCOVERY_PORT
            )
            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                runCatching { udp.send(packet) }
                val accepted = runCatching { server.accept() }.getOrNull()
                if (accepted != null) {
                    // The handshake itself is checked by the caller; this only filters the
                    // obvious case of a different phone answering first.
                    accepted.soTimeout = HANDSHAKE_TIMEOUT_MS
                    return accepted
                }
            }
        }
        return null
    }

    private fun buildDiscovery(
        role: String,
        pairingId: String,
        deviceId: String,
        port: Int
    ): ByteArray = "$MAGIC|$role|$pairingId|$deviceId|$port".toByteArray(Charsets.UTF_8)

    private fun parseDiscovery(text: String): Discovery? {
        if (text.length > MAX_DISCOVERY_BYTES) return null
        val parts = text.split('|')
        if (parts.size != 5 || parts[0] != MAGIC) return null
        val role = parts[1]
        if (role != ROLE_OFFER) return null
        val pairingId = parts[2]
        val deviceId = parts[3]
        val port = parts[4].toIntOrNull() ?: return null
        if (pairingId.isBlank() || deviceId.isBlank()) return null
        return Discovery(role = role, pairingId = pairingId, deviceId = deviceId, port = port)
    }

    private fun isExpectedHello(text: String, pairingId: String, expectedPeerDeviceId: String): Boolean {
        val parts = text.split('|')
        return parts.size == 3 &&
            parts[0] == "HELLO" &&
            parts[1] == pairingId &&
            parts[2] == expectedPeerDeviceId
    }

    private fun writeLine(writer: BufferedWriter, value: String) {
        writer.write(value)
        writer.write("\n")
        writer.flush()
    }

    private data class Discovery(
        val role: String,
        val pairingId: String,
        val deviceId: String,
        val port: Int
    )

    private companion object {
        private const val TAG = "CoupleLanSignaling"
        private const val MAGIC = "SUZIEE-COUPLE-LAN-1"
        private const val ROLE_OFFER = "OFFER"

        /** High, uncommon UDP port used only while the Couple screen is foregrounded. */
        private const val DISCOVERY_PORT = 47823
        private const val BROADCAST_ADDRESS = "255.255.255.255"
        private const val MAX_DISCOVERY_BYTES = 512
        private const val MAX_CODE_CHARS = 128 * 1024
        private const val DISCOVERY_TIMEOUT_MS = 18_000L
        private const val DISCOVERY_POLL_MS = 1_000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val EXCHANGE_TIMEOUT_MS = 45_000
    }
}
