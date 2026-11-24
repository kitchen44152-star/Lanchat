package com.example.data

import com.example.crypto.CryptoManager
import com.example.crypto.QrPeerIdentity
import com.example.data.local.ConversationDao
import com.example.data.local.ConversationEntity
import com.example.data.local.DtnRelayDao
import com.example.data.local.LocalIdentityConfig
import com.example.data.local.MediaTransferDao
import com.example.data.local.MediaTransferEntity
import com.example.data.local.MessageDao
import com.example.data.local.MessageEntity
import com.example.data.local.PeerDao
import com.example.data.local.PeerEntity
import com.example.data.local.SeenPacketDao
import com.example.data.local.ThemePreference
import com.example.data.local.TrustStatus
import com.example.data.local.UserPreferencesStore
import com.example.mesh.router.MeshRouter
import kotlinx.coroutines.flow.Flow

class LanChatRepository(
    private val preferencesStore: UserPreferencesStore,
    private val cryptoManager: CryptoManager,
    private val peerDao: PeerDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val mediaTransferDao: MediaTransferDao,
    private val dtnRelayDao: DtnRelayDao,
    private val seenPacketDao: SeenPacketDao,
    private val meshRouter: MeshRouter
) {
    val identityFlow: Flow<LocalIdentityConfig> = preferencesStore.identityFlow
    val conversationsFlow: Flow<List<ConversationEntity>> = conversationDao.observeConversations()
    val peersFlow: Flow<List<PeerEntity>> = peerDao.observeAllPeers()
    val transfersFlow: Flow<List<MediaTransferEntity>> = mediaTransferDao.observeAllTransfers()
    val pendingDtnCountFlow: Flow<Int> = dtnRelayDao.observePendingCount()

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        conversationDao.observeConversation(conversationId)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeMessages(conversationId)

    fun observePeer(nodeId: String): Flow<PeerEntity?> = peerDao.observePeer(nodeId)

    suspend fun initializeIdentity(displayName: String, backgroundRelayEnabled: Boolean): LocalIdentityConfig {
        val keys = cryptoManager.generateIdentity()
        preferencesStore.saveNewIdentity(
            nodeId = keys.nodeId,
            displayName = displayName.trim().ifBlank { "MeshNode" },
            signingPrivB64 = keys.signingPrivB64,
            signingPubB64 = keys.signingPubB64,
            dhPrivB64 = keys.dhPrivB64,
            dhPubB64 = keys.dhPubB64,
            backgroundRelayEnabled = backgroundRelayEnabled
        )
        meshRouter.ensureBroadcastConversation()
        return preferencesStore.getIdentitySnapshot()
    }

    suspend fun updateDisplayName(name: String) {
        preferencesStore.updateDisplayName(name)
        meshRouter.updateIdentityAndRebroadcast(preferencesStore.getIdentitySnapshot())
    }

    suspend fun setBackgroundRelay(enabled: Boolean) {
        preferencesStore.setBackgroundRelayEnabled(enabled)
    }

    suspend fun setMaxHops(hops: Int) {
        preferencesStore.setMaxHops(hops)
        meshRouter.updateIdentity(preferencesStore.getIdentitySnapshot())
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        preferencesStore.setThemePreference(theme)
    }

    suspend fun setShareLocationOnRadar(enabled: Boolean) {
        preferencesStore.setShareLocationOnRadar(enabled)
        meshRouter.updateIdentityAndRebroadcast(preferencesStore.getIdentitySnapshot())
    }

    suspend fun markConversationRead(conversationId: String) {
        conversationDao.markRead(conversationId)
    }

    suspend fun verifyPeerFromQr(qrIdentity: QrPeerIdentity): PeerEntity {
        val existing = peerDao.getPeer(qrIdentity.nodeId)
        val verified = PeerEntity(
            nodeId = qrIdentity.nodeId,
            displayName = qrIdentity.displayName,
            signingPubKeyB64 = qrIdentity.signingPubB64,
            dhPubKeyB64 = qrIdentity.dhPubB64,
            trustStatus = TrustStatus.VERIFIED_QR,
            lastSeenAt = System.currentTimeMillis(),
            bestHopCount = existing?.bestHopCount ?: Int.MAX_VALUE,
            nextHopNodeId = existing?.nextHopNodeId,
            linkQuality = existing?.linkQuality ?: 0,
            isDirectlyConnected = existing?.isDirectlyConnected ?: false
        )
        peerDao.upsertPeer(verified)
        return verified
    }

    suspend fun markPeerVerifiedManually(nodeId: String) {
        peerDao.updateTrustStatus(nodeId, TrustStatus.VERIFIED_QR)
    }

    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteConversation(conversationId)
    }

    suspend fun clearAllChatHistory() {
        messageDao.clearAll()
        mediaTransferDao.clearAll()
        dtnRelayDao.clearAll()
        seenPacketDao.clearAll()
        conversationDao.deleteAllUserConversations()
        meshRouter.ensureBroadcastConversation()
    }
}
