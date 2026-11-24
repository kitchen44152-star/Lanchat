package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedImage(
    val file: File,
    val microThumbBase64: String,
    val width: Int,
    val height: Int
)

class ImageProcessor(
    private val context: Context,
    private val outputDir: File
) {
    companion object {
        const val MAX_EDGE_PX = 1440
        const val JPEG_QUALITY = 80
        const val COMPACT_JPEG_QUALITY = 68
        const val SOFT_MAX_FILE_BYTES = 550 * 1024
        const val THUMB_SIZE_PX = 24
    }

    suspend fun processImageUri(uri: Uri): ProcessedImage? = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val stagedSource = File(outputDir, "src_${UUID.randomUUID()}.tmp")

        try {
            // Single-pass copy avoids OEM content provider errors on repeated openInputStream() calls
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    stagedSource.outputStream().use { out ->
                        input.copyTo(out)
                    }
                    true
                } ?: false
            }.getOrDefault(false)

            if (!copied || !stagedSource.exists() || stagedSource.length() <= 0L) {
                return@withContext null
            }

            val orientation = runCatching {
                ExifInterface(stagedSource.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val decoded = decodeBitmapSafely(stagedSource) ?: return@withContext null

            var oriented: Bitmap? = null
            var scaled: Bitmap? = null
            var thumb: Bitmap? = null
            try {
                oriented = applyExifOrientation(decoded, orientation)
                if (oriented !== decoded && !decoded.isRecycled) {
                    decoded.recycle()
                }

                scaled = scaleDownIfNeeded(oriented, MAX_EDGE_PX)
                if (scaled !== oriented && !oriented.isRecycled) {
                    oriented.recycle()
                    oriented = null
                }

                val outFile = File(outputDir, "img_${UUID.randomUUID()}.jpg")
                outFile.outputStream().use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                }
                if (outFile.length() > SOFT_MAX_FILE_BYTES) {
                    outFile.outputStream().use { fos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, COMPACT_JPEG_QUALITY, fos)
                    }
                }

                thumb = Bitmap.createScaledBitmap(scaled, THUMB_SIZE_PX, THUMB_SIZE_PX, true)
                val thumbStream = ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 55, thumbStream)
                val thumbB64 = Base64.encodeToString(thumbStream.toByteArray(), Base64.NO_WRAP)

                ProcessedImage(
                    file = outFile,
                    microThumbBase64 = thumbB64,
                    width = scaled.width,
                    height = scaled.height
                )
            } finally {
                thumb?.takeIf { !it.isRecycled }?.recycle()
                scaled?.takeIf { !it.isRecycled }?.recycle()
                oriented?.takeIf { !it.isRecycled }?.recycle()
                if (!decoded.isRecycled) decoded.recycle()
            }
        } finally {
            runCatching { stagedSource.delete() }
        }
    }

    private fun decodeBitmapSafely(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
            if (bmp != null) return bmp
        }

        // Fallback to ImageDecoder on API 28+ (handles HEIC/AVIF/wide-color OEM captures cleanly)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > MAX_EDGE_PX && longest > 0) {
                        val ratio = MAX_EDGE_PX.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (w * ratio).roundToInt().coerceAtLeast(1),
                            (h * ratio).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
        }
        return null
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var inSampleSize = 1
        val longest = max(width, height)
        while ((longest / inSampleSize) > maxEdge * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val newW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
