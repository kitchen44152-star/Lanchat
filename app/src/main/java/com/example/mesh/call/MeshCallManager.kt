package com.example.mesh.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.TrustStatus
import com.example.media.EncodedAdpcmFrame
import com.example.media.VoiceCallAudioEngine
import com.example.mesh.protocol.CallAnswerPayload
import com.example.mesh.protocol.CallEndPayload
import com.example.mesh.protocol.CallOfferPayload
import com.example.mesh.protocol.CallRejectPayload
import com.example.mesh.protocol.PacketType
import com.example.mesh.router.IncomingCallEvent
import com.example.mesh.router.MeshRouter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class CallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    ACTIVE,
    ENDED
}

enum class CallEndReason {
    NONE,
    HANGUP,
    DECLINED,
    BUSY,
    NO_ANSWER,
    UNREACHABLE,
    LINK_LOST,
    MIC_PERMISSION_DENIED
}

data class MeshCallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val peerNodeId: String = "",
    val peerName: String = "",
    val trustStatus: TrustStatus = TrustStatus.UNVERIFIED_TOFU,
    val hopCount: Int = 1,
    val isIncoming: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isMinimized: Boolean = false,
    val startedAtMs: Long = 0L,
    val durationSec: Int = 0,
    val localAudioLevel: Float = 0f,
    val remoteAudioLevel: Float = 0f,
    val endReason: CallEndReason = CallEndReason.NONE
) {
    val hasLiveOverlay: Boolean
        get() = phase != CallPhase.IDLE
}

