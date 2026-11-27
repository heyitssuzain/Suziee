package com.example.ui.couple

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couple.CoupleCodec
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.playback.MusicPlayerManager
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard

/**
 * The 11 Couple Space screens and their lightweight internal navigation.
 *
 * The screens are intentionally not a second Navigation-Compose graph: the destination is
 * one tab, and a small back stack keeps the pairing flow from leaking into the app's main
 * navigation. Back always returns to the Couple overview.
 */
private enum class CouplePage {
    OVERVIEW,
    PAIRING_START,
    INVITE,
    JOIN,
    CONFIRM,
    CONNECTION,
    CHAT,
    PLAYLIST,
    NOW_PLAYING,
    PEER,
    SETTINGS
}

@Composable
fun CoupleSpaceScreen(
    controller: CoupleSpaceController,
    playerManager: MusicPlayerManager,
    onImmersiveChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by controller.uiState.collectAsState()
    val incoming by controller.incomingCode.collectAsState()

    var page by remember { mutableStateOf(CouplePage.OVERVIEW) }
    var incomingCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incoming?.code) {
        val code = incoming ?: return@LaunchedEffect
        incomingCode = code.code
        page = when (code.kind) {
            CoupleCodec.KIND_INVITE -> CouplePage.JOIN
            CoupleCodec.KIND_ACCEPT, CoupleCodec.KIND_CONFIRM -> CouplePage.CONFIRM
            CoupleCodec.KIND_OFFER, CoupleCodec.KIND_ANSWER -> CouplePage.CONNECTION
            else -> CouplePage.OVERVIEW
        }
        // Route once. The local `incomingCode` keeps the value for the destination screen,
        // while the controller's queue is cleared so returning to the tab does not replay it.
        controller.consumeIncomingCode()
    }

    // A peer-dependent screen is meaningless after unpairing; return to the pairing entry
    // point rather than showing an empty chat or queue.
    LaunchedEffect(state.hasPeer, page) {
        val requiresPeer = page in setOf(
            CouplePage.CONNECTION,
            CouplePage.CHAT,
            CouplePage.PLAYLIST,
            CouplePage.NOW_PLAYING,
            CouplePage.PEER,
            CouplePage.SETTINGS
        )
        if (!state.hasPeer && requiresPeer) page = CouplePage.PAIRING_START
    }

    LaunchedEffect(page) {
        onImmersiveChange(page != CouplePage.OVERVIEW)
    }
    DisposableEffect(Unit) {
        onDispose { onImmersiveChange(false) }
    }

    BackHandler(enabled = page != CouplePage.OVERVIEW) {
        page = CouplePage.OVERVIEW
        incomingCode = null
        controller.consumeIncomingCode()
    }

    val backToOverview: () -> Unit = {
        page = CouplePage.OVERVIEW
        incomingCode = null
        controller.consumeIncomingCode()
    }

    when (page) {
        CouplePage.OVERVIEW -> CoupleOverviewScreen(
            state = state,
            onNavigate = { page = it },
            onDismissError = controller::clearError,
            modifier = modifier
        )

        CouplePage.PAIRING_START -> CouplePairingStartScreen(
            controller = controller,
            state = state,
            onBack = backToOverview,
            onInvite = { page = CouplePage.INVITE },
            onJoin = { page = CouplePage.JOIN },
            modifier = modifier
        )

        CouplePage.INVITE -> CoupleInviteScreen(
            controller = controller,
            state = state,
            onBack = backToOverview,
            onDone = backToOverview,
            modifier = modifier
        )

        CouplePage.JOIN -> CoupleJoinScreen(
            controller = controller,
            state = state,
            initialCode = incomingCode,
            onConsumed = {
                incomingCode = null
                controller.consumeIncomingCode()
            },
            onBack = backToOverview,
            onDone = backToOverview,
            modifier = modifier
        )

        CouplePage.CONFIRM -> CoupleConfirmScreen(
            controller = controller,
            state = state,
            initialCode = incomingCode,
            onConsumed = {
                incomingCode = null
                controller.consumeIncomingCode()
            },
            onBack = backToOverview,
            onDone = backToOverview,
            modifier = modifier
        )

        CouplePage.CONNECTION -> CoupleConnectionScreen(
            controller = controller,
            state = state,
            initialCode = incomingCode,
            onConsumed = {
                incomingCode = null
                controller.consumeIncomingCode()
            },
            onBack = backToOverview,
            modifier = modifier
        )

        CouplePage.CHAT -> CoupleChatScreen(
            controller = controller,
            state = state,
            onBack = backToOverview,
            modifier = modifier
        )

        CouplePage.PLAYLIST -> CouplePlaylistScreen(
            controller = controller,
            playerManager = playerManager,
            state = state,
            onBack = backToOverview,
            modifier = modifier
        )

        CouplePage.NOW_PLAYING -> CoupleNowPlayingScreen(
            controller = controller,
            playerManager = playerManager,
            state = state,
            onBack = backToOverview,
            modifier = modifier
        )

        CouplePage.PEER -> CouplePeerScreen(
            controller = controller,
            state = state,
            onBack = backToOverview,
            onUnpaired = backToOverview,
            modifier = modifier
        )

        CouplePage.SETTINGS -> CoupleSettingsScreen(
            controller = controller,
            state = state,
            onBack = backToOverview,
            modifier = modifier
        )
    }
}

