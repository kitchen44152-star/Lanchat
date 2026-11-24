package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY isDirectlyConnected DESC, bestHopCount ASC, lastSeenAt DESC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getPeer(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId LIMIT 1")
    fun observePeer(nodeId: String): Flow<PeerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isDirectlyConnected = 0, bestHopCount = 2147483647, nextHopNodeId = NULL, linkQuality = 0")
    suspend fun markAllDisconnected()

    @Query("UPDATE peers SET trustStatus = :status WHERE nodeId = :nodeId")
    suspend fun updateTrustStatus(nodeId: String, status: TrustStatus)

    @Query("DELETE FROM peers")
    suspend fun clearAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :conversationId")
    suspend fun markRead(conversationId: String)

    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageTimestamp = :timestamp, unreadCount = unreadCount + :unreadDelta WHERE conversationId = :conversationId")
    suspend fun updateLastMessage(conversationId: String, preview: String, timestamp: Long, unreadDelta: Int)

    @Query("UPDATE conversations SET title = :title WHERE conversationId = :conversationId")
    suspend fun updateTitle(conversationId: String, title: String)

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId AND conversationId != 'BROADCAST_GLOBAL'")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversations WHERE conversationId != 'BROADCAST_GLOBAL'")
    suspend fun deleteAllUserConversations()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY localOrderTs ASC, timestamp ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT MAX(localOrderTs) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMaxLocalOrderTs(conversationId: String): Long?

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE transferId = :transferId LIMIT 1")
    suspend fun getMessageByTransferId(transferId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status, hopCount = CASE WHEN :hopCount > hopCount THEN :hopCount ELSE hopCount END WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus, hopCount: Int)

    @Query("UPDATE messages SET deliveryStatus = 'RELAYED', hopCount = CASE WHEN :hopCount > hopCount THEN :hopCount ELSE hopCount END WHERE messageId = :messageId AND deliveryStatus = 'QUEUED'")
    suspend fun markRelayedIfQueued(messageId: String, hopCount: Int)

    @Query("UPDATE messages SET mediaLocalPath = :localPath WHERE transferId = :transferId")
    suspend fun attachCompletedMedia(transferId: String, localPath: String)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    suspend fun updateReactions(messageId: String, reactionsJson: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

@Dao
interface MediaTransferDao {
    @Query("SELECT * FROM media_transfers")
    fun observeAllTransfers(): Flow<List<MediaTransferEntity>>

    @Query("SELECT * FROM media_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransfer(transferId: String): MediaTransferEntity?

    @Query("SELECT * FROM media_transfers WHERE status IN ('PENDING', 'ACTIVE')")
    suspend fun getResumableTransfers(): List<MediaTransferEntity>

    @Query("SELECT * FROM media_transfers WHERE direction = 'OUTBOUND' AND status IN ('PENDING', 'ACTIVE')")
    suspend fun getOutboundResumableTransfers(): List<MediaTransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: MediaTransferEntity)

    @Query("UPDATE media_transfers SET ackedChunksBitset = :bitset, bytesTransferred = :bytesTransferred, status = :status, updatedAt = :updatedAt WHERE transferId = :transferId")
    suspend fun updateProgress(
        transferId: String,
        bitset: String,
        bytesTransferred: Long,
        status: TransferStatus,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE media_transfers SET status = :status, errorReason = :errorReason, updatedAt = :updatedAt WHERE transferId = :transferId")
    suspend fun updateStatus(
        transferId: String,
        status: TransferStatus,
        errorReason: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM media_transfers")
    suspend fun clearAll()
}

@Dao
interface DtnRelayDao {
    @Query("SELECT * FROM dtn_relay_queue WHERE expiresAt > :now ORDER BY createdAt ASC")
    suspend fun getActivePackets(now: Long = System.currentTimeMillis()): List<DtnRelayPacketEntity>

    @Query("SELECT COUNT(*) FROM dtn_relay_queue WHERE expiresAt > :now")
    fun observePendingCount(now: Long = System.currentTimeMillis()): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(packet: DtnRelayPacketEntity)

    @Query("DELETE FROM dtn_relay_queue WHERE packetId = :packetId")
    suspend fun remove(packetId: String)

    @Query("DELETE FROM dtn_relay_queue WHERE expiresAt <= :now")
    suspend fun purgeExpired(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM dtn_relay_queue WHERE packetId IN (SELECT packetId FROM dtn_relay_queue ORDER BY createdAt DESC LIMIT -1 OFFSET :maxCount)")
    suspend fun trimToMax(maxCount: Int)

    @Query("DELETE FROM dtn_relay_queue")
    suspend fun clearAll()
}

@Dao
interface SeenPacketDao {
    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId)")
    suspend fun hasSeen(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(packet: SeenPacketEntity)

    @Query("DELETE FROM seen_packets WHERE seenAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    @Query("DELETE FROM seen_packets")
    suspend fun clearAll()
}
