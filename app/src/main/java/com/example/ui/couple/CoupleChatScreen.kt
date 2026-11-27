package com.example.ui.couple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couple.CoupleChatMessage
import com.example.couple.CoupleSpaceController
import com.example.couple.CoupleUiState
import com.example.data.local.entities.DeliveryState
import com.example.ui.theme.SuzieeTheme

@Composable
fun CoupleChatScreen(
    controller: CoupleSpaceController,
    state: CoupleUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { controller.setChatVisible(true) }
    DisposableEffect(Unit) {
        onDispose { controller.setChatVisible(false) }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        controller.sendMessage(text)
        draft = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPrimary)
            .imePadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            CoupleHeader(
                title = "Chat",
                subtitle = if (state.authenticated) {
                    "End-to-end encrypted over the peer connection."
                } else {
                    "Offline. New messages stay queued on this phone."
                },
                onBack = onBack,
                trailing = {
                    CoupleStatusPill(
                        state = state.connectionState,
                        authenticated = state.authenticated
                    )
                }
            )
            if (state.error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                CoupleErrorBanner(message = state.error, onDismiss = controller::clearError)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Messages are stored on this device and delivered in order when the " +
                        "connection is open. A saved message is never marked delivered until the " +
                        "other phone acknowledges it.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            if (state.messages.isEmpty()) {
                item {
                    CoupleEmptyState(
                        icon = Icons.Filled.Send,
                        title = "No messages yet",
                        body = "Say hello. The queue survives closing the app and reconnects."
                    )
                }
            }
            items(state.messages, key = { it.eventId }) { message ->
                CoupleChatBubble(
                    message = message,
                    onRetry = { controller.retryMessage(message.eventId) }
                )
            }
            item { Spacer(modifier = Modifier.height(6.dp)) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 4_000) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (state.authenticated) "Message…" else "Queued until connected…",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.accentPrimary,
                    focusedContainerColor = colors.surfaceElevated,
                    unfocusedContainerColor = colors.surfaceCard
                )
            )
            Spacer(modifier = Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (draft.isBlank()) colors.surfaceElevated else coupleAccentContainer()
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { send() },
                    enabled = draft.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Send",
                        tint = if (draft.isBlank()) colors.textMuted else coupleOnAccent(),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CoupleChatBubble(
    message: CoupleChatMessage,
    onRetry: () -> Unit
) {
    val colors = SuzieeTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.widthIn(max = 310.dp),
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.outgoing) 16.dp else 4.dp,
                    bottomEnd = if (message.outgoing) 4.dp else 16.dp
                ),
                color = if (message.outgoing) {
                    colors.accentPrimary.copy(alpha = 0.18f)
                } else {
                    colors.surfaceElevated
                },
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (message.outgoing) {
                        colors.accentPrimary.copy(alpha = 0.35f)
                    } else {
                        colors.border
                    }
                )
            ) {
                Text(
                    text = message.body,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Row(
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(message.createdAt),
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
                if (message.outgoing) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = when (message.deliveryState) {
                            DeliveryState.PENDING -> "Pending"
                            DeliveryState.SENT -> "Sent"
                            DeliveryState.DELIVERED -> "Delivered"
                            DeliveryState.FAILED -> "Failed"
                            else -> message.deliveryState
                        },
                        color = when (message.deliveryState) {
                            // Small 10sp labels use body text on light themes: the pale success
                            // and error tints do not reach 4.5:1 on a white-pink card.
                            DeliveryState.DELIVERED ->
                                if (colors.isDark) colors.success else colors.textPrimary
                            DeliveryState.FAILED ->
                                if (colors.isDark) colors.error else colors.textPrimary
                            else -> colors.textSecondary
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (message.deliveryState == DeliveryState.FAILED) {
                        TextButton(onClick = onRetry, modifier = Modifier.height(26.dp)) {
                            Text("Retry", color = coupleAccentText(), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
