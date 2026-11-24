package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.crypto.QrPayloadCodec
import com.example.data.local.LocalIdentityConfig
import com.example.data.local.PeerEntity
import com.example.media.QrCodeAnalyzer
import com.example.ui.components.TrustBadge
import com.example.ui.components.isolateBidi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyPeerScreen(
    identity: LocalIdentityConfig,
    myQrPayload: String,
    targetPeer: PeerEntity?,
    safetyNumber: String?,
    onBack: () -> Unit,
    onQrScanned: (raw: String, onDone: (PeerEntity?) -> Unit) -> Unit,
    onManualVerify: (String) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var scanStatusMessage by remember { mutableStateOf<String?>(null) }
    var scanSuccess by remember { mutableStateOf(false) }
    var copiedFeedback by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("verify_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_chats))
                    }
                },
                title = { Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.verify_tab_my_qr)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.verify_tab_scan_qr)) }
                )
            }

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = myQrPayload) {
                        value = withContext(Dispatchers.Default) {
                            QrPayloadCodec.renderQrBitmap(myQrPayload, 360).asImageBitmap()
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.testTag("my_qr_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier.size(220.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val bmp = qrBitmap
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = stringResource(R.string.verify_tab_my_qr),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = identity.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.mesh_sheet_node_id, isolateBidi(identity.nodeId)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF475569)
                                )
                                TextButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("LanChat Node ID", identity.nodeId))
                                        copiedFeedback = true
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        if (copiedFeedback) {
                                            stringResource(R.string.action_copied)
                                        } else {
                                            stringResource(R.string.action_copy)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (targetPeer != null && safetyNumber != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.verify_safety_number_title, isolateBidi(targetPeer.displayName)),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                TrustBadge(trustStatus = targetPeer.trustStatus)
                                Text(
                                    text = safetyNumber,
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Button(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onManualVerify(targetPeer.nodeId)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.verify_confirm_safety))
                                }
                            }
                        }
                    }
                }
            } else {
                QrScannerTab(
                    scanStatusMessage = scanStatusMessage,
                    scanSuccess = scanSuccess,
                    onRawQrDetected = { raw ->
                        onQrScanned(raw) { verified ->
                            if (verified != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scanSuccess = true
                                scanStatusMessage = context.getString(
                                    R.string.verify_scan_success,
                                    verified.displayName,
                                    verified.nodeId.take(8)
                                )
                            } else {
                                scanSuccess = false
                                scanStatusMessage = context.getString(R.string.verify_scan_invalid)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun QrScannerTab(
    scanStatusMessage: String?,
    scanSuccess: Boolean,
    onRawQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchEnabled by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasCameraPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.verify_camera_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.verify_camera_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.verify_camera_grant))
                    }
                }
            }
        } else {
            val executor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(Unit) {
                onDispose { executor.shutdown() }
            }

            val reticleColor = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(2.dp, reticleColor, RoundedCornerShape(24.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(executor, QrCodeAnalyzer(onRawQrDetected))
                                }
                            runCatching {
                                provider.unbindAll()
                                boundCamera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val boxSize = minOf(size.width, size.height) * 0.56f
                    val left = (size.width - boxSize) / 2f
                    val top = (size.height - boxSize) / 2f
                    val right = left + boxSize
                    val bottom = top + boxSize
                    val len = 26.dp.toPx()
                    val stroke = 4.dp.toPx()
                    val corners = listOf(
                        Offset(left, top) to Offset(left + len, top),
                        Offset(left, top) to Offset(left, top + len),
                        Offset(right, top) to Offset(right - len, top),
                        Offset(right, top) to Offset(right, top + len),
                        Offset(left, bottom) to Offset(left + len, bottom),
                        Offset(left, bottom) to Offset(left, bottom - len),
                        Offset(right, bottom) to Offset(right - len, bottom),
                        Offset(right, bottom) to Offset(right, bottom - len)
                    )
                    corners.forEach { (start, end) ->
                        drawLine(
                            color = reticleColor,
                            start = start,
                            end = end,
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                }

                IconButton(
                    onClick = {
                        torchEnabled = !torchEnabled
                        boundCamera?.cameraControl?.enableTorch(torchEnabled)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }

            Text(
                text = scanStatusMessage ?: stringResource(R.string.verify_scan_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = if (scanSuccess) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }
    }
}
