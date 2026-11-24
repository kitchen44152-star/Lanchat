package com.example.mesh.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class SimulatedMeshBus {
    private val nodes = ConcurrentHashMap<String, SimulatedMeshTransport>()
    private val edges = ConcurrentHashMap<String, MutableSet<String>>()

    fun register(nodeId: String, transport: SimulatedMeshTransport) {
        nodes[nodeId] = transport
        edges.putIfAbsent(nodeId, ConcurrentHashMap.newKeySet())
    }

    fun connectBidirectional(a: String, b: String) {
        val ta = nodes[a] ?: return
        val tb = nodes[b] ?: return
        edges.getOrPut(a) { ConcurrentHashMap.newKeySet() }.add(b)
        edges.getOrPut(b) { ConcurrentHashMap.newKeySet() }.add(a)
        ta.onVirtualLinkUp(b, tb.displayName)
        tb.onVirtualLinkUp(a, ta.displayName)
    }

    fun disconnectBidirectional(a: String, b: String) {
        edges[a]?.remove(b)
        edges[b]?.remove(a)
        nodes[a]?.onVirtualLinkDown(b)
        nodes[b]?.onVirtualLinkDown(a)
    }

    fun deliver(fromNodeId: String, toNodeId: String, bytes: ByteArray): Boolean {
        val connected = edges[fromNodeId]?.contains(toNodeId) == true
        if (!connected) return false
        val target = nodes[toNodeId] ?: return false
        return target.deliverIncoming(fromEndpointId = fromNodeId, bytes = bytes)
    }
}

class SimulatedMeshTransport(
    private val bus: SimulatedMeshBus
) : MeshTransport {

    private val _state = MutableStateFlow(TransportState())
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _directLinks = MutableStateFlow<Map<String, DirectLinkInfo>>(emptyMap())
    override val directLinks: StateFlow<Map<String, DirectLinkInfo>> = _directLinks.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 512)
    override val incomingFrames: SharedFlow<IncomingFrame> = _incomingFrames.asSharedFlow()

    private val _payloadProgress = MutableSharedFlow<LinkPayloadProgress>(extraBufferCapacity = 512)
    override val payloadProgress: SharedFlow<LinkPayloadProgress> = _payloadProgress.asSharedFlow()

    private val _diagnosticLogs = MutableStateFlow<List<MeshLogEntry>>(emptyList())
    override val diagnosticLogs: StateFlow<List<MeshLogEntry>> = _diagnosticLogs.asStateFlow()

    var nodeId: String = ""
        private set
    var displayName: String = ""
        private set

    override fun start(localNodeId: String, displayName: String) {
        this.nodeId = localNodeId
        this.displayName = displayName
        bus.register(localNodeId, this)
        _state.value = TransportState(TransportStatus.ACTIVE, "Simulated mesh active")
    }

    override fun updateDisplayName(displayName: String) {
        if (displayName.isNotBlank()) {
            this.displayName = displayName.trim()
        }
    }

    override fun stop() {
        _directLinks.value = emptyMap()
        _state.value = TransportState(TransportStatus.STOPPED, "Simulated mesh stopped")
    }

    fun onVirtualLinkUp(remoteNodeId: String, remoteName: String) {
        val next = _directLinks.value.toMutableMap()
        next[remoteNodeId] = DirectLinkInfo(
            endpointId = remoteNodeId,
            remoteNodeId = remoteNodeId,
            remoteDisplayName = remoteName,
            linkQuality = 100
        )
        _directLinks.value = next
    }

    fun onVirtualLinkDown(remoteNodeId: String) {
        val next = _directLinks.value.toMutableMap()
        next.remove(remoteNodeId)
        _directLinks.value = next
    }

    fun deliverIncoming(fromEndpointId: String, bytes: ByteArray): Boolean {
        return _incomingFrames.tryEmit(IncomingFrame(fromEndpointId = fromEndpointId, bytes = bytes))
    }

    override suspend fun sendToEndpoint(endpointId: String, bytes: ByteArray): Boolean {
        if (_state.value.status != TransportStatus.ACTIVE) return false
        return bus.deliver(nodeId, endpointId, bytes)
    }

    override suspend fun broadcastBytes(bytes: ByteArray, excludeEndpointId: String?): Int {
        if (_state.value.status != TransportStatus.ACTIVE) return 0
        var count = 0
        for (ep in _directLinks.value.keys) {
            if (ep != excludeEndpointId && bus.deliver(nodeId, ep, bytes)) {
                count++
            }
        }
        return count
    }
}
