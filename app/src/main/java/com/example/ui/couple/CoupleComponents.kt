package com.example.ui.couple

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.couple.CoupleConnectionState
import com.example.couple.CoupleQr
import com.example.ui.theme.SuzieeTheme
import com.example.ui.theme.ThemedCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The content colour for a filled accent button.
 *
 * It is derived from the active palette rather than hard-coded so it stays legible on all
 * three themes: dark themes use the near-black page colour on the pastel Aurora accent, and
 * the two light themes use white on their deep accent.
 */
@Composable
fun coupleOnAccent(): Color {
    val colors = SuzieeTheme.colors
    return if (colors.isDark) colors.bgPrimary else Color.White
}

/**
 * The filled accent used for buttons and avatars.
 *
 * Pink Clay's bright rose is a lovely surface but only reaches ~3.8:1 against white, which is
 * below the 4.5:1 body-text threshold. Its deep berry `highlight` is the same hue family and
 * reaches ~10:1, so light themes use that for filled controls while Aurora keeps its pastel
 * accent on the near-black page colour.
 */
@Composable
fun coupleAccentContainer(): Color {
    val colors = SuzieeTheme.colors
    return if (colors.isDark) colors.accentPrimary else colors.highlight
}

/** Accent colour for icons and small accent text; contrast-safe on every theme. */
@Composable
fun coupleAccentText(): Color {
    val colors = SuzieeTheme.colors
    return if (colors.isDark) colors.accentPrimary else colors.highlight
}

@Composable
fun CoupleScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = SuzieeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        CoupleHeader(title = title, subtitle = subtitle, onBack = onBack)
        Spacer(modifier = Modifier.height(18.dp))
        content()
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun CoupleHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = SuzieeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun CoupleStatusPill(
    state: CoupleConnectionState,
    authenticated: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    val label: String
    val tint: Color
    val labelColor: Color
    when {
        authenticated && state == CoupleConnectionState.CONNECTED -> {
            label = "Connected"
            tint = colors.success
            // The pale success tint is a large surface, not a text background: the label uses
            // body text on light themes so 12sp stays above 4.5:1.
            labelColor = if (colors.isDark) colors.success else colors.textPrimary
        }

        state == CoupleConnectionState.CONNECTING -> {
            label = "Connecting"
            tint = colors.accentSecondary
            labelColor = if (colors.isDark) colors.accentSecondary else colors.highlight
        }

        state == CoupleConnectionState.AUTHENTICATING -> {
            label = "Verifying"
            tint = colors.accentSecondary
            labelColor = if (colors.isDark) colors.accentSecondary else colors.highlight
        }

        state == CoupleConnectionState.RECONNECTING -> {
            label = "Reconnecting"
            tint = colors.accentPrimary
            labelColor = if (colors.isDark) colors.accentPrimary else colors.highlight
        }

        state == CoupleConnectionState.FAILED -> {
            label = "Failed"
            tint = colors.error
            labelColor = if (colors.isDark) colors.error else colors.textPrimary
        }

        else -> {
            label = "Offline"
            tint = colors.textMuted
            labelColor = colors.textSecondary
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = label,
                color = labelColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun CouplePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null
) {
    val colors = SuzieeTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        shape = RoundedCornerShape(colors.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = coupleAccentContainer(),
            contentColor = coupleOnAccent(),
            disabledContainerColor = colors.surfaceElevated,
            disabledContentColor = colors.textMuted
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = coupleOnAccent()
            )
            Spacer(modifier = Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CoupleSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val colors = SuzieeTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(colors.cornerRadius),
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.textPrimary,
            disabledContentColor = colors.textMuted
        )
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CoupleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    enabled: Boolean = true
) {
    val colors = SuzieeTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = colors.textSecondary) },
        placeholder = {
            if (placeholder != null) {
                Text(text = placeholder, color = colors.textSecondary)
            }
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        shape = RoundedCornerShape(colors.cornerRadius),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textMuted,
            focusedBorderColor = colors.accentPrimary,
            unfocusedBorderColor = colors.border,
            disabledBorderColor = colors.border,
            focusedLabelColor = coupleAccentText(),
            unfocusedLabelColor = colors.textSecondary,
            cursorColor = colors.accentPrimary,
            focusedContainerColor = colors.surfaceElevated,
            unfocusedContainerColor = colors.surfaceCard,
            disabledContainerColor = colors.surfaceElevated
        )
    )
}

