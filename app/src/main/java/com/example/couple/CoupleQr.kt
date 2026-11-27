package com.example.couple

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/**
 * QR rendering and decoding for pairing / connection codes.
 *
 * ZXing's core artifact is pure Java, so this file has no CameraX dependency. "Scanning"
 * launches the system camera app through `ActivityResultContracts.TakePicture`, which keeps
 * the app small and avoids a second camera pipeline next to the system one. A screenshot
 * can also be picked from the gallery, which is useful on devices without a camera or when
 * the code arrived as an image.
 *
 * QR modules are intentionally pure black on pure white. That is a functional constraint of
 * the symbology, not a theme colour: every scanner needs high contrast, and the surrounding
 * card still uses the active Suziee palette.
 */
object CoupleQr {

    /**
     * Byte-mode capacity of a version-40 QR at error-correction level L is 2,953 bytes.
     * Pairing payloads are tiny; a full SDP can exceed this, in which case the UI falls back
     * to copy/paste instead of rendering an unreadable code.
     */
    const val MAX_QR_BYTES = 2_900

    fun canEncode(content: String): Boolean =
        content.isNotBlank() && content.toByteArray(Charsets.UTF_8).size <= MAX_QR_BYTES

    fun encode(content: String, sizePx: Int = 720): Bitmap? {
        if (!canEncode(content)) return null
        val dimension = sizePx.coerceIn(320, 1_600)
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1
        )
        return runCatching {
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                dimension,
                dimension,
                hints
            )
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                val row = y * matrix.width
                for (x in 0 until matrix.width) {
                    pixels[row + x] = if (matrix[x, y]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        }.getOrNull()
    }

    fun decode(bitmap: Bitmap): String? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        return runCatching {
            MultiFormatReader().apply { setHints(hints) }.decode(binary).text
        }.getOrNull()
    }

    fun decode(bytes: ByteArray): String? =
        runCatching {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            decode(bitmap)
        }.getOrNull()

    fun readCaptured(context: Context, uri: Uri): String? =
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            decode(bytes)
        }.getOrNull()

    /** A private cache file that the system camera can write into through the FileProvider. */
    fun newCaptureFile(context: Context): File {
        val directory = File(context.cacheDir, "couple_qr").apply { mkdirs() }
        return File(directory, "capture_${System.currentTimeMillis()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
}
