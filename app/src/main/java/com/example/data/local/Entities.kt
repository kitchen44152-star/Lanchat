package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TrustStatus {
    UNVERIFIED_TOFU,
    VERIFIED_QR,
    KEY_CHANGED_WARNING
}

enum class ConversationType {
    BROADCAST,
    DIRECT,
    GROUP
}

enum class MessageType {
    TEXT,
    VOICE,
    IMAGE
}

enum class DeliveryStatus {
    QUEUED,
    RELAYED,
    DELIVERED,
    READ,
    FAILED
}

enum class TransferDirection {
    OUTBOUND,
    INBOUND
}

enum class TransferStatus {
    PENDING,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val signingPubKeyB64: String,
    val dhPubKeyB64: String,
    val trustStatus: TrustStatus = TrustStatus.UNVERIFIED_TOFU,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val bestHopCount: Int = Int.MAX_VALUE,
    val nextHopNodeId: String? = null,
    val linkQuality: Int = 0,
    val isDirectlyConnected: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUpdatedAt: Long? = null
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val type: ConversationType,
    val title: String,
    val peerNodeId: String? = null,
    val groupKeyB64: String? = null,
    val memberNodeIdsJson: String = "[]",
    val lastMessagePreview: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["conversationId", "localOrderTs"]),
        Index(value = ["transferId"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderNodeId: String,
    val senderName: String,
    val timestamp: Long,
    val localOrderTs: Long = timestamp,
    val type: MessageType,
    val bodyText: String = "",
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val reactionsJson: String = "{}",
    val transferId: String? = null,
    val mediaLocalPath: String? = null,
    val mediaSha256: String? = null,
    val mediaTotalBytes: Long = 0L,
    val mediaDurationMs: Long = 0L,
    val waveformSamplesJson: String = "[]",
    val microThumbBase64: String? = null,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.QUEUED,
    val hopCount: Int = 0,
    val isIncoming: Boolean = false
)

@Entity(
    tableName = "media_transfers",
    indices = [Index(value = ["messageId"])]
)
data class MediaTransferEntity(
    @PrimaryKey val transferId: String,
    val messageId: String,
    val conversationId: String,
    val targetNodeId: String?,
    val direction: TransferDirection,
    val mimeType: String,
    val localStagingPath: String,
    val sha256: String,
    val totalBytes: Long,
    val totalChunks: Int,
    val chunkSizeBytes: Int,
    val ackedChunksBitset: String,
    val bytesTransferred: Long = 0L,
    val status: TransferStatus = TransferStatus.PENDING,
    val errorReason: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "dtn_relay_queue",
    indices = [Index(value = ["targetNodeId", "expiresAt"])]
)
data class DtnRelayPacketEntity(
    @PrimaryKey val packetId: String,
    val targetNodeId: String?,
    val senderNodeId: String,
    val packetJson: String,
    val ttl: Int,
    val hopCount: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val attemptCount: Int = 0,
    val nextRetryAt: Long = 0L
)

@Entity(
    tableName = "seen_packets",
    indices = [Index(value = ["seenAt"])]
)
data class SeenPacketEntity(
    @PrimaryKey val packetId: String,
    val seenAt: Long = System.currentTimeMillis()
)
