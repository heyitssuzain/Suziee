package com.example.ui.couple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.couple.CouplePairingState
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard
import kotlinx.coroutines.launch

@Composable
fun CouplePairingStartScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    onBack: () -> Unit,
    onInvite: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val scope = rememberCoroutineScope()
    var name by remember {
        mutableStateOf(if (state.localDisplayName == "Me") "" else state.localDisplayName)
    }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    CoupleScreenScaffold(
        title = "Pair two phones",
        subtitle = "Three short codes. No account, no phone number, no server.",
        onBack = onBack
    ) {
        if (state.hasPeer) {
            CoupleErrorBanner(
                message = "A device is already paired. Unpair it in “This pairing” before starting a new one.",
                onDismiss = {}
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
        if (localError != null) {
            CoupleErrorBanner(message = localError!!, onDismiss = { localError = null })
            Spacer(modifier = Modifier.height(14.dp))
        }

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = "Your name on their phone",
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                CoupleTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = "Display name",
                    placeholder = "e.g. Suziee"
                )
                Spacer(modifier = Modifier.height(14.dp))
                CouplePrimaryButton(
                    text = "Create an invitation",
                    onClick = {
                        busy = true
                        localError = null
                        scope.launch {
                            controller.createInvite(name)
                                .onSuccess { onInvite() }
                                .onFailure { localError = it.message ?: "Could not create the invitation" }
                            busy = false
                        }
                    },
                    enabled = !state.hasPeer,
                    loading = busy,
                    icon = Icons.Filled.Favorite
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Already have their invitation?",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Scan the QR code they show, or paste the code they send you.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                CoupleSecondaryButton(
                    text = "Join with an invitation",
                    onClick = onJoin,
                    enabled = !state.hasPeer,
                    icon = Icons.Filled.QrCodeScanner
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        CouplePairingSteps()
    }
}

@Composable
fun CoupleInviteScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun applyReply(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) {
            localError = "Paste or scan the reply code first"
            return
        }
        busy = true
        localError = null
        scope.launch {
            controller.applyReply(clean)
                .onFailure { localError = it.message ?: "That reply could not be verified" }
            busy = false
        }
    }

    val scanner = rememberQrScanner { decoded ->
        if (decoded != null) {
            pasted = decoded
            applyReply(decoded)
        }
    }

    CoupleScreenScaffold(
        title = "Your invitation",
        subtitle = "Send this to your person. It expires in 15 minutes.",
        onBack = onBack
    ) {
        if (localError != null) {
            CoupleErrorBanner(message = localError!!, onDismiss = { localError = null })
            Spacer(modifier = Modifier.height(14.dp))
        }

        when (val pairing = state.pairingState) {
            is CouplePairingState.InviteCreated -> {
                CoupleCodeCard(
                    title = "1 · Invitation code",
                    code = pairing.code,
                    hint = "They scan this, then send you a reply code."
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "2 · Scan their reply",
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
                    label = "Reply code",
                    placeholder = "Paste the suziee://couple/... reply",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(10.dp))
                CouplePrimaryButton(
                    text = "Verify reply",
                    onClick = { applyReply(pasted) },
                    loading = busy,
                    icon = Icons.Filled.Check
                )
            }

            is CouplePairingState.ReplyScanned -> {
                CoupleCodeCard(
                    title = "3 · Confirmation code",
                    code = pairing.confirmationCode,
                    hint = "Send this back to ${pairing.peerName}. They confirm it on their phone."
                )
                Spacer(modifier = Modifier.height(14.dp))
                ThemedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Compare this fingerprint",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pairing.peerFingerprint,
                            color = coupleAccentText(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "It must match the fingerprint on ${pairing.peerName}'s phone.",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                CouplePrimaryButton(
                    text = "I've sent the confirmation",
                    onClick = {
                        controller.finishInviterPairing()
                        onDone()
                    },
                    icon = Icons.Filled.Check
                )
            }

            is CouplePairingState.Completed -> {
                CoupleSuccessCard(name = pairing.peer.displayName)
                Spacer(modifier = Modifier.height(14.dp))
                CouplePrimaryButton(text = "Open Couple Space", onClick = onDone)
            }

            is CouplePairingState.Failed -> {
                CoupleErrorBanner(message = pairing.message, onDismiss = { controller.cancelPairing() })
                Spacer(modifier = Modifier.height(14.dp))
                CoupleSecondaryButton(
                    text = "Start over",
                    onClick = {
                        controller.cancelPairing()
                        onBack()
                    }
                )
            }

            else -> {
                CoupleEmptyState(
                    icon = Icons.Filled.Favorite,
                    title = "No invitation in progress",
                    body = "Create a fresh invitation from the pairing screen."
                )
            }
        }
    }
}

