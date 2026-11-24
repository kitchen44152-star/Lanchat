package com.example.media

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrCodeAnalyzer(
    private val onQrDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    @Volatile
    private var lastDetectedAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastDetectedAt < 1200L) {
            image.close()
            return
        }

        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            val text = result?.text
            if (!text.isNullOrBlank()) {
                lastDetectedAt = now
                onQrDetected(text)
            }
        } catch (_: Exception) {
            // No QR in frame
        } finally {
            reader.reset()
            image.close()
        }
    }
}
