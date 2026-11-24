package com.example.mesh.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class PacketType {
    HELLO_BEACON,
    CHAT_MESSAGE,
    MEDIA_CHUNK,
    CHUNK_ACK,
    DELIVERY_ACK,
    READ_RECEIPT,
    INV_SYNC,
    GROUP_INVITE,
    REACTION_EVENT,
    CALL_OFFER,
    CALL_ANSWER,
    CALL_REJECT,
    CALL_END,
    CALL_AUDIO
}

@Serializable
data class MeshPacket(
    val packetId: String,
    val type: PacketType,
    val senderNodeId: String,
    val destNodeId: String?, // null = broadcast / group flood
    val nextHopNodeId: String? = null, // explicit next-hop hint for unicast DSDV
    val ttl: Int = 6,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val payloadJson: String,
    val signatureB64: String = ""
)

@Serializable
data class RouteSummaryEntry(
    val targetNodeId: String,
    val displayName: String,
    val signingPubKeyB64: String,
    val dhPubKeyB64: String,
    val hopCount: Int,
    val seqNo: Long,
    val linkQuality: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUpdatedAt: Long? = null
)

@Serializable
data class HelloBeaconPayload(
    val nodeId: String,
    val displayName: String,
    val signingPubKeyB64: String,
    val dhPubKeyB64: String,
    val seqNo: Long,
    val relayCapable: Boolean = true,
    val routes: List<RouteSummaryEntry> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUpdatedAt: Long? = null
)

@Serializable
data class ChatMessagePayload(
    val messageId: String,
    val conversationId: String,
    val conversationType: String, // BROADCAST, DIRECT, GROUP
    val groupTitle: String? = null,
    val senderName: String,
    val messageType: String, // TEXT, VOICE, IMAGE
    val encryptedBodyB64: String, // plaintext UTF-8 B64 for public BROADCAST, AES-GCM B64 for DIRECT/GROUP
    val isEncrypted: Boolean,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val transferId: String? = null,
    val mediaSha256: String? = null,
    val mediaTotalBytes: Long = 0L,
    val totalChunks: Int = 0,
    val mediaDurationMs: Long = 0L,
    val waveformSamples: List<Int> = emptyList(),
    val microThumbBase64: String? = null
)

@Serializable
data class MediaChunkPayload(
    val transferId: String,
    val messageId: String,
    val conversationId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val totalBytes: Long,
    val fileSha256: String,
    val chunkSha256: String,
    val encryptedChunkB64: String,
    val isEncrypted: Boolean,
    val mimeType: String
)

@Serializable
data class ChunkAckPayload(
    val transferId: String,
    val chunkIndex: Int,
    val ackedBitmapHex: String? = null
)

@Serializable
data class DeliveryAckPayload(
    val messageId: String,
    val conversationId: String,
    val reachedAt: Long,
    val finalHopCount: Int
)

@Serializable
data class ReadReceiptPayload(
    val messageId: String,
    val conversationId: String,
    val readAt: Long
)

@Serializable
data class InvSyncPayload(
    val knownPacketIds: List<String>,
    val incompleteTransferChunks: Map<String, List<Int>> = emptyMap()
)

@Serializable
data class GroupInvitePayload(
    val groupId: String,
    val groupTitle: String,
    val memberNodeIds: List<String>,
    val encryptedGroupKeyB64: String
)

@Serializable
data class ReactionEventPayload(
    val messageId: String,
    val conversationId: String,
    val emoji: String,
    val isEncrypted: Boolean = false,
    val isRemove: Boolean = false
)

@Serializable
data class CallOfferPayload(
    val callId: String,
    val callerName: String,
    val sampleRateHz: Int = 16000,
    val codec: String = "IMA_ADPCM"
)

@Serializable
data class CallAnswerPayload(
    val callId: String,
    val accepterName: String
)

@Serializable
data class CallRejectPayload(
    val callId: String,
    val reason: String // DECLINED, BUSY, TIMEOUT
)

@Serializable
data class CallEndPayload(
    val callId: String,
    val durationSec: Int = 0
)

@Serializable
data class CallAudioPayload(
    val callId: String,
    val seqNo: Int,
    val encryptedAdpcmB64: String,
    val initialPredictor: Int,
    val initialStepIndex: Int,
    val sampleCount: Int
)