@Composable
fun CoupleJoinScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    initialCode: String?,
    onConsumed: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val scope = rememberCoroutineScope()
    var inviteCode by remember { mutableStateOf(initialCode.orEmpty()) }
    var confirmationCode by remember { mutableStateOf("") }
    var name by remember {
        mutableStateOf(if (state.localDisplayName == "Me") "" else state.localDisplayName)
    }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialCode) {
        if (!initialCode.isNullOrBlank()) inviteCode = initialCode
    }

    fun join(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) {
            localError = "Scan or paste their invitation first"
            return
        }
        busy = true
        localError = null
        scope.launch {
            controller.acceptInvite(clean, name)
                .onSuccess { onConsumed() }
                .onFailure { localError = it.message ?: "That invitation could not be verified" }
            busy = false
        }
    }

    fun confirm(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) {
            localError = "Scan or paste the confirmation code first"
            return
        }
        busy = true
        localError = null
        scope.launch {
            controller.applyConfirm(clean)
                .onSuccess {
                    onConsumed()
                    onDone()
                }
                .onFailure { localError = it.message ?: "That confirmation could not be verified" }
            busy = false
        }
    }

    val inviteScanner = rememberQrScanner { decoded ->
        if (decoded != null) {
            inviteCode = decoded
            join(decoded)
        }
    }
    val confirmScanner = rememberQrScanner { decoded ->
        if (decoded != null) {
            confirmationCode = decoded
            confirm(decoded)
        }
    }

    CoupleScreenScaffold(
        title = if (state.pairingState is CouplePairingState.InviteScanned) {
            "Reply with this code"
        } else {
            "Join their space"
        },
        subtitle = if (state.pairingState is CouplePairingState.InviteScanned) {
            "Send this reply back to your person."
        } else {
            "Scan or paste the invitation they created."
        },
        onBack = onBack
    ) {
        if (localError != null) {
            CoupleErrorBanner(message = localError!!, onDismiss = { localError = null })
            Spacer(modifier = Modifier.height(14.dp))
        }

        when (val pairing = state.pairingState) {
            is CouplePairingState.InviteScanned -> {
                CoupleCodeCard(
                    title = "Your reply code",
                    code = pairing.replyCode,
                    hint = "They scan this and send you one final confirmation code."
                )
                Spacer(modifier = Modifier.height(14.dp))
                ThemedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "You are joining ${pairing.peerName}",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pairing.peerFingerprint,
                            color = coupleAccentText(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Check that this fingerprint matches their phone before you finish.",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Final confirmation",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                CoupleScanButtons(scanner = confirmScanner)
                Spacer(modifier = Modifier.height(12.dp))
                CoupleTextField(
                    value = confirmationCode,
                    onValueChange = { confirmationCode = it },
                    label = "Confirmation code",
                    placeholder = "Paste the confirmation they send back",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(10.dp))
                CouplePrimaryButton(
                    text = "Finish pairing",
                    onClick = { confirm(confirmationCode) },
                    loading = busy,
                    icon = Icons.Filled.Check
                )
            }

            is CouplePairingState.Completed -> {
                CoupleSuccessCard(name = pairing.peer.displayName)
                Spacer(modifier = Modifier.height(14.dp))
                CouplePrimaryButton(text = "Open Couple Space", onClick = onDone)
            }

            is CouplePairingState.Failed -> {
                CoupleErrorBanner(message = pairing.message, onDismiss = { controller.cancelPairing() })
                Spacer(modifier = Modifier.height(14.dp))
                CoupleSecondaryButton(
                    text = "Try another invitation",
                    onClick = {
                        controller.cancelPairing()
                        inviteCode = ""
                    }
                )
            }

            else -> {
                CoupleScanButtons(scanner = inviteScanner)
                Spacer(modifier = Modifier.height(14.dp))
                CoupleTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it },
                    label = "Invitation code",
                    placeholder = "Paste the suziee://couple/... invitation",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(10.dp))
                CoupleTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = "Your display name",
                    placeholder = "The name they will see"
                )
                Spacer(modifier = Modifier.height(12.dp))
                CouplePrimaryButton(
                    text = "Accept invitation",
                    onClick = { join(inviteCode) },
                    loading = busy,
                    icon = Icons.Filled.Favorite
                )
            }
        }
    }
}

