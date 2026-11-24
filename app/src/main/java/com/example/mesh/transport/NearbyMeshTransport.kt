package com.example.mesh.transport

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class NearbyMeshTransport(
    private val context: Context
) : MeshTransport {

    companion object {
        const val SERVICE_ID = "com.aistudio.lanchat.mesh.v1"
        const val MAX_FRAME_BYTES = ConnectionsClient.MAX_BYTES_DATA_SIZE // 32,768 bytes
        private const val MAX_LOG_ENTRIES = 120
        private const val MAINTENANCE_INTERVAL_MS = 5_000L
        private const val DISCOVERY_REFRESH_CYCLES = 2
        private const val SEND_DELIVERY_TIMEOUT_MS = 6_000L
        private const val DISCOVERY_NUDGE_COOLDOWN_MS = 10_000L
        private const val MAX_CONSECUTIVE_FAILURES = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionsClient: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private val _state = MutableStateFlow(TransportState())
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _directLinks = MutableStateFlow<Map<String, DirectLinkInfo>>(emptyMap())
    override val directLinks: StateFlow<Map<String, DirectLinkInfo>> = _directLinks.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 256)
    override val incomingFrames: SharedFlow<IncomingFrame> = _incomingFrames.asSharedFlow()

    private val _payloadProgress = MutableSharedFlow<LinkPayloadProgress>(extraBufferCapacity = 256)
    override val payloadProgress: SharedFlow<LinkPayloadProgress> = _payloadProgress.asSharedFlow()

    private val _diagnosticLogs = MutableStateFlow<List<MeshLogEntry>>(emptyList())
    override val diagnosticLogs: StateFlow<List<MeshLogEntry>> = _diagnosticLogs.asStateFlow()

    private val discoveredEndpoints = ConcurrentHashMap<String, Pair<String, String>>() // endpointId -> (nodeId, name)
    private val pendingEndpoints = ConcurrentHashMap<String, Pair<String, String>>() // endpointId -> (nodeId, name)
    private val connectingEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val consecutiveSendFailures = ConcurrentHashMap<String, Int>()
    private val awaitingPayloadOutcomes = ConcurrentHashMap<Long, Pair<String, CompletableDeferred<Boolean>>>()
    private val lastDiscoveryNudgeAt = AtomicLong(0L)

    private var localNodeId: String = ""
    private var localDisplayName: String = ""
    @Volatile
    private var isStarted = false
    @Volatile
    private var shouldRunWhenRadiosReady = false
    private var maintenanceJob: Job? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                consecutiveSendFailures[endpointId] = 0
                _incomingFrames.tryEmit(IncomingFrame(fromEndpointId = endpointId, bytes = bytes))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val terminal = update.status != PayloadTransferUpdate.Status.IN_PROGRESS
            val success = update.status == PayloadTransferUpdate.Status.SUCCESS
            if (terminal) {
                val pending = awaitingPayloadOutcomes.remove(update.payloadId)
                pending?.second?.complete(success)
                if (success) {
                    consecutiveSendFailures[endpointId] = 0
                } else {
                    recordSendFailure(endpointId, "payload update status=${update.status}", hardReset = false)
                }
            }
            _payloadProgress.tryEmit(
                LinkPayloadProgress(
                    payloadId = update.payloadId,
                    endpointId = endpointId,
                    bytesTransferred = update.bytesTransferred,
                    totalBytes = update.totalBytes,
                    isSuccess = success,
                    isTerminal = terminal
                )
            )
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (remoteId, remoteName) = parseEndpointName(info.endpointName, endpointId)
            pendingEndpoints[endpointId] = remoteId to remoteName
            discoveredEndpoints[endpointId] = remoteId to remoteName
            log("CONN", "Connection initiated with $remoteName ($remoteId), accepting...")
            runCatching {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }.onFailure { e ->
                pendingEndpoints.remove(endpointId)
                connectingEndpoints.remove(endpointId)
                log("ERR", "acceptConnection failed for $endpointId: ${e.message}")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connectingEndpoints.remove(endpointId)
            val info = pendingEndpoints.remove(endpointId) ?: discoveredEndpoints[endpointId]
            val code = result.status.statusCode
            if (result.status.isSuccess || code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                retryCounts.remove(endpointId)
                consecutiveSendFailures[endpointId] = 0
                val (remoteId, remoteName) = info ?: (endpointId to "Peer-${endpointId.take(4)}")

                // Replace any older zombie endpoint pointing to the same peer nodeId
                val updated = _directLinks.value.toMutableMap()
                val staleForSamePeer = updated.entries
                    .filter { it.key != endpointId && it.value.remoteNodeId == remoteId }
                    .map { it.key }
                staleForSamePeer.forEach { oldEp ->
                    updated.remove(oldEp)
                    failAwaitingPayloadsForEndpoint(oldEp)
                    runCatching { connectionsClient.disconnectFromEndpoint(oldEp) }
                }

                updated[endpointId] = DirectLinkInfo(
                    endpointId = endpointId,
                    remoteNodeId = remoteId,
                    remoteDisplayName = remoteName,
                    linkQuality = 95
                )
                _directLinks.value = updated
                log("LINK", "Connected to $remoteName ($remoteId) via endpoint $endpointId")
            } else {
                val nextAttempt = ((retryCounts[endpointId] ?: 0) + 1).coerceAtMost(5)
                retryCounts[endpointId] = if (code == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) 1 else nextAttempt
                lastAttemptAt[endpointId] = System.currentTimeMillis()
                log("WARN", "Connection failed to $endpointId (code=$code)")
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            val quality = when (bandwidthInfo.quality) {
                BandwidthInfo.Quality.HIGH -> 100
                BandwidthInfo.Quality.MEDIUM -> 75
                BandwidthInfo.Quality.LOW -> 45
                else -> 60
            }
            val current = _directLinks.value[endpointId] ?: return
            val updated = _directLinks.value.toMutableMap()
            updated[endpointId] = current.copy(linkQuality = quality)
            _directLinks.value = updated
            log("LINK", "Bandwidth updated on $endpointId -> quality=$quality")
        }

        override fun onDisconnected(endpointId: String) {
            handleEndpointDown(endpointId, shouldAttemptReconnect = true, reason = "onDisconnected")
        }
    }

    private fun handleEndpointDown(endpointId: String, shouldAttemptReconnect: Boolean, reason: String) {
        connectingEndpoints.remove(endpointId)
        pendingEndpoints.remove(endpointId)
        consecutiveSendFailures.remove(endpointId)
        failAwaitingPayloadsForEndpoint(endpointId)
        retryCounts[endpointId] = 0
        lastAttemptAt[endpointId] = System.currentTimeMillis()

        val removed = _directLinks.value[endpointId]
        if (removed != null) {
            val updated = _directLinks.value.toMutableMap()
            updated.remove(endpointId)
            _directLinks.value = updated
            log("LINK", "Disconnected from ${removed.remoteDisplayName} ($endpointId): $reason")
        }

        val disc = discoveredEndpoints[endpointId]
        if (shouldAttemptReconnect && isStarted && disc != null) {
            scope.launch {
                delay(1500)
                if (isStarted &&
                    discoveredEndpoints.containsKey(endpointId) &&
                    !_directLinks.value.containsKey(endpointId) &&
                    !connectingEndpoints.contains(endpointId)
                ) {
                    initiateWithBackoff(endpointId, disc.first, disc.second)
                }
            }
        } else if (shouldAttemptReconnect && isStarted) {
            nudgeDiscovery()
        }
    }

    private fun failAwaitingPayloadsForEndpoint(endpointId: String) {
        val iter = awaitingPayloadOutcomes.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.first == endpointId) {
                entry.value.second.complete(false)
                iter.remove()
            }
        }
    }

    private fun recordSendFailure(endpointId: String, detail: String, hardReset: Boolean) {
        val count = consecutiveSendFailures.compute(endpointId) { _, prev -> (prev ?: 0) + 1 } ?: 1
        if (hardReset || count >= MAX_CONSECUTIVE_FAILURES) {
            log("WARN", "Evicting stale endpoint $endpointId ($detail, failures=$count)")
            runCatching { connectionsClient.disconnectFromEndpoint(endpointId) }
            handleEndpointDown(endpointId, shouldAttemptReconnect = !hardReset, reason = detail)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!isStarted) return
            val (remoteId, remoteName) = parseEndpointName(info.endpointName, endpointId)
            if (remoteId == localNodeId) return
            discoveredEndpoints[endpointId] = remoteId to remoteName
            if (!_directLinks.value.containsKey(endpointId)) {
                retryCounts[endpointId] = 0
            }

            // If we already have a healthy link to this nodeId, skip duplicate handshake
            val existingSamePeer = _directLinks.value.entries.firstOrNull { it.value.remoteNodeId == remoteId }
            if (_directLinks.value.containsKey(endpointId)) {
                return
            }
            if (existingSamePeer != null) {
                val existingFails = consecutiveSendFailures[existingSamePeer.key] ?: 0
                if (existingFails == 0) {
                    return
                }
                // Existing link to this peer was already struggling; drop it and connect fresh
                log("WARN", "Replacing failing link ${existingSamePeer.key} with fresh endpoint $endpointId")
                runCatching { connectionsClient.disconnectFromEndpoint(existingSamePeer.key) }
                handleEndpointDown(existingSamePeer.key, shouldAttemptReconnect = false, reason = "replaced by $endpointId")
            }

            log("DISC", "Found peer $remoteName ($remoteId) at $endpointId")

            // Deterministic tie-breaker prevents dual-connect race conditions in P2P_CLUSTER
            val shouldInitiate = localNodeId < remoteId
            if (!shouldInitiate) {
                log("DISC", "Waiting passively for $remoteName ($remoteId) per tie-breaker")
                scope.launch {
                    delay(2800)
                    if (isStarted &&
                        discoveredEndpoints.containsKey(endpointId) &&
                        !_directLinks.value.containsKey(endpointId) &&
                        !_directLinks.value.values.any { it.remoteNodeId == remoteId } &&
                        !connectingEndpoints.contains(endpointId) &&
                        !pendingEndpoints.containsKey(endpointId)
                    ) {
                        initiateWithBackoff(endpointId, remoteId, remoteName)
                    }
                }
                return
            }

            initiateWithBackoff(endpointId, remoteId, remoteName)
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            connectingEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            retryCounts.remove(endpointId)
            lastAttemptAt.remove(endpointId)
            log("DISC", "Endpoint lost: $endpointId")
        }
    }

    private fun initiateWithBackoff(endpointId: String, remoteId: String, remoteName: String) {
        if (!isStarted || connectingEndpoints.contains(endpointId)) return
        if (_directLinks.value.containsKey(endpointId) || _directLinks.value.values.any { it.remoteNodeId == remoteId }) {
            return
        }
        connectingEndpoints.add(endpointId)
        val attempt = retryCounts[endpointId] ?: 0
        val delayMs = if (attempt == 0) 0L else (800L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)

        scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!isStarted ||
                !discoveredEndpoints.containsKey(endpointId) ||
                _directLinks.value.containsKey(endpointId) ||
                _directLinks.value.values.any { it.remoteNodeId == remoteId }
            ) {
                connectingEndpoints.remove(endpointId)
                return@launch
            }
            pendingEndpoints[endpointId] = remoteId to remoteName
            lastAttemptAt[endpointId] = System.currentTimeMillis()
            val myEndpointName = "$localNodeId|$localDisplayName"
            try {
                connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e ->
                        connectingEndpoints.remove(endpointId)
                        pendingEndpoints.remove(endpointId)
                        retryCounts[endpointId] = (attempt + 1).coerceAtMost(5)
                        val apiCode = (e as? ApiException)?.statusCode
                        if (apiCode == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN) {
                            discoveredEndpoints.remove(endpointId)
                        }
                        log("WARN", "requestConnection failed ($endpointId): ${e.message}")
                    }
            } catch (se: SecurityException) {
                connectingEndpoints.remove(endpointId)
                pendingEndpoints.remove(endpointId)
                _state.value = TransportState(TransportStatus.PERMISSION_REQUIRED, "Missing Nearby permissions")
                log("ERR", "SecurityException requesting connection: ${se.message}")
            }
        }
    }

    override fun start(localNodeId: String, displayName: String) {
        this.localNodeId = localNodeId
        this.localDisplayName = displayName.ifBlank { "LanChatNode" }
        shouldRunWhenRadiosReady = true

        if (!hasRequiredPermissions(context)) {
            isStarted = false
            _state.value = TransportState(
                status = TransportStatus.PERMISSION_REQUIRED,
                message = "Bluetooth & Nearby Wi-Fi permissions required"
            )
            log("PERM", "Cannot start Nearby transport: permissions not granted")
            return
        }

        scope.launch {
            val btOn = isBluetoothEnabled()
            val wifiOn = isWifiEnabled()

            if (isStarted && _state.value.status == TransportStatus.ACTIVE) {
                _state.value = _state.value.copy(bluetoothEnabled = btOn, wifiEnabled = wifiOn)
                return@launch
            }

            if (!btOn && !wifiOn) {
                isStarted = false
                _state.value = TransportState(
                    status = TransportStatus.ERROR,
                    message = "Enable Bluetooth or Wi-Fi to form offline mesh",
                    bluetoothEnabled = false,
                    wifiEnabled = false
                )
                log("WARN", "Both Bluetooth and Wi-Fi radios are turned off")
                ensureMaintenanceLoop()
                return@launch
            }

            if (_state.value.status == TransportStatus.ERROR) {
                runCatching {
                    connectionsClient.stopAdvertising()
                    connectionsClient.stopDiscovery()
                }
            }

            isStarted = true
            _state.value = TransportState(
                status = TransportStatus.STARTING,
                message = "Starting P2P cluster mesh...",
                bluetoothEnabled = btOn,
                wifiEnabled = wifiOn
            )
            startAdvertisingAndDiscovery(btOn, wifiOn)
            ensureMaintenanceLoop()
        }
    }

    override fun updateDisplayName(displayName: String) {
        val cleaned = displayName.trim().ifBlank { return }
        if (cleaned == localDisplayName) return
        localDisplayName = cleaned
        if (!isStarted || !hasRequiredPermissions(context)) return

        scope.launch {
            val myEndpointName = "$localNodeId|$localDisplayName"
            val advOptions = AdvertisingOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
            runCatching {
                connectionsClient.stopAdvertising()
                delay(200)
                connectionsClient.startAdvertising(myEndpointName, SERVICE_ID, connectionLifecycleCallback, advOptions)
                    .addOnSuccessListener {
                        log("MESH", "Re-advertising updated identity: $myEndpointName")
                    }
            }
        }
    }

    override fun nudgeDiscovery() {
        if (!shouldRunWhenRadiosReady || !hasRequiredPermissions(context)) return
        val now = System.currentTimeMillis()
        val prev = lastDiscoveryNudgeAt.get()
        if (now - prev < DISCOVERY_NUDGE_COOLDOWN_MS) return
        if (!lastDiscoveryNudgeAt.compareAndSet(prev, now)) return

        scope.launch {
            val btOn = isBluetoothEnabled()
            val wifiOn = isWifiEnabled()
            if (!btOn && !wifiOn) return@launch

            if (!isStarted || _state.value.status == TransportStatus.ERROR) {
                start(localNodeId, localDisplayName)
                return@launch
            }

            runCatching {
                connectionsClient.stopDiscovery()
                delay(250)
                if (isStarted) {
                    val discOptions = DiscoveryOptions.Builder()
                        .setStrategy(Strategy.P2P_CLUSTER)
                        .build()
                    connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                    log("MESH", "Auto-refreshed discovery scan")
                }
            }
        }
    }

    private fun startAdvertisingAndDiscovery(btOn: Boolean, wifiOn: Boolean) {
        val myEndpointName = "$localNodeId|$localDisplayName"
        try {
            val advOptions = AdvertisingOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
            val discOptions = DiscoveryOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()

            val radioHint = when {
                !btOn -> " (Wi-Fi only · Bluetooth off)"
                !wifiOn -> " (Bluetooth only · Wi-Fi off)"
                else -> ""
            }

            connectionsClient.startAdvertising(myEndpointName, SERVICE_ID, connectionLifecycleCallback, advOptions)
                .addOnSuccessListener {
                    log("MESH", "Advertising started as $myEndpointName")
                    _state.value = TransportState(
                        status = TransportStatus.ACTIVE,
                        message = "Mesh P2P cluster active$radioHint",
                        bluetoothEnabled = btOn,
                        wifiEnabled = wifiOn
                    )
                }
                .addOnFailureListener { e ->
                    log("ERR", "startAdvertising failed: ${e.message}")
                    if (_state.value.status != TransportStatus.ACTIVE) {
                        isStarted = false
                        _state.value = TransportState(
                            status = TransportStatus.ERROR,
                            message = e.message ?: "Advertising failed",
                            bluetoothEnabled = btOn,
                            wifiEnabled = wifiOn
                        )
                    }
                }

            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                .addOnSuccessListener {
                    log("MESH", "Discovery started for $SERVICE_ID")
                }
                .addOnFailureListener { e ->
                    log("ERR", "startDiscovery failed: ${e.message}")
                }
        } catch (se: SecurityException) {
            isStarted = false
            _state.value = TransportState(
                status = TransportStatus.PERMISSION_REQUIRED,
                message = se.message ?: "Permission denied",
                bluetoothEnabled = btOn,
                wifiEnabled = wifiOn
            )
            log("ERR", "SecurityException starting Nearby: ${se.message}")
        } catch (t: Throwable) {
            isStarted = false
            _state.value = TransportState(
                status = TransportStatus.ERROR,
                message = t.message ?: "Nearby unavailable",
                bluetoothEnabled = btOn,
                wifiEnabled = wifiOn
            )
            log("ERR", "Nearby initialization error: ${t.message}")
        }
    }

    private fun ensureMaintenanceLoop() {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
            var idleCycles = 0
            while (isActive && shouldRunWhenRadiosReady) {
                delay(MAINTENANCE_INTERVAL_MS)
                if (!shouldRunWhenRadiosReady) break
                val btOn = isBluetoothEnabled()
                val wifiOn = isWifiEnabled()
                val current = _state.value
                if (current.bluetoothEnabled != btOn || current.wifiEnabled != wifiOn) {
                    _state.value = current.copy(bluetoothEnabled = btOn, wifiEnabled = wifiOn)
                }
                if (!btOn && !wifiOn) continue

                // Automatically recover if radios were re-enabled after initial error
                if (!isStarted && hasRequiredPermissions(context)) {
                    isStarted = true
                    _state.value = TransportState(
                        status = TransportStatus.STARTING,
                        message = "Starting P2P cluster mesh...",
                        bluetoothEnabled = btOn,
                        wifiEnabled = wifiOn
                    )
                    startAdvertisingAndDiscovery(btOn, wifiOn)
                    continue
                }
                if (!isStarted) continue

                val now = System.currentTimeMillis()
                // Retry discovered endpoints that are not connected or actively handshaking
                for ((epId, pair) in discoveredEndpoints.entries) {
                    val (remoteId, remoteName) = pair
                    if (_directLinks.value.containsKey(epId) ||
                        _directLinks.value.values.any { it.remoteNodeId == remoteId } ||
                        connectingEndpoints.contains(epId) ||
                        pendingEndpoints.containsKey(epId)
                    ) {
                        continue
                    }
                    val lastTry = lastAttemptAt[epId] ?: 0L
                    if (now - lastTry >= 6_000L) {
                        if ((retryCounts[epId] ?: 0) >= 5) {
                            retryCounts[epId] = 1
                        }
                        initiateWithBackoff(epId, remoteId, remoteName)
                    }
                }

                // Periodically refresh discovery if no peers are connected to keep BLE/Wi-Fi Direct scans responsive
                if (_directLinks.value.isEmpty() && connectingEndpoints.isEmpty()) {
                    idleCycles++
                    if (idleCycles >= DISCOVERY_REFRESH_CYCLES) {
                        idleCycles = 0
                        nudgeDiscovery()
                    }
                } else {
                    idleCycles = 0
                }
            }
        }
    }

    override fun stop() {
        shouldRunWhenRadiosReady = false
        isStarted = false
        maintenanceJob?.cancel()
        maintenanceJob = null
        discoveredEndpoints.clear()
        pendingEndpoints.clear()
        connectingEndpoints.clear()
        retryCounts.clear()
        lastAttemptAt.clear()
        consecutiveSendFailures.clear()
        awaitingPayloadOutcomes.values.forEach { it.second.complete(false) }
        awaitingPayloadOutcomes.clear()
        _directLinks.value = emptyMap()
        _state.value = TransportState(TransportStatus.STOPPED, "Mesh stopped")
        log("MESH", "Mesh transport stopped")
        scope.launch {
            runCatching {
                connectionsClient.stopAdvertising()
                connectionsClient.stopDiscovery()
                connectionsClient.stopAllEndpoints()
            }
        }
    }

    override suspend fun sendToEndpoint(endpointId: String, bytes: ByteArray): Boolean {
        if (!isStarted || !_directLinks.value.containsKey(endpointId)) return false
        if (bytes.size > MAX_FRAME_BYTES) {
            log("ERR", "Frame (${bytes.size} B) exceeds Nearby limit ($MAX_FRAME_BYTES B)")
            return false
        }

        val payload = Payload.fromBytes(bytes)
        val outcome = CompletableDeferred<Boolean>()
        awaitingPayloadOutcomes[payload.id] = endpointId to outcome

        val binderAccepted = withTimeoutOrNull(SEND_DELIVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    connectionsClient.sendPayload(endpointId, payload)
                        .addOnSuccessListener {
                            if (cont.isActive) cont.resume(true)
                        }
                        .addOnFailureListener { e ->
                            awaitingPayloadOutcomes.remove(payload.id)
                            outcome.complete(false)
                            val code = (e as? ApiException)?.statusCode
                            val hard = code == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN ||
                                code == ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL
                            recordSendFailure(endpointId, "sendPayload code=$code: ${e.message}", hardReset = hard)
                            if (cont.isActive) cont.resume(false)
                        }
                } catch (e: Exception) {
                    awaitingPayloadOutcomes.remove(payload.id)
                    outcome.complete(false)
                    recordSendFailure(endpointId, "sendPayload exception: ${e.message}", hardReset = false)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

        if (binderAccepted != true) {
            awaitingPayloadOutcomes.remove(payload.id)
            if (binderAccepted == null) {
                recordSendFailure(endpointId, "sendPayload binder timeout", hardReset = false)
            }
            return false
        }

        // Wait for over-the-air PayloadTransferUpdate terminal outcome so dead links are detected immediately
        val delivered = withTimeoutOrNull(SEND_DELIVERY_TIMEOUT_MS) { outcome.await() }
        if (delivered == null) {
            awaitingPayloadOutcomes.remove(payload.id)
            recordSendFailure(endpointId, "payload delivery ack timeout", hardReset = false)
            return false
        }
        return delivered
    }

    override suspend fun sendFastUnreliable(endpointId: String, bytes: ByteArray): Boolean {
        if (!isStarted || !_directLinks.value.containsKey(endpointId)) return false
        if (bytes.size > MAX_FRAME_BYTES) return false
        val payload = Payload.fromBytes(bytes)
        return runCatching {
            val result = withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine { cont ->
                    connectionsClient.sendPayload(endpointId, payload)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                        .addOnFailureListener { e ->
                            val code = (e as? ApiException)?.statusCode
                            if (code == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN) {
                                recordSendFailure(endpointId, "voice send ENDPOINT_UNKNOWN", hardReset = true)
                            }
                            if (cont.isActive) cont.resume(false)
                        }
                }
            }
            result == true
        }.getOrDefault(false)
    }

    private fun isBluetoothEnabled(): Boolean {
        return runCatching {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.isEnabled ?: true
        }.getOrDefault(true)
    }

    private fun isWifiEnabled(): Boolean {
        return runCatching {
            val mgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            mgr?.isWifiEnabled ?: true
        }.getOrDefault(true)
    }

    override suspend fun broadcastBytes(bytes: ByteArray, excludeEndpointId: String?): Int {
        val targets = _directLinks.value.keys.filter { it != excludeEndpointId }
        var sentCount = 0
        for (ep in targets) {
            if (sendToEndpoint(ep, bytes)) sentCount++
        }
        return sentCount
    }

    private fun parseEndpointName(raw: String, fallbackId: String): Pair<String, String> {
        val idx = raw.indexOf('|')
        return if (idx > 0 && idx < raw.length - 1) {
            raw.substring(0, idx) to raw.substring(idx + 1)
        } else {
            fallbackId to raw.ifBlank { "Peer-${fallbackId.take(4)}" }
        }
    }

    private fun log(tag: String, message: String) {
        val current = _diagnosticLogs.value
        val next = (listOf(MeshLogEntry(tag = tag, message = message)) + current).take(MAX_LOG_ENTRIES)
        _diagnosticLogs.value = next
    }

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needed = requiredNearbyPermissions()
        return needed.all { perm ->
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requiredNearbyPermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            else -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
}
