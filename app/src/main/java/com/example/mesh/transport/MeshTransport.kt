package com.example.mesh.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportStatus {
    STOPPED,
    STARTING,
    ACTIVE,
    PERMISSION_REQUIRED,
    ERROR
}

data class TransportState(
    val status: TransportStatus = TransportStatus.STOPPED,
    val message: String = "Mesh idle",
    val bluetoothEnabled: Boolean = true,
    val wifiEnabled: Boolean = true
)

data class DirectLinkInfo(
    val endpointId: String,
    val remoteNodeId: String,
    val remoteDisplayName: String,
    val linkQuality: Int = 100,
    val connectedAt: Long = System.currentTimeMillis()
)

data class IncomingFrame(
    val fromEndpointId: String,
    val bytes: ByteArray
)

data class LinkPayloadProgress(
    val payloadId: Long,
    val endpointId: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isSuccess: Boolean,
    val isTerminal: Boolean
)

data class MeshLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String
)

interface MeshTransport {
    val state: StateFlow<TransportState>
    val directLinks: StateFlow<Map<String, DirectLinkInfo>>
    val incomingFrames: SharedFlow<IncomingFrame>
    val payloadProgress: SharedFlow<LinkPayloadProgress>
    val diagnosticLogs: StateFlow<List<MeshLogEntry>>

    fun start(localNodeId: String, displayName: String)
    fun stop()
    fun updateDisplayName(displayName: String) {}
    fun nudgeDiscovery() {}
    suspend fun sendToEndpoint(endpointId: String, bytes: ByteArray): Boolean
    suspend fun sendFastUnreliable(endpointId: String, bytes: ByteArray): Boolean = sendToEndpoint(endpointId, bytes)
    suspend fun broadcastBytes(bytes: ByteArray, excludeEndpointId: String? = null): Int
}