@Composable
fun CoupleConfirmScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    initialCode: String?,
    onConsumed: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initialCode.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialCode) {
        if (!initialCode.isNullOrBlank()) code = initialCode
    }

    fun confirm(value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) {
            localError = "Scan or paste the confirmation code first"
            return
        }
        busy = true
        localError = null
        scope.launch {
            controller.applyConfirm(clean)
                .onSuccess {
                    onConsumed()
                    onDone()
                }
                .onFailure { localError = it.message ?: "That confirmation could not be verified" }
            busy = false
        }
    }

    val scanner = rememberQrScanner { decoded ->
        if (decoded != null) {
            code = decoded
            confirm(decoded)
        }
    }

    CoupleScreenScaffold(
        title = "Confirm the pairing",
        subtitle = "The last step binds both phones to the fingerprints you just compared.",
        onBack = onBack
    ) {
        if (localError != null) {
            CoupleErrorBanner(message = localError!!, onDismiss = { localError = null })
            Spacer(modifier = Modifier.height(14.dp))
        }

        when (val pairing = state.pairingState) {
            is CouplePairingState.ReplyScanned -> {
                CoupleCodeCard(
                    title = "Confirmation for ${pairing.peerName}",
                    code = pairing.confirmationCode,
                    hint = "Send this to them. They scan it to finish."
                )
                Spacer(modifier = Modifier.height(14.dp))
                ThemedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Verify the fingerprint",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pairing.peerFingerprint,
                            color = coupleAccentText(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Both phones must show the same value.",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                CouplePrimaryButton(
                    text = "Done — we're paired",
                    onClick = {
                        controller.finishInviterPairing()
                        onDone()
                    },
                    icon = Icons.Filled.Check
                )
            }

            is CouplePairingState.InviteScanned -> {
                CoupleScanButtons(scanner = scanner)
                Spacer(modifier = Modifier.height(14.dp))
                CoupleTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "Confirmation code",
                    placeholder = "Paste the confirmation code",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(modifier = Modifier.height(10.dp))
                CouplePrimaryButton(
                    text = "Finish pairing",
                    onClick = { confirm(code) },
                    loading = busy,
                    icon = Icons.Filled.Check
                )
            }

            is CouplePairingState.Completed -> {
                CoupleSuccessCard(name = pairing.peer.displayName)
                Spacer(modifier = Modifier.height(14.dp))
                CouplePrimaryButton(text = "Open Couple Space", onClick = onDone)
            }

            is CouplePairingState.Failed -> {
                CoupleErrorBanner(message = pairing.message, onDismiss = { controller.cancelPairing() })
            }

            else -> {
                CoupleEmptyState(
                    icon = Icons.Filled.Lock,
                    title = "Nothing to confirm",
                    body = "Start from an invitation, or open a connection code if you are already paired."
                )
            }
        }
    }
}

@Composable
private fun CouplePairingSteps() {
    val colors = SuzieeTheme.colors
    ThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "What happens next",
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1  You create an invitation QR.\n" +
                    "2  They scan it and send back a signed reply.\n" +
                    "3  You send the final confirmation, and both phones compare fingerprints.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Each code is signed with a non-exportable Android Keystore key. A screenshot " +
                    "or a copied code cannot be edited without breaking the signature.",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun CoupleSuccessCard(name: String) {
    val colors = SuzieeTheme.colors
    ThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(42.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Paired with $name",
                color = colors.textPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Chat and the shared queue are ready. Open a connection when you want to listen together.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
