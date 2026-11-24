package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.crypto.QrPayloadCodec
import com.example.crypto.QrPeerIdentity
import com.example.data.local.ConversationEntity
import com.example.data.local.LocalIdentityConfig
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.data.local.PeerEntity
import com.example.data.local.ThemePreference
import com.example.media.RecordingState
import com.example.media.VoicePlaybackState
import com.example.mesh.call.MeshCallUiState
import com.example.mesh.location.CompassHeadingState
import com.example.mesh.location.LocationFixStatus
import com.example.mesh.location.OfflineGeoPoint
import com.example.mesh.router.MeshStatsUi
import com.example.mesh.router.RouteEntry
import com.example.mesh.router.TransferProgressUi
import com.example.mesh.service.MeshRelayService
import com.example.mesh.transport.MeshLogEntry
import com.example.mesh.transport.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class LanChatUiAlert {
    IMAGE_DECODE_FAILED,
    DISPLAY_NAME_UPDATED,
    LOCATION_PERMISSION_NEEDED
}

class LanChatViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val repo = container.repository
    private val router = container.meshRouter

    val identity: StateFlow<LocalIdentityConfig> = repo.identityFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalIdentityConfig())

    val isIdentityLoaded: StateFlow<Boolean> = repo.identityFlow
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val conversations: StateFlow<List<ConversationEntity>> = repo.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peers: StateFlow<List<PeerEntity>> = repo.peersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transferProgress: StateFlow<Map<String, TransferProgressUi>> = router.transferProgress
    val meshStats: StateFlow<MeshStatsUi> = router.meshStats
    val routes: StateFlow<Map<String, RouteEntry>> = router.routesFlow
    val transportState: StateFlow<TransportState> = container.transport.state
    val diagnosticLogs: StateFlow<List<MeshLogEntry>> = container.transport.diagnosticLogs

    val pendingDtnCount: StateFlow<Int> = repo.pendingDtnCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recordingState: StateFlow<RecordingState> = container.voiceRecorder.state
    val playbackState: StateFlow<VoicePlaybackState> = container.voicePlayer.state

    val compassHeadingFlow: Flow<CompassHeadingState> = container.compassTracker.observeHeading()
    val localLocation: StateFlow<OfflineGeoPoint?> = container.offlineLocationProvider.currentLocation
    val locationFixStatus: StateFlow<LocationFixStatus> = container.offlineLocationProvider.fixStatus
    val callState: StateFlow<MeshCallUiState> = container.callManager.callState

    private val _uiAlerts = MutableSharedFlow<LanChatUiAlert>(extraBufferCapacity = 16)
    val uiAlerts: SharedFlow<LanChatUiAlert> = _uiAlerts.asSharedFlow()

    init {
        viewModelScope.launch {
            router.ensureBroadcastConversation()
        }
        viewModelScope.launch {
            repo.identityFlow.collect { id ->
                if (id.isOnboarded) {
                    router.updateIdentity(id)
                    container.callManager.updateLocalDisplayName(id.displayName)
                }
                if (id.isOnboarded && id.shareLocationOnRadar && container.offlineLocationProvider.hasLocationPermission()) {
                    container.offlineLocationProvider.startIfPermitted()
                } else {
                    container.offlineLocationProvider.stop()
                }
            }
        }
        viewModelScope.launch {
            var lastBeaconAt = 0L
            container.offlineLocationProvider.currentLocation.collect { geo ->
                if (geo != null && identity.value.isOnboarded && identity.value.shareLocationOnRadar) {
                    val now = System.currentTimeMillis()
                    if (now - lastBeaconAt >= 2_500L) {
                        lastBeaconAt = now
                        router.sendHelloBeaconNow()
                    }
                }
            }
        }
    }

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        repo.observeConversation(conversationId)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        repo.observeMessages(conversationId)

    fun observePeer(nodeId: String): Flow<PeerEntity?> =
        repo.observePeer(nodeId)

    fun requiredMeshPermissions(): List<String> = container.transport.requiredNearbyPermissions()

    fun hasMeshPermissions(context: Context): Boolean = container.transport.hasRequiredPermissions(context)

    fun hasLocationPermission(): Boolean = container.offlineLocationProvider.hasLocationPermission()

    fun completeOnboarding(
        context: Context,
        displayName: String,
        enableBackgroundRelay: Boolean,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val saved = repo.initializeIdentity(displayName, enableBackgroundRelay)
            router.updateIdentity(saved)
            val fgsStarted = if (enableBackgroundRelay && MeshRelayService.hasConnectedDevicePrerequisite(context)) {
                MeshRelayService.startRelay(context)
            } else {
                false
            }
            if (!fgsStarted && hasMeshPermissions(context)) {
                router.start(saved)
            }
            onComplete()
        }
    }

    fun ensureMeshStartedIfOnboarded(context: Context) {
        val id = identity.value
        if (!id.isOnboarded) return
        router.updateIdentity(id)
        val fgsStarted = if (id.backgroundRelayEnabled && MeshRelayService.hasConnectedDevicePrerequisite(context)) {
            MeshRelayService.startRelay(context)
        } else {
            false
        }
        if (!fgsStarted && hasMeshPermissions(context)) {
            router.start(id)
        }
    }

    fun toggleBackgroundRelay(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            repo.setBackgroundRelay(enabled)
            if (enabled) {
                if (MeshRelayService.hasConnectedDevicePrerequisite(context)) {
                    MeshRelayService.startRelay(context)
                }
                val id = identity.value
                if (id.isOnboarded) router.start(id)
            } else {
                MeshRelayService.stopRelay(context)
            }
        }
    }

    fun setShareLocationOnRadar(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                if (container.offlineLocationProvider.hasLocationPermission()) {
                    container.offlineLocationProvider.startIfPermitted()
                    repo.setShareLocationOnRadar(true)
                    delay(150L)
                    router.sendHelloBeaconNow()
                } else {
                    repo.setShareLocationOnRadar(false)
                    _uiAlerts.tryEmit(LanChatUiAlert.LOCATION_PERMISSION_NEEDED)
                }
            } else {
                container.offlineLocationProvider.stop()
                repo.setShareLocationOnRadar(false)
                delay(100L)
                router.sendHelloBeaconNow()
            }
        }
    }

    fun startVoiceCall(peerNodeId: String) {
        container.voicePlayer.pauseIfPlaying()
        container.callManager.startOutgoingCall(peerNodeId)
    }

    fun acceptIncomingCall() {
        container.voicePlayer.pauseIfPlaying()
        container.callManager.acceptIncomingCall()
    }

    fun rejectOrEndCall() {
        container.callManager.rejectOrEndCall()
    }

    fun toggleCallMute() {
        container.callManager.toggleMute()
    }

    fun toggleCallSpeaker() {
        container.callManager.toggleSpeaker()
    }

    fun setCallMinimized(minimized: Boolean) {
        container.callManager.setMinimized(minimized)
    }

    fun restartMeshDiscovery() {
        val id = identity.value
        if (id.isOnboarded) {
            router.stop()
            router.start(id)
        }
    }

    fun sendText(
        conversationId: String,
        text: String,
        replyToMessageId: String? = null,
        replyPreview: String? = null
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            router.sendTextMessage(conversationId, trimmed, replyToMessageId, replyPreview)
        }
    }

    fun sendImageFromUri(conversationId: String, uri: Uri, caption: String = "") {
        viewModelScope.launch {
            val processed = runCatching { container.imageProcessor.processImageUri(uri) }.getOrNull()
            if (processed == null) {
                _uiAlerts.tryEmit(LanChatUiAlert.IMAGE_DECODE_FAILED)
                return@launch
            }
            val msgId = router.sendMediaMessage(
                conversationId = conversationId,
                type = MessageType.IMAGE,
                mediaFile = processed.file,
                mimeType = "image/jpeg",
                caption = caption,
                microThumbBase64 = processed.microThumbBase64
            )
            if (msgId == null) {
                _uiAlerts.tryEmit(LanChatUiAlert.IMAGE_DECODE_FAILED)
            }
        }
    }

    fun startVoiceRecording(): Boolean {
        container.voicePlayer.pauseIfPlaying()
        return container.voiceRecorder.startRecording()
    }

    fun stopAndSendVoiceNote(conversationId: String) {
        val note = container.voiceRecorder.stopAndFinish() ?: return
        viewModelScope.launch {
            router.sendMediaMessage(
                conversationId = conversationId,
                type = MessageType.VOICE,
                mediaFile = note.file,
                mimeType = "audio/mp4",
                durationMs = note.durationMs,
                waveformSamples = note.waveformBars
            )
        }
    }

    fun cancelVoiceRecording() {
        container.voiceRecorder.cancelRecording()
    }

    fun togglePlayVoice(messageId: String, filePath: String) {
        if (!File(filePath).exists()) return
        container.voicePlayer.togglePlay(messageId, filePath)
    }

    fun seekVoice(messageId: String, fraction: Float) {
        container.voicePlayer.seekTo(messageId, fraction)
    }

    fun cycleVoiceSpeed() {
        container.voicePlayer.cycleSpeed()
    }

    fun pauseTransfer(transferId: String) = router.pauseTransfer(transferId)
    fun resumeTransfer(transferId: String) = router.resumeOutboundTransfer(transferId)
    fun cancelTransfer(transferId: String) = router.cancelTransfer(transferId)

    fun setActiveConversation(conversationId: String?) {
        container.activeConversationId = conversationId
    }

    fun markConversationRead(conversationId: String) {
        viewModelScope.launch {
            repo.markConversationRead(conversationId)
        }
    }

    fun markIncomingMessagesRead(conversationId: String, messages: List<MessageEntity>) {
        viewModelScope.launch {
            repo.markConversationRead(conversationId)
            router.markIncomingMessagesRead(conversationId, messages)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            repo.deleteConversation(conversationId)
        }
    }

    fun openDirectChat(peerNodeId: String, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            val convId = router.openOrCreateDirectConversation(peerNodeId)
            onOpened(convId)
        }
    }

    fun createGroup(title: String, memberNodeIds: List<String>, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val groupId = router.createEncryptedGroup(title, memberNodeIds) ?: return@launch
            onCreated(groupId)
        }
    }

    fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            router.sendReaction(conversationId, messageId, emoji)
        }
    }

    fun buildMyQrString(): String {
        val id = identity.value
        return QrPayloadCodec.encode(
            QrPeerIdentity(
                nodeId = id.nodeId,
                displayName = id.displayName,
                signingPubB64 = id.signingPubB64,
                dhPubB64 = id.dhPubB64
            )
        )
    }

    fun computeSafetyNumber(peer: PeerEntity): String {
        val id = identity.value
        return container.cryptoManager.computeSafetyNumber(
            localNodeId = id.nodeId,
            localSigningPubB64 = id.signingPubB64,
            localDhPubB64 = id.dhPubB64,
            remoteNodeId = peer.nodeId,
            remoteSigningPubB64 = peer.signingPubKeyB64,
            remoteDhPubB64 = peer.dhPubKeyB64
        )
    }

    fun verifyScannedQr(rawQr: String, onResult: (PeerEntity?) -> Unit) {
        val parsed = QrPayloadCodec.verifyAndDecode(rawQr, container.cryptoManager)
        if (parsed == null || parsed.nodeId == identity.value.nodeId) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val verified = repo.verifyPeerFromQr(parsed)
            onResult(verified)
        }
    }

    fun verifyPeerManually(nodeId: String) {
        viewModelScope.launch {
            repo.markPeerVerifiedManually(nodeId)
        }
    }

    fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repo.updateDisplayName(trimmed)
            _uiAlerts.tryEmit(LanChatUiAlert.DISPLAY_NAME_UPDATED)
        }
    }

    fun setMaxHops(hops: Int) {
        viewModelScope.launch { repo.setMaxHops(hops) }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch { repo.setThemePreference(pref) }
    }

    fun clearChatHistory() {
        viewModelScope.launch { repo.clearAllChatHistory() }
    }

    override fun onCleared() {
        container.offlineLocationProvider.stop()
        container.voicePlayer.stopAndRelease()
        super.onCleared()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LanChatViewModel(container) as T
        }
    }
}
