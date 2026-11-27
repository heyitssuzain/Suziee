package com.example.couple

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.data.local.SuzieeDatabase
import com.example.data.local.entities.CoupleEventEntity
import com.example.data.local.entities.CoupleMessageEntity
import com.example.data.local.entities.CouplePeerEntity
import com.example.data.local.entities.CouplePendingPairingEntity
import com.example.data.local.entities.CoupleSharedTrackEntity
import com.example.data.local.entities.DeliveryState
import com.example.data.preferences.SuzieePreferences
import com.example.domain.model.Track
import com.example.playback.MusicPlayerManager
import com.example.playback.PlaybackSyncEvent
import com.example.playback.PlaybackSyncEventType
import com.example.playback.PlaybackSyncSink
import com.example.playback.RemotePlaybackAction
import com.example.playback.RemotePlaybackCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class CoupleIncomingCode(
    val kind: String,
    val code: String
)

data class CoupleUiState(
    val identity: CoupleIdentity? = null,
    val peer: CouplePeer? = null,
    val connectionState: CoupleConnectionState = CoupleConnectionState.OFFLINE,
    val connectionDetail: String? = null,
    val pairingState: CouplePairingState = CouplePairingState.Idle,
    val signalState: CoupleSignalState = CoupleSignalState(),
    val messages: List<CoupleChatMessage> = emptyList(),
    val sharedTracks: List<SharedTrack> = emptyList(),
    val nowPlaying: CoupleNowPlaying? = null,
    val peerListening: Boolean = false,
    val unreadCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val clockOffsetMs: Long = 0L,
    val clockRttMs: Long = 0L,
    val lastSyncAt: Long? = null,
    val authenticated: Boolean = false,
    val foreground: Boolean = false,
    val localDisplayName: String = "Me",
    val lanReconnectEnabled: Boolean = false
) {
    val hasPeer: Boolean get() = peer != null
    val isConnected: Boolean get() = connectionState == CoupleConnectionState.CONNECTED
    val isAuthenticated: Boolean get() = authenticated
}

/**
 * The single orchestrator for Couple Space.
 *
 * It owns no UI and no Activity. Everything it needs is injected, so the protocol, merge and
 * sync logic can be unit-tested without Android, and the Compose screens only observe
 * [uiState] and call intent-level methods.
 *
 * Security boundaries enforced here:
 *  - private keys are only touched through [CoupleIdentityStore] (Android Keystore in the app);
 *  - every incoming frame is validated, sender-checked and replay-protected before Room;
 *  - pairing and connection payloads are signature-verified against the persisted peer key;
 *  - message bodies and authentication material are never logged.
 */
