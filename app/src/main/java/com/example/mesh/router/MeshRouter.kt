package com.example.mesh.router

import com.example.crypto.CryptoManager
import com.example.data.local.ConversationDao
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationType
import com.example.data.local.DeliveryStatus
import com.example.data.local.DtnRelayDao
import com.example.data.local.DtnRelayPacketEntity
import com.example.data.local.LocalIdentityConfig
import com.example.data.local.MediaTransferDao
import com.example.data.local.MediaTransferEntity
import com.example.data.local.MessageDao
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.data.local.PeerDao
import com.example.data.local.PeerEntity
import com.example.data.local.SeenPacketDao
import com.example.data.local.SeenPacketEntity
import com.example.data.local.TransferDirection
import com.example.data.local.TransferStatus
import com.example.data.local.TrustStatus
import com.example.mesh.location.OfflineGeoPoint
import com.example.mesh.protocol.CallAnswerPayload
import com.example.mesh.protocol.CallAudioPayload
import com.example.mesh.protocol.CallEndPayload
import com.example.mesh.protocol.CallOfferPayload
import com.example.mesh.protocol.CallRejectPayload
import com.example.mesh.protocol.ChatMessagePayload
import com.example.mesh.protocol.ChunkAckPayload
import com.example.mesh.protocol.DeliveryAckPayload
import com.example.mesh.protocol.GroupInvitePayload
import com.example.mesh.protocol.HelloBeaconPayload
import com.example.mesh.protocol.InvSyncPayload
import com.example.mesh.protocol.MediaChunkPayload
import com.example.mesh.protocol.MeshPacket
import com.example.mesh.protocol.PacketType
import com.example.mesh.protocol.ReactionEventPayload
import com.example.mesh.protocol.ReadReceiptPayload
import com.example.mesh.protocol.RouteSummaryEntry
import com.example.mesh.transport.DirectLinkInfo
import com.example.mesh.transport.MeshTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class IncomingMessageAlert(
    val conversationId: String,
    val messageId: String,
    val senderName: String,
    val preview: String
)

sealed interface IncomingCallEvent {
    val senderNodeId: String
    val hopCount: Int

    data class Offer(
        override val senderNodeId: String,
        override val hopCount: Int,
        val payload: CallOfferPayload,
        val trustStatus: TrustStatus
    ) : IncomingCallEvent

    data class Answer(
        override val senderNodeId: String,
        override val hopCount: Int,
        val payload: CallAnswerPayload
    ) : IncomingCallEvent

    data class Reject(
        override val senderNodeId: String,
        override val hopCount: Int,
        val payload: CallRejectPayload
    ) : IncomingCallEvent

    data class End(
        override val senderNodeId: String,
        override val hopCount: Int,
        val payload: CallEndPayload
    ) : IncomingCallEvent

    data class Audio(
        override val senderNodeId: String,
        override val hopCount: Int,
        val callId: String,
        val seqNo: Int,
        val adpcmBytes: ByteArray,
        val initialPredictor: Int,
        val initialStepIndex: Int,
        val sampleCount: Int
    ) : IncomingCallEvent
}

data class RouteEntry(
    val targetNodeId: String,
    val displayName: String,
    val signingPubKeyB64: String,
    val dhPubKeyB64: String,
    val nextHopNodeId: String,
    val nextHopEndpointId: String,
    val hopCount: Int,
    val seqNo: Long,
    val linkQuality: Int,
    val updatedAt: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUpdatedAt: Long? = null
)