@Composable
private fun CoupleOverviewScreen(
    state: CoupleUiState,
    onNavigate: (CouplePage) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors

    CoupleScreenScaffold(
        title = "Couple Space",
        subtitle = "A private room for two, without an account or a server."
    ) {
        if (state.error != null) {
            CoupleErrorBanner(message = state.error, onDismiss = onDismissError)
            Spacer(modifier = Modifier.height(14.dp))
        }

        val peer = state.peer
        if (peer == null) {
            ThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.size(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = coupleAccentText(),
                            modifier = Modifier.size(46.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Your music, together",
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Pair two phones directly. Chat, build a shared queue and listen in sync — " +
                            "the connection is encrypted and nothing is stored in the cloud.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    CouplePrimaryButton(
                        text = "Pair a device",
                        onClick = { onNavigate(CouplePage.PAIRING_START) },
                        icon = Icons.Filled.Lock
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            CoupleHowItWorks()
            return@CoupleScreenScaffold
        }

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoupleAvatar(name = peer.displayName, size = 50.dp)
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.displayName,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = peer.fingerprint,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    CoupleStatusPill(
                        state = state.connectionState,
                        authenticated = state.authenticated
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = state.connectionDetail
                        ?: if (state.authenticated) {
                            "Encrypted peer-to-peer session"
                        } else {
                            "Paired. Open a connection to talk and listen together."
                        },
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CouplePrimaryButton(
                        text = if (state.authenticated) "Open chat" else "Connect",
                        onClick = {
                            if (state.authenticated) onNavigate(CouplePage.CHAT)
                            else onNavigate(CouplePage.CONNECTION)
                        },
                        modifier = Modifier.weight(1f),
                        icon = if (state.authenticated) Icons.Filled.Chat else Icons.Filled.Wifi
                    )
                    CoupleSecondaryButton(
                        text = "Sync",
                        onClick = { onNavigate(CouplePage.NOW_PLAYING) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Sync
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoupleActionTile(
                icon = Icons.Filled.Chat,
                title = "Chat",
                subtitle = if (state.unreadCount > 0) {
                    "${state.unreadCount} unread"
                } else {
                    "Messages queue offline"
                },
                onClick = { onNavigate(CouplePage.CHAT) },
                modifier = Modifier.weight(1f),
                badge = if (state.unreadCount > 0) state.unreadCount.toString() else null
            )
            CoupleActionTile(
                icon = Icons.Filled.QueueMusic,
                title = "Shared queue",
                subtitle = "${state.sharedTracks.size} tracks",
                onClick = { onNavigate(CouplePage.PLAYLIST) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoupleActionTile(
                icon = Icons.Filled.GraphicEq,
                title = "Now playing",
                subtitle = if (state.nowPlaying != null) "In sync" else "Nothing playing",
                onClick = { onNavigate(CouplePage.NOW_PLAYING) },
                modifier = Modifier.weight(1f)
            )
            CoupleActionTile(
                icon = Icons.Filled.Wifi,
                title = "Connection",
                subtitle = if (state.authenticated) "Connected" else "Pairing codes",
                onClick = { onNavigate(CouplePage.CONNECTION) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoupleActionTile(
                icon = Icons.Filled.Person,
                title = "This pairing",
                subtitle = "Fingerprint and unpair",
                onClick = { onNavigate(CouplePage.PEER) },
                modifier = Modifier.weight(1f)
            )
            CoupleActionTile(
                icon = Icons.Filled.Settings,
                title = "Settings",
                subtitle = "Name, Wi-Fi, privacy",
                onClick = { onNavigate(CouplePage.SETTINGS) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        CoupleHowItWorks()
    }
}

@Composable
private fun CoupleHowItWorks() {
    val colors = SuzieeTheme.colors
    ThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "How the connection works",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            CoupleInfoRow("Identity", "Android Keystore EC P-256 key")
            CoupleInfoRow("Transport", "WebRTC DTLS DataChannels")
            CoupleInfoRow("Signalling", "Signed QR / deep-link codes")
            CoupleInfoRow("Storage", "On-device Room only")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "There is no central server. Global reconnects use the manual codes; " +
                    "same-Wi-Fi reconnect is an optional convenience, not a background service.",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}
