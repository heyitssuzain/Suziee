package com.example.ui.couple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couple.CoupleCodec
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard
import kotlinx.coroutines.launch

/**
 * Manual signed-code connection plus the opt-in same-Wi-Fi shortcut.
 *
 * The two paths produce exactly the same WebRTC session: the LAN path only replaces the
 * human copying step. It is never a background service, and it still verifies the signed
 * offer against the persisted pairing key.
 */
@Composable
fun CoupleConnectionScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    initialCode: String?,
    onConsumed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf(initialCode.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var lanBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialCode) {
        if (!initialCode.isNullOrBlank()) pasted = initialCode
    }

    fun useCode(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) {
            localError = "Paste or scan a connection code first"
            return
        }
        busy = true
        localError = null
        scope.launch {
            controller.applyConnectionCode(clean)
                .onSuccess { onConsumed() }
                .onFailure { localError = it.message ?: "That connection code was rejected" }
            busy = false
        }
    }

    fun createOffer() {
        busy = true
        localError = null
        scope.launch {
            controller.createConnectionCode()
                .onFailure { localError = it.message ?: "Could not create an offer" }
            busy = false
        }
    }

    fun reconnectLan() {
        lanBusy = true
        localError = null
        scope.launch {
            controller.connectOverLan()
                .onFailure { localError = it.message ?: "No paired device answered on this Wi-Fi" }
            lanBusy = false
        }
    }

    val scanner = rememberQrScanner { decoded ->
        if (decoded != null) {
            pasted = decoded
            useCode(decoded)
        }
    }

    CoupleScreenScaffold(
        title = "Connect",
        subtitle = "Open the encrypted session, then chat and listen together.",
        onBack = onBack
    ) {
        if (localError != null) {
            CoupleErrorBanner(message = localError!!, onDismiss = { localError = null })
            Spacer(modifier = Modifier.height(14.dp))
        }

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = "Session status",
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CoupleStatusPill(
                        state = state.connectionState,
                        authenticated = state.authenticated
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = state.connectionDetail
                        ?: if (state.authenticated) {
                            "The DataChannels are open and authenticated."
                        } else {
                            "No session yet. Use a signed offer and answer code below."
                        },
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
                if (state.authenticated) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CouplePrimaryButton(
                        text = "Disconnect",
                        onClick = controller::disconnect,
                        icon = Icons.Filled.Sync
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = "Same Wi-Fi reconnect",
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Optional. When both phones are on the same Wi-Fi and the app is open, " +
                        "Suziee broadcasts a tiny discovery packet and exchanges the same signed codes " +
                        "over the local network. Nothing is sent to a server.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Enable Wi-Fi reconnect",
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
                Spacer(modifier = Modifier.height(10.dp))
                CouplePrimaryButton(
                    text = "Look for ${state.peer?.displayName ?: "my person"}",
                    onClick = { reconnectLan() },
                    enabled = state.lanReconnectEnabled,
                    loading = lanBusy,
                    icon = Icons.Filled.Wifi
                )
                if (!state.lanReconnectEnabled) {
                    Text(
                        text = "Turn the switch on first. This path is deliberately off by default.",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Signed connection codes",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "One phone creates an offer, the other replies with an answer. " +
                        "The whole SDP is signed, so the codes can travel through any chat app.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                CouplePrimaryButton(
                    text = "Create a fresh offer",
                    onClick = { createOffer() },
                    loading = busy,
                    icon = Icons.Filled.QrCodeScanner
                )
            }
        }

        val localCode = state.signalState.localCode
        if (!localCode.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            CoupleCodeCard(
                title = if (state.signalState.localKind == CoupleCodec.KIND_ANSWER) {
                    "Answer code — send this back"
                } else {
                    "Offer code — send this to your person"
                },
                code = localCode,
                hint = state.signalState.hint
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = if (localCode.isNullOrBlank()) {
                "Have a code from your person?"
            } else {
                "Paste their answer or offer"
            },
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        CoupleScanButtons(scanner = scanner)
        Spacer(modifier = Modifier.height(12.dp))
        CoupleTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = "Connection code",
            placeholder = "Paste the suziee://couple/... code",
            singleLine = false,
            minLines = 3,
            maxLines = 6
        )
        Spacer(modifier = Modifier.height(10.dp))
        CouplePrimaryButton(
            text = "Use this code",
            onClick = { useCode(pasted) },
            loading = busy,
            icon = Icons.Filled.Check
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "If the two phones are on different networks, the manual code exchange is the " +
                "supported path. There is no relay or rendezvous server in this build.",
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}