data class TransferProgressUi(
    val transferId: String,
    val messageId: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val chunksAcked: Int,
    val totalChunks: Int,
    val speedBytesPerSec: Long,
    val status: TransferStatus,
    val errorReason: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class MeshStatsUi(
    val relayedPacketCount: Int = 0,
    val directPeerCount: Int = 0,
    val reachablePeerCount: Int = 0
)

class MeshRouter(
    private val transport: MeshTransport,
    private val crypto: CryptoManager,
    private val peerDao: PeerDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val mediaTransferDao: MediaTransferDao,
    private val dtnRelayDao: DtnRelayDao,
    private val seenPacketDao: SeenPacketDao,
    private val mediaRootDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val enableBeaconLoop: Boolean = true
) {

    companion object {
        const val BROADCAST_CONVERSATION_ID = "BROADCAST_GLOBAL"
        // 16 KB raw slice -> ~22.5 KB encrypted Base64 JSON packet (< Nearby's 32 KB limit)
        const val CHUNK_SIZE_BYTES = 16 * 1024
        const val WINDOW_SIZE = 4
        const val DTN_TTL_MS = 24 * 60 * 60 * 1000L
        private const val ROUTE_EXPIRY_MS = 45_000L
        private const val SEEN_LRU_CAPACITY = 2000
        private const val SEEN_DB_RETENTION_MS = 48 * 60 * 60 * 1000L
        private const val MAX_DTN_QUEUE_SIZE = 150
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val routingTable = ConcurrentHashMap<String, RouteEntry>()
    private val _routesFlow = MutableStateFlow<Map<String, RouteEntry>>(emptyMap())
    val routesFlow: StateFlow<Map<String, RouteEntry>> = _routesFlow.asStateFlow()

    private val _transferProgress = MutableStateFlow<Map<String, TransferProgressUi>>(emptyMap())
    val transferProgress: StateFlow<Map<String, TransferProgressUi>> = _transferProgress.asStateFlow()

    private val _meshStats = MutableStateFlow(MeshStatsUi())
    val meshStats: StateFlow<MeshStatsUi> = _meshStats.asStateFlow()

    private val _incomingMessageAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 32)
    val incomingMessageAlerts: SharedFlow<IncomingMessageAlert> = _incomingMessageAlerts.asSharedFlow()

    private val _incomingCallEvents = MutableSharedFlow<IncomingCallEvent>(extraBufferCapacity = 128)
    val incomingCallEvents: SharedFlow<IncomingCallEvent> = _incomingCallEvents.asSharedFlow()

    private val seenMutex = Mutex()
    private val orderMutex = Mutex()
    private val dtnFlushMutex = Mutex()
    private val seenLru = object : LinkedHashMap<String, Long>(SEEN_LRU_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > SEEN_LRU_CAPACITY
        }
    }
    private val callAudioSeenLock = Any()
    private val callAudioSeenLru = object : LinkedHashMap<String, Long>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 512
        }
    }
    private val pairwiseKeyCache = ConcurrentHashMap<String, ByteArray>()

    private val pausedTransfers = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()
    private val activeStreamingTransfers = ConcurrentHashMap.newKeySet<String>()
    private val sentReadReceipts = ConcurrentHashMap.newKeySet<String>()
    private val chunkAckWaiters = ConcurrentHashMap<String, MutableSet<Int>>()
    private val inboundTransferBuffers = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val inboundTransferMeta = ConcurrentHashMap<String, MediaChunkPayload>()

    @Volatile
    var localLocationProvider: (() -> OfflineGeoPoint?)? = null

    @Volatile
    private var identity: LocalIdentityConfig = LocalIdentityConfig()
    private var beaconSeqNo: Long = 0L
    private var collectorJob: Job? = null
    private var linkWatcherJob: Job? = null
    private var beaconJob: Job? = null
    private var relayedCounter = 0

    init {
        mediaRootDir.mkdirs()
    }

    fun start(localIdentity: LocalIdentityConfig) {
        if (!localIdentity.isOnboarded || localIdentity.nodeId.isBlank()) return
        identity = localIdentity
        transport.start(localIdentity.nodeId, localIdentity.displayName)
        ensureLoopsRunning()
        scope.launch {
            ensureBroadcastConversation()
            loadPersistedTransfers()
        }
    }

    fun updateIdentity(localIdentity: LocalIdentityConfig) {
        identity = localIdentity
    }

    suspend fun updateIdentityAndRebroadcast(localIdentity: LocalIdentityConfig) {
        identity = localIdentity
        transport.updateDisplayName(localIdentity.displayName)
        broadcastHelloBeacon()
    }

    fun stop() {
        beaconJob?.cancel()
        collectorJob?.cancel()
        linkWatcherJob?.cancel()
        beaconJob = null
        collectorJob = null
        linkWatcherJob = null
        transport.stop()
        routingTable.clear()
        _routesFlow.value = emptyMap()
        scope.launch {
            runCatching { peerDao.markAllDisconnected() }
        }
    }

    private fun ensureLoopsRunning() {
        if (collectorJob == null) {
            collectorJob = scope.launch {
                transport.incomingFrames.collect { frame ->
                    handleIncomingBytes(frame.fromEndpointId, frame.bytes)
                }
            }
        }
        if (linkWatcherJob == null) {
            linkWatcherJob = scope.launch {
                var previousEndpoints = emptySet<String>()
                transport.directLinks.collect { links ->
                    val currentEndpoints = links.keys
                    val newlyConnected = currentEndpoints - previousEndpoints
                    val disconnected = previousEndpoints - currentEndpoints
                    previousEndpoints = currentEndpoints

                    if (disconnected.isNotEmpty()) {
                        pruneRoutesForDisconnectedEndpoints(disconnected, links)
                    }
                    if (newlyConnected.isNotEmpty()) {
                        for (ep in newlyConnected) {
                            sendHelloToEndpoint(ep)
                            sendInvSyncToEndpoint(ep)
                        }
                        flushDtnQueue()
                        resumePendingOutboundTransfers()
                    }
                    refreshMeshStats(links)
                }
            }
        }
        if (enableBeaconLoop && beaconJob == null) {
            beaconJob = scope.launch {
                while (isActive) {
                    delay(5_000L)
                    pruneExpiredRoutes()
                    broadcastHelloBeacon()
                    flushDtnQueue()
                }
            }
        }
    }

    suspend fun ensureBroadcastConversation() {
        val existing = conversationDao.getConversation(BROADCAST_CONVERSATION_ID)
        if (existing == null) {
            conversationDao.upsertConversation(
                ConversationEntity(
                    conversationId = BROADCAST_CONVERSATION_ID,
                    type = ConversationType.BROADCAST,
                    title = "#Nearby Mesh",
                    lastMessagePreview = "Signed public mesh channel",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    isPinned = true
                )
            )
        }
    }

    private suspend fun loadPersistedTransfers() {
        val resumable = mediaTransferDao.getResumableTransfers()
        val map = _transferProgress.value.toMutableMap()
        for (t in resumable) {
            val acked = decodeBitset(t.ackedChunksBitset).size
            map[t.transferId] = TransferProgressUi(
                transferId = t.transferId,
                messageId = t.messageId,
                direction = t.direction,
                bytesTransferred = t.bytesTransferred,
                totalBytes = t.totalBytes,
                chunksAcked = acked,
                totalChunks = t.totalChunks,
                speedBytesPerSec = 0L,
                status = t.status,
                errorReason = t.errorReason
            )
        }
        _transferProgress.value = map
    }

    suspend fun sendHelloBeaconNow() {
        broadcastHelloBeacon()
    }

    private suspend fun broadcastHelloBeacon() {
        val id = identity
        if (!id.isOnboarded) return
        val packet = buildHelloPacket(id)
        val encoded = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        transport.broadcastBytes(encoded)
    }

    private suspend fun sendHelloToEndpoint(endpointId: String) {
        val id = identity
        if (!id.isOnboarded) return
        val packet = buildHelloPacket(id)
        val encoded = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        transport.sendToEndpoint(endpointId, encoded)
    }

    private fun buildHelloPacket(id: LocalIdentityConfig): MeshPacket {
        beaconSeqNo++
        val summaries = routingTable.values.map {
            RouteSummaryEntry(
                targetNodeId = it.targetNodeId,
                displayName = it.displayName,
                signingPubKeyB64 = it.signingPubKeyB64,
                dhPubKeyB64 = it.dhPubKeyB64,
                hopCount = it.hopCount,
                seqNo = it.seqNo,
                linkQuality = it.linkQuality,
                latitude = it.latitude,
                longitude = it.longitude,
                locationUpdatedAt = it.locationUpdatedAt
            )
        }
        val geo = if (id.shareLocationOnRadar) localLocationProvider?.invoke() else null
        val hello = HelloBeaconPayload(
            nodeId = id.nodeId,
            displayName = id.displayName,
            signingPubKeyB64 = id.signingPubB64,
            dhPubKeyB64 = id.dhPubB64,
            seqNo = beaconSeqNo,
            relayCapable = true,
            routes = summaries,
            latitude = geo?.latitude,
            longitude = geo?.longitude,
            locationUpdatedAt = geo?.timestampMs
        )
        val payloadJson = json.encodeToString(hello)
        val packetId = "hello-${id.nodeId}-$beaconSeqNo"
        val timestamp = System.currentTimeMillis()
        val sig = signEnvelope(id, packetId, id.nodeId, payloadJson, timestamp)
        return MeshPacket(
            packetId = packetId,
            type = PacketType.HELLO_BEACON,
            senderNodeId = id.nodeId,
            destNodeId = null,
            ttl = 1,
            hopCount = 0,
            timestamp = timestamp,
            payloadJson = payloadJson,
            signatureB64 = sig
        )
    }

    private suspend fun sendInvSyncToEndpoint(endpointId: String) {
        val id = identity
        if (!id.isOnboarded) return
        val knownIds = seenMutex.withLock { seenLru.keys.toList().takeLast(80) }
        val incomplete = mutableMapOf<String, List<Int>>()
        for (t in mediaTransferDao.getResumableTransfers()) {
            if (t.direction == TransferDirection.INBOUND) {
                incomplete[t.transferId] = decodeBitset(t.ackedChunksBitset).toList()
            }
        }
        val inv = InvSyncPayload(knownPacketIds = knownIds, incompleteTransferChunks = incomplete)
        val packet = MeshPacket(
            packetId = "inv-${UUID.randomUUID()}",
            type = PacketType.INV_SYNC,
            senderNodeId = id.nodeId,
            destNodeId = null,
            ttl = 1,
            hopCount = 0,
            payloadJson = json.encodeToString(inv)
        )
        transport.sendToEndpoint(endpointId, json.encodeToString(packet).toByteArray(Charsets.UTF_8))
    }

    suspend fun handleIncomingBytes(fromEndpointId: String, bytes: ByteArray) {
        val packet = runCatching {
            json.decodeFromString<MeshPacket>(bytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return

        if (packet.senderNodeId == identity.nodeId) return

        // HELLO_BEACON is single-hop link state; never deduplicated globally by seenLru across time
        if (packet.type == PacketType.HELLO_BEACON) {
            handleHelloBeacon(fromEndpointId, packet)
            return
        }

        if (packet.type == PacketType.INV_SYNC) {
            handleInvSync(fromEndpointId, packet)
            return
        }

        // Real-time voice audio frames bypass Room seen_packets table to prevent SQLite overhead
        if (packet.type == PacketType.CALL_AUDIO) {
            handleCallAudioPacket(fromEndpointId, packet)
            return
        }

        val alreadySeen = markSeenIfNew(packet.packetId)
        if (alreadySeen) return

        when (packet.type) {
            PacketType.CHAT_MESSAGE -> handleChatMessagePacket(fromEndpointId, packet)
            PacketType.MEDIA_CHUNK -> handleMediaChunkPacket(fromEndpointId, packet)
            PacketType.CHUNK_ACK -> handleChunkAckPacket(fromEndpointId, packet)
            PacketType.DELIVERY_ACK -> handleDeliveryAckPacket(fromEndpointId, packet)
            PacketType.READ_RECEIPT -> handleReadReceiptPacket(fromEndpointId, packet)
            PacketType.GROUP_INVITE -> handleGroupInvitePacket(fromEndpointId, packet)
            PacketType.REACTION_EVENT -> handleReactionEventPacket(fromEndpointId, packet)
            PacketType.CALL_OFFER,
            PacketType.CALL_ANSWER,
            PacketType.CALL_REJECT,
            PacketType.CALL_END -> handleCallSignalPacket(fromEndpointId, packet)
            else -> Unit
        }
    }

    private suspend fun handleHelloBeacon(fromEndpointId: String, packet: MeshPacket) {
        val hello = runCatching { json.decodeFromString<HelloBeaconPayload>(packet.payloadJson) }.getOrNull()
            ?: return
        val sigOk = crypto.verifySignature(
            hello.signingPubKeyB64,
            envelopeBytes(packet.packetId, packet.senderNodeId, packet.payloadJson, packet.timestamp),
            packet.signatureB64
        )
        if (!sigOk) return

        val linkQuality = transport.directLinks.value[fromEndpointId]?.linkQuality ?: 90
        upsertDiscoveredPeer(
            nodeId = hello.nodeId,
            displayName = hello.displayName,
            signingPubKeyB64 = hello.signingPubKeyB64,
            dhPubKeyB64 = hello.dhPubKeyB64,
            hopCount = 1,
            nextHopNodeId = hello.nodeId,
            linkQuality = linkQuality,
            isDirect = true,
            latitude = hello.latitude,
            longitude = hello.longitude,
            locationUpdatedAt = hello.locationUpdatedAt
        )

        val prevDirectRoute = routingTable[hello.nodeId]
        val routeUpdated = prevDirectRoute == null || prevDirectRoute.nextHopEndpointId != fromEndpointId
        val directEntry = RouteEntry(
            targetNodeId = hello.nodeId,
            displayName = hello.displayName,
            signingPubKeyB64 = hello.signingPubKeyB64,
            dhPubKeyB64 = hello.dhPubKeyB64,
            nextHopNodeId = hello.nodeId,
            nextHopEndpointId = fromEndpointId,
            hopCount = 1,
            seqNo = hello.seqNo,
            linkQuality = linkQuality,
            latitude = hello.latitude,
            longitude = hello.longitude,
            locationUpdatedAt = hello.locationUpdatedAt
        )
        routingTable[hello.nodeId] = directEntry

        val advertisedTargetIds = hello.routes.map { it.targetNodeId }.toSet()

        // DSDV route withdrawal: prune any multi-hop route via hello.nodeId that neighbor stopped advertising
        val withdrawn = routingTable.entries.filter { (targetId, entry) ->
            entry.nextHopNodeId == hello.nodeId &&
                targetId != hello.nodeId &&
                !advertisedTargetIds.contains(targetId)
        }.map { it.key }
        for (withdrawnId in withdrawn) {
            routingTable.remove(withdrawnId)
            val peer = peerDao.getPeer(withdrawnId)
            if (peer != null) {
                peerDao.upsertPeer(
                    peer.copy(
                        isDirectlyConnected = false,
                        bestHopCount = Int.MAX_VALUE,
                        nextHopNodeId = null,
                        linkQuality = 0
                    )
                )
            }
        }

        // Process advertised multi-hop distance-vector routes (split horizon: ignore routes back to self)
        for (advertised in hello.routes) {
            if (advertised.targetNodeId == identity.nodeId) continue
            val newHopCount = advertised.hopCount + 1
            if (newHopCount > identity.maxHops) continue
            val existing = routingTable[advertised.targetNodeId]
            val shouldReplace = existing == null ||
                advertised.seqNo > existing.seqNo ||
                (advertised.seqNo == existing.seqNo && newHopCount < existing.hopCount) ||
                existing.nextHopNodeId == hello.nodeId

            if (shouldReplace) {
                val effectiveQuality = minOf(linkQuality, advertised.linkQuality)
                routingTable[advertised.targetNodeId] = RouteEntry(
                    targetNodeId = advertised.targetNodeId,
                    displayName = advertised.displayName,
                    signingPubKeyB64 = advertised.signingPubKeyB64,
                    dhPubKeyB64 = advertised.dhPubKeyB64,
                    nextHopNodeId = hello.nodeId,
                    nextHopEndpointId = fromEndpointId,
                    hopCount = newHopCount,
                    seqNo = advertised.seqNo,
                    linkQuality = effectiveQuality,
                    latitude = advertised.latitude,
                    longitude = advertised.longitude,
                    locationUpdatedAt = advertised.locationUpdatedAt
                )
                upsertDiscoveredPeer(
                    nodeId = advertised.targetNodeId,
                    displayName = advertised.displayName,
                    signingPubKeyB64 = advertised.signingPubKeyB64,
                    dhPubKeyB64 = advertised.dhPubKeyB64,
                    hopCount = newHopCount,
                    nextHopNodeId = hello.nodeId,
                    linkQuality = effectiveQuality,
                    isDirect = false,
                    latitude = advertised.latitude,
                    longitude = advertised.longitude,
                    locationUpdatedAt = advertised.locationUpdatedAt
                )
            }
        }
        _routesFlow.value = routingTable.toMap()
        refreshMeshStats(transport.directLinks.value)
        if (routeUpdated || hello.routes.isNotEmpty() || dtnRelayDao.getActivePackets().isNotEmpty()) {
            scope.launch { flushDtnQueue() }
        }
    }

    private suspend fun upsertDiscoveredPeer(
        nodeId: String,
        displayName: String,
        signingPubKeyB64: String,
        dhPubKeyB64: String,
        hopCount: Int,
        nextHopNodeId: String?,
        linkQuality: Int,
        isDirect: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        locationUpdatedAt: Long? = null
    ) {
        val existing = peerDao.getPeer(nodeId)
        val trust = if (existing == null) {
            TrustStatus.UNVERIFIED_TOFU
        } else if (existing.signingPubKeyB64 != signingPubKeyB64 || existing.dhPubKeyB64 != dhPubKeyB64) {
            TrustStatus.KEY_CHANGED_WARNING
        } else {
            existing.trustStatus
        }

        val updated = PeerEntity(
            nodeId = nodeId,
            displayName = displayName,
            signingPubKeyB64 = if (trust == TrustStatus.KEY_CHANGED_WARNING && existing != null) existing.signingPubKeyB64 else signingPubKeyB64,
            dhPubKeyB64 = if (trust == TrustStatus.KEY_CHANGED_WARNING && existing != null) existing.dhPubKeyB64 else dhPubKeyB64,
            trustStatus = trust,
            lastSeenAt = System.currentTimeMillis(),
            bestHopCount = hopCount,
            nextHopNodeId = nextHopNodeId,
            linkQuality = linkQuality,
            isDirectlyConnected = isDirect,
            latitude = latitude,
            longitude = longitude,
            locationUpdatedAt = locationUpdatedAt
        )
        peerDao.upsertPeer(updated)

        // Propagate updated display name to any existing direct conversation with this peer
        val directConvId = "peer:$nodeId"
        val existingConv = conversationDao.getConversation(directConvId)
        if (existingConv != null &&
            existingConv.type == ConversationType.DIRECT &&
            displayName.isNotBlank() &&
            existingConv.title != displayName
        ) {
            conversationDao.updateTitle(directConvId, displayName)
        }
    }

    private suspend fun handleInvSync(fromEndpointId: String, packet: MeshPacket) {
        val inv = runCatching { json.decodeFromString<InvSyncPayload>(packet.payloadJson) }.getOrNull() ?: return
        val remoteSeen = inv.knownPacketIds.toSet()
        val queued = dtnRelayDao.getActivePackets()
        for (entry in queued) {
            if (!remoteSeen.contains(entry.packetId)) {
                transport.sendToEndpoint(fromEndpointId, entry.packetJson.toByteArray(Charsets.UTF_8))
            }
        }
        // Resume missing chunks requested by peer
        for ((transferId, ackedList) in inv.incompleteTransferChunks) {
            val transfer = mediaTransferDao.getTransfer(transferId) ?: continue
            if (transfer.direction == TransferDirection.OUTBOUND && transfer.status != TransferStatus.COMPLETED) {
                val ackedSet = ackedList.toSet()
                val existingAcked = decodeBitset(transfer.ackedChunksBitset)
                val merged = existingAcked + ackedSet
                mediaTransferDao.updateProgress(
                    transferId = transferId,
                    bitset = encodeBitset(merged),
                    bytesTransferred = minOf(transfer.totalBytes, merged.size.toLong() * transfer.chunkSizeBytes),
                    status = TransferStatus.ACTIVE
                )
                resumeOutboundTransfer(transferId)
            }
        }
    }

    private suspend fun handleChatMessagePacket(fromEndpointId: String, packet: MeshPacket) {
        val msgPayload = runCatching { json.decodeFromString<ChatMessagePayload>(packet.payloadJson) }.getOrNull()
            ?: return

        val isForMe = packet.destNodeId == null || packet.destNodeId == identity.nodeId
        val isBroadcastOrGroup = packet.destNodeId == null

        if (isForMe) {
            deliverChatLocally(packet, msgPayload)
        }

        // Relay if multi-hop TTL remains and packet is either broadcast/group or destined for someone else
        if (packet.ttl > 1 && (isBroadcastOrGroup || packet.destNodeId != identity.nodeId)) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    private suspend fun deliverChatLocally(packet: MeshPacket, payload: ChatMessagePayload) {
        val conversationType = runCatching { ConversationType.valueOf(payload.conversationType) }
            .getOrDefault(ConversationType.BROADCAST)

        val localConversationId = when (conversationType) {
            ConversationType.BROADCAST -> BROADCAST_CONVERSATION_ID
            ConversationType.DIRECT -> "peer:${packet.senderNodeId}"
            ConversationType.GROUP -> payload.conversationId
        }

        val senderPeer = peerDao.getPeer(packet.senderNodeId)
        val decryptedBody = when {
            !payload.isEncrypted -> {
                val sigValid = senderPeer?.let {
                    crypto.verifySignature(
                        it.signingPubKeyB64,
                        envelopeBytes(packet.packetId, packet.senderNodeId, packet.payloadJson, packet.timestamp),
                        packet.signatureB64
                    )
                } ?: true
                if (!sigValid) return
                crypto.decodeB64(payload.encryptedBodyB64).toString(Charsets.UTF_8)
            }
            conversationType == ConversationType.DIRECT -> {
                val peerDh = senderPeer?.dhPubKeyB64 ?: routingTable[packet.senderNodeId]?.dhPubKeyB64 ?: return
                val aad = "${payload.messageId}|${packet.senderNodeId}|${identity.nodeId}"
                runCatching {
                    crypto.decryptTextFromPeer(
                        localDhPrivB64 = identity.dhPrivB64,
                        remoteDhPubB64 = peerDh,
                        localNodeId = identity.nodeId,
                        remoteNodeId = packet.senderNodeId,
                        ciphertextB64 = payload.encryptedBodyB64,
                        aad = aad
                    )
                }.getOrNull() ?: return
            }
            conversationType == ConversationType.GROUP -> {
                val conv = conversationDao.getConversation(localConversationId) ?: return
                val groupKey = conv.groupKeyB64 ?: return
                val aad = "${payload.messageId}|${packet.senderNodeId}|$localConversationId"
                runCatching {
                    crypto.decryptWithGroupKey(groupKey, payload.encryptedBodyB64, aad).toString(Charsets.UTF_8)
                }.getOrNull() ?: return
            }
            else -> return
        }

        val existingConv = conversationDao.getConversation(localConversationId)
        val resolvedSenderName = senderPeer?.displayName ?: payload.senderName
        if (existingConv == null) {
            val title = when (conversationType) {
                ConversationType.BROADCAST -> "#Nearby Mesh"
                ConversationType.DIRECT -> resolvedSenderName
                ConversationType.GROUP -> payload.groupTitle ?: "Mesh Group"
            }
            conversationDao.upsertConversation(
                ConversationEntity(
                    conversationId = localConversationId,
                    type = conversationType,
                    title = title,
                    peerNodeId = if (conversationType == ConversationType.DIRECT) packet.senderNodeId else null,
                    isPinned = conversationType == ConversationType.BROADCAST
                )
            )
        } else if (
            conversationType == ConversationType.DIRECT &&
            resolvedSenderName.isNotBlank() &&
            existingConv.title != resolvedSenderName
        ) {
            conversationDao.updateTitle(localConversationId, resolvedSenderName)
        }

        val msgType = runCatching { MessageType.valueOf(payload.messageType) }.getOrDefault(MessageType.TEXT)
        val preview = when (msgType) {
            MessageType.TEXT -> decryptedBody
            MessageType.VOICE -> "Voice note (${(payload.mediaDurationMs / 1000).coerceAtLeast(1)}s)"
            MessageType.IMAGE -> "Photo${if (decryptedBody.isNotBlank()) ": $decryptedBody" else ""}"
        }

        val existingCompletedTransfer = payload.transferId?.let { mediaTransferDao.getTransfer(it) }
        val completedLocalPath = if (existingCompletedTransfer?.status == TransferStatus.COMPLETED) {
            existingCompletedTransfer.localStagingPath
        } else {
            null
        }

        // Order strictly by local device arrival time + monotonic sequence so sender clock skew never sorts new replies above old messages
        val localOrderTs = nextLocalOrderTs(localConversationId)
        val messageEntity = MessageEntity(
            messageId = payload.messageId,
            conversationId = localConversationId,
            senderNodeId = packet.senderNodeId,
            senderName = resolvedSenderName,
            timestamp = packet.timestamp,
            localOrderTs = localOrderTs,
            type = msgType,
            bodyText = decryptedBody,
            replyToMessageId = payload.replyToMessageId,
            replyToPreview = payload.replyToPreview,
            transferId = payload.transferId,
            mediaLocalPath = completedLocalPath,
            mediaSha256 = payload.mediaSha256,
            mediaTotalBytes = payload.mediaTotalBytes,
            mediaDurationMs = payload.mediaDurationMs,
            waveformSamplesJson = json.encodeToString(payload.waveformSamples),
            microThumbBase64 = payload.microThumbBase64,
            deliveryStatus = DeliveryStatus.DELIVERED,
            hopCount = packet.hopCount + 1,
            isIncoming = true
        )

        val inserted = messageDao.insertMessage(messageEntity)
        if (inserted != -1L) {
            conversationDao.updateLastMessage(localConversationId, preview, localOrderTs, unreadDelta = 1)
            _incomingMessageAlerts.tryEmit(
                IncomingMessageAlert(
                    conversationId = localConversationId,
                    messageId = payload.messageId,
                    senderName = resolvedSenderName,
                    preview = preview
                )
            )
            if (payload.transferId != null && payload.mediaTotalBytes > 0L && existingCompletedTransfer == null) {
                val transfer = MediaTransferEntity(
                    transferId = payload.transferId,
                    messageId = payload.messageId,
                    conversationId = localConversationId,
                    targetNodeId = packet.senderNodeId,
                    direction = TransferDirection.INBOUND,
                    mimeType = if (msgType == MessageType.VOICE) "audio/mp4" else "image/jpeg",
                    localStagingPath = File(mediaRootDir, "${payload.transferId}.part").absolutePath,
                    sha256 = payload.mediaSha256.orEmpty(),
                    totalBytes = payload.mediaTotalBytes,
                    totalChunks = payload.totalChunks,
                    chunkSizeBytes = CHUNK_SIZE_BYTES,
                    ackedChunksBitset = "",
                    bytesTransferred = 0L,
                    status = TransferStatus.ACTIVE
                )
                mediaTransferDao.upsertTransfer(transfer)
                updateTransferUiState(transfer, 0L)
            }
        }

        // Send End-to-End Delivery ACK back to sender for DIRECT messages
        if (conversationType == ConversationType.DIRECT) {
            val ack = DeliveryAckPayload(
                messageId = payload.messageId,
                conversationId = "peer:${identity.nodeId}",
                reachedAt = System.currentTimeMillis(),
                finalHopCount = packet.hopCount + 1
            )
            val ackPacket = MeshPacket(
                packetId = "dack-${payload.messageId}-${identity.nodeId}",
                type = PacketType.DELIVERY_ACK,
                senderNodeId = identity.nodeId,
                destNodeId = packet.senderNodeId,
                ttl = identity.maxHops,
                hopCount = 0,
                payloadJson = json.encodeToString(ack)
            )
            sendOrBufferPacket(ackPacket)
        }
    }

    private suspend fun handleMediaChunkPacket(fromEndpointId: String, packet: MeshPacket) {
        val chunk = runCatching { json.decodeFromString<MediaChunkPayload>(packet.payloadJson) }.getOrNull() ?: return
        val isForMe = packet.destNodeId == null || packet.destNodeId == identity.nodeId

        if (isForMe) {
            processInboundChunk(packet, chunk)
        }
        if (packet.ttl > 1 && (packet.destNodeId == null || packet.destNodeId != identity.nodeId)) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    private suspend fun processInboundChunk(packet: MeshPacket, chunk: MediaChunkPayload) {
        val rawChunkBytes = runCatching { crypto.decodeB64(chunk.encryptedChunkB64) }.getOrNull() ?: return
        val decryptedChunk = if (!chunk.isEncrypted) {
            rawChunkBytes
        } else {
            val conv = conversationDao.getConversation(chunk.conversationId)
            val aad = "${chunk.transferId}|${chunk.chunkIndex}|${packet.senderNodeId}"
            if (conv?.type == ConversationType.GROUP && conv.groupKeyB64 != null) {
                runCatching {
                    crypto.decryptAesGcm(crypto.decodeB64(conv.groupKeyB64), rawChunkBytes, aad.toByteArray(Charsets.UTF_8))
                }.getOrNull() ?: return
            } else {
                val peerDh = peerDao.getPeer(packet.senderNodeId)?.dhPubKeyB64
                    ?: routingTable[packet.senderNodeId]?.dhPubKeyB64 ?: return
                val key = crypto.derivePairwiseKey(
                    identity.dhPrivB64,
                    peerDh,
                    identity.nodeId,
                    packet.senderNodeId
                )
                runCatching {
                    crypto.decryptAesGcm(key, rawChunkBytes, aad.toByteArray(Charsets.UTF_8))
                }.getOrNull() ?: return
            }
        }

        if (crypto.sha256Hex(decryptedChunk) != chunk.chunkSha256) return

        val existing = mediaTransferDao.getTransfer(chunk.transferId)
        if (existing?.status == TransferStatus.COMPLETED) {
            // Transfer already assembled earlier; re-send CHUNK_ACK with fresh packetId in case sender missed the original ACK
            val reAckPayload = ChunkAckPayload(
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                ackedBitmapHex = existing.ackedChunksBitset
            )
            val reAckPacket = MeshPacket(
                packetId = "cack-${chunk.transferId}-${chunk.chunkIndex}-${UUID.randomUUID().toString().take(8)}",
                type = PacketType.CHUNK_ACK,
                senderNodeId = identity.nodeId,
                destNodeId = packet.senderNodeId,
                ttl = identity.maxHops,
                hopCount = 0,
                payloadJson = json.encodeToString(reAckPayload)
            )
            sendOrBufferPacket(reAckPacket)
            return
        }

        val buffer = inboundTransferBuffers.getOrPut(chunk.transferId) { ConcurrentHashMap() }
        buffer[chunk.chunkIndex] = decryptedChunk
        inboundTransferMeta[chunk.transferId] = chunk

        val receivedIndices = buffer.keys.toSet()
        val bytesDone = buffer.values.sumOf { it.size.toLong() }.coerceAtMost(chunk.totalBytes)

        val ackPayload = ChunkAckPayload(
            transferId = chunk.transferId,
            chunkIndex = chunk.chunkIndex,
            ackedBitmapHex = encodeBitset(receivedIndices)
        )
        val ackPacket = MeshPacket(
            packetId = "cack-${chunk.transferId}-${chunk.chunkIndex}-${UUID.randomUUID().toString().take(8)}",
            type = PacketType.CHUNK_ACK,
            senderNodeId = identity.nodeId,
            destNodeId = packet.senderNodeId,
            ttl = identity.maxHops,
            hopCount = 0,
            payloadJson = json.encodeToString(ackPayload)
        )
        sendOrBufferPacket(ackPacket)

        if (receivedIndices.size >= chunk.totalChunks) {
            val assembled = ByteArray(chunk.totalBytes.toInt())
            var offset = 0
            for (idx in 0 until chunk.totalChunks) {
                val part = buffer[idx] ?: return
                val copyLen = minOf(part.size, assembled.size - offset)
                System.arraycopy(part, 0, assembled, offset, copyLen)
                offset += copyLen
            }
            if (crypto.sha256Hex(assembled) != chunk.fileSha256) {
                existing?.let {
                    mediaTransferDao.updateStatus(it.transferId, TransferStatus.FAILED, "Checksum mismatch")
                    updateTransferUiState(it.copy(status = TransferStatus.FAILED, errorReason = "Checksum mismatch"), 0L)
                }
                return
            }
            val ext = if (chunk.mimeType.contains("audio")) "m4a" else "jpg"
            val finalFile = File(mediaRootDir, "${chunk.transferId}.$ext")
            finalFile.writeBytes(assembled)
            inboundTransferBuffers.remove(chunk.transferId)
            inboundTransferMeta.remove(chunk.transferId)

            messageDao.attachCompletedMedia(chunk.transferId, finalFile.absolutePath)
            val completedEntity = (existing ?: MediaTransferEntity(
                transferId = chunk.transferId,
                messageId = chunk.messageId,
                conversationId = chunk.conversationId,
                targetNodeId = packet.senderNodeId,
                direction = TransferDirection.INBOUND,
                mimeType = chunk.mimeType,
                localStagingPath = finalFile.absolutePath,
                sha256 = chunk.fileSha256,
                totalBytes = chunk.totalBytes,
                totalChunks = chunk.totalChunks,
                chunkSizeBytes = CHUNK_SIZE_BYTES,
                ackedChunksBitset = encodeBitset(receivedIndices),
                bytesTransferred = chunk.totalBytes,
                status = TransferStatus.COMPLETED
            )).copy(
                localStagingPath = finalFile.absolutePath,
                ackedChunksBitset = encodeBitset(receivedIndices),
                bytesTransferred = chunk.totalBytes,
                status = TransferStatus.COMPLETED
            )
            mediaTransferDao.upsertTransfer(completedEntity)
            updateTransferUiState(completedEntity, 0L)
        } else if (existing != null) {
            val updated = existing.copy(
                ackedChunksBitset = encodeBitset(receivedIndices),
                bytesTransferred = bytesDone,
                status = TransferStatus.ACTIVE,
                updatedAt = System.currentTimeMillis()
            )
            mediaTransferDao.upsertTransfer(updated)
            updateTransferUiState(updated, CHUNK_SIZE_BYTES.toLong())
        }
    }

    private suspend fun handleChunkAckPacket(fromEndpointId: String, packet: MeshPacket) {
        val ack = runCatching { json.decodeFromString<ChunkAckPayload>(packet.payloadJson) }.getOrNull() ?: return
        if (packet.destNodeId == identity.nodeId) {
            val transfer = mediaTransferDao.getTransfer(ack.transferId) ?: return
            val acked = decodeBitset(transfer.ackedChunksBitset).toMutableSet()
            acked.add(ack.chunkIndex)
            ack.ackedBitmapHex?.let { acked.addAll(decodeBitset(it)) }
            chunkAckWaiters.getOrPut(ack.transferId) { ConcurrentHashMap.newKeySet() }.addAll(acked)

            val done = acked.size >= transfer.totalChunks
            val bytesDone = if (done) transfer.totalBytes else minOf(transfer.totalBytes, acked.size.toLong() * transfer.chunkSizeBytes)
            val newStatus = if (done) TransferStatus.COMPLETED else TransferStatus.ACTIVE
            val updated = transfer.copy(
                ackedChunksBitset = encodeBitset(acked),
                bytesTransferred = bytesDone,
                status = newStatus,
                updatedAt = System.currentTimeMillis()
            )
            mediaTransferDao.upsertTransfer(updated)
            updateTransferUiState(updated, CHUNK_SIZE_BYTES.toLong())
            if (done) {
                messageDao.updateDeliveryStatus(transfer.messageId, DeliveryStatus.DELIVERED, packet.hopCount + 1)
            }
        } else if (packet.ttl > 1) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    private suspend fun handleDeliveryAckPacket(fromEndpointId: String, packet: MeshPacket) {
        val ack = runCatching { json.decodeFromString<DeliveryAckPayload>(packet.payloadJson) }.getOrNull() ?: return
        if (packet.destNodeId == identity.nodeId) {
            messageDao.updateDeliveryStatus(ack.messageId, DeliveryStatus.DELIVERED, ack.finalHopCount)
        } else if (packet.ttl > 1) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    private suspend fun handleReadReceiptPacket(fromEndpointId: String, packet: MeshPacket) {
        val receipt = runCatching { json.decodeFromString<ReadReceiptPayload>(packet.payloadJson) }.getOrNull() ?: return
        if (packet.destNodeId == identity.nodeId) {
            messageDao.updateDeliveryStatus(receipt.messageId, DeliveryStatus.READ, packet.hopCount + 1)
        } else if (packet.ttl > 1) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    private suspend fun handleGroupInvitePacket(fromEndpointId: String, packet: MeshPacket) {
        val invite = runCatching { json.decodeFromString<GroupInvitePayload>(packet.payloadJson) }.getOrNull() ?: return
        if (packet.destNodeId == identity.nodeId) {
            val peerDh = peerDao.getPeer(packet.senderNodeId)?.dhPubKeyB64
                ?: routingTable[packet.senderNodeId]?.dhPubKeyB64 ?: return
            val aad = "${invite.groupId}|${packet.senderNodeId}|${identity.nodeId}"
            val groupKeyB64 = runCatching {
                crypto.decryptTextFromPeer(
                    localDhPrivB64 = identity.dhPrivB64,
                    remoteDhPubB64 = peerDh,
                    localNodeId = identity.nodeId,
                    remoteNodeId = packet.senderNodeId,
                    ciphertextB64 = invite.encryptedGroupKeyB64,
                    aad = aad
                )
            }.getOrNull() ?: return

            conversationDao.upsertConversation(
                ConversationEntity(
                    conversationId = invite.groupId,
                    type = ConversationType.GROUP,
                    title = invite.groupTitle,
                    groupKeyB64 = groupKeyB64,
                    memberNodeIdsJson = json.encodeToString(invite.memberNodeIds),
                    lastMessagePreview = "Joined encrypted group",
                    lastMessageTimestamp = packet.timestamp
                )
            )
        } else if (packet.ttl > 1) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    private suspend fun handleReactionEventPacket(fromEndpointId: String, packet: MeshPacket) {
        val ev = runCatching { json.decodeFromString<ReactionEventPayload>(packet.payloadJson) }.getOrNull() ?: return
        if (packet.destNodeId == null || packet.destNodeId == identity.nodeId) {
            val msg = messageDao.getMessage(ev.messageId)
            if (msg != null) {
                val map = runCatching { json.decodeFromString<MutableMap<String, List<String>>>(msg.reactionsJson) }
                    .getOrDefault(mutableMapOf())
                val currentReactors = map[ev.emoji]?.toMutableList() ?: mutableListOf()
                if (ev.isRemove) {
                    currentReactors.remove(packet.senderNodeId)
                    if (currentReactors.isEmpty()) {
                        map.remove(ev.emoji)
                    } else {
                        map[ev.emoji] = currentReactors
                    }
                } else {
                    if (!currentReactors.contains(packet.senderNodeId)) {
                        currentReactors.add(packet.senderNodeId)
                    }
                    map[ev.emoji] = currentReactors
                }
                messageDao.updateReactions(ev.messageId, json.encodeToString(map))
            }
        }
        if (packet.ttl > 1 && (packet.destNodeId == null || packet.destNodeId != identity.nodeId)) {
            forwardPacket(packet, excludeEndpointId = fromEndpointId)
        }
    }

    // ============================================================================================
    // Real-Time Voice Call Fast-Path Signaling & E2E Encrypted Audio Relay
    // ============================================================================================

    private suspend fun getOrDerivePairwiseKey(peerNodeId: String): ByteArray? {
        val cacheKey = "${identity.nodeId}|$peerNodeId|${identity.dhPrivB64.take(12)}"
        pairwiseKeyCache[cacheKey]?.let { return it }
        val peerDh = routingTable[peerNodeId]?.dhPubKeyB64
            ?: peerDao.getPeer(peerNodeId)?.dhPubKeyB64
            ?: return null
        val derived = crypto.derivePairwiseKey(identity.dhPrivB64, peerDh, identity.nodeId, peerNodeId)
        pairwiseKeyCache[cacheKey] = derived
        return derived
    }

    private suspend fun handleCallSignalPacket(fromEndpointId: String, packet: MeshPacket) {
        if (packet.destNodeId != identity.nodeId) {
            if (packet.ttl > 1) {
                forwardCallPacket(packet, excludeEndpointId = fromEndpointId)
            }
            return
        }
        val senderPeer = peerDao.getPeer(packet.senderNodeId)
        val signingKey = senderPeer?.signingPubKeyB64 ?: routingTable[packet.senderNodeId]?.signingPubKeyB64
        if (signingKey != null && packet.signatureB64.isNotBlank()) {
            val sigOk = crypto.verifySignature(
                signingKey,
                envelopeBytes(packet.packetId, packet.senderNodeId, packet.payloadJson, packet.timestamp),
                packet.signatureB64
            )
            if (!sigOk) return
        }

        val hop = packet.hopCount + 1
        when (packet.type) {
            PacketType.CALL_OFFER -> {
                val offer = runCatching { json.decodeFromString<CallOfferPayload>(packet.payloadJson) }.getOrNull() ?: return
                _incomingCallEvents.tryEmit(
                    IncomingCallEvent.Offer(
                        senderNodeId = packet.senderNodeId,
                        hopCount = hop,
                        payload = offer,
                        trustStatus = senderPeer?.trustStatus ?: TrustStatus.UNVERIFIED_TOFU
                    )
                )
            }
            PacketType.CALL_ANSWER -> {
                val ans = runCatching { json.decodeFromString<CallAnswerPayload>(packet.payloadJson) }.getOrNull() ?: return
                _incomingCallEvents.tryEmit(IncomingCallEvent.Answer(packet.senderNodeId, hop, ans))
            }
            PacketType.CALL_REJECT -> {
                val rej = runCatching { json.decodeFromString<CallRejectPayload>(packet.payloadJson) }.getOrNull() ?: return
                _incomingCallEvents.tryEmit(IncomingCallEvent.Reject(packet.senderNodeId, hop, rej))
            }
            PacketType.CALL_END -> {
                val end = runCatching { json.decodeFromString<CallEndPayload>(packet.payloadJson) }.getOrNull() ?: return
                _incomingCallEvents.tryEmit(IncomingCallEvent.End(packet.senderNodeId, hop, end))
            }
            else -> Unit
        }
    }

    private suspend fun handleCallAudioPacket(fromEndpointId: String, packet: MeshPacket) {
        val dup = synchronized(callAudioSeenLock) {
            if (callAudioSeenLru.containsKey(packet.packetId)) true
            else {
                callAudioSeenLru[packet.packetId] = System.currentTimeMillis()
                false
            }
        }
        if (dup) return

        if (packet.destNodeId != identity.nodeId) {
            if (packet.ttl > 1) {
                forwardCallPacket(packet, excludeEndpointId = fromEndpointId)
            }
            return
        }

        val audio = runCatching { json.decodeFromString<CallAudioPayload>(packet.payloadJson) }.getOrNull() ?: return
        val key = getOrDerivePairwiseKey(packet.senderNodeId) ?: return
        val aad = "${audio.callId}|${audio.seqNo}|${packet.senderNodeId}|${identity.nodeId}".toByteArray(Charsets.UTF_8)
        val plainAdpcm = runCatching {
            crypto.decryptAesGcm(key, crypto.decodeB64(audio.encryptedAdpcmB64), aad)
        }.getOrNull() ?: return

        _incomingCallEvents.tryEmit(
            IncomingCallEvent.Audio(
                senderNodeId = packet.senderNodeId,
                hopCount = packet.hopCount + 1,
                callId = audio.callId,
                seqNo = audio.seqNo,
                adpcmBytes = plainAdpcm,
                initialPredictor = audio.initialPredictor,
                initialStepIndex = audio.initialStepIndex,
                sampleCount = audio.sampleCount
            )
        )
    }

    private suspend fun forwardCallPacket(packet: MeshPacket, excludeEndpointId: String) {
        val dest = packet.destNodeId ?: return
        val resolved = resolveLiveEndpoint(dest) ?: return
        val (nextHopNode, liveEp) = resolved
        if (liveEp == excludeEndpointId) return
        val forwarded = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1,
            nextHopNodeId = nextHopNode
        )
        val raw = json.encodeToString(forwarded).toByteArray(Charsets.UTF_8)
        if (packet.type == PacketType.CALL_AUDIO) {
            transport.sendFastUnreliable(liveEp, raw)
        } else {
            transport.sendToEndpoint(liveEp, raw)
        }
    }

    fun getRouteForPeer(nodeId: String): RouteEntry? = routingTable[nodeId]

    suspend fun getPeerSnapshot(nodeId: String): PeerEntity? = peerDao.getPeer(nodeId)

    suspend fun sendCallSignal(destNodeId: String, type: PacketType, payloadJson: String): Boolean {
        val id = identity
        if (!id.isOnboarded) return false
        val route = routingTable[destNodeId] ?: return false
        val packetId = "callsig-${type.name}-${UUID.randomUUID()}"
        val ts = System.currentTimeMillis()
        val sig = signEnvelope(id, packetId, id.nodeId, payloadJson, ts)
        val packet = MeshPacket(
            packetId = packetId,
            type = type,
            senderNodeId = id.nodeId,
            destNodeId = destNodeId,
            nextHopNodeId = route.nextHopNodeId,
            ttl = id.maxHops,
            hopCount = 0,
            timestamp = ts,
            payloadJson = payloadJson,
            signatureB64 = sig
        )
        markSeenIfNew(packetId)
        return transport.sendToEndpoint(
            route.nextHopEndpointId,
            json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        )
    }

    suspend fun sendCallAudioFrame(
        destNodeId: String,
        callId: String,
        seqNo: Int,
        adpcmBytes: ByteArray,
        initialPredictor: Int,
        initialStepIndex: Int,
        sampleCount: Int
    ): Boolean {
        val id = identity
        if (!id.isOnboarded) return false
        val (nextHopNode, liveEp) = resolveLiveEndpoint(destNodeId) ?: return false
        val key = getOrDerivePairwiseKey(destNodeId) ?: return false
        val aad = "$callId|$seqNo|${id.nodeId}|$destNodeId".toByteArray(Charsets.UTF_8)
        val cipher = crypto.encryptAesGcm(key, adpcmBytes, aad)
        val payload = CallAudioPayload(
            callId = callId,
            seqNo = seqNo,
            encryptedAdpcmB64 = crypto.encodeB64(cipher),
            initialPredictor = initialPredictor,
            initialStepIndex = initialStepIndex,
            sampleCount = sampleCount
        )
        val packet = MeshPacket(
            packetId = "caud-$callId-$seqNo-${id.nodeId}",
            type = PacketType.CALL_AUDIO,
            senderNodeId = id.nodeId,
            destNodeId = destNodeId,
            nextHopNodeId = nextHopNode,
            ttl = id.maxHops,
            hopCount = 0,
            payloadJson = json.encodeToString(payload)
        )
        return transport.sendFastUnreliable(
            liveEp,
            json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        )
    }

    suspend fun recordCallLogMessage(
        peerNodeId: String,
        peerDisplayName: String,
        summaryText: String,
        isIncoming: Boolean
    ) {
        val id = identity
        if (!id.isOnboarded) return
        val convId = openOrCreateDirectConversation(peerNodeId)
        val localOrderTs = nextLocalOrderTs(convId)
        val now = System.currentTimeMillis()
        val msg = MessageEntity(
            messageId = "calllog-${UUID.randomUUID()}",
            conversationId = convId,
            senderNodeId = if (isIncoming) peerNodeId else id.nodeId,
            senderName = if (isIncoming) peerDisplayName else id.displayName,
            timestamp = now,
            localOrderTs = localOrderTs,
            type = MessageType.TEXT,
            bodyText = summaryText,
            deliveryStatus = DeliveryStatus.DELIVERED,
            hopCount = if (isIncoming) 1 else 0,
            isIncoming = isIncoming
        )
        messageDao.insertMessage(msg)
        conversationDao.updateLastMessage(convId, summaryText, localOrderTs, unreadDelta = 0)
    }

    // ============================================================================================
    // Outbound Messaging & Multi-Hop Media Transfer API
    // ============================================================================================

    suspend fun sendTextMessage(
        conversationId: String,
        text: String,
        replyToMessageId: String? = null,
        replyToPreview: String? = null
    ): String? {
        val conv = conversationDao.getConversation(conversationId) ?: return null
        val id = identity
        if (!id.isOnboarded) return null

        val messageId = "msg-${UUID.randomUUID()}"
        val timestamp = System.currentTimeMillis()
        val destNodeId = if (conv.type == ConversationType.DIRECT) conv.peerNodeId else null
        val isEncrypted = conv.type != ConversationType.BROADCAST

        val encodedBody = when (conv.type) {
            ConversationType.BROADCAST -> crypto.encodeB64(text.toByteArray(Charsets.UTF_8))
            ConversationType.DIRECT -> {
                val peerId = conv.peerNodeId ?: return null
                val peerDh = peerDao.getPeer(peerId)?.dhPubKeyB64 ?: routingTable[peerId]?.dhPubKeyB64 ?: return null
                val aad = "$messageId|${id.nodeId}|$peerId"
                crypto.encryptTextForPeer(id.dhPrivB64, peerDh, id.nodeId, peerId, text, aad)
            }
            ConversationType.GROUP -> {
                val groupKey = conv.groupKeyB64 ?: return null
                val aad = "$messageId|${id.nodeId}|$conversationId"
                crypto.encryptWithGroupKey(groupKey, text.toByteArray(Charsets.UTF_8), aad)
            }
        }

        val payload = ChatMessagePayload(
            messageId = messageId,
            conversationId = conversationId,
            conversationType = conv.type.name,
            groupTitle = if (conv.type == ConversationType.GROUP) conv.title else null,
            senderName = id.displayName,
            messageType = MessageType.TEXT.name,
            encryptedBodyB64 = encodedBody,
            isEncrypted = isEncrypted,
            replyToMessageId = replyToMessageId,
            replyToPreview = replyToPreview
        )
        val payloadJson = json.encodeToString(payload)
        val sig = signEnvelope(id, messageId, id.nodeId, payloadJson, timestamp)
        val packet = MeshPacket(
            packetId = messageId,
            type = PacketType.CHAT_MESSAGE,
            senderNodeId = id.nodeId,
            destNodeId = destNodeId,
            ttl = id.maxHops,
            hopCount = 0,
            timestamp = timestamp,
            payloadJson = payloadJson,
            signatureB64 = sig
        )

        val localOrderTs = nextLocalOrderTs(conversationId)
        val localEntity = MessageEntity(
            messageId = messageId,
            conversationId = conversationId,
            senderNodeId = id.nodeId,
            senderName = id.displayName,
            timestamp = timestamp,
            localOrderTs = localOrderTs,
            type = MessageType.TEXT,
            bodyText = text,
            replyToMessageId = replyToMessageId,
            replyToPreview = replyToPreview,
            deliveryStatus = DeliveryStatus.QUEUED,
            hopCount = 0,
            isIncoming = false
        )
        messageDao.insertMessage(localEntity)
        conversationDao.updateLastMessage(conversationId, text, localOrderTs, unreadDelta = 0)
        markSeenIfNew(packet.packetId)

        val dispatched = sendOrBufferPacket(packet)
        if (dispatched) {
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.RELAYED, 1)
        }
        return messageId
    }

    suspend fun sendMediaMessage(
        conversationId: String,
        type: MessageType,
        mediaFile: File,
        mimeType: String,
        caption: String = "",
        durationMs: Long = 0L,
        waveformSamples: List<Int> = emptyList(),
        microThumbBase64: String? = null
    ): String? {
        val conv = conversationDao.getConversation(conversationId) ?: return null
        val id = identity
        if (!id.isOnboarded || !mediaFile.exists()) return null

        val bytes = mediaFile.readBytes()
        val sha256 = crypto.sha256Hex(bytes)
        val totalChunks = ((bytes.size + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).coerceAtLeast(1)
        val messageId = "msg-${UUID.randomUUID()}"
        val transferId = "xfer-${UUID.randomUUID()}"
        val timestamp = System.currentTimeMillis()
        val destNodeId = if (conv.type == ConversationType.DIRECT) conv.peerNodeId else null
        val isEncrypted = conv.type != ConversationType.BROADCAST

        val captionEncoded = when (conv.type) {
            ConversationType.BROADCAST -> crypto.encodeB64(caption.toByteArray(Charsets.UTF_8))
            ConversationType.DIRECT -> {
                val peerId = conv.peerNodeId ?: return null
                val peerDh = peerDao.getPeer(peerId)?.dhPubKeyB64 ?: routingTable[peerId]?.dhPubKeyB64 ?: return null
                crypto.encryptTextForPeer(id.dhPrivB64, peerDh, id.nodeId, peerId, caption, "$messageId|${id.nodeId}|$peerId")
            }
            ConversationType.GROUP -> {
                val groupKey = conv.groupKeyB64 ?: return null
                crypto.encryptWithGroupKey(groupKey, caption.toByteArray(Charsets.UTF_8), "$messageId|${id.nodeId}|$conversationId")
            }
        }

        val payload = ChatMessagePayload(
            messageId = messageId,
            conversationId = conversationId,
            conversationType = conv.type.name,
            groupTitle = if (conv.type == ConversationType.GROUP) conv.title else null,
            senderName = id.displayName,
            messageType = type.name,
            encryptedBodyB64 = captionEncoded,
            isEncrypted = isEncrypted,
            transferId = transferId,
            mediaSha256 = sha256,
            mediaTotalBytes = bytes.size.toLong(),
            totalChunks = totalChunks,
            mediaDurationMs = durationMs,
            waveformSamples = waveformSamples,
            microThumbBase64 = microThumbBase64
        )
        val payloadJson = json.encodeToString(payload)
        val headerPacket = MeshPacket(
            packetId = messageId,
            type = PacketType.CHAT_MESSAGE,
            senderNodeId = id.nodeId,
            destNodeId = destNodeId,
            ttl = id.maxHops,
            hopCount = 0,
            timestamp = timestamp,
            payloadJson = payloadJson,
            signatureB64 = signEnvelope(id, messageId, id.nodeId, payloadJson, timestamp)
        )

        val preview = if (type == MessageType.VOICE) {
            "Voice note (${(durationMs / 1000).coerceAtLeast(1)}s)"
        } else {
            "Photo${if (caption.isNotBlank()) ": $caption" else ""}"
        }

        val localOrderTs = nextLocalOrderTs(conversationId)
        messageDao.insertMessage(
            MessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                senderNodeId = id.nodeId,
                senderName = id.displayName,
                timestamp = timestamp,
                localOrderTs = localOrderTs,
                type = type,
                bodyText = caption,
                transferId = transferId,
                mediaLocalPath = mediaFile.absolutePath,
                mediaSha256 = sha256,
                mediaTotalBytes = bytes.size.toLong(),
                mediaDurationMs = durationMs,
                waveformSamplesJson = json.encodeToString(waveformSamples),
                microThumbBase64 = microThumbBase64,
                deliveryStatus = DeliveryStatus.QUEUED,
                hopCount = 0,
                isIncoming = false
            )
        )
        conversationDao.updateLastMessage(conversationId, preview, localOrderTs, unreadDelta = 0)

        val transferEntity = MediaTransferEntity(
            transferId = transferId,
            messageId = messageId,
            conversationId = conversationId,
            targetNodeId = destNodeId,
            direction = TransferDirection.OUTBOUND,
            mimeType = mimeType,
            localStagingPath = mediaFile.absolutePath,
            sha256 = sha256,
            totalBytes = bytes.size.toLong(),
            totalChunks = totalChunks,
            chunkSizeBytes = CHUNK_SIZE_BYTES,
            ackedChunksBitset = "",
            bytesTransferred = 0L,
            status = TransferStatus.ACTIVE
        )
        mediaTransferDao.upsertTransfer(transferEntity)
        updateTransferUiState(transferEntity, 0L)

        markSeenIfNew(headerPacket.packetId)
        val sentHeader = sendOrBufferPacket(headerPacket)
        if (sentHeader) {
            messageDao.updateDeliveryStatus(messageId, DeliveryStatus.RELAYED, 1)
        }

        scope.launch {
            streamChunksForTransfer(transferEntity, conv)
        }
        return messageId
    }

    private suspend fun nextLocalOrderTs(conversationId: String): Long = orderMutex.withLock {
        val now = System.currentTimeMillis()
        val maxPrev = messageDao.getMaxLocalOrderTs(conversationId) ?: 0L
        maxOf(now, maxPrev + 1L)
    }

    private suspend fun streamChunksForTransfer(transfer: MediaTransferEntity, conv: ConversationEntity) {
        if (!activeStreamingTransfers.add(transfer.transferId)) return
        try {
            val file = File(transfer.localStagingPath)
            if (!file.exists()) {
                mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED, "Local file missing")
                updateTransferUiState(transfer.copy(status = TransferStatus.FAILED, errorReason = "Local file missing"), 0L)
                return
            }
            val bytes = file.readBytes()
            val id = identity
            val isEncrypted = conv.type != ConversationType.BROADCAST

            val encKey: ByteArray? = when (conv.type) {
                ConversationType.BROADCAST -> null
                ConversationType.DIRECT -> {
                    val peerId = conv.peerNodeId ?: return
                    val peerDh = peerDao.getPeer(peerId)?.dhPubKeyB64 ?: routingTable[peerId]?.dhPubKeyB64 ?: return
                    crypto.derivePairwiseKey(id.dhPrivB64, peerDh, id.nodeId, peerId)
                }
                ConversationType.GROUP -> conv.groupKeyB64?.let { crypto.decodeB64(it) }
            }

            for (attemptPass in 0..2) {
                val latest = mediaTransferDao.getTransfer(transfer.transferId) ?: transfer
                val chunkSz = latest.chunkSizeBytes.coerceAtLeast(1024)
                val acked = decodeBitset(latest.ackedChunksBitset).toMutableSet()
                if (acked.size >= latest.totalChunks) break

                val reachable = if (latest.targetNodeId == null) {
                    transport.directLinks.value.isNotEmpty()
                } else {
                    resolveLiveEndpoint(latest.targetNodeId) != null
                }
                if (!reachable) {
                    val queued = latest.copy(status = TransferStatus.PENDING)
                    mediaTransferDao.upsertTransfer(queued)
                    updateTransferUiState(queued, 0L)
                    transport.nudgeDiscovery()
                    return
                }

                var sentAnyInPass = false
                for (chunkIndex in 0 until latest.totalChunks) {
                    if (cancelledTransfers.contains(latest.transferId)) {
                        mediaTransferDao.updateStatus(latest.transferId, TransferStatus.FAILED, "Cancelled")
                        updateTransferUiState(latest.copy(status = TransferStatus.FAILED, errorReason = "Cancelled"), 0L)
                        return
                    }
                    while (pausedTransfers.contains(latest.transferId)) {
                        delay(250)
                        if (cancelledTransfers.contains(latest.transferId)) return
                    }
                    if (acked.contains(chunkIndex)) continue

                    val start = chunkIndex * chunkSz
                    if (start >= bytes.size) continue
                    val end = minOf(bytes.size, start + chunkSz)
                    val slice = bytes.copyOfRange(start, end)
                    val chunkSha = crypto.sha256Hex(slice)
                    val aad = "${latest.transferId}|$chunkIndex|${id.nodeId}"

                    val encodedSlice = if (isEncrypted && encKey != null) {
                        crypto.encodeB64(crypto.encryptAesGcm(encKey, slice, aad.toByteArray(Charsets.UTF_8)))
                    } else {
                        crypto.encodeB64(slice)
                    }

                    val chunkPayload = MediaChunkPayload(
                        transferId = latest.transferId,
                        messageId = latest.messageId,
                        conversationId = latest.conversationId,
                        chunkIndex = chunkIndex,
                        totalChunks = latest.totalChunks,
                        totalBytes = latest.totalBytes,
                        fileSha256 = latest.sha256,
                        chunkSha256 = chunkSha,
                        encryptedChunkB64 = encodedSlice,
                        isEncrypted = isEncrypted,
                        mimeType = latest.mimeType
                    )
                    val packet = MeshPacket(
                        packetId = "chunk-${latest.transferId}-$chunkIndex-${UUID.randomUUID().toString().take(8)}",
                        type = PacketType.MEDIA_CHUNK,
                        senderNodeId = id.nodeId,
                        destNodeId = latest.targetNodeId,
                        ttl = id.maxHops,
                        hopCount = 0,
                        payloadJson = json.encodeToString(chunkPayload)
                    )
                    markSeenIfNew(packet.packetId)
                    val startedAt = System.currentTimeMillis()
                    val sent = sendOrBufferPacket(packet)
                    if (sent) sentAnyInPass = true
                    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(15L)
                    val speed = (slice.size * 1000L) / elapsedMs

                    if (sent && latest.targetNodeId == null) {
                        acked.add(chunkIndex)
                        val bytesDone = minOf(latest.totalBytes, acked.size.toLong() * chunkSz)
                        val done = acked.size >= latest.totalChunks
                        val nextStatus = if (done) TransferStatus.COMPLETED else TransferStatus.ACTIVE
                        val updated = latest.copy(
                            ackedChunksBitset = encodeBitset(acked),
                            bytesTransferred = bytesDone,
                            status = nextStatus
                        )
                        mediaTransferDao.upsertTransfer(updated)
                        updateTransferUiState(updated, speed)
                    } else if (sent) {
                        // Never display 100% until peer's CHUNK_ACK confirms actual receipt
                        val maxUnackedBytes = ((latest.totalBytes * 85L) / 100L).coerceAtLeast(1L).coerceAtMost(latest.totalBytes)
                        val estimatedBytes = minOf(maxUnackedBytes, (chunkIndex + 1).toLong() * chunkSz)
                        val optimistic = latest.copy(bytesTransferred = maxOf(latest.bytesTransferred, estimatedBytes))
                        updateTransferUiState(optimistic, speed)
                    }
                    if ((chunkIndex + 1) % WINDOW_SIZE == 0) {
                        delay(25)
                    }
                }

                // For direct unicast transfers, wait briefly for ACKs and re-send missing chunks if needed
                if (latest.targetNodeId == null || !sentAnyInPass) break
                delay(600)
            }
        } finally {
            activeStreamingTransfers.remove(transfer.transferId)
        }
    }

    private suspend fun resumePendingOutboundTransfers() {
        val pending = mediaTransferDao.getOutboundResumableTransfers()
        for (t in pending) {
            if (pausedTransfers.contains(t.transferId) || cancelledTransfers.contains(t.transferId)) continue
            val target = t.targetNodeId
            val reachable = if (target == null) transport.directLinks.value.isNotEmpty() else resolveLiveEndpoint(target) != null
            if (!reachable) continue
            val conv = conversationDao.getConversation(t.conversationId) ?: continue
            scope.launch {
                streamChunksForTransfer(t, conv)
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        pausedTransfers.add(transferId)
        scope.launch {
            val t = mediaTransferDao.getTransfer(transferId) ?: return@launch
            val paused = t.copy(status = TransferStatus.PAUSED)
            mediaTransferDao.upsertTransfer(paused)
            updateTransferUiState(paused, 0L)
        }
    }

    fun resumeOutboundTransfer(transferId: String) {
        pausedTransfers.remove(transferId)
        cancelledTransfers.remove(transferId)
        scope.launch {
            val t = mediaTransferDao.getTransfer(transferId) ?: return@launch
            val conv = conversationDao.getConversation(t.conversationId) ?: return@launch
            val active = t.copy(status = TransferStatus.ACTIVE, errorReason = null)
            mediaTransferDao.upsertTransfer(active)
            updateTransferUiState(active, 0L)
            streamChunksForTransfer(active, conv)
        }
    }

    fun cancelTransfer(transferId: String) {
        cancelledTransfers.add(transferId)
        pausedTransfers.remove(transferId)
        scope.launch {
            val t = mediaTransferDao.getTransfer(transferId) ?: return@launch
            val failed = t.copy(status = TransferStatus.FAILED, errorReason = "Cancelled by user")
            mediaTransferDao.upsertTransfer(failed)
            updateTransferUiState(failed, 0L)
        }
    }

    suspend fun createEncryptedGroup(title: String, memberNodeIds: List<String>): String? {
        val id = identity
        if (!id.isOnboarded) return null
        val groupId = "group:${UUID.randomUUID()}"
        val groupKeyB64 = crypto.generateRandomSymmetricKeyB64()
        val allMembers = (memberNodeIds + id.nodeId).distinct()

        conversationDao.upsertConversation(
            ConversationEntity(
                conversationId = groupId,
                type = ConversationType.GROUP,
                title = title.trim().ifBlank { "Mesh Group" },
                groupKeyB64 = groupKeyB64,
                memberNodeIdsJson = json.encodeToString(allMembers),
                lastMessagePreview = "Encrypted group created",
                lastMessageTimestamp = System.currentTimeMillis()
            )
        )

        for (memberId in memberNodeIds) {
            if (memberId == id.nodeId) continue
            val peerDh = peerDao.getPeer(memberId)?.dhPubKeyB64 ?: routingTable[memberId]?.dhPubKeyB64 ?: continue
            val aad = "$groupId|${id.nodeId}|$memberId"
            val encKey = crypto.encryptTextForPeer(id.dhPrivB64, peerDh, id.nodeId, memberId, groupKeyB64, aad)
            val invite = GroupInvitePayload(
                groupId = groupId,
                groupTitle = title.trim().ifBlank { "Mesh Group" },
                memberNodeIds = allMembers,
                encryptedGroupKeyB64 = encKey
            )
            val packet = MeshPacket(
                packetId = "ginv-$groupId-$memberId",
                type = PacketType.GROUP_INVITE,
                senderNodeId = id.nodeId,
                destNodeId = memberId,
                ttl = id.maxHops,
                hopCount = 0,
                payloadJson = json.encodeToString(invite)
            )
            markSeenIfNew(packet.packetId)
            sendOrBufferPacket(packet)
        }
        return groupId
    }

    suspend fun openOrCreateDirectConversation(peerNodeId: String): String {
        val convId = "peer:$peerNodeId"
        val existing = conversationDao.getConversation(convId)
        if (existing == null) {
            val peer = peerDao.getPeer(peerNodeId)
            conversationDao.upsertConversation(
                ConversationEntity(
                    conversationId = convId,
                    type = ConversationType.DIRECT,
                    title = peer?.displayName ?: "Peer ${peerNodeId.take(6)}",
                    peerNodeId = peerNodeId,
                    lastMessagePreview = "End-to-end encrypted chat",
                    lastMessageTimestamp = System.currentTimeMillis()
                )
            )
        }
        return convId
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        val id = identity
        if (!id.isOnboarded) return

        val msg = messageDao.getMessage(messageId) ?: return
        val map = runCatching { json.decodeFromString<MutableMap<String, List<String>>>(msg.reactionsJson) }
            .getOrDefault(mutableMapOf())
        val list = map[emoji]?.toMutableList() ?: mutableListOf()
        val isRemove = list.contains(id.nodeId)
        if (isRemove) {
            list.remove(id.nodeId)
            if (list.isEmpty()) map.remove(emoji) else map[emoji] = list
        } else {
            list.add(id.nodeId)
            map[emoji] = list
        }
        messageDao.updateReactions(messageId, json.encodeToString(map))

        val dest = if (conv.type == ConversationType.DIRECT) conv.peerNodeId else null
        val ev = ReactionEventPayload(
            messageId = messageId,
            conversationId = conversationId,
            emoji = emoji,
            isRemove = isRemove
        )
        val packet = MeshPacket(
            packetId = "react-$messageId-${id.nodeId}-$emoji-${System.currentTimeMillis()}",
            type = PacketType.REACTION_EVENT,
            senderNodeId = id.nodeId,
            destNodeId = dest,
            ttl = id.maxHops,
            hopCount = 0,
            payloadJson = json.encodeToString(ev)
        )
        markSeenIfNew(packet.packetId)
        sendOrBufferPacket(packet)
    }

    suspend fun markIncomingMessagesRead(conversationId: String, incomingMessages: List<MessageEntity>) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (conv.type != ConversationType.DIRECT) return
        for (msg in incomingMessages) {
            if (msg.isIncoming && msg.deliveryStatus != DeliveryStatus.READ && sentReadReceipts.add(msg.messageId)) {
                messageDao.updateDeliveryStatus(msg.messageId, DeliveryStatus.READ, msg.hopCount)
                sendReadReceipt(conversationId, msg.messageId, msg.senderNodeId)
            }
        }
    }

    suspend fun sendReadReceipt(conversationId: String, messageId: String, senderNodeId: String) {
        val conv = conversationDao.getConversation(conversationId) ?: return
        if (conv.type != ConversationType.DIRECT) return
        val id = identity
        if (!id.isOnboarded) return
        val receipt = ReadReceiptPayload(messageId, conversationId, System.currentTimeMillis())
        val packet = MeshPacket(
            packetId = "read-$messageId-${id.nodeId}",
            type = PacketType.READ_RECEIPT,
            senderNodeId = id.nodeId,
            destNodeId = senderNodeId,
            ttl = id.maxHops,
            hopCount = 0,
            payloadJson = json.encodeToString(receipt)
        )
        markSeenIfNew(packet.packetId)
        sendOrBufferPacket(packet)
    }

    // ============================================================================================
    // Routing, Flooding & DTN Store-and-Forward Internals
    // ============================================================================================

    private fun resolveLiveEndpoint(destNodeId: String): Pair<String, String>? {
        val activeLinks = transport.directLinks.value
        val route = routingTable[destNodeId]
        if (route != null && activeLinks.containsKey(route.nextHopEndpointId)) {
            return route.nextHopNodeId to route.nextHopEndpointId
        }
        val directFallback = activeLinks.values.firstOrNull { it.remoteNodeId == destNodeId }
        if (directFallback != null) {
            return destNodeId to directFallback.endpointId
        }
        return null
    }

    private suspend fun sendOrBufferPacket(packet: MeshPacket): Boolean {
        val bytes = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        val dest = packet.destNodeId
        if (dest == null) {
            val sentCount = transport.broadcastBytes(bytes)
            if (sentCount == 0 && packet.type != PacketType.MEDIA_CHUNK) {
                enqueueDtn(packet)
                transport.nudgeDiscovery()
            }
            return sentCount > 0
        }

        val resolved = resolveLiveEndpoint(dest)
        if (resolved != null) {
            val (nextHopNode, liveEndpointId) = resolved
            val packetWithHint = packet.copy(nextHopNodeId = nextHopNode)
            val sent = transport.sendToEndpoint(
                liveEndpointId,
                json.encodeToString(packetWithHint).toByteArray(Charsets.UTF_8)
            )
            if (sent) return true
        }

        // Media chunks are persisted & resumed via MediaTransferEntity + INV_SYNC instead of bloating DTN SQLite blobs
        if (packet.type != PacketType.MEDIA_CHUNK) {
            enqueueDtn(packet)
            transport.nudgeDiscovery()
        }
        return false
    }

    private suspend fun forwardPacket(packet: MeshPacket, excludeEndpointId: String) {
        if (packet.ttl <= 1) return
        val forwarded = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1
        )
        relayedCounter++
        refreshMeshStats(transport.directLinks.value)

        val dest = forwarded.destNodeId
        if (dest != null) {
            val resolved = resolveLiveEndpoint(dest)
            if (resolved != null && resolved.second != excludeEndpointId) {
                val (nextHopNode, liveEndpointId) = resolved
                val hinted = forwarded.copy(nextHopNodeId = nextHopNode)
                val ok = transport.sendToEndpoint(
                    liveEndpointId,
                    json.encodeToString(hinted).toByteArray(Charsets.UTF_8)
                )
                if (ok) return
            }
            if (forwarded.type != PacketType.MEDIA_CHUNK) {
                enqueueDtn(forwarded)
            }
        } else {
            // Controlled broadcast flooding with small jitter to avoid radio collisions
            delay(Random.nextLong(25L, 95L))
            transport.broadcastBytes(
                json.encodeToString(forwarded).toByteArray(Charsets.UTF_8),
                excludeEndpointId = excludeEndpointId
            )
        }
    }

    private suspend fun enqueueDtn(packet: MeshPacket) {
        val now = System.currentTimeMillis()
        dtnRelayDao.enqueue(
            DtnRelayPacketEntity(
                packetId = packet.packetId,
                targetNodeId = packet.destNodeId,
                senderNodeId = packet.senderNodeId,
                packetJson = json.encodeToString(packet),
                ttl = packet.ttl,
                hopCount = packet.hopCount,
                createdAt = now,
                expiresAt = now + DTN_TTL_MS
            )
        )
        dtnRelayDao.trimToMax(MAX_DTN_QUEUE_SIZE)
    }

    suspend fun flushDtnQueue() = dtnFlushMutex.withLock {
        val now = System.currentTimeMillis()
        dtnRelayDao.purgeExpired(now)
        seenPacketDao.purgeOlderThan(now - SEEN_DB_RETENTION_MS)
        val links = transport.directLinks.value
        val pending = dtnRelayDao.getActivePackets(now)
        if (links.isEmpty()) {
            if (pending.isNotEmpty() || mediaTransferDao.getOutboundResumableTransfers().isNotEmpty()) {
                transport.nudgeDiscovery()
            }
            return@withLock
        }

        var failedAllTargets = pending.isNotEmpty()
        for (entry in pending) {
            val target = entry.targetNodeId
            if (target == null) {
                val sent = transport.broadcastBytes(entry.packetJson.toByteArray(Charsets.UTF_8))
                if (sent > 0) {
                    failedAllTargets = false
                    dtnRelayDao.remove(entry.packetId)
                    if (entry.senderNodeId == identity.nodeId) {
                        messageDao.markRelayedIfQueued(entry.packetId, 1)
                    }
                }
            } else {
                val resolved = resolveLiveEndpoint(target)
                if (resolved != null) {
                    val sent = transport.sendToEndpoint(resolved.second, entry.packetJson.toByteArray(Charsets.UTF_8))
                    if (sent) {
                        failedAllTargets = false
                        dtnRelayDao.remove(entry.packetId)
                        if (entry.senderNodeId == identity.nodeId) {
                            messageDao.markRelayedIfQueued(entry.packetId, 1)
                        }
                    }
                }
            }
        }
        if (failedAllTargets) {
            transport.nudgeDiscovery()
        }
        resumePendingOutboundTransfers()
    }

    private suspend fun markSeenIfNew(packetId: String): Boolean {
        val inMemory = seenMutex.withLock {
            if (seenLru.containsKey(packetId)) {
                true
            } else {
                seenLru[packetId] = System.currentTimeMillis()
                false
            }
        }
        if (inMemory) return true
        if (seenPacketDao.hasSeen(packetId)) return true
        seenPacketDao.markSeen(SeenPacketEntity(packetId = packetId))
        return false
    }

    private suspend fun pruneRoutesForDisconnectedEndpoints(
        disconnected: Set<String>,
        remainingLinks: Map<String, DirectLinkInfo>
    ) {
        val toRemove = routingTable.entries.filter { it.value.nextHopEndpointId in disconnected }.map { it.key }
        for (nodeId in toRemove) {
            routingTable.remove(nodeId)
            val peer = peerDao.getPeer(nodeId)
            if (peer != null) {
                peerDao.upsertPeer(
                    peer.copy(
                        isDirectlyConnected = false,
                        bestHopCount = Int.MAX_VALUE,
                        nextHopNodeId = null,
                        linkQuality = 0
                    )
                )
            }
        }
        _routesFlow.value = routingTable.toMap()
        refreshMeshStats(remainingLinks)
    }

    private fun pruneExpiredRoutes() {
        val cutoff = System.currentTimeMillis() - ROUTE_EXPIRY_MS
        val activeEndpoints = transport.directLinks.value.keys
        val stale = routingTable.entries.filter {
            it.value.updatedAt < cutoff && !activeEndpoints.contains(it.value.nextHopEndpointId)
        }.map { it.key }
        stale.forEach { routingTable.remove(it) }
        if (stale.isNotEmpty()) {
            _routesFlow.value = routingTable.toMap()
        }
    }

    private fun refreshMeshStats(links: Map<String, DirectLinkInfo>) {
        _meshStats.value = MeshStatsUi(
            relayedPacketCount = relayedCounter,
            directPeerCount = links.size,
            reachablePeerCount = routingTable.size
        )
    }

    private fun updateTransferUiState(entity: MediaTransferEntity, speedBytesPerSec: Long) {
        val ackedCount = decodeBitset(entity.ackedChunksBitset).size
        val map = _transferProgress.value.toMutableMap()
        map[entity.transferId] = TransferProgressUi(
            transferId = entity.transferId,
            messageId = entity.messageId,
            direction = entity.direction,
            bytesTransferred = entity.bytesTransferred,
            totalBytes = entity.totalBytes,
            chunksAcked = ackedCount,
            totalChunks = entity.totalChunks,
            speedBytesPerSec = speedBytesPerSec,
            status = entity.status,
            errorReason = entity.errorReason
        )
        _transferProgress.value = map
    }

    private fun signEnvelope(
        id: LocalIdentityConfig,
        packetId: String,
        senderNodeId: String,
        payloadJson: String,
        timestamp: Long
    ): String {
        return crypto.signBytes(id.signingPrivB64, envelopeBytes(packetId, senderNodeId, payloadJson, timestamp))
    }

    private fun envelopeBytes(
        packetId: String,
        senderNodeId: String,
        payloadJson: String,
        timestamp: Long
    ): ByteArray {
        return "$packetId|$senderNodeId|$timestamp|$payloadJson".toByteArray(Charsets.UTF_8)
    }

    private fun encodeBitset(indices: Set<Int>): String = indices.sorted().joinToString(",")

    private fun decodeBitset(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()
        return raw.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }
}