class CoupleSpaceController(
    private val context: Context,
    private val database: SuzieeDatabase,
    private val playerManager: MusicPlayerManager,
    private val preferences: SuzieePreferences,
    private val identityStore: CoupleIdentityStore = AndroidKeystoreIdentityStore(context),
    private val turn: CoupleTurnConfig? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val peerDao = database.couplePeerDao()
    private val messageDao = database.coupleMessageDao()
    private val sharedTrackDao = database.coupleSharedTrackDao()
    private val eventDao = database.coupleEventDao()
    private val pendingDao = database.couplePendingPairingDao()

    private val _uiState = MutableStateFlow(CoupleUiState())
    val uiState: StateFlow<CoupleUiState> = _uiState.asStateFlow()

    private val _incomingCode = MutableStateFlow<CoupleIncomingCode?>(null)
    val incomingCode: StateFlow<CoupleIncomingCode?> = _incomingCode.asStateFlow()

    private var started = false
    private var identity: CoupleIdentity? = null
    private var activePeerEntity: CouplePeerEntity? = null
    private var pendingPairing: CouplePendingPairingEntity? = null
    private var localDisplayName = "Me"

    private var connection: CouplePeerConnection? = null
    private var sessionId: String? = null
    private var authenticated = false
    private var transportConnected = false
    private var authHelloSent = false
    private var foreground = false

    private val sequenceTracker = CoupleSequenceTracker()
    private val controlSeq = AtomicLong(0)
    private val presenceSeq = AtomicLong(0)
    private val syncSeq = AtomicLong(0)
    private val chatSeq = AtomicLong(0)
    private val playlistSeq = AtomicLong(0)
    private var lamport = CoupleLamportClock()
    private val clock = CoupleClockSync()

    private var lastRemotePlayback: PlaybackPayload? = null
    private var pendingPlayback: PlaybackPayload? = null
    private var lastRemotePlaybackAtLocal = 0L
    private var lastLocalPlaybackAt = 0L
    private var lastSeekAt = 0L
    private var lastPresenceAt = 0L
    private var lastEventPruneAt = 0L
    private var lastLanAttemptAt = 0L
    private var chatVisible = false

    private var clockJob: Job? = null
    private var syncJob: Job? = null
    private var rateResetJob: Job? = null
    private var backgroundJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastSeededPeerId: String? = null

    private val playbackSink = object : PlaybackSyncSink {
        override fun onLocalPlaybackEvent(event: PlaybackSyncEvent) = handleLocalPlayback(event)
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true

        scope.launch {
            runCatching { withContext(Dispatchers.IO) { identityStore.getOrCreate() } }
                .onSuccess { created ->
                    identity = created
                    _uiState.update { it.copy(identity = created) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(error = "The secure identity could not be opened: ${error.message}")
                    }
                }
            restorePendingPairing()
        }

        scope.launch {
            preferences.coupleDisplayName.collect { name ->
                localDisplayName = name.ifBlank { "Me" }
                _uiState.update { it.copy(localDisplayName = localDisplayName) }
            }
        }
        scope.launch {
            preferences.isCoupleLanReconnectEnabled.collect { enabled ->
                _uiState.update { it.copy(lanReconnectEnabled = enabled) }
            }
        }

        scope.launch {
            peerDao.observeActivePeer().collect { peer ->
                activePeerEntity = peer
                _uiState.update { it.copy(peer = peer?.toDomain()) }
                // Zero overhead for people who never pair: the player only pays for a sink
                // while a trusted peer record exists.
                playerManager.playbackSyncSink = if (peer != null) playbackSink else null
                if (peer != null && peer.peerId != lastSeededPeerId) {
                    lastSeededPeerId = peer.peerId
                    chatSeq.set(peer.localChatSeq)
                    playlistSeq.set(peer.localPlaylistSeq)
                    sequenceTracker.seed(CoupleChannel.CHAT.wireName, peer.lastRemoteChatSeq)
                    sequenceTracker.seed(CoupleChannel.PLAYLIST.wireName, peer.lastRemotePlaylistSeq)
                    lamport.restore(
                        maxOf(peer.playlistOrderLamport, sharedTrackDao.maxLamport(peer.peerId) ?: 0L)
                    )
                }
            }
        }

        scope.launch {
            peerDao.observeActivePeer()
                .map { it?.peerId }
                .distinctUntilChanged()
                .collectLatest { peerId ->
                    if (peerId == null) {
                        _uiState.update { it.copy(sharedTracks = emptyList()) }
                    } else {
                        sharedTrackDao.observeActive(peerId).collect { tracks ->
                            _uiState.update { it.copy(sharedTracks = tracks.map { it.toDomain() }) }
                        }
                    }
                }
        }

        scope.launch {
            messageDao.observeAll().collect { messages ->
                _uiState.update { it.copy(messages = messages.map { it.toDomain() }) }
            }
        }

        scope.launch {
            playerManager.playbackState.collect { state ->
                updateNowPlaying(state)
            }
        }
    }

    fun onForeground() {
        foreground = true
        backgroundJob?.cancel()
        _uiState.update { it.copy(foreground = true) }
        if (authenticated) sendPresence(force = true)
        val peer = activePeerEntity
        val canTryLan = _uiState.value.lanReconnectEnabled &&
            peer?.lastConnectedAt != null &&
            connection == null &&
            System.currentTimeMillis() - lastLanAttemptAt > LAN_AUTO_RETRY_INTERVAL_MS
        if (canTryLan) {
            lastLanAttemptAt = System.currentTimeMillis()
            scope.launch { connectOverLan(silent = true) }
        }
    }

    fun onBackground() {
        foreground = false
        _uiState.update { it.copy(foreground = false) }
        if (authenticated) sendPresence(force = true)
        backgroundJob?.cancel()
        // No foreground service and no permanent background socket: the encrypted peer
        // connection is closed shortly after the app leaves the foreground. Pairing, chat and
        // the shared queue stay on disk, so reconnecting later never requires pairing again.
        backgroundJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            if (!foreground) disconnectInternal()
        }
    }

    // ------------------------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------------------------

    suspend fun createInvite(displayName: String): Result<String> = runCatching {
        if (activePeerEntity != null) error("Unpair the current device before creating a new invitation")
        val id = requireIdentity()
        val name = CoupleCrypto.sanitizeDisplayName(displayName).ifBlank { "Me" }
        preferences.setCoupleDisplayName(name)
        localDisplayName = name

        val pairingId = CoupleEventIds.newPairingId()
        val nonce = CoupleEventIds.newNonce()
        val createdAt = now()
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        val signature = signBase64(
            CoupleTranscripts.invite(pairingId, id.deviceId, publicKey, nonce, createdAt, name)
        )
        val payload = InvitePayload(
            pairingId = pairingId,
            displayName = name,
            deviceId = id.deviceId,
            publicKey = publicKey,
            nonce = nonce,
            createdAt = createdAt,
            signature = signature
        )
        val code = CoupleSignaling.encodeInvite(payload)
        val pending = CouplePendingPairingEntity(
            pairingId = pairingId,
            role = CouplePendingPairingEntity.ROLE_INVITER,
            localDisplayName = name,
            remoteDeviceId = "",
            remotePublicKey = "",
            remoteDisplayName = "",
            localNonce = nonce,
            remoteNonce = "",
            createdAt = createdAt,
            stage = CouplePendingPairingEntity.STAGE_INVITE_CREATED
        )
        pendingDao.upsert(pending)
        pendingPairing = pending
        _uiState.update {
            it.copy(
                pairingState = CouplePairingState.InviteCreated(code, name),
                error = null,
                busy = false
            )
        }
        code
    }

    suspend fun acceptInvite(code: String, displayName: String): Result<String> = runCatching {
        if (activePeerEntity != null) error("Unpair the current device before joining a new one")
        val id = requireIdentity()
        val name = CoupleCrypto.sanitizeDisplayName(displayName).ifBlank { "Me" }
        val invite = CoupleSignaling.decodeInvite(code) ?: error("That is not a Suziee Couple invitation")
        CoupleSignaling.verifyInvite(invite, now())?.let { error(it) }
        if (invite.deviceId == id.deviceId) error("This invitation belongs to this phone")

        preferences.setCoupleDisplayName(name)
        localDisplayName = name

        val joinerNonce = CoupleEventIds.newNonce()
        val createdAt = now()
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        val signature = signBase64(
            CoupleTranscripts.accept(
                pairingId = invite.pairingId,
                inviterDeviceId = invite.deviceId,
                inviterPublicKeyBase64 = invite.publicKey,
                joinerDeviceId = id.deviceId,
                joinerPublicKeyBase64 = publicKey,
                inviterNonce = invite.nonce,
                joinerNonce = joinerNonce,
                createdAt = createdAt
            )
        )
        val reply = AcceptPayload(
            pairingId = invite.pairingId,
            displayName = name,
            deviceId = id.deviceId,
            publicKey = publicKey,
            inviterDeviceId = invite.deviceId,
            inviterPublicKey = invite.publicKey,
            inviterNonce = invite.nonce,
            joinerNonce = joinerNonce,
            createdAt = createdAt,
            signature = signature
        )
        val replyCode = CoupleSignaling.encodeAccept(reply)
        val pending = CouplePendingPairingEntity(
            pairingId = invite.pairingId,
            role = CouplePendingPairingEntity.ROLE_JOINER,
            localDisplayName = name,
            remoteDeviceId = invite.deviceId,
            remotePublicKey = invite.publicKey,
            remoteDisplayName = CoupleCrypto.sanitizeDisplayName(invite.displayName),
            localNonce = joinerNonce,
            remoteNonce = invite.nonce,
            createdAt = createdAt,
            stage = CouplePendingPairingEntity.STAGE_ACCEPT_CREATED
        )
        pendingDao.upsert(pending)
        pendingPairing = pending

        val fingerprint = CoupleCrypto.publicKeyFromBase64(invite.publicKey)
            ?.let { CoupleCrypto.fingerprint(it) }
            .orEmpty()
        _uiState.update {
            it.copy(
                pairingState = CouplePairingState.InviteScanned(
                    replyCode = replyCode,
                    peerName = CoupleCrypto.sanitizeDisplayName(invite.displayName),
                    peerFingerprint = fingerprint
                ),
                error = null,
                busy = false
            )
        }
        replyCode
    }

    suspend fun applyReply(code: String): Result<String> = runCatching {
        val pending = pendingPairing ?: error("Start an invitation first")
        if (pending.role != CouplePendingPairingEntity.ROLE_INVITER) {
            error("This phone joined the pairing, so it should send a reply, not scan one")
        }
        val id = requireIdentity()

        if (pending.stage == CouplePendingPairingEntity.STAGE_ACCEPT_VERIFIED) {
            val rebuilt = rebuildConfirm(pending, id)
            _uiState.update {
                it.copy(
                    pairingState = CouplePairingState.ReplyScanned(
                        confirmationCode = rebuilt,
                        peerName = pending.remoteDisplayName,
                        peerFingerprint = fingerprintOf(pending.remotePublicKey)
                    ),
                    busy = false
                )
            }
            return@runCatching rebuilt
        }

        val accept = CoupleSignaling.decodeAccept(code) ?: error("That is not a Suziee Couple reply")
        CoupleSignaling.verifyAccept(
            payload = accept,
            expectedPairingId = pending.pairingId,
            expectedInviterDeviceId = id.deviceId,
            expectedInviterPublicKey = CoupleCrypto.publicKeyBase64(id.publicKey),
            expectedInviterNonce = pending.localNonce,
            now = now()
        )?.let { error(it) }

        val updated = pending.copy(
            remoteDeviceId = accept.deviceId,
            remotePublicKey = accept.publicKey,
            remoteDisplayName = CoupleCrypto.sanitizeDisplayName(accept.displayName),
            remoteNonce = accept.joinerNonce,
            stage = CouplePendingPairingEntity.STAGE_ACCEPT_VERIFIED
        )
        pendingDao.upsert(updated)
        pendingPairing = updated

        val confirmCode = rebuildConfirm(updated, id, accept.signature)
        storePeer(
            peerId = accept.deviceId,
            displayName = CoupleCrypto.sanitizeDisplayName(accept.displayName),
            publicKey = accept.publicKey,
            pairingId = updated.pairingId,
            inviteSignature = "",
            acceptSignature = accept.signature,
            confirmSignature = signatureOfConfirm(updated, id)
        )
        _uiState.update {
            it.copy(
                pairingState = CouplePairingState.ReplyScanned(
                    confirmationCode = confirmCode,
                    peerName = CoupleCrypto.sanitizeDisplayName(accept.displayName),
                    peerFingerprint = fingerprintOf(accept.publicKey)
                ),
                busy = false,
                error = null
            )
        }
        confirmCode
    }

    suspend fun applyConfirm(code: String): Result<Unit> = runCatching {
        val pending = pendingPairing ?: error("Start by scanning an invitation first")
        if (pending.role != CouplePendingPairingEntity.ROLE_JOINER) {
            error("This phone created the invitation, so it should create the confirmation code")
        }
        val id = requireIdentity()
        val confirm = CoupleSignaling.decodeConfirm(code)
            ?: error("That is not a Suziee Couple confirmation")
        CoupleSignaling.verifyConfirm(
            payload = confirm,
            expectedPairingId = pending.pairingId,
            expectedInviterDeviceId = pending.remoteDeviceId,
            expectedInviterPublicKey = pending.remotePublicKey,
            expectedJoinerDeviceId = id.deviceId,
            expectedJoinerPublicKey = CoupleCrypto.publicKeyBase64(id.publicKey),
            expectedJoinerNonce = pending.localNonce,
            now = now()
        )?.let { error(it) }

        storePeer(
            peerId = pending.remoteDeviceId,
            displayName = pending.remoteDisplayName,
            publicKey = pending.remotePublicKey,
            pairingId = pending.pairingId,
            inviteSignature = "",
            acceptSignature = "",
            confirmSignature = confirm.signature
        )
        _uiState.update { it.copy(busy = false, error = null) }
        Unit
    }

    fun finishInviterPairing() {
        val peer = activePeerEntity ?: return
        scope.launch {
            pendingDao.clear()
            pendingPairing = null
            _uiState.update {
                it.copy(pairingState = CouplePairingState.Completed(peer.toDomain()))
            }
        }
    }

    fun cancelPairing() {
        scope.launch {
            pendingDao.clear()
            pendingPairing = null
            _uiState.update {
                it.copy(pairingState = CouplePairingState.Idle, signalState = CoupleSignalState(), error = null)
            }
        }
    }

    private suspend fun restorePendingPairing() {
        val pending = pendingDao.get() ?: return
        if (now() - pending.createdAt > PAIRING_RESTORE_WINDOW_MS) {
            pendingDao.clear()
            return
        }
        val id = requireIdentity()
        pendingPairing = pending
        when {
            pending.role == CouplePendingPairingEntity.ROLE_INVITER &&
                pending.stage == CouplePendingPairingEntity.STAGE_INVITE_CREATED -> {
                val code = rebuildInvite(pending, id)
                _uiState.update {
                    it.copy(pairingState = CouplePairingState.InviteCreated(code, pending.localDisplayName))
                }
            }

            pending.role == CouplePendingPairingEntity.ROLE_INVITER &&
                pending.stage == CouplePendingPairingEntity.STAGE_ACCEPT_VERIFIED -> {
                val code = rebuildConfirm(pending, id)
                _uiState.update {
                    it.copy(
                        pairingState = CouplePairingState.ReplyScanned(
                            confirmationCode = code,
                            peerName = pending.remoteDisplayName,
                            peerFingerprint = fingerprintOf(pending.remotePublicKey)
                        )
                    )
                }
            }

            pending.role == CouplePendingPairingEntity.ROLE_JOINER &&
                pending.stage == CouplePendingPairingEntity.STAGE_ACCEPT_CREATED -> {
                val code = rebuildAccept(pending, id)
                _uiState.update {
                    it.copy(
                        pairingState = CouplePairingState.InviteScanned(
                            replyCode = code,
                            peerName = pending.remoteDisplayName,
                            peerFingerprint = fingerprintOf(pending.remotePublicKey)
                        )
                    )
                }
            }

            else -> pendingDao.clear()
        }
    }

    private suspend fun rebuildInvite(
        pending: CouplePendingPairingEntity,
        id: CoupleIdentity
    ): String {
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        val signature = signBase64(
            CoupleTranscripts.invite(
                pending.pairingId,
                id.deviceId,
                publicKey,
                pending.localNonce,
                pending.createdAt,
                pending.localDisplayName
            )
        )
        return CoupleSignaling.encodeInvite(
            InvitePayload(
                pairingId = pending.pairingId,
                displayName = pending.localDisplayName,
                deviceId = id.deviceId,
                publicKey = publicKey,
                nonce = pending.localNonce,
                createdAt = pending.createdAt,
                signature = signature
            )
        )
    }

    private suspend fun rebuildAccept(
        pending: CouplePendingPairingEntity,
        id: CoupleIdentity
    ): String {
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        val createdAt = now()
        val signature = signBase64(
            CoupleTranscripts.accept(
                pairingId = pending.pairingId,
                inviterDeviceId = pending.remoteDeviceId,
                inviterPublicKeyBase64 = pending.remotePublicKey,
                joinerDeviceId = id.deviceId,
                joinerPublicKeyBase64 = publicKey,
                inviterNonce = pending.remoteNonce,
                joinerNonce = pending.localNonce,
                createdAt = createdAt
            )
        )
        return CoupleSignaling.encodeAccept(
            AcceptPayload(
                pairingId = pending.pairingId,
                displayName = pending.localDisplayName,
                deviceId = id.deviceId,
                publicKey = publicKey,
                inviterDeviceId = pending.remoteDeviceId,
                inviterPublicKey = pending.remotePublicKey,
                inviterNonce = pending.remoteNonce,
                joinerNonce = pending.localNonce,
                createdAt = createdAt,
                signature = signature
            )
        )
    }

    private suspend fun rebuildConfirm(
        pending: CouplePendingPairingEntity,
        id: CoupleIdentity,
        knownSignature: String? = null
    ): String {
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        val createdAt = now()
        val signature = knownSignature ?: signBase64(
            CoupleTranscripts.confirm(
                pairingId = pending.pairingId,
                inviterDeviceId = id.deviceId,
                inviterPublicKeyBase64 = publicKey,
                joinerDeviceId = pending.remoteDeviceId,
                joinerPublicKeyBase64 = pending.remotePublicKey,
                joinerNonce = pending.remoteNonce,
                createdAt = createdAt
            )
        )
        return CoupleSignaling.encodeConfirm(
            ConfirmPayload(
                pairingId = pending.pairingId,
                displayName = pending.localDisplayName,
                deviceId = id.deviceId,
                publicKey = publicKey,
                joinerDeviceId = pending.remoteDeviceId,
                joinerPublicKey = pending.remotePublicKey,
                joinerNonce = pending.remoteNonce,
                createdAt = createdAt,
                signature = signature
            )
        )
    }

    private suspend fun signatureOfConfirm(
        pending: CouplePendingPairingEntity,
        id: CoupleIdentity
    ): String {
        // The exact bytes are not needed for verification later; this only records that a
        // confirmation was produced. Re-signing the canonical transcript is deterministic in
        // meaning and avoids storing the one-time signature twice.
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        return signBase64(
            CoupleTranscripts.confirm(
                pairingId = pending.pairingId,
                inviterDeviceId = id.deviceId,
                inviterPublicKeyBase64 = publicKey,
                joinerDeviceId = pending.remoteDeviceId,
                joinerPublicKeyBase64 = pending.remotePublicKey,
                joinerNonce = pending.remoteNonce,
                createdAt = now()
            )
        )
    }

    private suspend fun storePeer(
        peerId: String,
        displayName: String,
        publicKey: String,
        pairingId: String,
        inviteSignature: String,
        acceptSignature: String,
        confirmSignature: String
    ) {
        val existing = peerDao.getPeer(peerId)
        val fingerprint = fingerprintOf(publicKey)
        val entity = CouplePeerEntity(
            peerId = peerId,
            displayName = displayName.ifBlank { "My person" },
            publicKey = publicKey,
            pairingId = pairingId,
            pairedAt = existing?.pairedAt ?: now(),
            lastConnectedAt = existing?.lastConnectedAt,
            status = CouplePeerEntity.STATUS_PAIRED,
            fingerprint = fingerprint,
            inviteSignature = inviteSignature,
            acceptSignature = acceptSignature,
            confirmSignature = confirmSignature,
            lastRemoteChatSeq = existing?.lastRemoteChatSeq ?: 0L,
            lastRemotePlaylistSeq = existing?.lastRemotePlaylistSeq ?: 0L,
            localChatSeq = existing?.localChatSeq ?: 0L,
            localPlaylistSeq = existing?.localPlaylistSeq ?: 0L,
            playlistOrderLamport = existing?.playlistOrderLamport ?: 0L,
            playlistOrderActor = existing?.playlistOrderActor ?: "",
            lastSeenAt = existing?.lastSeenAt ?: 0L
        )
        peerDao.upsert(entity)
        activePeerEntity = entity
        lastSeededPeerId = entity.peerId
        chatSeq.set(entity.localChatSeq)
        playlistSeq.set(entity.localPlaylistSeq)
        sequenceTracker.seed(CoupleChannel.CHAT.wireName, entity.lastRemoteChatSeq)
        sequenceTracker.seed(CoupleChannel.PLAYLIST.wireName, entity.lastRemotePlaylistSeq)
        lamport.restore(
            maxOf(entity.playlistOrderLamport, sharedTrackDao.maxLamport(entity.peerId) ?: 0L)
        )
        pendingDao.clear()
        pendingPairing = null
        _uiState.update {
            it.copy(
                peer = entity.toDomain(),
                pairingState = CouplePairingState.Completed(entity.toDomain()),
                error = null
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Connection signalling
    // ------------------------------------------------------------------------------------

    suspend fun createConnectionCode(): Result<String> = runCatching {
        val peer = activePeerEntity ?: error("Pair a device first")
        val id = requireIdentity()
        _uiState.update { it.copy(busy = true, error = null) }

        connection?.close()
        authenticated = false
        authHelloSent = false
        transportConnected = false
        sequenceTracker.resetSessionChannels()
        controlSeq.set(0)
        presenceSeq.set(0)
        syncSeq.set(0)
        sessionId = CoupleEventIds.newSessionId()

        val conn = newConnection()
        connection = conn
        _uiState.update {
            it.copy(
                connectionState = CoupleConnectionState.CONNECTING,
                connectionDetail = "Creating an encrypted offer…"
            )
        }

        val sdp = conn.createOffer()
        val session = sessionId ?: error("No signalling session")
        val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
        val signature = signBase64(
            CoupleTranscripts.signal(
                kind = CoupleCodec.KIND_OFFER,
                pairingId = peer.pairingId,
                session = session,
                from = id.deviceId,
                sdp = sdp
            )
        )
        val payload = ConnectionPayload(
            kind = CoupleCodec.KIND_OFFER,
            pairingId = peer.pairingId,
            session = session,
            from = id.deviceId,
            publicKey = publicKey,
            sdp = sdp,
            createdAt = now(),
            signature = signature
        )
        val code = CoupleSignaling.encodeConnection(payload)
        _uiState.update {
            it.copy(
                busy = false,
                signalState = CoupleSignalState(
                    localCode = code,
                    localKind = CoupleCodec.KIND_OFFER,
                    waitingForPeer = true,
                    hint = "Send this offer to ${peer.displayName}, then paste their answer below."
                )
            )
        }
        code
    }.onFailure { error ->
        _uiState.update {
            it.copy(
                busy = false,
                connectionState = CoupleConnectionState.FAILED,
                connectionDetail = error.message ?: "Could not create a connection code"
            )
        }
    }

    suspend fun applyConnectionCode(code: String): Result<String?> = runCatching {
        val peer = activePeerEntity ?: error("Pair a device first")
        val id = requireIdentity()
        val payload = CoupleSignaling.decodeConnection(code)
            ?: error("That is not a Suziee Couple connection code")
        _uiState.update { it.copy(busy = true, error = null) }

        when (payload.kind) {
            CoupleCodec.KIND_OFFER -> {
                CoupleSignaling.verifyConnection(
                    payload = payload,
                    expectedPairingId = peer.pairingId,
                    expectedPeerId = peer.peerId,
                    expectedPeerPublicKey = peer.publicKey,
                    expectedSession = null,
                    now = now()
                )?.let { error(it) }

                connection?.close()
                authenticated = false
                authHelloSent = false
                transportConnected = false
                sequenceTracker.resetSessionChannels()
                controlSeq.set(0)
                presenceSeq.set(0)
                syncSeq.set(0)
                sessionId = payload.session

                val conn = newConnection()
                connection = conn
                _uiState.update {
                    it.copy(
                        connectionState = CoupleConnectionState.CONNECTING,
                        connectionDetail = "Accepting the encrypted offer…"
                    )
                }
                val answerSdp = conn.createAnswer(payload.sdp)
                val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
                val signature = signBase64(
                    CoupleTranscripts.signal(
                        kind = CoupleCodec.KIND_ANSWER,
                        pairingId = peer.pairingId,
                        session = payload.session,
                        from = id.deviceId,
                        sdp = answerSdp
                    )
                )
                val answer = ConnectionPayload(
                    kind = CoupleCodec.KIND_ANSWER,
                    pairingId = peer.pairingId,
                    session = payload.session,
                    from = id.deviceId,
                    publicKey = publicKey,
                    sdp = answerSdp,
                    createdAt = now(),
                    signature = signature
                )
                val answerCode = CoupleSignaling.encodeConnection(answer)
                _uiState.update {
                    it.copy(
                        busy = false,
                        signalState = CoupleSignalState(
                            localCode = answerCode,
                            localKind = CoupleCodec.KIND_ANSWER,
                            waitingForPeer = true,
                            hint = "Send this answer back to ${peer.displayName}."
                        )
                    )
                }
                answerCode
            }

            CoupleCodec.KIND_ANSWER -> {
                val current = connection ?: error("Create an offer on this phone first")
                val expectedSession = sessionId ?: error("No active signalling session")
                CoupleSignaling.verifyConnection(
                    payload = payload,
                    expectedPairingId = peer.pairingId,
                    expectedPeerId = peer.peerId,
                    expectedPeerPublicKey = peer.publicKey,
                    expectedSession = expectedSession,
                    now = now()
                )?.let { error(it) }
                current.applyAnswer(payload.sdp)
                _uiState.update {
                    it.copy(
                        busy = false,
                        signalState = CoupleSignalState(
                            localCode = null,
                            localKind = null,
                            waitingForPeer = false,
                            hint = "Connecting…"
                        )
                    )
                }
                null
            }

            else -> error("Unsupported connection code")
        }
    }.onFailure { error ->
        _uiState.update {
            it.copy(
                busy = false,
                connectionState = CoupleConnectionState.FAILED,
                connectionDetail = error.message ?: "Could not use that connection code"
            )
        }
    }

    /**
     * Best-effort same-Wi-Fi reconnect. It is opt-in, uses only the local network, and still
     * verifies the signed WebRTC offer against the paired key. It never becomes a cloud
     * rendezvous and it is not a background service.
     */
    suspend fun connectOverLan(silent: Boolean = false): Result<Unit> {
        val peer = activePeerEntity ?: return Result.failure(IllegalStateException("Pair a device first"))
        val id = runCatching { requireIdentity() }.getOrElse { return Result.failure(it) }
        if (!preferences.isCoupleLanReconnectEnabled.first()) {
            val failure = IllegalStateException("Turn on Wi-Fi reconnect in Couple settings first")
            if (!silent) _uiState.update { it.copy(error = failure.message) }
            return Result.failure(failure)
        }

        if (!silent) {
            _uiState.update {
                it.copy(
                    busy = true,
                    connectionState = CoupleConnectionState.CONNECTING,
                    connectionDetail = "Looking for ${peer.displayName} on this Wi-Fi…",
                    error = null
                )
            }
        }

        val lan = CoupleLanSignaling()
        val ok = runCatching {
            if (id.deviceId < peer.peerId) {
                lan.offer(
                    pairingId = peer.pairingId,
                    localDeviceId = id.deviceId,
                    expectedPeerDeviceId = peer.peerId,
                    createOffer = { createConnectionCode().getOrThrow() },
                    onAnswer = { answer -> applyConnectionCode(answer).getOrThrow() }
                )
            } else {
                lan.answer(
                    pairingId = peer.pairingId,
                    localDeviceId = id.deviceId,
                    expectedPeerDeviceId = peer.peerId,
                    onOffer = { offer ->
                        applyConnectionCode(offer).getOrThrow()
                            ?: error("The offer did not produce an answer")
                    }
                )
            }
        }.getOrDefault(false)

        return if (ok) {
            _uiState.update { it.copy(busy = false) }
            Result.success(Unit)
        } else {
            val failure = IllegalStateException(
                "No paired device answered on this Wi-Fi. Use the manual connection codes instead."
            )
            _uiState.update {
                it.copy(
                    busy = false,
                    connectionState = CoupleConnectionState.OFFLINE,
                    connectionDetail = if (silent) it.connectionDetail else failure.message
                )
            }
            Result.failure(failure)
        }
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    fun unpair() {
        scope.launch {
            disconnectInternal()
            val peerId = activePeerEntity?.peerId
            if (peerId != null) {
                messageDao.failAllUndelivered("Device unpaired")
                peerDao.deletePeer(peerId)
                messageDao.deleteAll()
                sharedTrackDao.deleteAll(peerId)
                eventDao.deleteAll()
            }
            pendingDao.clear()
            pendingPairing = null
            withContext(Dispatchers.IO) { identityStore.reset() }
            identity = null
            activePeerEntity = null
            lastSeededPeerId = null
            sequenceTracker.reset()
            lamport = CoupleLamportClock()
            chatSeq.set(0)
            playlistSeq.set(0)
            playerManager.playbackSyncSink = null
            _uiState.value = CoupleUiState(
                localDisplayName = localDisplayName,
                lanReconnectEnabled = _uiState.value.lanReconnectEnabled,
                foreground = foreground
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------------------------

    fun sendMessage(body: String) {
        val clean = CoupleValidator.sanitizeChatBody(body)
        if (clean.isBlank()) return
        scope.launch {
            val peer = activePeerEntity ?: return@launch
            val sequence = nextSequence(CoupleChannel.CHAT)
            val message = CoupleMessageEntity(
                eventId = CoupleEventIds.newEventId(),
                peerId = peer.peerId,
                direction = CoupleMessageEntity.DIRECTION_OUT,
                body = clean,
                createdAt = now(),
                deliveryState = DeliveryState.PENDING,
                sequence = sequence
            )
            messageDao.insertMessage(message)
            sendChatMessage(message)
        }
    }

    fun retryMessage(eventId: String) {
        scope.launch {
            val message = messageDao.getMessage(eventId) ?: return@launch
            messageDao.markPending(eventId)
            sendChatMessage(message.copy(deliveryState = DeliveryState.PENDING))
        }
    }

    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) _uiState.update { it.copy(unreadCount = 0) }
    }

    fun clearChat() {
        scope.launch { messageDao.deleteAll() }
    }

    private suspend fun sendChatMessage(message: CoupleMessageEntity) {
        if (!authenticated) return
        val conn = connection ?: return
        if (!conn.isOpen(CoupleChannel.CHAT)) return
        if (now() - message.createdAt > CoupleProtocol.MAX_QUEUED_AGE_MS) {
            messageDao.markFailed(message.eventId, "Expired before it could be delivered")
            return
        }
        val sender = identity ?: return
        val payload = ChatPayload(body = message.body, sentAt = message.createdAt)
        val envelope = CoupleEnvelope(
            v = CoupleProtocol.VERSION,
            id = message.eventId,
            sender = sender.deviceId,
            type = CoupleProtocol.EVENT_CHAT_MESSAGE,
            seq = message.sequence,
            // The envelope timestamp is the send time, not the compose time. A queued
            // message may be days old, and a peer with a slightly different clock must not
            // reject it as "future". The original compose time stays in the payload.
            ts = now(),
            session = null,
            payload = CoupleJson.encode(payload)
        )
        if (conn.send(CoupleChannel.CHAT, CoupleJson.encode(envelope))) {
            messageDao.markSent(message.eventId, now())
        }
    }

    private fun flushPendingChat() {
        scope.launch {
            val peer = activePeerEntity ?: return@launch
            messageDao.pendingMessages()
                .filter { it.peerId == peer.peerId }
                .forEach { sendChatMessage(it) }
        }
    }

    private suspend fun sendAck(ackId: String) {
        if (!authenticated) return
        val conn = connection ?: return
        val envelope = createEnvelope(
            channel = CoupleChannel.CHAT,
            type = CoupleProtocol.EVENT_CHAT_ACK,
            payload = CoupleJson.encode(ChatAckPayload(ack = ackId)),
            sessionBound = false
        )
        conn.send(CoupleChannel.CHAT, CoupleJson.encode(envelope))
    }

    // ------------------------------------------------------------------------------------
    // Shared playlist
    // ------------------------------------------------------------------------------------

    fun addToSharedPlaylist(track: Track) {
        scope.launch {
            val peer = activePeerEntity ?: return@launch
            if (track.id.isBlank()) return@launch
            val id = requireIdentity()
            val current = sharedTrackDao.getAll(peer.peerId)
            val logical = lamport.next()
            val incoming = CoupleSharedTrackEntity(
                peerId = peer.peerId,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds,
                artworkUrl = track.artworkUrl,
                position = CouplePlaylistMerge.nextPosition(current),
                lamport = logical,
                updatedBy = id.deviceId,
                updatedAt = now(),
                deleted = false
            )
            val result = CouplePlaylistMerge.applyAdd(current, incoming)
            if (!result.accepted) return@launch
            val merged = result.tracks.firstOrNull { it.trackId == track.id } ?: incoming
            sharedTrackDao.upsert(merged)
            sendPlaylistEvent(
                payload = PlaylistPayload(
                    op = PlaylistPayload.OP_ADD,
                    track = merged.toPayload(),
                    lamport = logical,
                    actor = id.deviceId
                ),
                sequence = nextSequence(CoupleChannel.PLAYLIST)
            )
        }
    }

    fun removeFromSharedPlaylist(trackId: String) {
        scope.launch {
            val peer = activePeerEntity ?: return@launch
            val id = requireIdentity()
            val current = sharedTrackDao.getAll(peer.peerId)
            val logical = lamport.next()
            val result = CouplePlaylistMerge.applyRemove(
                current = current,
                peerId = peer.peerId,
                trackId = trackId,
                lamport = logical,
                actor = id.deviceId,
                now = now()
            )
            if (!result.accepted) return@launch
            result.tracks.firstOrNull { it.trackId == trackId }?.let { sharedTrackDao.upsert(it) }
            sendPlaylistEvent(
                payload = PlaylistPayload(
                    op = PlaylistPayload.OP_REMOVE,
                    trackId = trackId,
                    lamport = logical,
                    actor = id.deviceId
                ),
                sequence = nextSequence(CoupleChannel.PLAYLIST)
            )
        }
    }

    fun moveSharedTrack(trackId: String, toIndex: Int) {
        scope.launch {
            val peer = activePeerEntity ?: return@launch
            val id = requireIdentity()
            val current = sharedTrackDao.getAll(peer.peerId)
            val active = CouplePlaylistMerge.activeSorted(current)
            val fromIndex = active.indexOfFirst { it.trackId == trackId }
            if (fromIndex < 0) return@launch
            val reordered = active.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex.coerceIn(0, reordered.size), moved)
            val order = reordered.map { it.trackId }
            val logical = lamport.next()
            val result = CouplePlaylistMerge.applyOrder(
                current = current,
                order = order,
                lamport = logical,
                actor = id.deviceId,
                currentOrderLamport = peer.playlistOrderLamport,
                currentOrderActor = peer.playlistOrderActor
            )
            if (!result.accepted) return@launch
            sharedTrackDao.upsertAll(result.tracks)
            peerDao.setPlaylistOrderVersion(peer.peerId, logical, id.deviceId)
            activePeerEntity = peer.copy(playlistOrderLamport = logical, playlistOrderActor = id.deviceId)
            sendPlaylistEvent(
                payload = PlaylistPayload(
                    op = PlaylistPayload.OP_MOVE,
                    order = order,
                    lamport = logical,
                    actor = id.deviceId
                ),
                sequence = nextSequence(CoupleChannel.PLAYLIST)
            )
        }
    }

    fun clearSharedPlaylist() {
        scope.launch {
            val peer = activePeerEntity ?: return@launch
            val id = requireIdentity()
            val current = sharedTrackDao.getAll(peer.peerId)
            val active = CouplePlaylistMerge.activeSorted(current)
            active.forEach { track ->
                val logical = lamport.next()
                val result = CouplePlaylistMerge.applyRemove(
                    current = sharedTrackDao.getAll(peer.peerId),
                    peerId = peer.peerId,
                    trackId = track.trackId,
                    lamport = logical,
                    actor = id.deviceId,
                    now = now()
                )
                if (result.accepted) {
                    result.tracks.firstOrNull { it.trackId == track.trackId }
                        ?.let { sharedTrackDao.upsert(it) }
                    sendPlaylistEvent(
                        payload = PlaylistPayload(
                            op = PlaylistPayload.OP_REMOVE,
                            trackId = track.trackId,
                            lamport = logical,
                            actor = id.deviceId
                        ),
                        sequence = nextSequence(CoupleChannel.PLAYLIST)
                    )
                }
            }
        }
    }

    fun playSharedTrack(track: Track) {
        val queue = _uiState.value.sharedTracks.map { it.toTrack() }
        playerManager.playTrack(track, queue.ifEmpty { listOf(track) })
    }

    private suspend fun sendPlaylistEvent(payload: PlaylistPayload, sequence: Long) {
        if (!authenticated) return
        val conn = connection ?: return
        if (!conn.isOpen(CoupleChannel.PLAYLIST)) return
        val sender = identity ?: return
        val envelope = CoupleEnvelope(
            v = CoupleProtocol.VERSION,
            id = CoupleEventIds.newEventId(),
            sender = sender.deviceId,
            type = CoupleProtocol.EVENT_PLAYLIST,
            seq = sequence,
            ts = now(),
            session = null,
            payload = CoupleJson.encode(payload)
        )
        conn.send(CoupleChannel.PLAYLIST, CoupleJson.encode(envelope))
    }

    private fun sendPlaylistSnapshot() {
        scope.launch {
            if (!authenticated) return@launch
            val peer = activePeerEntity ?: return@launch
            val conn = connection ?: return@launch
            if (!conn.isOpen(CoupleChannel.PLAYLIST)) return@launch
            val sender = identity ?: return@launch
            val all = sharedTrackDao.getAll(peer.peerId)
            if (all.isEmpty() && peer.playlistOrderLamport == 0L) return@launch
            val order = CouplePlaylistMerge.activeSorted(all).map { it.trackId }
            val payload = PlaylistPayload(
                op = PlaylistPayload.OP_SNAPSHOT,
                items = all.map { it.toPayload() },
                order = order,
                lamport = peer.playlistOrderLamport,
                actor = peer.playlistOrderActor.ifBlank { sender.deviceId }
            )
            val envelope = CoupleEnvelope(
                v = CoupleProtocol.VERSION,
                id = CoupleEventIds.newEventId(),
                sender = sender.deviceId,
                type = CoupleProtocol.EVENT_PLAYLIST,
                seq = nextSequence(CoupleChannel.PLAYLIST),
                ts = now(),
                session = null,
                payload = CoupleJson.encode(payload)
            )
            conn.send(CoupleChannel.PLAYLIST, CoupleJson.encode(envelope))
        }
    }

    // ------------------------------------------------------------------------------------
    // Playback sync
    // ------------------------------------------------------------------------------------

    fun pushCurrentPlayback() {
        val state = playerManager.playbackState.value
        val track = state.currentTrack ?: return
        lastLocalPlaybackAt = now()
        val payload = PlaybackPayload(
            action = PlaybackPayload.ACTION_TRACK,
            track = track.toRemote(),
            queue = state.queue.map { it.toRemote() },
            positionMs = state.currentPositionMs,
            playing = state.isPlaying,
            issuedAt = now(),
            speed = 1f
        )
        pendingPlayback = payload
        sendPlayback(payload)
    }

    fun resyncNow() {
        if (!authenticated) return
        if (isFollower()) {
            correctDrift(forceSeek = true)
        } else {
            pushCurrentPlayback()
        }
    }

    private fun handleLocalPlayback(event: PlaybackSyncEvent) {
        lastLocalPlaybackAt = event.issuedAt
        sendPresence()
        if (!authenticated) return
        val action = when (event.type) {
            PlaybackSyncEventType.TRACK -> PlaybackPayload.ACTION_TRACK
            PlaybackSyncEventType.PLAY -> PlaybackPayload.ACTION_PLAY
            PlaybackSyncEventType.PAUSE -> PlaybackPayload.ACTION_PAUSE
            PlaybackSyncEventType.SEEK -> PlaybackPayload.ACTION_SEEK
            PlaybackSyncEventType.NEXT -> PlaybackPayload.ACTION_NEXT
            PlaybackSyncEventType.PREVIOUS -> PlaybackPayload.ACTION_PREVIOUS
            PlaybackSyncEventType.QUEUE -> PlaybackPayload.ACTION_QUEUE
        }
        val payload = PlaybackPayload(
            action = action,
            track = event.track?.toRemote(),
            queue = event.queue.map { it.toRemote() },
            positionMs = event.positionMs,
            playing = event.isPlaying,
            issuedAt = event.issuedAt,
            speed = event.speed
        )
        pendingPlayback = payload
        sendPlayback(payload)
    }

    private fun sendPlayback(payload: PlaybackPayload) {
        if (!authenticated) return
        val conn = connection ?: return
        val envelope = createEnvelope(
            channel = CoupleChannel.SYNC,
            type = CoupleProtocol.EVENT_PLAYBACK,
            payload = CoupleJson.encode(payload),
            sessionBound = true
        )
        if (conn.send(CoupleChannel.SYNC, CoupleJson.encode(envelope))) {
            pendingPlayback = null
        }
    }

    private fun correctDrift(forceSeek: Boolean = false) {
        val remote = lastRemotePlayback ?: return
        if (!remote.playing) return
        if (!forceSeek && !isFollower()) return
        val local = playerManager.playbackState.value
        if (local.currentTrack?.id != remote.track?.id) return
        val nowMs = now()
        val expected = PlaybackSyncPolicy.expectedPositionMs(
            basePositionMs = remote.positionMs,
            issuedAtRemoteMs = remote.issuedAt,
            localNowMs = nowMs,
            clock = clock,
            playing = remote.playing
        )
        val decision = if (forceSeek) {
            DriftCorrection(DriftDecision.SEEK, seekPositionMs = expected)
        } else {
            PlaybackSyncPolicy.decide(
                expectedPositionMs = expected,
                actualPositionMs = local.currentPositionMs,
                playing = local.isPlaying,
                lastSeekAtMs = lastSeekAt,
                nowMs = nowMs
            )
        }
        when (decision.decision) {
            DriftDecision.SEEK -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.SEEK,
                        positionMs = decision.seekPositionMs
                    )
                )
                lastSeekAt = nowMs
            }

            DriftDecision.SPEED_UP, DriftDecision.SLOW_DOWN -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.RATE,
                        speed = decision.speed
                    )
                )
                scheduleRateReset()
            }

            DriftDecision.NONE -> Unit
        }
        _uiState.update { it.copy(lastSyncAt = nowMs) }
    }

    private fun scheduleRateReset() {
        rateResetJob?.cancel()
        rateResetJob = scope.launch {
            delay(RATE_CORRECTION_WINDOW_MS)
            if (authenticated) {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(action = RemotePlaybackAction.RATE, speed = 1f)
                )
            }
        }
    }

    private fun isFollower(): Boolean {
        if (lastRemotePlaybackAtLocal == 0L) return false
        if (lastLocalPlaybackAt == 0L) return true
        if (lastRemotePlaybackAtLocal > lastLocalPlaybackAt) return true
        if (lastRemotePlaybackAtLocal < lastLocalPlaybackAt) return false
        val localId = identity?.deviceId.orEmpty()
        val peerId = activePeerEntity?.peerId.orEmpty()
        return localId > peerId
    }

    private fun startSyncLoops() {
        clockJob?.cancel()
        syncJob?.cancel()
        clockJob = scope.launch {
            while (isActive && authenticated) {
                sendClockPing()
                delay(CLOCK_PING_INTERVAL_MS)
            }
        }
        syncJob = scope.launch {
            while (isActive && authenticated) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                correctDrift()
            }
        }
    }

    private fun sendClockPing() {
        if (!authenticated) return
        val conn = connection ?: return
        val payload = ClockPayload(kind = ClockPayload.KIND_PING, t0 = now())
        val envelope = createEnvelope(
            channel = CoupleChannel.SYNC,
            type = CoupleProtocol.EVENT_CLOCK_PING,
            payload = CoupleJson.encode(payload),
            sessionBound = true
        )
        conn.send(CoupleChannel.SYNC, CoupleJson.encode(envelope))
    }

    private fun sendPresence(force: Boolean = false) {
        if (!authenticated) return
        val nowMs = now()
        if (!force && nowMs - lastPresenceAt < PRESENCE_THROTTLE_MS) return
        lastPresenceAt = nowMs
        val conn = connection ?: return
        val state = playerManager.playbackState.value
        val payload = PresencePayload(
            online = foreground,
            activity = if (state.isPlaying) "listening" else "idle",
            trackId = state.currentTrack?.id,
            displayName = localDisplayName
        )
        val envelope = createEnvelope(
            channel = CoupleChannel.PRESENCE,
            type = CoupleProtocol.EVENT_PRESENCE,
            payload = CoupleJson.encode(payload),
            sessionBound = true
        )
        conn.send(CoupleChannel.PRESENCE, CoupleJson.encode(envelope))
    }

    // ------------------------------------------------------------------------------------
    // Frame handling
    // ------------------------------------------------------------------------------------

    @Synchronized
    private fun onFrame(channel: CoupleChannel, raw: String) {
        val peer = activePeerEntity ?: return
        val validation = CoupleValidator.validate(raw, peer.peerId, now())
        val envelope = when (validation) {
            is CoupleValidation.Valid -> validation.envelope
            is CoupleValidation.Invalid -> {
                Log.w(TAG, "Rejected ${channel.wireName} frame: ${validation.reason}")
                return
            }
        }

        if (envelope.type in CoupleProtocol.sessionBoundTypes) {
            if (envelope.session != sessionId) return
            if (envelope.type != CoupleProtocol.EVENT_AUTH && !authenticated) return
        }

        if (!sequenceTracker.accept(channel.wireName, envelope.seq, envelope.id)) {
            // A sender may resend an unacknowledged chat message after reconnecting. The
            // message is already stored, so re-acknowledge it instead of reprocessing it.
            if (envelope.type == CoupleProtocol.EVENT_CHAT_MESSAGE) {
                scope.launch { if (messageDao.getMessage(envelope.id) != null) sendAck(envelope.id) }
            }
            return
        }

        scope.launch {
            if (envelope.type == CoupleProtocol.EVENT_CHAT_MESSAGE ||
                envelope.type == CoupleProtocol.EVENT_PLAYLIST
            ) {
                val inserted = eventDao.insert(
                    CoupleEventEntity(
                        eventId = envelope.id,
                        peerId = peer.peerId,
                        kind = envelope.type,
                        receivedAt = now()
                    )
                )
                if (inserted == -1L) return@launch
                pruneEventsIfNeeded()
            }
            dispatch(envelope)
        }
    }

    private suspend fun dispatch(envelope: CoupleEnvelope) {
        when (envelope.type) {
            CoupleProtocol.EVENT_AUTH -> handleAuth(envelope)
            CoupleProtocol.EVENT_CHAT_MESSAGE -> handleChat(envelope)
            CoupleProtocol.EVENT_CHAT_ACK -> handleAck(envelope)
            CoupleProtocol.EVENT_PRESENCE -> handlePresence(envelope)
            CoupleProtocol.EVENT_PLAYBACK -> handlePlayback(envelope)
            CoupleProtocol.EVENT_PLAYLIST -> handlePlaylist(envelope)
            CoupleProtocol.EVENT_CLOCK_PING -> handleClockPing(envelope)
            CoupleProtocol.EVENT_CLOCK_PONG -> handleClockPong(envelope)
        }
    }

    private suspend fun handleAuth(envelope: CoupleEnvelope) {
        if (authenticated) return
        val peer = activePeerEntity ?: return
        val session = sessionId ?: return
        val payload = CoupleJson.decode<AuthPayload>(envelope.payload)
            ?: return failAuthentication("Malformed authentication")
        if (payload.deviceId != peer.peerId ||
            payload.publicKey != peer.publicKey ||
            payload.pairingId != peer.pairingId ||
            payload.session != session
        ) {
            return failAuthentication("Authentication identity mismatch")
        }
        val key = CoupleCrypto.publicKeyFromBase64(payload.publicKey)
            ?: return failAuthentication("Invalid identity key")
        if (CoupleCrypto.deviceId(key) != payload.deviceId) {
            return failAuthentication("Identity does not match its key")
        }
        val signature = CoupleBase64.decode(payload.signature)
            ?: return failAuthentication("Invalid authentication signature")
        val transcript = CoupleTranscripts.auth(
            pairingId = payload.pairingId,
            session = payload.session,
            deviceId = payload.deviceId,
            publicKeyBase64 = payload.publicKey,
            nonce = payload.nonce
        )
        if (!CoupleCrypto.verify(key, transcript, signature)) {
            return failAuthentication("Authentication signature rejected")
        }

        authenticated = true
        authHelloSent = true
        peerDao.markConnected(peer.peerId, now())
        val remoteName = CoupleCrypto.sanitizeDisplayName(payload.displayName)
        if (remoteName.isNotBlank() && remoteName != peer.displayName) {
            peerDao.upsert(peer.copy(displayName = remoteName))
        }
        _uiState.update {
            it.copy(
                connectionState = CoupleConnectionState.CONNECTED,
                connectionDetail = "Encrypted peer-to-peer session",
                authenticated = true,
                error = null
            )
        }
        startSyncLoops()
        flushPendingChat()
        sendPlaylistSnapshot()
        sendPresence(force = true)
        refreshNowPlaying()
    }

    private fun failAuthentication(reason: String) {
        _uiState.update {
            it.copy(
                connectionState = CoupleConnectionState.FAILED,
                connectionDetail = reason,
                authenticated = false
            )
        }
        connection?.close()
        connection = null
        authenticated = false
    }

    private suspend fun handleChat(envelope: CoupleEnvelope) {
        val peer = activePeerEntity ?: return
        val payload = CoupleJson.decode<ChatPayload>(envelope.payload) ?: return
        val body = CoupleValidator.sanitizeChatBody(payload.body)
        if (body.isBlank()) return
        // Prefer the sender's compose time for display, but never trust it blindly: a
        // wildly future timestamp falls back to the validated envelope time.
        val composedAt = payload.sentAt.takeIf {
            it > 0L && it <= envelope.ts + CoupleProtocol.MAX_CLOCK_SKEW_MS
        } ?: envelope.ts
        val inserted = messageDao.insertMessage(
            CoupleMessageEntity(
                eventId = envelope.id,
                peerId = peer.peerId,
                direction = CoupleMessageEntity.DIRECTION_IN,
                body = body,
                createdAt = composedAt,
                sentAt = now(),
                deliveredAt = now(),
                deliveryState = DeliveryState.DELIVERED,
                sequence = envelope.seq
            )
        )
        peerDao.setLastRemoteChatSeq(peer.peerId, envelope.seq)
        if (inserted != -1L && !chatVisible) {
            _uiState.update { it.copy(unreadCount = it.unreadCount + 1) }
        }
        sendAck(envelope.id)
    }

    private suspend fun handleAck(envelope: CoupleEnvelope) {
        val payload = CoupleJson.decode<ChatAckPayload>(envelope.payload) ?: return
        messageDao.markDelivered(payload.ack, now())
    }

    private suspend fun handlePresence(envelope: CoupleEnvelope) {
        val peer = activePeerEntity ?: return
        val payload = CoupleJson.decode<PresencePayload>(envelope.payload) ?: return
        val listening = payload.online && payload.activity == "listening"
        peerDao.markSeen(peer.peerId, now())
        val remoteName = CoupleCrypto.sanitizeDisplayName(payload.displayName)
        if (remoteName.isNotBlank() && remoteName != peer.displayName) {
            peerDao.upsert(peer.copy(displayName = remoteName))
        }
        _uiState.update {
            it.copy(
                peerListening = listening,
                nowPlaying = it.nowPlaying?.copy(peerListening = listening)
            )
        }
    }

    private suspend fun handlePlayback(envelope: CoupleEnvelope) {
        val payload = CoupleJson.decode<PlaybackPayload>(envelope.payload) ?: return
        if (clock.hasSample() && lastLocalPlaybackAt > 0L) {
            val remoteIssuedLocal = clock.remoteToLocal(payload.issuedAt)
            if (lastLocalPlaybackAt > remoteIssuedLocal + STALE_COMMAND_MS) return
        }

        when (payload.action) {
            PlaybackPayload.ACTION_TRACK -> {
                val remoteTrack = payload.track?.toTrack() ?: return
                val queue = payload.queue.map { it.toTrack() }.ifEmpty { listOf(remoteTrack) }
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.TRACK,
                        track = remoteTrack,
                        queue = queue,
                        positionMs = payload.positionMs,
                        playing = payload.playing,
                        speed = payload.speed
                    )
                )
                lastRemotePlayback = payload
            }

            PlaybackPayload.ACTION_PLAY, PlaybackPayload.ACTION_RESUME -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.PLAY,
                        positionMs = payload.positionMs,
                        playing = true
                    )
                )
                lastRemotePlayback = payload.copy(playing = true)
            }

            PlaybackPayload.ACTION_PAUSE -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(action = RemotePlaybackAction.PAUSE)
                )
                lastRemotePlayback = payload.copy(playing = false)
            }

            PlaybackPayload.ACTION_SEEK -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.SEEK,
                        positionMs = payload.positionMs
                    )
                )
                lastRemotePlayback = payload
            }

            PlaybackPayload.ACTION_NEXT -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(action = RemotePlaybackAction.NEXT)
                )
            }

            PlaybackPayload.ACTION_PREVIOUS -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(action = RemotePlaybackAction.PREVIOUS)
                )
            }

            PlaybackPayload.ACTION_QUEUE -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.QUEUE,
                        queue = payload.queue.map { it.toTrack() }
                    )
                )
            }

            PlaybackPayload.ACTION_RATE -> {
                playerManager.applyRemotePlayback(
                    RemotePlaybackCommand(
                        action = RemotePlaybackAction.RATE,
                        speed = payload.speed
                    )
                )
            }
        }

        lastRemotePlaybackAtLocal = now()
        _uiState.update { it.copy(lastSyncAt = now()) }
        refreshNowPlaying()
    }

    private suspend fun handlePlaylist(envelope: CoupleEnvelope) {
        val peer = activePeerEntity ?: return
        val payload = CoupleJson.decode<PlaylistPayload>(envelope.payload) ?: return
        if (payload.actor != envelope.sender) return
        lamport.observe(maxOf(payload.lamport, envelope.seq))
        val current = sharedTrackDao.getAll(peer.peerId)

        when (payload.op) {
            PlaylistPayload.OP_ADD -> {
                val item = payload.track ?: return
                if (item.updatedBy.isNotBlank() && item.updatedBy != envelope.sender) return
                val incoming = item.toEntity(peer.peerId, now())
                val result = CouplePlaylistMerge.applyAdd(current, incoming)
                if (result.accepted) {
                    result.tracks.firstOrNull { it.trackId == item.trackId }
                        ?.let { sharedTrackDao.upsert(it) }
                }
            }

            PlaylistPayload.OP_REMOVE -> {
                val result = CouplePlaylistMerge.applyRemove(
                    current = current,
                    peerId = peer.peerId,
                    trackId = payload.trackId,
                    lamport = payload.lamport,
                    actor = payload.actor,
                    now = now()
                )
                if (result.accepted) {
                    result.tracks.firstOrNull { it.trackId == payload.trackId }
                        ?.let { sharedTrackDao.upsert(it) }
                }
            }

            PlaylistPayload.OP_MOVE -> {
                val result = CouplePlaylistMerge.applyOrder(
                    current = current,
                    order = payload.order,
                    lamport = payload.lamport,
                    actor = payload.actor,
                    currentOrderLamport = peer.playlistOrderLamport,
                    currentOrderActor = peer.playlistOrderActor
                )
                if (result.accepted) {
                    sharedTrackDao.upsertAll(result.tracks)
                    peerDao.setPlaylistOrderVersion(peer.peerId, payload.lamport, payload.actor)
                    activePeerEntity = peer.copy(
                        playlistOrderLamport = payload.lamport,
                        playlistOrderActor = payload.actor
                    )
                }
            }

            PlaylistPayload.OP_SNAPSHOT -> {
                val incoming = payload.items.map { it.toEntity(peer.peerId, now()) }
                val result = CouplePlaylistMerge.applySnapshot(
                    current = current,
                    incoming = incoming,
                    order = payload.order,
                    lamport = payload.lamport,
                    actor = payload.actor,
                    currentOrderLamport = peer.playlistOrderLamport,
                    currentOrderActor = peer.playlistOrderActor
                )
                if (result.accepted) {
                    sharedTrackDao.upsertAll(result.tracks)
                    if (payload.order.isNotEmpty() && versionIsNewer(
                            payload.lamport,
                            payload.actor,
                            peer.playlistOrderLamport,
                            peer.playlistOrderActor
                        )
                    ) {
                        peerDao.setPlaylistOrderVersion(peer.peerId, payload.lamport, payload.actor)
                        activePeerEntity = peer.copy(
                            playlistOrderLamport = payload.lamport,
                            playlistOrderActor = payload.actor
                        )
                    }
                }
            }
        }
        peerDao.setLastRemotePlaylistSeq(peer.peerId, envelope.seq)
    }

    private suspend fun handleClockPing(envelope: CoupleEnvelope) {
        val payload = CoupleJson.decode<ClockPayload>(envelope.payload) ?: return
        val conn = connection ?: return
        val reply = ClockPayload(
            kind = ClockPayload.KIND_PONG,
            t0 = payload.t0,
            t1 = now(),
            t2 = now()
        )
        val replyEnvelope = createEnvelope(
            channel = CoupleChannel.SYNC,
            type = CoupleProtocol.EVENT_CLOCK_PONG,
            payload = CoupleJson.encode(reply),
            sessionBound = true
        )
        conn.send(CoupleChannel.SYNC, CoupleJson.encode(replyEnvelope))
    }

    private suspend fun handleClockPong(envelope: CoupleEnvelope) {
        val payload = CoupleJson.decode<ClockPayload>(envelope.payload) ?: return
        clock.onPong(payload.t0, payload.t1, payload.t2, now())
        _uiState.update {
            it.copy(
                clockOffsetMs = clock.offsetMs(),
                clockRttMs = clock.bestSample()?.rttMs ?: 0L,
                lastSyncAt = now()
            )
        }
        refreshNowPlaying()
    }

    private fun pruneEventsIfNeeded() {
        val nowMs = now()
        if (nowMs - lastEventPruneAt < EVENT_PRUNE_INTERVAL_MS) return
        lastEventPruneAt = nowMs
        scope.launch {
            eventDao.prune(nowMs - CoupleProtocol.MAX_QUEUED_AGE_MS)
        }
    }

    // ------------------------------------------------------------------------------------
    // Transport callbacks
    // ------------------------------------------------------------------------------------

    private fun handleTransportConnected() {
        if (authenticated) return
        transportConnected = true
        if (_uiState.value.connectionState != CoupleConnectionState.CONNECTED) {
            _uiState.update {
                it.copy(
                    connectionState = CoupleConnectionState.AUTHENTICATING,
                    connectionDetail = "Verifying the paired identity…"
                )
            }
        }
        sendAuthHello()
    }

    private fun handleTransportDisconnected() {
        if (connection == null) return
        _uiState.update {
            it.copy(
                connectionState = CoupleConnectionState.RECONNECTING,
                connectionDetail = "Network changed. Trying to recover the encrypted session…"
            )
        }
        connection?.restartIce()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_GRACE_MS)
            if (!authenticated && _uiState.value.connectionState == CoupleConnectionState.RECONNECTING) {
                _uiState.update {
                    it.copy(
                        connectionState = CoupleConnectionState.FAILED,
                        connectionDetail = "Connection lost. Create a fresh connection code."
                    )
                }
            }
        }
    }

    private fun handleTransportFailed(reason: String) {
        _uiState.update {
            it.copy(
                connectionState = CoupleConnectionState.FAILED,
                connectionDetail = reason
            )
        }
    }

    private fun handleTransportClosed() {
        _uiState.update { it.copy(connectionState = CoupleConnectionState.OFFLINE) }
    }

    private fun handleChannelOpen(channel: CoupleChannel) {
        when (channel) {
            CoupleChannel.CONTROL -> sendAuthHello()
            CoupleChannel.CHAT -> if (authenticated) flushPendingChat()
            CoupleChannel.PLAYLIST -> if (authenticated) sendPlaylistSnapshot()
            CoupleChannel.PRESENCE -> if (authenticated) sendPresence(force = true)
            CoupleChannel.SYNC -> {
                if (authenticated) {
                    pendingPlayback?.let { payload ->
                        sendPlayback(payload)
                    }
                    sendClockPing()
                }
            }
        }
    }

    private fun sendAuthHello() {
        if (authenticated || authHelloSent) return
        val id = identity ?: return
        val peer = activePeerEntity ?: return
        val session = sessionId ?: return
        val conn = connection ?: return
        if (!conn.isOpen(CoupleChannel.CONTROL)) return
        authHelloSent = true
        scope.launch {
            val nonce = CoupleEventIds.newNonce()
            val publicKey = CoupleCrypto.publicKeyBase64(id.publicKey)
            val signature = signBase64(
                CoupleTranscripts.auth(
                    pairingId = peer.pairingId,
                    session = session,
                    deviceId = id.deviceId,
                    publicKeyBase64 = publicKey,
                    nonce = nonce
                )
            )
            val payload = AuthPayload(
                deviceId = id.deviceId,
                publicKey = publicKey,
                pairingId = peer.pairingId,
                session = session,
                nonce = nonce,
                signature = signature,
                displayName = localDisplayName
            )
            val envelope = createEnvelope(
                channel = CoupleChannel.CONTROL,
                type = CoupleProtocol.EVENT_AUTH,
                payload = CoupleJson.encode(payload),
                sessionBound = true
            )
            if (!conn.send(CoupleChannel.CONTROL, CoupleJson.encode(envelope))) {
                authHelloSent = false
            }
        }
    }

    private fun newConnection(): CouplePeerConnection = CouplePeerConnection(
        context = context,
        listener = object : CouplePeerConnection.Listener {
            override fun onTransportConnected() = handleTransportConnected()
            override fun onTransportDisconnected() = handleTransportDisconnected()
            override fun onTransportFailed(reason: String) = handleTransportFailed(reason)
            override fun onTransportClosed() = handleTransportClosed()
            override fun onChannelOpen(channel: CoupleChannel) = handleChannelOpen(channel)
            override fun onMessage(channel: CoupleChannel, text: String) = onFrame(channel, text)
        },
        turn = turn
    )

    private fun disconnectInternal() {
        backgroundJob?.cancel()
        reconnectJob?.cancel()
        clockJob?.cancel()
        syncJob?.cancel()
        rateResetJob?.cancel()
        connection?.close()
        connection = null
        authenticated = false
        transportConnected = false
        authHelloSent = false
        sessionId = null
        sequenceTracker.resetSessionChannels()
        controlSeq.set(0)
        presenceSeq.set(0)
        syncSeq.set(0)
        clock.reset()
        lastRemotePlayback = null
        pendingPlayback = null
        lastRemotePlaybackAtLocal = 0L
        lastLocalPlaybackAt = 0L
        lastSeekAt = 0L
        _uiState.update {
            it.copy(
                connectionState = CoupleConnectionState.OFFLINE,
                connectionDetail = null,
                authenticated = false,
                peerListening = false,
                signalState = CoupleSignalState()
            )
        }
        refreshNowPlaying()
    }

    // ------------------------------------------------------------------------------------
    // Deep links, identity and helpers
    // ------------------------------------------------------------------------------------

    fun handleDeepLink(raw: String?) {
        if (raw.isNullOrBlank()) return
        val normalized = CoupleCodec.normalizeIncoming(raw) ?: return
        val link = CoupleCodec.decodeLink(normalized) ?: return
        if (link.kind == CoupleCodec.KIND_EVENT) return
        _incomingCode.value = CoupleIncomingCode(kind = link.kind, code = normalized)
    }

    fun consumeIncomingCode() {
        _incomingCode.value = null
    }

    fun setDisplayName(name: String) {
        val clean = CoupleCrypto.sanitizeDisplayName(name)
        if (clean.isBlank()) return
        localDisplayName = clean
        scope.launch {
            preferences.setCoupleDisplayName(clean)
            _uiState.update { it.copy(localDisplayName = clean) }
            if (authenticated) sendPresence(force = true)
        }
    }

    fun setLanReconnectEnabled(enabled: Boolean) {
        scope.launch {
            preferences.setCoupleLanReconnectEnabled(enabled)
            _uiState.update { it.copy(lanReconnectEnabled = enabled) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun requireIdentity(): CoupleIdentity {
        identity?.let { return it }
        return withContext(Dispatchers.IO) {
            identityStore.getOrCreate().also {
                identity = it
                _uiState.update { state -> state.copy(identity = it) }
            }
        }
    }

    private suspend fun signBase64(data: ByteArray): String = withContext(Dispatchers.IO) {
        CoupleBase64.encode(identityStore.sign(data))
    }

    private fun createEnvelope(
        channel: CoupleChannel,
        type: String,
        payload: String,
        sessionBound: Boolean
    ): CoupleEnvelope {
        val id = identity ?: error("The secure identity is not ready")
        return CoupleEnvelope(
            v = CoupleProtocol.VERSION,
            id = CoupleEventIds.newEventId(),
            sender = id.deviceId,
            type = type,
            seq = nextSequence(channel),
            ts = now(),
            session = if (sessionBound) sessionId else null,
            payload = payload
        )
    }

    private fun nextSequence(channel: CoupleChannel): Long = when (channel) {
        CoupleChannel.CONTROL -> controlSeq.incrementAndGet()
        CoupleChannel.PRESENCE -> presenceSeq.incrementAndGet()
        CoupleChannel.SYNC -> syncSeq.incrementAndGet()
        CoupleChannel.CHAT -> chatSeq.incrementAndGet().also { sequence ->
            activePeerEntity?.peerId?.let { peerId ->
                scope.launch { peerDao.setLocalChatSeq(peerId, sequence) }
            }
        }

        CoupleChannel.PLAYLIST -> playlistSeq.incrementAndGet().also { sequence ->
            activePeerEntity?.peerId?.let { peerId ->
                scope.launch { peerDao.setLocalPlaylistSeq(peerId, sequence) }
            }
        }
    }

    private fun updateNowPlaying(state: com.example.playback.PlaybackState) {
        val track = state.currentTrack
        _uiState.update {
            it.copy(
                nowPlaying = track?.let { current ->
                    CoupleNowPlaying(
                        track = current,
                        positionMs = state.currentPositionMs,
                        isPlaying = state.isPlaying,
                        synced = authenticated && clock.hasSample(),
                        peerListening = it.peerListening,
                        unavailableReason = state.errorMessage
                    )
                }
            )
        }
    }

    private fun refreshNowPlaying() {
        updateNowPlaying(playerManager.playbackState.value)
    }

    private fun fingerprintOf(publicKeyBase64: String): String =
        CoupleCrypto.publicKeyFromBase64(publicKeyBase64)
            ?.let { CoupleCrypto.fingerprint(it) }
            .orEmpty()

    private fun now(): Long = System.currentTimeMillis()

    private fun Track.toRemote(): RemoteTrackPayload = RemoteTrackPayload(
        id = id,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        artworkUrl = artworkUrl
    )

    private fun RemoteTrackPayload.toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        durationSeconds = durationSeconds,
        artworkUrl = artworkUrl
    )

    private fun CoupleSharedTrackEntity.toPayload(): PlaylistItemPayload = PlaylistItemPayload(
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

    private fun PlaylistItemPayload.toEntity(peerId: String, now: Long): CoupleSharedTrackEntity =
        CoupleSharedTrackEntity(
            peerId = peerId,
            trackId = trackId,
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            artworkUrl = artworkUrl,
            position = position,
            lamport = lamport,
            updatedBy = updatedBy.ifBlank { actorFallback },
            updatedAt = now,
            deleted = deleted
        )

    private val actorFallback: String
        get() = activePeerEntity?.peerId.orEmpty()

    private companion object {
        private const val TAG = "CoupleSpace"

        private const val BACKGROUND_GRACE_MS = 3_000L
        private const val RECONNECT_GRACE_MS = 8_000L
        private const val CLOCK_PING_INTERVAL_MS = 4_000L
        private const val DRIFT_CHECK_INTERVAL_MS = 5_000L
        private const val RATE_CORRECTION_WINDOW_MS = 6_000L
        private const val PRESENCE_THROTTLE_MS = 2_000L
        private const val STALE_COMMAND_MS = 900L
        private const val PAIRING_RESTORE_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val EVENT_PRUNE_INTERVAL_MS = 60L * 60 * 1000
        private const val LAN_AUTO_RETRY_INTERVAL_MS = 30_000L
    }
}