class MeshCallManager(
    private val context: Context,
    private val router: MeshRouter,
    private val audioEngine: VoiceCallAudioEngine,
    private val enableAudioHardware: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val audioSeq = AtomicInteger(0)

    private val _callState = MutableStateFlow(MeshCallUiState())
    val callState: StateFlow<MeshCallUiState> = _callState.asStateFlow()

    @Volatile
    private var localDisplayName: String = "LanChat"
    private var ringTimeoutJob: Job? = null
    private var timerJob: Job? = null
    private var endDismissJob: Job? = null
    private var routeLostSinceMs: Long = 0L

    init {
        observeIncomingSignals()
        observeRouteHealth()
    }

    fun updateLocalDisplayName(name: String) {
        if (name.isNotBlank()) {
            localDisplayName = name.trim()
        }
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startOutgoingCall(peerNodeId: String) {
        scope.launch {
            val current = _callState.value
            if (current.phase != CallPhase.IDLE && current.phase != CallPhase.ENDED) return@launch

            endDismissJob?.cancel()
            val peer = router.getPeerSnapshot(peerNodeId)
            val route = router.getRouteForPeer(peerNodeId)
            val peerName = peer?.displayName ?: route?.displayName ?: "Peer ${peerNodeId.take(6)}"
            val trust = peer?.trustStatus ?: TrustStatus.UNVERIFIED_TOFU

            if (route == null) {
                finishCallLocked(
                    callId = null,
                    peerNodeId = peerNodeId,
                    peerName = peerName,
                    trust = trust,
                    isIncoming = false,
                    reason = CallEndReason.UNREACHABLE,
                    durationSec = 0,
                    wasConnected = false
                )
                return@launch
            }

            if (enableAudioHardware && !hasMicPermission()) {
                finishCallLocked(
                    callId = null,
                    peerNodeId = peerNodeId,
                    peerName = peerName,
                    trust = trust,
                    isIncoming = false,
                    reason = CallEndReason.MIC_PERMISSION_DENIED,
                    durationSec = 0,
                    wasConnected = false
                )
                return@launch
            }

            val callId = "call-${UUID.randomUUID()}"
            audioSeq.set(0)
            _callState.value = MeshCallUiState(
                phase = CallPhase.OUTGOING_RINGING,
                callId = callId,
                peerNodeId = peerNodeId,
                peerName = peerName,
                trustStatus = trust,
                hopCount = route.hopCount,
                isIncoming = false,
                isMuted = false,
                isSpeakerOn = false,
                isMinimized = false
            )

            val offerJson = json.encodeToString(
                CallOfferPayload(
                    callId = callId,
                    callerName = localDisplayName
                )
            )
            val sent = router.sendCallSignal(peerNodeId, PacketType.CALL_OFFER, offerJson)
            if (!sent) {
                finishCallLocked(
                    callId = callId,
                    peerNodeId = peerNodeId,
                    peerName = peerName,
                    trust = trust,
                    isIncoming = false,
                    reason = CallEndReason.UNREACHABLE,
                    durationSec = 0,
                    wasConnected = false
                )
                return@launch
            }

            if (enableAudioHardware) {
                audioEngine.startOutgoingRingback()
            }
            startRingTimeout(callId, isIncoming = false)
        }
    }

    fun acceptIncomingCall() {
        scope.launch {
            val current = _callState.value
            val callId = current.callId ?: return@launch
            if (current.phase != CallPhase.INCOMING_RINGING) return@launch

            if (enableAudioHardware && !hasMicPermission()) {
                rejectOrEndCall(explicitReason = CallEndReason.MIC_PERMISSION_DENIED)
                return@launch
            }

            ringTimeoutJob?.cancel()
            if (enableAudioHardware) {
                audioEngine.stopTones()
            }
            _callState.value = current.copy(phase = CallPhase.CONNECTING, isMinimized = false)

            val answerJson = json.encodeToString(
                CallAnswerPayload(
                    callId = callId,
                    accepterName = localDisplayName
                )
            )
            val sent = router.sendCallSignal(current.peerNodeId, PacketType.CALL_ANSWER, answerJson)
            if (!sent) {
                finishCallLocked(
                    callId = callId,
                    peerNodeId = current.peerNodeId,
                    peerName = current.peerName,
                    trust = current.trustStatus,
                    isIncoming = true,
                    reason = CallEndReason.LINK_LOST,
                    durationSec = 0,
                    wasConnected = false
                )
                return@launch
            }

            activateAudioSession(callId)
        }
    }

    fun rejectOrEndCall(explicitReason: CallEndReason? = null) {
        scope.launch {
            val current = _callState.value
            val callId = current.callId
            if (current.phase == CallPhase.IDLE || current.phase == CallPhase.ENDED) {
                _callState.value = MeshCallUiState()
                return@launch
            }

            when (current.phase) {
                CallPhase.INCOMING_RINGING -> {
                    val rejectReasonCode = if (explicitReason == CallEndReason.MIC_PERMISSION_DENIED) "DECLINED" else "DECLINED"
                    if (callId != null) {
                        val payload = json.encodeToString(CallRejectPayload(callId, rejectReasonCode))
                        router.sendCallSignal(current.peerNodeId, PacketType.CALL_REJECT, payload)
                    }
                    finishCallLocked(
                        callId = callId,
                        peerNodeId = current.peerNodeId,
                        peerName = current.peerName,
                        trust = current.trustStatus,
                        isIncoming = true,
                        reason = explicitReason ?: CallEndReason.DECLINED,
                        durationSec = 0,
                        wasConnected = false
                    )
                }
                CallPhase.OUTGOING_RINGING, CallPhase.CONNECTING, CallPhase.ACTIVE -> {
                    val wasConnected = current.phase == CallPhase.ACTIVE
                    if (callId != null) {
                        val payload = json.encodeToString(CallEndPayload(callId, current.durationSec))
                        router.sendCallSignal(current.peerNodeId, PacketType.CALL_END, payload)
                    }
                    finishCallLocked(
                        callId = callId,
                        peerNodeId = current.peerNodeId,
                        peerName = current.peerName,
                        trust = current.trustStatus,
                        isIncoming = current.isIncoming,
                        reason = explicitReason ?: CallEndReason.HANGUP,
                        durationSec = current.durationSec,
                        wasConnected = wasConnected
                    )
                }
                else -> Unit
            }
        }
    }

    fun toggleMute() {
        val current = _callState.value
        val nextMuted = !current.isMuted
        audioEngine.setMuted(nextMuted)
        _callState.value = current.copy(isMuted = nextMuted, localAudioLevel = if (nextMuted) 0f else current.localAudioLevel)
    }

    fun toggleSpeaker() {
        val current = _callState.value
        val nextSpeaker = !current.isSpeakerOn
        if (enableAudioHardware) {
            audioEngine.setSpeakerphone(nextSpeaker)
        }
        _callState.value = current.copy(isSpeakerOn = nextSpeaker)
    }

    fun setMinimized(minimized: Boolean) {
        val current = _callState.value
        if (current.phase == CallPhase.ACTIVE || current.phase == CallPhase.OUTGOING_RINGING || current.phase == CallPhase.CONNECTING) {
            _callState.value = current.copy(isMinimized = minimized)
        }
    }

    private fun observeIncomingSignals() {
        scope.launch {
            router.incomingCallEvents.collect { event ->
                when (event) {
                    is IncomingCallEvent.Offer -> handleIncomingOffer(event)
                    is IncomingCallEvent.Answer -> handleIncomingAnswer(event)
                    is IncomingCallEvent.Reject -> handleIncomingReject(event)
                    is IncomingCallEvent.End -> handleIncomingEnd(event)
                    is IncomingCallEvent.Audio -> handleIncomingAudio(event)
                }
            }
        }
    }

    private suspend fun handleIncomingOffer(event: IncomingCallEvent.Offer) {
        val current = _callState.value
        if (current.phase != CallPhase.IDLE && current.phase != CallPhase.ENDED) {
            val rejectJson = json.encodeToString(CallRejectPayload(event.payload.callId, "BUSY"))
            router.sendCallSignal(event.senderNodeId, PacketType.CALL_REJECT, rejectJson)
            return
        }

        endDismissJob?.cancel()
        audioSeq.set(0)
        val peer = router.getPeerSnapshot(event.senderNodeId)
        val resolvedName = peer?.displayName?.ifBlank { event.payload.callerName } ?: event.payload.callerName

        _callState.value = MeshCallUiState(
            phase = CallPhase.INCOMING_RINGING,
            callId = event.payload.callId,
            peerNodeId = event.senderNodeId,
            peerName = resolvedName,
            trustStatus = event.trustStatus,
            hopCount = event.hopCount,
            isIncoming = true,
            isMuted = false,
            isSpeakerOn = false,
            isMinimized = false
        )
        if (enableAudioHardware) {
            audioEngine.startIncomingRingtone()
        }
        startRingTimeout(event.payload.callId, isIncoming = true)
    }

    private fun handleIncomingAnswer(event: IncomingCallEvent.Answer) {
        val current = _callState.value
        if (current.callId != event.payload.callId) return
        if (current.phase != CallPhase.OUTGOING_RINGING && current.phase != CallPhase.CONNECTING) return
        ringTimeoutJob?.cancel()
        if (enableAudioHardware) {
            audioEngine.stopTones()
        }
        activateAudioSession(event.payload.callId)
    }

    private suspend fun handleIncomingReject(event: IncomingCallEvent.Reject) {
        val current = _callState.value
        if (current.callId != event.payload.callId) return
        val reason = when (event.payload.reason.uppercase(Locale.US)) {
            "BUSY" -> CallEndReason.BUSY
            "TIMEOUT" -> CallEndReason.NO_ANSWER
            else -> CallEndReason.DECLINED
        }
        finishCallLocked(
            callId = current.callId,
            peerNodeId = current.peerNodeId,
            peerName = current.peerName,
            trust = current.trustStatus,
            isIncoming = current.isIncoming,
            reason = reason,
            durationSec = 0,
            wasConnected = false
        )
    }

    private suspend fun handleIncomingEnd(event: IncomingCallEvent.End) {
        val current = _callState.value
        if (current.callId != event.payload.callId) return
        val wasConnected = current.phase == CallPhase.ACTIVE
        finishCallLocked(
            callId = current.callId,
            peerNodeId = current.peerNodeId,
            peerName = current.peerName,
            trust = current.trustStatus,
            isIncoming = current.isIncoming,
            reason = if (!wasConnected && current.isIncoming) CallEndReason.NO_ANSWER else CallEndReason.HANGUP,
            durationSec = current.durationSec.coerceAtLeast(event.payload.durationSec),
            wasConnected = wasConnected
        )
    }

    private fun handleIncomingAudio(event: IncomingCallEvent.Audio) {
        val current = _callState.value
        if (current.callId != event.callId) return
        if (current.phase != CallPhase.ACTIVE && current.phase != CallPhase.CONNECTING) return
        if (enableAudioHardware) {
            audioEngine.enqueueRemoteFrame(
                EncodedAdpcmFrame(
                    data = event.adpcmBytes,
                    initialPredictor = event.initialPredictor,
                    initialStepIndex = event.initialStepIndex,
                    sampleCount = event.sampleCount
                )
            )
        }
    }

    private fun activateAudioSession(callId: String) {
        val current = _callState.value
        if (current.callId != callId) return
        val startMs = System.currentTimeMillis()
        routeLostSinceMs = 0L
        _callState.value = current.copy(
            phase = CallPhase.ACTIVE,
            startedAtMs = startMs,
            durationSec = 0
        )

        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val latest = _callState.value
                if (latest.phase != CallPhase.ACTIVE || latest.callId != callId) break
                val elapsed = ((System.currentTimeMillis() - latest.startedAtMs) / 1000L).toInt().coerceAtLeast(0)
                _callState.value = latest.copy(durationSec = elapsed)
            }
        }

        if (enableAudioHardware) {
            audioEngine.setMuted(current.isMuted)
            audioEngine.startAudioStream(
                speakerEnabled = current.isSpeakerOn,
                onLocalFrame = { frame, level ->
                    val latest = _callState.value
                    if (latest.phase == CallPhase.ACTIVE && latest.callId == callId) {
                        _callState.value = latest.copy(localAudioLevel = level)
                        val seq = audioSeq.incrementAndGet()
                        scope.launch {
                            router.sendCallAudioFrame(
                                destNodeId = latest.peerNodeId,
                                callId = callId,
                                seqNo = seq,
                                adpcmBytes = frame.data,
                                initialPredictor = frame.initialPredictor,
                                initialStepIndex = frame.initialStepIndex,
                                sampleCount = frame.sampleCount
                            )
                        }
                    }
                },
                onRemoteLevel = { level ->
                    val latest = _callState.value
                    if (latest.phase == CallPhase.ACTIVE && latest.callId == callId) {
                        _callState.value = latest.copy(remoteAudioLevel = level)
                    }
                }
            )
        }
    }

    private fun startRingTimeout(callId: String, isIncoming: Boolean) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(35_000L)
            val latest = _callState.value
            if (latest.callId != callId) return@launch
            if (latest.phase == CallPhase.OUTGOING_RINGING || latest.phase == CallPhase.INCOMING_RINGING) {
                if (isIncoming) {
                    router.sendCallSignal(
                        latest.peerNodeId,
                        PacketType.CALL_REJECT,
                        json.encodeToString(CallRejectPayload(callId, "TIMEOUT"))
                    )
                } else {
                    router.sendCallSignal(
                        latest.peerNodeId,
                        PacketType.CALL_END,
                        json.encodeToString(CallEndPayload(callId, 0))
                    )
                }
                finishCallLocked(
                    callId = callId,
                    peerNodeId = latest.peerNodeId,
                    peerName = latest.peerName,
                    trust = latest.trustStatus,
                    isIncoming = latest.isIncoming,
                    reason = CallEndReason.NO_ANSWER,
                    durationSec = 0,
                    wasConnected = false
                )
            }
        }
    }

    private fun observeRouteHealth() {
        scope.launch {
            while (isActive) {
                delay(2000L)
                val current = _callState.value
                if (current.phase == CallPhase.ACTIVE || current.phase == CallPhase.CONNECTING) {
                    val route = router.getRouteForPeer(current.peerNodeId)
                    if (route == null) {
                        if (routeLostSinceMs == 0L) {
                            routeLostSinceMs = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - routeLostSinceMs >= 6_000L) {
                            finishCallLocked(
                                callId = current.callId,
                                peerNodeId = current.peerNodeId,
                                peerName = current.peerName,
                                trust = current.trustStatus,
                                isIncoming = current.isIncoming,
                                reason = CallEndReason.LINK_LOST,
                                durationSec = current.durationSec,
                                wasConnected = current.phase == CallPhase.ACTIVE
                            )
                        }
                    } else {
                        routeLostSinceMs = 0L
                        if (route.hopCount != current.hopCount) {
                            _callState.value = current.copy(hopCount = route.hopCount)
                        }
                    }
                } else {
                    routeLostSinceMs = 0L
                }
            }
        }
    }

    private suspend fun finishCallLocked(
        callId: String?,
        peerNodeId: String,
        peerName: String,
        trust: TrustStatus,
        isIncoming: Boolean,
        reason: CallEndReason,
        durationSec: Int,
        wasConnected: Boolean
    ) {
        ringTimeoutJob?.cancel()
        timerJob?.cancel()
        ringTimeoutJob = null
        timerJob = null
        routeLostSinceMs = 0L
        if (enableAudioHardware) {
            audioEngine.stopAudioStream()
        }

        if (peerNodeId.isNotBlank() && reason != CallEndReason.UNREACHABLE && reason != CallEndReason.MIC_PERMISSION_DENIED) {
            val summary = when {
                wasConnected || durationSec > 0 -> context.getString(R.string.call_log_completed, formatCallDuration(durationSec))
                reason == CallEndReason.DECLINED -> context.getString(R.string.call_log_declined)
                else -> context.getString(R.string.call_log_missed)
            }
            runCatching {
                router.recordCallLogMessage(peerNodeId, peerName, summary, isIncoming)
            }
        }

        _callState.value = MeshCallUiState(
            phase = CallPhase.ENDED,
            callId = callId,
            peerNodeId = peerNodeId,
            peerName = peerName,
            trustStatus = trust,
            isIncoming = isIncoming,
            durationSec = durationSec,
            endReason = reason,
            isMinimized = false
        )

        endDismissJob?.cancel()
        endDismissJob = scope.launch {
            delay(1800L)
            if (_callState.value.phase == CallPhase.ENDED && _callState.value.callId == callId) {
                _callState.value = MeshCallUiState()
            }
        }
    }

    companion object {
        fun formatCallDuration(totalSeconds: Int): String {
            val s = totalSeconds.coerceAtLeast(0)
            val mins = s / 60
            val secs = s % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
