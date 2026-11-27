package com.example.ui.couple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard

@Composable
fun CouplePeerScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    onBack: () -> Unit,
    onUnpaired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    var showUnpairConfirm by remember { mutableStateOf(false) }
    val peer = state.peer

    CoupleScreenScaffold(
        title = "This pairing",
        subtitle = "A pairing is a long-lived trust record, not the temporary connection.",
        onBack = onBack
    ) {
        if (peer == null) {
            CoupleEmptyState(
                icon = Icons.Filled.Person,
                title = "No paired device",
                body = "Pair a phone first. Pairing survives app restarts and reconnects."
            )
            return@CoupleScreenScaffold
        }

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoupleAvatar(name = peer.displayName, size = 54.dp)
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.displayName,
                            color = colors.textPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (peer.isPaired) "Paired" else "Revoked",
                            color = if (peer.isPaired) colors.success else colors.error,
                            fontSize = 12.sp
                        )
                    }
                    CoupleStatusPill(
                        state = state.connectionState,
                        authenticated = state.authenticated
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SAFETY NUMBER",
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = peer.fingerprint,
                    color = coupleAccentText(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Read this aloud and check it matches the other phone before trusting the pairing.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))
                CoupleInfoRow("Device ID", shortDeviceId(peer.deviceId), mono = true)
                CoupleInfoRow("Pairing ID", shortDeviceId(peer.pairingId), mono = true)
                CoupleInfoRow("Paired", formatTimestamp(peer.pairedAt))
                CoupleInfoRow("Last connected", formatTimestamp(peer.lastConnectedAt ?: 0L))
                CoupleInfoRow("Last seen", formatRelative(peer.lastSeenAt))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "What is stored",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Only this phone's non-exportable Keystore key is private. The pairing " +
                        "record holds the remote public key, the pairing id and sequence counters. " +
                        "Message bodies and queue rows stay in the app's Room database.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        CoupleSecondaryButton(
            text = "Disconnect",
            onClick = controller::disconnect,
            enabled = state.connectionState != com.example.couple.CoupleConnectionState.OFFLINE
        )
        Spacer(modifier = Modifier.height(10.dp))
        CoupleSecondaryButton(
            text = "Unpair and delete keys",
            onClick = { showUnpairConfirm = true },
            icon = Icons.Filled.Delete
        )
        Text(
            text = "Unpairing deletes the Keystore identity, the peer record, this pairing's chat and " +
                "shared queue, and revokes the trust relationship. The other phone must pair again.",
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair this device?", color = colors.textPrimary) },
            text = {
                Text(
                    text = "The Keystore key is deleted and cannot be recovered. Chat history and the " +
                        "shared queue for this pairing are removed. Your music library is not affected.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairConfirm = false
                        controller.unpair()
                        onUnpaired()
                    }
                ) {
                    Text("Unpair", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
fun CoupleSettingsScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    var name by remember { mutableStateOf(state.localDisplayName) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    val pendingOutgoing = state.messages.count {
        it.outgoing && it.deliveryState != "DELIVERED"
    }

    CoupleScreenScaffold(
        title = "Couple settings",
        subtitle = "Local preferences. Nothing here is sent to a server.",
        onBack = onBack
    ) {
        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Display name",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                CoupleTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = "The name they see",
                    placeholder = "Your name"
                )
                Spacer(modifier = Modifier.height(10.dp))
                CouplePrimaryButton(
                    text = "Save name",
                    onClick = { controller.setDisplayName(name) },
                    enabled = name.isNotBlank() && name != state.localDisplayName
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Same-Wi-Fi reconnect",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Best-effort local discovery while both phones are on the same network. " +
                        "Off by default; it never runs as a background service.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.lanReconnectEnabled) "Enabled" else "Disabled",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.lanReconnectEnabled,
                        onCheckedChange = controller::setLanReconnectEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = coupleOnAccent(),
                            checkedTrackColor = coupleAccentContainer(),
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surfaceElevated,
                            uncheckedBorderColor = colors.border
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Privacy",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                CoupleInfoRow("Backend", "None")
                CoupleInfoRow("Accounts", "None")
                CoupleInfoRow("Chat storage", "On-device Room")
                CoupleInfoRow("Transport", "WebRTC DTLS + signed frames")
                CoupleInfoRow("Pairing keys", "Android Keystore")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Chat bodies, private keys and authentication material are never logged. " +
                        "Connection codes contain public keys and signed SDP only.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Diagnostics",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                CoupleInfoRow("Connection", state.connectionState.name)
                CoupleInfoRow("Detail", state.connectionDetail ?: "—")
                CoupleInfoRow("Authenticated", if (state.authenticated) "Yes" else "No")
                CoupleInfoRow("Clock offset", "${state.clockOffsetMs} ms")
                CoupleInfoRow("Clock RTT", "${state.clockRttMs} ms")
                CoupleInfoRow("Last sync", state.lastSyncAt?.let { formatRelative(it) } ?: "Never")
                CoupleInfoRow("Undelivered", "$pendingOutgoing outgoing")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        CoupleSecondaryButton(
            text = "Clear chat history",
            onClick = { showClearChatConfirm = true },
            enabled = state.messages.isNotEmpty(),
            icon = Icons.Filled.Delete
        )
        Text(
            text = "Clearing chat removes messages on this phone only. The other phone keeps its copy.",
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text("Clear chat history?", color = colors.textPrimary) },
            text = {
                Text(
                    text = "This removes every message in this pairing on this phone. It does not " +
                        "change the other phone's history.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.clearChat()
                        showClearChatConfirm = false
                    }
                ) {
                    Text("Clear", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}
