package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoManager
import com.example.data.local.DeliveryStatus
import com.example.data.local.LanChatDatabase
import com.example.data.local.LocalIdentityConfig
import com.example.data.local.MessageType
import com.example.data.local.TransferStatus
import com.example.mesh.router.MeshRouter
import com.example.mesh.transport.SimulatedMeshBus
import com.example.mesh.transport.SimulatedMeshTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeshRouterSimulationTest {

    private lateinit var context: Context
    private val crypto = CryptoManager()
    private val dbs = mutableListOf<LanChatDatabase>()
    private val routers = mutableListOf<MeshRouter>()
    private val tempDirs = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        routers.forEach { it.stop() }
        dbs.forEach { it.close() }
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun createNode(bus: SimulatedMeshBus, name: String): Triple<LocalIdentityConfig, MeshRouter, LanChatDatabase> {
        val keys = crypto.generateIdentity()
        val id = LocalIdentityConfig(
            nodeId = keys.nodeId,
            displayName = name,
            signingPrivB64 = keys.signingPrivB64,
            signingPubB64 = keys.signingPubB64,
            dhPrivB64 = keys.dhPrivB64,
            dhPubB64 = keys.dhPubB64,
            isOnboarded = true,
            maxHops = 6
        )
        val db = Room.inMemoryDatabaseBuilder(context, LanChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dbs.add(db)

        val dir = File(context.cacheDir, "test_media_${keys.nodeId}").apply { mkdirs() }
        tempDirs.add(dir)

        val transport = SimulatedMeshTransport(bus)
        val router = MeshRouter(
            transport = transport,
            crypto = crypto,
            peerDao = db.peerDao(),
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            mediaTransferDao = db.mediaTransferDao(),
            dtnRelayDao = db.dtnRelayDao(),
            seenPacketDao = db.seenPacketDao(),
            mediaRootDir = dir,
            enableBeaconLoop = false
        )
        routers.add(router)
        router.start(id)
        return Triple(id, router, db)
    }

    @Test
    fun `multi-hop dsdv routing relays encrypted direct message and 32kb chunked media across intermediate node`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "Alpha")
        val (idB, routerB, dbB) = createNode(bus, "RelayBravo")
        val (idC, routerC, dbC) = createNode(bus, "Charlie")

        // Topology: A <-> B <-> C (A and C cannot reach each other directly)
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        bus.connectBidirectional(idB.nodeId, idC.nodeId)
        delay(200)

        // Two beacon rounds propagate 2-hop distance-vector route from A through B to C and vice versa
        routerA.sendHelloBeaconNow()
        routerC.sendHelloBeaconNow()
        delay(200)
        routerB.sendHelloBeaconNow()
        delay(250)

        val routeAToC = routerA.routesFlow.value[idC.nodeId]
        assertEquals(2, routeAToC?.hopCount)
        assertEquals(idB.nodeId, routeAToC?.nextHopNodeId)

        // Send E2E direct message from Alpha -> Charlie via RelayBravo
        val convId = routerA.openOrCreateDirectConversation(idC.nodeId)
        val msgId = routerA.sendTextMessage(convId, "Encrypted over 2 hops!")
        delay(350)

        // Charlie receives and decrypts it
        val charlieMsgs = dbC.messageDao().observeMessages("peer:${idA.nodeId}").first()
        assertEquals(1, charlieMsgs.size)
        assertEquals("Encrypted over 2 hops!", charlieMsgs.first().bodyText)
        assertEquals(2, charlieMsgs.first().hopCount)

        // RelayBravo never stores Charlie's private message in its local messages table
        val bravoMsgs = dbB.messageDao().observeMessages("peer:${idA.nodeId}").first()
        assertTrue(bravoMsgs.isEmpty())

        // Alpha receives Delivery ACK from Charlie
        val alphaMsg = dbA.messageDao().getMessage(msgId!!)
        assertEquals(DeliveryStatus.DELIVERED, alphaMsg?.deliveryStatus)

        // Send 75 KB media file (> 2 chunks of 32KB) from Alpha -> Charlie across RelayBravo
        val mediaFile = File(context.cacheDir, "sample_75kb.jpg")
        val rawPayload = ByteArray(75 * 1024) { (it % 251).toByte() }
        mediaFile.writeBytes(rawPayload)
        tempDirs.add(mediaFile)

        val mediaMsgId = routerA.sendMediaMessage(
            conversationId = convId,
            type = MessageType.IMAGE,
            mediaFile = mediaFile,
            mimeType = "image/jpeg",
            caption = "Trail map"
        )
        delay(700)

        val charlieUpdatedMsgs = dbC.messageDao().observeMessages("peer:${idA.nodeId}").first()
        val receivedMediaMsg = charlieUpdatedMsgs.find { it.messageId == mediaMsgId }
        assertTrue("Charlie should receive chunked image", receivedMediaMsg != null)
        val assembledFile = File(receivedMediaMsg!!.mediaLocalPath!!)
        assertTrue("Assembled media file must exist on Charlie", assembledFile.exists())
        assertEquals(rawPayload.size.toLong(), assembledFile.length())
        assertEquals(
            TransferStatus.COMPLETED,
            routerC.transferProgress.value[receivedMediaMsg.transferId]?.status
        )
    }

    @Test
    fun `dtn custody buffer delivers broadcast message after link heals`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "SenderA")
        val (idB, routerB, dbB) = createNode(bus, "ReceiverB")

        routerA.ensureBroadcastConversation()
        routerB.ensureBroadcastConversation()
        delay(100)

        // Send while disconnected -> stored in DTN queue
        routerA.sendTextMessage(MeshRouter.BROADCAST_CONVERSATION_ID, "Buffered SOS test")
        delay(150)
        assertEquals(1, dbA.dtnRelayDao().getActivePackets().size)

        // Connect link -> INV_SYNC + flushDtnQueue automatically delivers buffered packet
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(400)

        val msgsOnB = dbB.messageDao().observeMessages(MeshRouter.BROADCAST_CONVERSATION_ID).first()
        assertTrue(msgsOnB.any { it.bodyText == "Buffered SOS test" })
    }

    @Test
    fun `dsdv route withdrawal prunes unreachable multi-hop node when relay loses neighbor`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "Alpha")
        val (idB, routerB, _) = createNode(bus, "RelayBravo")
        val (idC, routerC, _) = createNode(bus, "Charlie")

        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        bus.connectBidirectional(idB.nodeId, idC.nodeId)
        delay(200)

        routerA.sendHelloBeaconNow()
        routerC.sendHelloBeaconNow()
        delay(200)
        routerB.sendHelloBeaconNow()
        delay(250)

        assertEquals(2, routerA.routesFlow.value[idC.nodeId]?.hopCount)

        // Break B <-> C and let B emit an updated beacon withdrawing C
        bus.disconnectBidirectional(idB.nodeId, idC.nodeId)
        delay(200)
        routerB.sendHelloBeaconNow()
        delay(250)

        assertNull("Route to C should be withdrawn from Alpha", routerA.routesFlow.value[idC.nodeId])
        val peerCOnA = dbA.peerDao().getPeer(idC.nodeId)
        assertEquals(Int.MAX_VALUE, peerCOnA?.bestHopCount)
    }

    @Test
    fun `read receipts and reaction toggling synchronize cleanly end to end`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "Alpha")
        val (idB, routerB, dbB) = createNode(bus, "Bravo")

        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(200)

        val convOnA = routerA.openOrCreateDirectConversation(idB.nodeId)
        val msgId = routerA.sendTextMessage(convOnA, "Confirm read + reaction")!!
        delay(250)

        val convOnB = "peer:${idA.nodeId}"
        val bMsgs = dbB.messageDao().observeMessages(convOnB).first()
        assertEquals(1, bMsgs.size)

        routerB.markIncomingMessagesRead(convOnB, bMsgs)
        delay(250)
        assertEquals(DeliveryStatus.READ, dbA.messageDao().getMessage(msgId)?.deliveryStatus)

        // Toggle reaction ON
        routerB.sendReaction(convOnB, msgId, "🔥")
        delay(250)
        assertTrue(dbA.messageDao().getMessage(msgId)?.reactionsJson?.contains("🔥") == true)

        // Toggle reaction OFF
        routerB.sendReaction(convOnB, msgId, "🔥")
        delay(250)
        assertFalse(dbA.messageDao().getMessage(msgId)?.reactionsJson?.contains("🔥") == true)
    }

    @Test
    fun `offline media send excludes raw chunks from dtn table and resumes on connect`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "SenderA")
        val (idB, routerB, dbB) = createNode(bus, "ReceiverB")

        routerA.ensureBroadcastConversation()
        routerB.ensureBroadcastConversation()
        delay(100)

        val mediaFile = File(context.cacheDir, "offline_75kb.jpg")
        val rawPayload = ByteArray(75 * 1024) { (it % 199).toByte() }
        mediaFile.writeBytes(rawPayload)
        tempDirs.add(mediaFile)

        val mediaMsgId = routerA.sendMediaMessage(
            conversationId = MeshRouter.BROADCAST_CONVERSATION_ID,
            type = MessageType.IMAGE,
            mediaFile = mediaFile,
            mimeType = "image/jpeg",
            caption = "Offline queued photo"
        )
        delay(350)

        // Only the lightweight CHAT_MESSAGE header packet should be in DTN queue (never MEDIA_CHUNK)
        val dtnQueued = dbA.dtnRelayDao().getActivePackets()
        assertEquals(1, dtnQueued.size)
        assertEquals(mediaMsgId, dtnQueued.first().packetId)

        // Connect A <-> B -> INV_SYNC / DTN flush / resumePendingOutboundTransfers delivers header + all chunks
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(900)

        val msgsOnB = dbB.messageDao().observeMessages(MeshRouter.BROADCAST_CONVERSATION_ID).first()
        val received = msgsOnB.find { it.messageId == mediaMsgId }
        assertTrue("Receiver should reconstruct resumed media message", received != null)
        val assembled = received?.mediaLocalPath?.let { File(it) }
        assertTrue("Assembled media file should exist after resume", assembled != null && assembled.exists())
        assertEquals(rawPayload.size.toLong(), assembled!!.length())
    }

    @Test
    fun `encrypted media chunk wire packets stay safely under 32kb nearby bytes limit`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, _, _) = createNode(bus, "SenderA")
        val (idB, _, _) = createNode(bus, "ReceiverB")
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)

        val key = crypto.derivePairwiseKey(idA.dhPrivB64, idB.dhPubB64, idA.nodeId, idB.nodeId)
        val rawChunk = ByteArray(MeshRouter.CHUNK_SIZE_BYTES) { 42 }
        val transferId = java.util.UUID.randomUUID().toString()
        val messageId = java.util.UUID.randomUUID().toString()
        val aad = "$transferId|0|${idA.nodeId}".toByteArray(Charsets.UTF_8)
        val cipherB64 = crypto.encodeB64(crypto.encryptAesGcm(key, rawChunk, aad))

        val chunkPayload = com.example.mesh.protocol.MediaChunkPayload(
            transferId = transferId,
            messageId = messageId,
            conversationId = "peer:${idB.nodeId}",
            chunkIndex = 0,
            totalChunks = 4,
            totalBytes = rawChunk.size * 4L,
            fileSha256 = crypto.sha256Hex(rawChunk),
            chunkSha256 = crypto.sha256Hex(rawChunk),
            encryptedChunkB64 = cipherB64,
            isEncrypted = true,
            mimeType = "image/jpeg"
        )
        val payloadJson = kotlinx.serialization.json.Json.encodeToString(
            com.example.mesh.protocol.MediaChunkPayload.serializer(),
            chunkPayload
        )
        val packetId = java.util.UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val sig = crypto.signBytes(
            idA.signingPrivB64,
            "$packetId|${idA.nodeId}|$ts|$payloadJson".toByteArray(Charsets.UTF_8)
        )
        val packet = com.example.mesh.protocol.MeshPacket(
            packetId = packetId,
            type = com.example.mesh.protocol.PacketType.MEDIA_CHUNK,
            senderNodeId = idA.nodeId,
            destNodeId = idB.nodeId,
            ttl = 6,
            hopCount = 0,
            timestamp = ts,
            payloadJson = payloadJson,
            signatureB64 = sig
        )
        val encodedSize = kotlinx.serialization.json.Json.encodeToString(
            com.example.mesh.protocol.MeshPacket.serializer(),
            packet
        ).toByteArray(Charsets.UTF_8).size
        assertTrue(
            "Wire MEDIA_CHUNK packet must be < 32768 bytes for Nearby BYTES frame, got $encodedSize",
            encodedSize < 32_768
        )
    }

    @Test
    fun `messages sort by local arrival order even when sender clock is hours behind`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, _) = createNode(bus, "SenderA")
        val (idB, routerB, dbB) = createNode(bus, "ReceiverB")

        routerA.ensureBroadcastConversation()
        routerB.ensureBroadcastConversation()
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)

        // Exchange beacon so ReceiverB knows SenderA's Ed25519 key
        routerA.sendHelloBeaconNow()
        delay(150)

        // First normal message at current time
        routerA.sendTextMessage(MeshRouter.BROADCAST_CONVERSATION_ID, "First normal message")
        delay(200)

        // Inject a newly arrived broadcast packet whose sender clock is 3 hours in the past
        val skewedMsgId = "skewed-msg-1"
        val payload = com.example.mesh.protocol.ChatMessagePayload(
            messageId = skewedMsgId,
            conversationId = MeshRouter.BROADCAST_CONVERSATION_ID,
            conversationType = "BROADCAST",
            senderName = idA.displayName,
            messageType = "TEXT",
            encryptedBodyB64 = crypto.encodeB64("Arrived second with skewed clock".toByteArray(Charsets.UTF_8)),
            isEncrypted = false
        )
        val payloadJson = kotlinx.serialization.json.Json.encodeToString(
            com.example.mesh.protocol.ChatMessagePayload.serializer(),
            payload
        )
        val skewedTs = System.currentTimeMillis() - 10_800_000L // -3 hours!
        val sig = crypto.signBytes(
            idA.signingPrivB64,
            "$skewedMsgId|${idA.nodeId}|$skewedTs|$payloadJson".toByteArray(Charsets.UTF_8)
        )
        val skewedPacket = com.example.mesh.protocol.MeshPacket(
            packetId = skewedMsgId,
            type = com.example.mesh.protocol.PacketType.CHAT_MESSAGE,
            senderNodeId = idA.nodeId,
            destNodeId = null,
            ttl = 5,
            hopCount = 0,
            timestamp = skewedTs,
            payloadJson = payloadJson,
            signatureB64 = sig
        )
        val wire = kotlinx.serialization.json.Json.encodeToString(
            com.example.mesh.protocol.MeshPacket.serializer(),
            skewedPacket
        ).toByteArray(Charsets.UTF_8)
        assertTrue(bus.deliver(idA.nodeId, idB.nodeId, wire))
        delay(250)

        val ordered = dbB.messageDao().observeMessages(MeshRouter.BROADCAST_CONVERSATION_ID).first()
        assertEquals(2, ordered.size)
        assertEquals("First normal message", ordered[0].bodyText)
        assertEquals("Arrived second with skewed clock", ordered[1].bodyText)
        assertTrue(ordered[1].localOrderTs > ordered[0].localOrderTs)
    }

    @Test
    fun `updating display name and optional radar gps propagates across peers and direct chats`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, _) = createNode(bus, "OldName")
        val (idB, routerB, dbB) = createNode(bus, "ReceiverB")

        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)

        // Initial beacon + open direct conversation on B
        routerA.sendHelloBeaconNow()
        delay(200)
        val convOnB = routerB.openOrCreateDirectConversation(idA.nodeId)
        assertEquals("OldName", dbB.conversationDao().getConversation(convOnB)?.title)

        // Alpha renames + enables optional radar GPS coordinates
        routerA.localLocationProvider = {
            com.example.mesh.location.OfflineGeoPoint(latitude = 30.0444, longitude = 31.2357)
        }
        routerA.updateIdentityAndRebroadcast(idA.copy(displayName = "NewAlpha", shareLocationOnRadar = true))
        routerA.sendHelloBeaconNow()
        delay(250)

        val updatedPeer = dbB.peerDao().getPeer(idA.nodeId)
        assertEquals("NewAlpha", updatedPeer?.displayName)
        assertEquals(30.0444, updatedPeer?.latitude ?: 0.0, 0.0001)
        assertEquals(31.2357, updatedPeer?.longitude ?: 0.0, 0.0001)
        assertEquals("NewAlpha", dbB.conversationDao().getConversation(convOnB)?.title)
    }

    @Test
    fun `multi-hop end-to-end encrypted voice call relays signaling and audio without dtn or db bloat`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "CallerAlpha")
        val (idB, routerB, dbB) = createNode(bus, "RelayBravo")
        val (idC, routerC, dbC) = createNode(bus, "CalleeCharlie")

        // A <-> B <-> C
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        bus.connectBidirectional(idB.nodeId, idC.nodeId)
        delay(200)
        routerA.sendHelloBeaconNow()
        routerC.sendHelloBeaconNow()
        delay(200)
        routerB.sendHelloBeaconNow()
        delay(250)

        val engineA = com.example.media.VoiceCallAudioEngine(context)
        val engineC = com.example.media.VoiceCallAudioEngine(context)
        val callMgrA = com.example.mesh.call.MeshCallManager(context, routerA, engineA, enableAudioHardware = false).apply {
            updateLocalDisplayName(idA.displayName)
        }
        val callMgrC = com.example.mesh.call.MeshCallManager(context, routerC, engineC, enableAudioHardware = false).apply {
            updateLocalDisplayName(idC.displayName)
        }

        // Start outgoing call from Alpha -> Charlie across RelayBravo
        callMgrA.startOutgoingCall(idC.nodeId)
        delay(350)

        assertEquals(com.example.mesh.call.CallPhase.OUTGOING_RINGING, callMgrA.callState.value.phase)
        assertEquals(com.example.mesh.call.CallPhase.INCOMING_RINGING, callMgrC.callState.value.phase)
        val activeCallId = callMgrA.callState.value.callId!!

        // Charlie accepts -> both transition to ACTIVE
        callMgrC.acceptIncomingCall()
        delay(350)

        assertEquals(com.example.mesh.call.CallPhase.ACTIVE, callMgrA.callState.value.phase)
        assertEquals(com.example.mesh.call.CallPhase.ACTIVE, callMgrC.callState.value.phase)
        assertEquals(2, callMgrC.callState.value.hopCount)

        // Relay an encrypted ADPCM audio frame from Alpha -> Charlie through RelayBravo
        val frame = com.example.media.ImaAdpcmCodec.encode(ShortArray(160) { (it * 50).toShort() })
        val receivedAudioFrames = mutableListOf<com.example.mesh.router.IncomingCallEvent.Audio>()
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            routerC.incomingCallEvents.collect { ev ->
                if (ev is com.example.mesh.router.IncomingCallEvent.Audio) {
                    receivedAudioFrames.add(ev)
                }
            }
        }

        val audioSent = routerA.sendCallAudioFrame(
            destNodeId = idC.nodeId,
            callId = activeCallId,
            seqNo = 1,
            adpcmBytes = frame.data,
            initialPredictor = frame.initialPredictor,
            initialStepIndex = frame.initialStepIndex,
            sampleCount = frame.sampleCount
        )
        assertTrue(audioSent)
        delay(250)
        collectJob.cancel()

        assertEquals(1, receivedAudioFrames.size)
        assertEquals(activeCallId, receivedAudioFrames.first().callId)
        assertEquals(frame.sampleCount, receivedAudioFrames.first().sampleCount)
        assertEquals(2, receivedAudioFrames.first().hopCount)

        // Verify CALL_AUDIO never touches DTN queue on relay B or endpoints
        assertTrue(dbB.dtnRelayDao().getActivePackets().isEmpty())
        assertTrue(dbA.dtnRelayDao().getActivePackets().isEmpty())

        // Hang up and verify ENDED state + direct-chat call log persistence
        callMgrA.rejectOrEndCall()
        delay(300)

        assertEquals(com.example.mesh.call.CallPhase.ENDED, callMgrA.callState.value.phase)
        assertEquals(com.example.mesh.call.CallPhase.ENDED, callMgrC.callState.value.phase)
        val alphaCallMsgs = dbA.messageDao().observeMessages("peer:${idC.nodeId}").first()
        val charlieCallMsgs = dbC.messageDao().observeMessages("peer:${idA.nodeId}").first()
        assertTrue("Alpha should log completed call in chat", alphaCallMsgs.isNotEmpty())
        assertTrue("Charlie should log completed call in chat", charlieCallMsgs.isNotEmpty())
    }

    @Test
    fun `declining incoming call notifies caller and transitions to declined`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, _) = createNode(bus, "CallerAlpha")
        val (idB, routerB, _) = createNode(bus, "CalleeBravo")

        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)
        routerA.sendHelloBeaconNow()
        routerB.sendHelloBeaconNow()
        delay(150)

        val callMgrA = com.example.mesh.call.MeshCallManager(
            context,
            routerA,
            com.example.media.VoiceCallAudioEngine(context),
            enableAudioHardware = false
        )
        val callMgrB = com.example.mesh.call.MeshCallManager(
            context,
            routerB,
            com.example.media.VoiceCallAudioEngine(context),
            enableAudioHardware = false
        )

        callMgrA.startOutgoingCall(idB.nodeId)
        delay(250)
        assertEquals(com.example.mesh.call.CallPhase.INCOMING_RINGING, callMgrB.callState.value.phase)

        callMgrB.rejectOrEndCall()
        delay(250)

        assertEquals(com.example.mesh.call.CallPhase.ENDED, callMgrB.callState.value.phase)
        assertEquals(com.example.mesh.call.CallEndReason.DECLINED, callMgrB.callState.value.endReason)
        assertEquals(com.example.mesh.call.CallPhase.ENDED, callMgrA.callState.value.phase)
        assertEquals(com.example.mesh.call.CallEndReason.DECLINED, callMgrA.callState.value.endReason)
    }

    @Test
    fun `direct queued text and media automatically flush when link reconnects and beacon arrives`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, dbA) = createNode(bus, "SenderAlpha")
        val (idB, routerB, dbB) = createNode(bus, "ReceiverBravo")

        // Briefly connect to discover B's public key, then disconnect before sending
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)
        routerB.sendHelloBeaconNow()
        delay(150)
        bus.disconnectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)

        // Send a direct text while disconnected -> enters DTN Queue cleanly with status QUEUED
        val convA = routerA.openOrCreateDirectConversation(idB.nodeId)
        val msgId = routerA.sendTextMessage(convA, "Auto-flush without manual refresh")!!
        delay(150)
        assertEquals(1, dbA.dtnRelayDao().getActivePackets().size)
        assertEquals(DeliveryStatus.QUEUED, dbA.messageDao().getMessage(msgId)?.deliveryStatus)

        // Reconnect and send HELLO_BEACON -> DTN Queue must automatically flush to 0 and deliver message
        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)
        routerB.sendHelloBeaconNow()
        delay(350)

        assertTrue("DTN queue should drain automatically", dbA.dtnRelayDao().getActivePackets().isEmpty())
        assertEquals(DeliveryStatus.DELIVERED, dbA.messageDao().getMessage(msgId)?.deliveryStatus)
        val msgsOnB = dbB.messageDao().observeMessages("peer:${idA.nodeId}").first()
        assertTrue(msgsOnB.any { it.bodyText == "Auto-flush without manual refresh" })
    }

    @Test
    fun `retransmitted media chunk triggers re-ack even if receiver already assembled file`() = runBlocking {
        val bus = SimulatedMeshBus()
        val (idA, routerA, _) = createNode(bus, "SenderAlpha")
        val (idB, routerB, dbB) = createNode(bus, "ReceiverBravo")

        bus.connectBidirectional(idA.nodeId, idB.nodeId)
        delay(150)
        routerA.sendHelloBeaconNow()
        routerB.sendHelloBeaconNow()
        delay(150)

        val convA = routerA.openOrCreateDirectConversation(idB.nodeId)
        val voiceFile = File(context.cacheDir, "voice_10kb.m4a")
        voiceFile.writeBytes(ByteArray(10 * 1024) { (it % 127).toByte() })
        tempDirs.add(voiceFile)

        val msgId = routerA.sendMediaMessage(
            conversationId = convA,
            type = MessageType.VOICE,
            mediaFile = voiceFile,
            mimeType = "audio/mp4",
            durationMs = 2000L
        )!!
        delay(450)

        val transferId = dbB.messageDao().getMessage(msgId)?.transferId!!
        assertEquals(TransferStatus.COMPLETED, routerA.transferProgress.value[transferId]?.status)
        assertEquals(1, routerA.transferProgress.value[transferId]?.chunksAcked)
    }
}
