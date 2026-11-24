package com.example

import com.example.crypto.CryptoManager
import com.example.crypto.QrPayloadCodec
import com.example.crypto.QrPeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class CryptoAndQrTest {

    private val crypto = CryptoManager()

    @Test
    fun `e2e x25519 aes-gcm round trip and aad tamper rejection`() {
        val alice = crypto.generateIdentity()
        val bob = crypto.generateIdentity()

        val aad = "msg-1|${alice.nodeId}|${bob.nodeId}"
        val plaintext = "Meet at Ridge Camp 2 at 18:30"
        val cipher = crypto.encryptTextForPeer(
            localDhPrivB64 = alice.dhPrivB64,
            remoteDhPubB64 = bob.dhPubB64,
            localNodeId = alice.nodeId,
            remoteNodeId = bob.nodeId,
            plaintext = plaintext,
            aad = aad
        )

        val decrypted = crypto.decryptTextFromPeer(
            localDhPrivB64 = bob.dhPrivB64,
            remoteDhPubB64 = alice.dhPubB64,
            localNodeId = bob.nodeId,
            remoteNodeId = alice.nodeId,
            ciphertextB64 = cipher,
            aad = aad
        )
        assertEquals(plaintext, decrypted)

        var rejected = false
        try {
            crypto.decryptTextFromPeer(
                localDhPrivB64 = bob.dhPrivB64,
                remoteDhPubB64 = alice.dhPubB64,
                localNodeId = bob.nodeId,
                remoteNodeId = alice.nodeId,
                ciphertextB64 = cipher,
                aad = "tampered-aad"
            )
        } catch (_: GeneralSecurityException) {
            rejected = true
        }
        assertTrue("Tampered AAD must fail AEAD authentication", rejected)
    }

    @Test
    fun `ed25519 signature verification and symmetric safety number`() {
        val alice = crypto.generateIdentity()
        val bob = crypto.generateIdentity()

        val payload = "hello-mesh".toByteArray()
        val sig = crypto.signBytes(alice.signingPrivB64, payload)
        assertTrue(crypto.verifySignature(alice.signingPubB64, payload, sig))
        assertFalse(crypto.verifySignature(bob.signingPubB64, payload, sig))

        val s1 = crypto.computeSafetyNumber(
            alice.nodeId, alice.signingPubB64, alice.dhPubB64,
            bob.nodeId, bob.signingPubB64, bob.dhPubB64
        )
        val s2 = crypto.computeSafetyNumber(
            bob.nodeId, bob.signingPubB64, bob.dhPubB64,
            alice.nodeId, alice.signingPubB64, alice.dhPubB64
        )
        assertEquals(s1, s2)
    }

    @Test
    fun `qr payload codec encodes and decodes peer identity cleanly`() {
        val alice = crypto.generateIdentity()
        val qrIdentity = QrPeerIdentity(
            nodeId = alice.nodeId,
            displayName = "Alice Trail",
            signingPubB64 = alice.signingPubB64,
            dhPubB64 = alice.dhPubB64
        )
        val uri = QrPayloadCodec.encode(qrIdentity)
        val decoded = QrPayloadCodec.decode(uri)
        assertNotNull(decoded)
        assertEquals(qrIdentity, decoded)

        val verified = QrPayloadCodec.verifyAndDecode(uri, crypto)
        assertEquals(qrIdentity, verified)
    }

    @Test
    fun `qr payload verifyAndDecode rejects spoofed nodeId and malformed key bytes`() {
        val alice = crypto.generateIdentity()
        val bob = crypto.generateIdentity()

        val spoofed = QrPeerIdentity(
            nodeId = bob.nodeId,
            displayName = "Impostor",
            signingPubB64 = alice.signingPubB64,
            dhPubB64 = alice.dhPubB64
        )
        val spoofedUri = QrPayloadCodec.encode(spoofed)
        assertNull("Spoofed nodeId must fail key-binding check", QrPayloadCodec.verifyAndDecode(spoofedUri, crypto))

        val badKey = QrPeerIdentity(
            nodeId = alice.nodeId,
            displayName = "BadKey",
            signingPubB64 = crypto.encodeB64(byteArrayOf(1, 2, 3)),
            dhPubB64 = alice.dhPubB64
        )
        assertNull("Short key bytes must fail verifyAndDecode", QrPayloadCodec.verifyAndDecode(QrPayloadCodec.encode(badKey), crypto))
    }
}
