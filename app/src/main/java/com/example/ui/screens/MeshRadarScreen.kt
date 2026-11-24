package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.PeerEntity
import com.example.mesh.location.CompassHeadingState
import com.example.mesh.location.LocationFixStatus
import com.example.mesh.location.OfflineGeoPoint
import com.example.mesh.location.RadarGeoMath
import com.example.mesh.router.MeshStatsUi
import com.example.mesh.router.RouteEntry
import com.example.mesh.transport.TransportState
import com.example.mesh.transport.TransportStatus
import com.example.ui.components.PeerAvatar
import com.example.ui.components.TrustBadge
import com.example.ui.components.isolateBidi
import com.example.ui.theme.LocalLanChatColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshRadarScreen(
    localNodeId: String,
    localName: String,
    peers: List<PeerEntity>,
    routes: Map<String, RouteEntry>,
    stats: MeshStatsUi,
    pendingDtnCount: Int,
    transportState: TransportState,
    compassHeading: CompassHeadingState = CompassHeadingState(),
    localLocation: OfflineGeoPoint? = null,
    locationFixStatus: LocationFixStatus = LocationFixStatus.OFF,
    shareLocationOnRadar: Boolean = false,
    onToggleShareLocation: (Boolean) -> Unit = {},
    onRefreshDiscovery: () -> Unit,
    onMessagePeer: (String) -> Unit,
    onCallPeer: (String) -> Unit = {},
    onVerifyPeer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalLanChatColors.current
    val context = LocalContext.current
    val isArabic = Locale.getDefault().language == "ar"
    var inspectedPeer by remember { mutableStateOf<PeerEntity?>(null) }
    var pendingCallPeerId by remember { mutableStateOf<String?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onToggleShareLocation(granted)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val targetId = pendingCallPeerId
        pendingCallPeerId = null
        if (granted && targetId != null) {
            onCallPeer(targetId)
        }
    }

    fun launchCallWithPermission(peerId: String) {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            onCallPeer(peerId)
        } else {
            pendingCallPeerId = peerId
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun handleGpsToggleClick() {
        if (shareLocationOnRadar) {
            onToggleShareLocation(false)
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                onToggleShareLocation(true)
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    val topSubtitle = when (transportState.status) {
        TransportStatus.ACTIVE -> stringResource(R.string.mesh_status_active)
        TransportStatus.STARTING -> stringResource(R.string.mesh_status_starting)
        TransportStatus.PERMISSION_REQUIRED -> stringResource(R.string.mesh_status_permission_needed)
        TransportStatus.ERROR -> stringResource(R.string.mesh_status_error_retry)
        TransportStatus.STOPPED -> stringResource(R.string.mesh_status_paused)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.mesh_radar_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = topSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshDiscovery,
                        modifier = Modifier.testTag("mesh_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.mesh_scanning_title)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!transportState.bluetoothEnabled || !transportState.wifiEnabled) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (!transportState.bluetoothEnabled) Icons.Filled.BluetoothDisabled else Icons.Filled.WifiOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                val btOff = stringResource(R.string.mesh_radar_bt_off)
                                val wifiOff = stringResource(R.string.mesh_radar_wifi_off)
                                val enableBoth = stringResource(R.string.mesh_radar_enable_radios)
                                Text(
                                    text = buildString {
                                        if (!transportState.bluetoothEnabled) append(btOff)
                                        if (!transportState.wifiEnabled) append(wifiOff)
                                        append(enableBoth)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!transportState.bluetoothEnabled) {
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                            }
                                        }
                                    ) { Text(stringResource(R.string.mesh_radar_bt_settings)) }
                                }
                                if (!transportState.wifiEnabled) {
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                            }
                                        }
                                    ) { Text(stringResource(R.string.mesh_radar_wifi_settings)) }
                                }
                            }
                        }
                    }
                }
            }

            item {
                MeshTopologyRadarCard(
                    localName = localName,
                    localNodeId = localNodeId,
                    routes = routes.values.toList(),
                    peers = peers,
                    compassHeading = compassHeading,
                    localLocation = localLocation,
                    locationFixStatus = locationFixStatus,
                    shareLocationOnRadar = shareLocationOnRadar,
                    onToggleShareLocation = { handleGpsToggleClick() },
                    onInspectNode = { nodeId ->
                        inspectedPeer = peers.find { it.nodeId == nodeId }
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        label = stringResource(R.string.mesh_metric_direct),
                        value = stats.directPeerCount.toString(),
                        accent = ext.directGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = stringResource(R.string.mesh_metric_reachable),
                        value = stats.reachablePeerCount.toString(),
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = stringResource(R.string.mesh_metric_relayed),
                        value = stats.relayedPacketCount.toString(),
                        accent = ext.multiHopAmber,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = stringResource(R.string.mesh_metric_dtn),
                        value = pendingDtnCount.toString(),
                        accent = ext.dtnIndigo,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.mesh_discovered_nodes, peers.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (peers.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.mesh_scanning_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.mesh_scanning_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(peers, key = { it.nodeId }, contentType = { "peer" }) { peer ->
                    val geoSubtitle = if (localLocation != null && peer.latitude != null && peer.longitude != null) {
                        val dist = RadarGeoMath.distanceMeters(
                            localLocation.latitude,
                            localLocation.longitude,
                            peer.latitude,
                            peer.longitude
                        )
                        val brg = RadarGeoMath.bearingDegrees(
                            localLocation.latitude,
                            localLocation.longitude,
                            peer.latitude,
                            peer.longitude
                        )
                        val dir = localizedCardinal8(brg)
                        " · ${RadarGeoMath.formatDistance(dist, isArabic)} ($dir)"
                    } else if (peer.latitude != null && peer.longitude != null) {
                        " · ${stringResource(R.string.mesh_radar_peer_gps_ready)}"
                    } else ""

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .clickable { inspectedPeer = peer }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PeerAvatar(
                                title = peer.displayName,
                                trustStatus = peer.trustStatus,
                                isOnline = peer.isDirectlyConnected,
                                size = 46.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = peer.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    TrustBadge(trustStatus = peer.trustStatus, compact = true)
                                }
                                Spacer(Modifier.height(3.dp))
                                val hopInfo = when {
                                    peer.isDirectlyConnected -> stringResource(R.string.mesh_peer_direct_info, peer.linkQuality)
                                    peer.bestHopCount < 20 -> stringResource(
                                        R.string.mesh_peer_multihop_info,
                                        peer.bestHopCount,
                                        peer.nextHopNodeId?.take(6) ?: ""
                                    )
                                    else -> stringResource(R.string.mesh_peer_offline_info)
                                }
                                Text(
                                    text = "${isolateBidi(peer.nodeId.take(8))} · $hopInfo$geoSubtitle",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (routes.containsKey(peer.nodeId)) {
                                IconButton(
                                    onClick = { launchCallWithPermission(peer.nodeId) },
                                    modifier = Modifier.testTag("mesh_peer_call_${peer.nodeId.take(6)}")
                                ) {
                                    Icon(
                                        Icons.Filled.Call,
                                        contentDescription = stringResource(R.string.action_call),
                                        tint = ext.directGreen
                                    )
                                }
                            }
                            IconButton(onClick = { onVerifyPeer(peer.nodeId) }) {
                                Icon(Icons.Filled.QrCode2, contentDescription = stringResource(R.string.action_verify_qr))
                            }
                            IconButton(onClick = { onMessagePeer(peer.nodeId) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = stringResource(R.string.action_message),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    inspectedPeer?.let { peer ->
        val route = routes[peer.nodeId]
        val pLat = route?.latitude ?: peer.latitude
        val pLon = route?.longitude ?: peer.longitude
        val geoLocationText = if (localLocation != null && pLat != null && pLon != null) {
            val dist = RadarGeoMath.distanceMeters(localLocation.latitude, localLocation.longitude, pLat, pLon)
            val brg = RadarGeoMath.bearingDegrees(localLocation.latitude, localLocation.longitude, pLat, pLon)
            stringResource(
                R.string.mesh_sheet_distance,
                RadarGeoMath.formatDistance(dist, isArabic),
                "${brg.roundToInt()}° ${localizedCardinal8(brg)}"
            )
        } else if (pLat == null || pLon == null) {
            stringResource(R.string.mesh_radar_peer_no_gps)
        } else {
            stringResource(R.string.mesh_radar_peer_gps_ready)
        }

        ModalBottomSheet(onDismissRequest = { inspectedPeer = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PeerAvatar(
                        title = peer.displayName,
                        trustStatus = peer.trustStatus,
                        isOnline = peer.isDirectlyConnected,
                        size = 52.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.mesh_sheet_node_id, isolateBidi(peer.nodeId)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TrustBadge(trustStatus = peer.trustStatus, compact = true)
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when {
                                route != null && route.hopCount == 1 -> stringResource(R.string.mesh_sheet_route_direct, route.linkQuality)
                                route != null -> stringResource(R.string.mesh_sheet_route_multihop, route.hopCount, route.nextHopNodeId.take(8), route.linkQuality)
                                else -> stringResource(R.string.mesh_sheet_route_offline)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = geoLocationText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.mesh_sheet_encryption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val id = peer.nodeId
                            inspectedPeer = null
                            onVerifyPeer(id)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_verify), maxLines = 1)
                    }
                    if (route != null) {
                        OutlinedButton(
                            onClick = {
                                val id = peer.nodeId
                                inspectedPeer = null
                                launchCallWithPermission(id)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mesh_sheet_call_button")
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp), tint = ext.directGreen)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_call), maxLines = 1)
                        }
                    }
                    Button(
                        onClick = {
                            val id = peer.nodeId
                            inspectedPeer = null
                            onMessagePeer(id)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_message), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private enum class RadarMarkerKind {
    DIRECT,
    MULTI_HOP,
    LAST_KNOWN_GPS
}

private data class RadarPlotNode(
    val nodeId: String,
    val displayName: String,
    val hopCount: Int,
    val nextHopNodeId: String,
    val latitude: Double?,
    val longitude: Double?,
    val kind: RadarMarkerKind
)

@Composable
private fun MeshTopologyRadarCard(
    localName: String,
    localNodeId: String,
    routes: List<RouteEntry>,
    peers: List<PeerEntity>,
    compassHeading: CompassHeadingState,
    localLocation: OfflineGeoPoint?,
    locationFixStatus: LocationFixStatus,
    shareLocationOnRadar: Boolean,
    onToggleShareLocation: () -> Unit,
    onInspectNode: (String) -> Unit
) {
    val ext = LocalLanChatColors.current
    val isArabic = Locale.getDefault().language == "ar"
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val cardinalStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )

    val labelNorth = stringResource(R.string.mesh_compass_n)
    val labelEast = stringResource(R.string.mesh_compass_e)
    val labelSouth = stringResource(R.string.mesh_compass_s)
    val labelWest = stringResource(R.string.mesh_compass_w)

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var showGrid by remember { mutableStateOf(true) }

    val transition = rememberInfiniteTransition(label = "radarSweep")
    val sweepPulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseRadius"
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
    val dtnColor = ext.dtnIndigo

    val peerMap = remember(peers) { peers.associateBy { it.nodeId } }
    val plotNodes = remember(routes, peers, localLocation) {
        val activeIds = routes.map { it.targetNodeId }.toHashSet()
        val list = routes.map { r ->
            val p = peerMap[r.targetNodeId]
            RadarPlotNode(
                nodeId = r.targetNodeId,
                displayName = r.displayName,
                hopCount = r.hopCount,
                nextHopNodeId = r.nextHopNodeId,
                latitude = r.latitude ?: p?.latitude,
                longitude = r.longitude ?: p?.longitude,
                kind = if (r.hopCount == 1) RadarMarkerKind.DIRECT else RadarMarkerKind.MULTI_HOP
            )
        }.toMutableList()
        if (localLocation != null) {
            peers.forEach { p ->
                if (!activeIds.contains(p.nodeId) && p.latitude != null && p.longitude != null) {
                    list.add(
                        RadarPlotNode(
                            nodeId = p.nodeId,
                            displayName = p.displayName,
                            hopCount = p.bestHopCount,
                            nextHopNodeId = localNodeId,
                            latitude = p.latitude,
                            longitude = p.longitude,
                            kind = RadarMarkerKind.LAST_KNOWN_GPS
                        )
                    )
                }
            }
        }
        list
    }

    val gpsStatusSubtitle = when {
        !shareLocationOnRadar -> stringResource(R.string.mesh_radar_compass_only)
        locationFixStatus == LocationFixStatus.LOCKED_GPS && localLocation != null ->
            stringResource(R.string.mesh_radar_gps_locked, localLocation.accuracyMeters.roundToInt().coerceAtLeast(1))
        locationFixStatus == LocationFixStatus.LOCKED_CACHED && localLocation != null ->
            stringResource(R.string.mesh_radar_gps_cached, localLocation.accuracyMeters.roundToInt().coerceAtLeast(5))
        else -> stringResource(R.string.mesh_radar_gps_waiting)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mesh_radar_canvas_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.mesh_radar_compass_active,
                                compassHeading.azimuthDegrees.roundToInt(),
                                localizedCardinal8(compassHeading.azimuthDegrees)
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = gpsStatusSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                FilterChip(
                    selected = shareLocationOnRadar,
                    onClick = onToggleShareLocation,
                    label = {
                        Text(
                            text = if (shareLocationOnRadar) {
                                stringResource(R.string.mesh_radar_gps_share_on)
                            } else {
                                stringResource(R.string.mesh_radar_gps_share_off)
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (shareLocationOnRadar) Icons.Filled.MyLocation else Icons.Filled.LocationDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    modifier = Modifier.testTag("mesh_gps_toggle")
                )
            }

            // Interactive compass-locked & pannable/zoomable radar coordinate map
            val renderedPositions = remember { HashMap<String, Offset>() }
            val compassState = rememberUpdatedState(compassHeading)
            val plotNodesState = rememberUpdatedState(plotNodes)
            val localLocationState = rememberUpdatedState(localLocation)
            val localNodeIdState = rememberUpdatedState(localNodeId)
            val onInspectNodeState = rememberUpdatedState(onInspectNode)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(276.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(0.6f, 4.0f)
                            val maxPan = 140f * zoomScale
                            panOffset = Offset(
                                x = (panOffset.x + pan.x).coerceIn(-maxPan, maxPan),
                                y = (panOffset.y + pan.y).coerceIn(-maxPan, maxPan)
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val myId = localNodeIdState.value
                            val hit = renderedPositions.entries.firstOrNull { (id, pos) ->
                                id != myId && hypot(tap.x - pos.x, tap.y - pos.y) <= 44f
                            }
                            if (hit != null) {
                                onInspectNodeState.value(hit.key)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val activeCompass = compassState.value
                            val activeNodes = plotNodesState.value
                            val activeLocalLoc = localLocationState.value
                            val activeLocalNodeId = localNodeIdState.value

                            val center = Offset(size.width / 2f + panOffset.x, size.height / 2f + panOffset.y)
                            val baseR = minOf(size.width, size.height) * 0.40f
                            val maxR = baseR * zoomScale
                            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                            val ringStroke = Stroke(width = 1.2.dp.toPx(), pathEffect = dashEffect)
                            val edgeWidth = 2.2.dp.toPx()

                            val azimuth = activeCompass.azimuthDegrees
                            val cardinalMarks = listOf(
                                Triple(labelNorth, 0f, true),
                                Triple(labelEast, 90f, false),
                                Triple(labelSouth, 180f, false),
                                Triple(labelWest, 270f, false)
                            ).map { (text, deg, isNorth) ->
                                val rad = RadarGeoMath.relativeCanvasAngleRad(deg, azimuth)
                                val r = maxR + 14.dp.toPx()
                                val style = if (isNorth) cardinalStyle else labelStyle
                                val measured = textMeasurer.measure(text, style = style)
                                val x = center.x + (r * cos(rad)).toFloat() - measured.size.width / 2f
                                val y = center.y + (r * sin(rad)).toFloat() - measured.size.height / 2f
                                Pair(measured, Offset(x, y))
                            }

                            val positions = HashMap<String, Offset>(activeNodes.size + 1)
                            positions[activeLocalNodeId] = center

                            val ungeoDirect = activeNodes.filter {
                                it.kind == RadarMarkerKind.DIRECT &&
                                    (activeLocalLoc == null || it.latitude == null || it.longitude == null)
                            }
                            val ungeoMulti = activeNodes.filter {
                                it.kind == RadarMarkerKind.MULTI_HOP &&
                                    (activeLocalLoc == null || it.latitude == null || it.longitude == null)
                            }

                            val labelsToDraw = mutableListOf<Triple<RadarPlotNode, androidx.compose.ui.text.TextLayoutResult, Offset>>()

                            activeNodes.forEach { node ->
                                val hasGeo = activeLocalLoc != null && node.latitude != null && node.longitude != null
                                val pos = if (hasGeo) {
                                    val dist = RadarGeoMath.distanceMeters(
                                        activeLocalLoc!!.latitude,
                                        activeLocalLoc.longitude,
                                        node.latitude!!,
                                        node.longitude!!
                                    )
                                    val bearing = RadarGeoMath.bearingDegrees(
                                        activeLocalLoc.latitude,
                                        activeLocalLoc.longitude,
                                        node.latitude,
                                        node.longitude
                                    )
                                    val angleRad = RadarGeoMath.relativeCanvasAngleRad(bearing, azimuth)
                                    val r = baseR * RadarGeoMath.normalizedDistanceRadius(dist, zoomScale)
                                    Offset(
                                        center.x + (r * cos(angleRad)).toFloat(),
                                        center.y + (r * sin(angleRad)).toFloat()
                                    )
                                } else {
                                    // Anchor signal-only nodes to true-North sector bearings so they rotate
                                    // dynamically with the phone's compass rather than freezing on screen.
                                    if (node.kind == RadarMarkerKind.DIRECT) {
                                        val idx = ungeoDirect.indexOf(node).coerceAtLeast(0)
                                        val sectorDeg = RadarGeoMath.signalOnlySectorBearingDeg(node.nodeId, idx, ungeoDirect.size)
                                        val angleRad = RadarGeoMath.relativeCanvasAngleRad(sectorDeg, azimuth)
                                        val r = maxR * 0.44f
                                        Offset(center.x + (r * cos(angleRad)).toFloat(), center.y + (r * sin(angleRad)).toFloat())
                                    } else {
                                        val idx = ungeoMulti.indexOf(node).coerceAtLeast(0)
                                        val sectorDeg = RadarGeoMath.signalOnlySectorBearingDeg(node.nodeId, idx, ungeoMulti.size)
                                        val angleRad = RadarGeoMath.relativeCanvasAngleRad(sectorDeg, azimuth)
                                        val r = maxR * (0.72f + (node.hopCount.coerceAtMost(4) - 2).coerceAtLeast(0) * 0.12f)
                                        Offset(center.x + (r * cos(angleRad)).toFloat(), center.y + (r * sin(angleRad)).toFloat())
                                    }
                                }
                                positions[node.nodeId] = pos

                                val shortLabel = if (hasGeo) {
                                    val dist = RadarGeoMath.distanceMeters(
                                        activeLocalLoc!!.latitude,
                                        activeLocalLoc.longitude,
                                        node.latitude!!,
                                        node.longitude!!
                                    )
                                    "${node.displayName.take(9)} · ${RadarGeoMath.formatDistance(dist, isArabic)}"
                                } else {
                                    "${node.displayName.take(10)} (${node.hopCount}h)"
                                }
                                val measured = textMeasurer.measure(shortLabel, style = labelStyle)
                                val labelX = (pos.x - measured.size.width / 2f)
                                    .coerceIn(4f, (size.width - measured.size.width - 4f).coerceAtLeast(4f))
                                val labelY = (pos.y + 10.dp.toPx())
                                    .coerceIn(4f, (size.height - measured.size.height - 4f).coerceAtLeast(4f))
                                labelsToDraw.add(Triple(node, measured, Offset(labelX, labelY)))
                            }

                            renderedPositions.clear()
                            renderedPositions.putAll(positions)

                            onDrawBehind {
                                // Compass-aligned offline coordinate grid
                                if (showGrid) {
                                    val gridSpacing = (baseR * 0.35f * zoomScale).coerceAtLeast(22.dp.toPx())
                                    val northRad = RadarGeoMath.relativeCanvasAngleRad(0f, azimuth)
                                    val eastRad = RadarGeoMath.relativeCanvasAngleRad(90f, azimuth)
                                    val nVec = Offset(cos(northRad).toFloat(), sin(northRad).toFloat())
                                    val eVec = Offset(cos(eastRad).toFloat(), sin(eastRad).toFloat())
                                    val extent = maxOf(size.width, size.height) * 1.4f
                                    for (step in -6..6) {
                                        val offsetAlongE = eVec * (step * gridSpacing)
                                        drawLine(
                                            color = gridColor,
                                            start = center + offsetAlongE - nVec * extent,
                                            end = center + offsetAlongE + nVec * extent,
                                            strokeWidth = 1.dp.toPx()
                                        )
                                        val offsetAlongN = nVec * (step * gridSpacing)
                                        drawLine(
                                            color = gridColor,
                                            start = center + offsetAlongN - eVec * extent,
                                            end = center + offsetAlongN + eVec * extent,
                                            strokeWidth = 1.dp.toPx()
                                        )
                                    }
                                }

                                // Forward heading cone showing where top of phone points
                                drawArc(
                                    brush = Brush.radialGradient(
                                        colors = listOf(primary.copy(alpha = 0.22f), Color.Transparent),
                                        center = center,
                                        radius = maxR
                                    ),
                                    startAngle = -112f,
                                    sweepAngle = 44f,
                                    useCenter = true,
                                    topLeft = Offset(center.x - maxR, center.y - maxR),
                                    size = Size(maxR * 2f, maxR * 2f)
                                )

                                // Concentric distance / hop rings
                                drawCircle(color = ringColor, radius = maxR * 0.38f, center = center, style = ringStroke)
                                drawCircle(color = ringColor, radius = maxR * 0.68f, center = center, style = ringStroke)
                                drawCircle(color = ringColor, radius = maxR, center = center, style = ringStroke)

                                // Compass azimuth ticks every 30 degrees
                                for (deg in 0 until 360 step 30) {
                                    val rad = RadarGeoMath.relativeCanvasAngleRad(deg.toFloat(), azimuth)
                                    val tickInner = maxR - if (deg % 90 == 0) 7.dp.toPx() else 4.dp.toPx()
                                    drawLine(
                                        color = if (deg == 0) primary else ringColor,
                                        start = Offset(center.x + (tickInner * cos(rad)).toFloat(), center.y + (tickInner * sin(rad)).toFloat()),
                                        end = Offset(center.x + (maxR * cos(rad)).toFloat(), center.y + (maxR * sin(rad)).toFloat()),
                                        strokeWidth = if (deg == 0) 2.dp.toPx() else 1.dp.toPx()
                                    )
                                }

                                // Rotating compass N/E/S/W labels
                                cardinalMarks.forEach { (measured, offset) ->
                                    drawText(textLayoutResult = measured, topLeft = offset)
                                }

                                // Mesh topology links for active routes
                                activeNodes.forEach { node ->
                                    if (node.kind == RadarMarkerKind.LAST_KNOWN_GPS) return@forEach
                                    val targetPos = positions[node.nodeId] ?: return@forEach
                                    val parentPos = positions[node.nextHopNodeId] ?: center
                                    val startPos = if (node.hopCount == 1) center else parentPos
                                    drawLine(
                                        color = if (node.kind == RadarMarkerKind.DIRECT) {
                                            ext.directGreen.copy(alpha = 0.7f)
                                        } else {
                                            ext.multiHopAmber.copy(alpha = 0.7f)
                                        },
                                        start = startPos,
                                        end = targetPos,
                                        strokeWidth = edgeWidth
                                    )
                                }

                                // Peer markers and labels
                                labelsToDraw.forEach { (node, measured, topLeft) ->
                                    val pos = positions[node.nodeId] ?: return@forEach
                                    val nodeColor = when (node.kind) {
                                        RadarMarkerKind.DIRECT -> ext.directGreen
                                        RadarMarkerKind.MULTI_HOP -> ext.multiHopAmber
                                        RadarMarkerKind.LAST_KNOWN_GPS -> dtnColor
                                    }
                                    drawCircle(color = Color.White, radius = 8.5.dp.toPx(), center = pos)
                                    drawCircle(color = nodeColor, radius = 6.dp.toPx(), center = pos)
                                    drawText(textLayoutResult = measured, topLeft = topLeft)
                                }

                                // Local node center dot
                                drawCircle(color = primary.copy(alpha = 0.2f), radius = 17.dp.toPx(), center = center)
                                drawCircle(color = primary, radius = 9.5.dp.toPx(), center = center)
                                drawCircle(color = secondary, radius = 4.dp.toPx(), center = center)
                            }
                        }
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = panOffset.x
                            translationY = panOffset.y
                            scaleX = sweepPulse * zoomScale
                            scaleY = sweepPulse * zoomScale
                            alpha = (1f - sweepPulse) * 0.28f
                        }
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = minOf(size.width, size.height) * 0.40f
                    drawCircle(
                        color = primary,
                        radius = maxR,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Dynamic range scale badge (top-start)
                val visibleMeters = RadarGeoMath.visibleRangeMeters(zoomScale)
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.mesh_radar_scale_label,
                            RadarGeoMath.formatDistance(visibleMeters, isArabic)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Interactive map controls (+, -, Re-center, Grid) as a sleek horizontal pill bar at bottom-end with 48dp touch targets
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { zoomScale = (zoomScale * 1.35f).coerceAtMost(4.0f) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("radar_zoom_in")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.mesh_radar_zoom_in))
                        }
                        IconButton(
                            onClick = { zoomScale = (zoomScale / 1.35f).coerceAtLeast(0.6f) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("radar_zoom_out")
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.mesh_radar_zoom_out))
                        }
                        IconButton(
                            onClick = {
                                panOffset = Offset.Zero
                                zoomScale = 1f
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("radar_recenter")
                        ) {
                            Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.mesh_radar_recenter))
                        }
                        IconButton(
                            onClick = { showGrid = !showGrid },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("radar_grid_toggle")
                        ) {
                            Icon(
                                imageVector = if (showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
                                contentDescription = stringResource(R.string.mesh_radar_toggle_grid),
                                tint = if (showGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.mesh_radar_you_label,
                        isolateBidi(localName),
                        isolateBidi(localNodeId.take(6))
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LegendDot(stringResource(R.string.mesh_radar_legend_1hop), ext.directGreen)
                    LegendDot(stringResource(R.string.mesh_radar_legend_multihop), ext.multiHopAmber)
                }
            }
        }
    }
}

@Composable
private fun localizedCardinal8(bearingDeg: Float): String {
    val n = stringResource(R.string.mesh_compass_n)
    val e = stringResource(R.string.mesh_compass_e)
    val s = stringResource(R.string.mesh_compass_s)
    val w = stringResource(R.string.mesh_compass_w)
    return when (RadarGeoMath.cardinalIndex8(bearingDeg)) {
        0 -> n
        1 -> "$n$e"
        2 -> e
        3 -> "$s$e"
        4 -> s
        5 -> "$s$w"
        6 -> w
        else -> "$n$w"
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = RoundedCornerShape(4.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
