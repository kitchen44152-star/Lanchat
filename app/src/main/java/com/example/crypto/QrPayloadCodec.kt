package com.example.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder

data class QrPeerIdentity(
    val nodeId: String,
    val displayName: String,
    val signingPubB64: String,
    val dhPubB64: String
)

object QrPayloadCodec {

    private const val SCHEME = "lanchat://id"

    fun encode(identity: QrPeerIdentity): String {
        val nameEnc = URLEncoder.encode(identity.displayName, "UTF-8")
        return "$SCHEME?v=1&id=${identity.nodeId}&name=$nameEnc&sign=${identity.signingPubB64}&dh=${identity.dhPubB64}"
    }

    fun verifyAndDecode(raw: String, crypto: CryptoManager): QrPeerIdentity? {
        val decoded = decode(raw) ?: return null
        val signBytes = runCatching { crypto.decodeB64(decoded.signingPubB64) }.getOrNull() ?: return null
        val dhBytes = runCatching { crypto.decodeB64(decoded.dhPubB64) }.getOrNull() ?: return null
        if (signBytes.size != 32 || dhBytes.size != 32) return null
        if (crypto.deriveNodeId(signBytes) != decoded.nodeId) return null
        return decoded
    }

    fun decode(raw: String): QrPeerIdentity? {
        if (!raw.startsWith("$SCHEME?")) return null
        val query = raw.substringAfter("?")
        val params = query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()

        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val name = params["name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: return null
        val sign = params["sign"]?.takeIf { it.isNotBlank() } ?: return null
        val dh = params["dh"]?.takeIf { it.isNotBlank() } ?: return null
        return QrPeerIdentity(nodeId = id, displayName = name, signingPubB64 = sign, dhPubB64 = dh)
    }

    fun renderQrBitmap(content: String, sizePx: Int = 360): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }
}