@Composable
fun CoupleCodeCard(
    title: String,
    code: String,
    hint: String? = null,
    qrSize: Int = 560
) {
    val colors = SuzieeTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bitmap = remember(code, qrSize) { CoupleQr.encode(code, qrSize) }

    ThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .padding(12.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.size(232.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            } else {
                Text(
                    text = "This code is too large for a QR symbol. Copy it and send it in any chat app instead.",
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            SelectionContainer {
                Text(
                    text = code,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = coupleAccentText()
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", color = coupleAccentText(), fontSize = 13.sp)
                }
                TextButton(onClick = { shareText(context, code) }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = coupleAccentText()
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", color = coupleAccentText(), fontSize = 13.sp)
                }
            }
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun CoupleErrorBanner(message: String, onDismiss: () -> Unit) {
    val colors = SuzieeTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(colors.cornerRadius),
        color = colors.error.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                color = colors.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun CoupleEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    val colors = SuzieeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = coupleAccentText(),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            color = colors.textSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun CoupleAvatar(name: String, size: Dp = 46.dp) {
    val colors = SuzieeTheme.colors
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (colors.isDark) {
                    Brush.linearGradient(colors.accentGradient)
                } else {
                    Brush.linearGradient(listOf(colors.highlight, colors.highlight))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = coupleOnAccent(),
            fontSize = (size.value * 0.40f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CoupleActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    val colors = SuzieeTheme.colors
    ThemedCard(
        modifier = modifier.clickable(onClick = onClick),
        color = colors.surfaceCard
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.accentPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = coupleAccentText(),
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (badge != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.error.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = badge,
                            color = colors.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun CoupleInfoRow(label: String, value: String, mono: Boolean = false) {
    val colors = SuzieeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(118.dp)
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Actions returned by [rememberQrScanner]. Both are real system intents. */
data class CoupleQrScanner(
    val scanWithCamera: () -> Unit,
    val pickFromGallery: () -> Unit
)

/**
 * Wires the system camera and the system photo picker to ZXing.
 *
 * `TakePicture` writes into a FileProvider URI in the app cache; no broad storage
 * permission is needed and no image leaves the device.
 */
@Composable
fun rememberQrScanner(onResult: (String?) -> Unit): CoupleQrScanner {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingUri
        pendingUri = null
        if (!success || uri == null) {
            currentOnResult(null)
        } else {
            currentOnResult(CoupleQr.readCaptured(context, uri))
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            currentOnResult(null)
        } else {
            currentOnResult(CoupleQr.readCaptured(context, uri))
        }
    }

    return CoupleQrScanner(
        scanWithCamera = {
            runCatching {
                val file = CoupleQr.newCaptureFile(context)
                val uri = CoupleQr.uriFor(context, file)
                pendingUri = uri
                cameraLauncher.launch(uri)
            }.onFailure { currentOnResult(null) }
        },
        pickFromGallery = {
            runCatching { galleryLauncher.launch("image/*") }
                .onFailure { currentOnResult(null) }
        }
    )
}

@Composable
fun CoupleScanButtons(
    scanner: CoupleQrScanner,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CoupleSecondaryButton(
            text = "Scan QR",
            onClick = scanner.scanWithCamera,
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.QrCodeScanner
        )
        CoupleSecondaryButton(
            text = "From photos",
            onClick = scanner.pickFromGallery,
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.PhotoLibrary
        )
    }
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Share Suziee Couple code")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun formatDurationMs(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun formatRelative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return "Never"
    val delta = (now - timestamp).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "Just now"
        delta < 3_600_000L -> "${delta / 60_000L} min ago"
        delta < 86_400_000L -> "${delta / 3_600_000L} h ago"
        else -> formatTimestamp(timestamp)
    }
}

fun shortDeviceId(deviceId: String): String =
    if (deviceId.length <= 14) deviceId else "${deviceId.take(6)}…${deviceId.takeLast(4)}"
