package com.example.crypto

import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom

data class GeneratedIdentityKeys(
    val nodeId: String,
    val signingPrivB64: String,
    val signingPubB64: String,
    val dhPrivB64: String,
    val dhPubB64: String
)

class CryptoManager {

    private val secureRandom = SecureRandom()

    fun generateIdentity(): GeneratedIdentityKeys {
        val signKeyPair = Ed25519Sign.KeyPair.newKeyPair()
        val dhPriv = X25519.generatePrivateKey()
        val dhPub = X25519.publicFromPrivate(dhPriv)
        val nodeId = deriveNodeId(signKeyPair.publicKey)
        return GeneratedIdentityKeys(
            nodeId = nodeId,
            signingPrivB64 = encodeB64(signKeyPair.privateKey),
            signingPubB64 = encodeB64(signKeyPair.publicKey),
            dhPrivB64 = encodeB64(dhPriv),
            dhPubB64 = encodeB64(dhPub)
        )
    }

    fun deriveNodeId(signingPublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signingPublicKey)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun generateRandomSymmetricKeyB64(): String {
        val key = ByteArray(32)
        secureRandom.nextBytes(key)
        return encodeB64(key)
    }

    fun derivePairwiseKey(
        localDhPrivB64: String,
        remoteDhPubB64: String,
        localNodeId: String,
        remoteNodeId: String
    ): ByteArray {
        val priv = decodeB64(localDhPrivB64)
        val pub = decodeB64(remoteDhPubB64)
        val shared = X25519.computeSharedSecret(priv, pub)
        val sortedIds = listOf(localNodeId, remoteNodeId).sorted().joinToString("|")
        return Hkdf.computeHkdf(
            "HMACSHA256",
            shared,
            "LanChat-E2E-v1".toByteArray(Charsets.UTF_8),
            sortedIds.toByteArray(Charsets.UTF_8),
            32
        )
    }

    fun encryptAesGcm(
        keyBytes: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val aead = AesGcmJce(keyBytes)
        return aead.encrypt(plaintext, associatedData)
    }

    fun decryptAesGcm(
        keyBytes: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val aead = AesGcmJce(keyBytes)
        return aead.decrypt(ciphertext, associatedData)
    }

    fun encryptTextForPeer(
        localDhPrivB64: String,
        remoteDhPubB64: String,
        localNodeId: String,
        remoteNodeId: String,
        plaintext: String,
        aad: String
    ): String {
        val key = derivePairwiseKey(localDhPrivB64, remoteDhPubB64, localNodeId, remoteNodeId)
        val cipher = encryptAesGcm(
            keyBytes = key,
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            associatedData = aad.toByteArray(Charsets.UTF_8)
        )
        return encodeB64(cipher)
    }

    fun decryptTextFromPeer(
        localDhPrivB64: String,
        remoteDhPubB64: String,
        localNodeId: String,
        remoteNodeId: String,
        ciphertextB64: String,
        aad: String
    ): String {
        val key = derivePairwiseKey(localDhPrivB64, remoteDhPubB64, localNodeId, remoteNodeId)
        val plain = decryptAesGcm(
            keyBytes = key,
            ciphertext = decodeB64(ciphertextB64),
            associatedData = aad.toByteArray(Charsets.UTF_8)
        )
        return plain.toString(Charsets.UTF_8)
    }

    fun encryptWithGroupKey(
        groupKeyB64: String,
        plaintext: ByteArray,
        aad: String
    ): String {
        val key = decodeB64(groupKeyB64)
        return encodeB64(encryptAesGcm(key, plaintext, aad.toByteArray(Charsets.UTF_8)))
    }

    fun decryptWithGroupKey(
        groupKeyB64: String,
        ciphertextB64: String,
        aad: String
    ): ByteArray {
        val key = decodeB64(groupKeyB64)
        return decryptAesGcm(key, decodeB64(ciphertextB64), aad.toByteArray(Charsets.UTF_8))
    }

    fun signBytes(signingPrivB64: String, data: ByteArray): String {
        val signer = Ed25519Sign(decodeB64(signingPrivB64))
        return encodeB64(signer.sign(data))
    }

    fun verifySignature(signingPubB64: String, data: ByteArray, signatureB64: String): Boolean {
        return runCatching {
            val verifier = Ed25519Verify(decodeB64(signingPubB64))
            verifier.verify(decodeB64(signatureB64), data)
            true
        }.getOrDefault(false)
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computeSafetyNumber(
        localNodeId: String,
        localSigningPubB64: String,
        localDhPubB64: String,
        remoteNodeId: String,
        remoteSigningPubB64: String,
        remoteDhPubB64: String
    ): String {
        val first = "$localNodeId:$localSigningPubB64:$localDhPubB64"
        val second = "$remoteNodeId:$remoteSigningPubB64:$remoteDhPubB64"
        val ordered = listOf(first, second).sorted().joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(ordered.toByteArray(Charsets.UTF_8))
        val digits = StringBuilder()
        var i = 0
        while (digits.length < 30) {
            val b0 = hash[i % hash.size].toInt() and 0xFF
            val b1 = hash[(i + 1) % hash.size].toInt() and 0xFF
            val chunk = ((b0 shl 8) or b1) % 100000
            digits.append("%05d".format(chunk))
            i += 2
        }
        return digits.toString().chunked(5).joinToString(" ")
    }

    fun encodeB64(bytes: ByteArray): String = Base64.urlSafeEncode(bytes)

    fun decodeB64(value: String): ByteArray = Base64.urlSafeDecode(value)
}
