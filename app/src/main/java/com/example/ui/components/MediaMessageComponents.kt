package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.TransferStatus
import com.example.media.RecordingState
import com.example.media.VoicePlaybackState
import com.example.mesh.router.TransferProgressUi
import java.io.File
import java.util.Locale

@Composable
fun VoiceNoteBubble(
    messageId: String,
    localPath: String?,
    durationMs: Long,
    waveformSamples: List<Int>,
    playbackState: VoicePlaybackState,
    transferProgress: TransferProgressUi?,
    onTogglePlay: (String, String) -> Unit,
    onSeek: (String, Float) -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isThisPlaying = playbackState.activeMessageId == messageId && playbackState.isPlaying
    val isThisActive = playbackState.activeMessageId == messageId
    val progress = if (isThisActive) playbackState.progressFraction else 0f
    val bars = remember(waveformSamples) {
        if (waveformSamples.isEmpty()) List(32) { 28 } else waveformSamples
    }

    Column(modifier = modifier.widthIn(min = 210.dp, max = 270.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val fileReady = remember(localPath, transferProgress?.status) {
                !localPath.isNullOrBlank() && File(localPath).exists()
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(44.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("voice_play_button_$messageId")
                    .clip(CircleShape)
                    .clickable(enabled = fileReady) {
                        if (localPath != null) onTogglePlay(messageId, localPath)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (!fileReady && transferProgress != null && transferProgress.status == TransferStatus.ACTIVE) {
                        CircularProgressIndicator(
                            progress = { transferProgress.progressFraction },
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isThisPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isThisPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                val activeColor = MaterialTheme.colorScheme.primary
                val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .pointerInput(messageId, isThisActive) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeek(messageId, fraction)
                            }
                        }
                ) {
                    val count = bars.size
                    val slotWidth = size.width / count.toFloat()
                    val barWidth = (slotWidth * 0.62f).coerceAtLeast(2.dp.toPx())
                    bars.forEachIndexed { idx, amp ->
                        val norm = (amp / 100f).coerceIn(0.14f, 1f)
                        val barH = size.height * norm
                        val top = (size.height - barH) / 2f
                        val barFraction = idx.toFloat() / count.toFloat()
                        drawRoundRect(
                            color = if (barFraction <= progress) activeColor else inactiveColor,
                            topLeft = Offset(idx * slotWidth, top),
                            size = Size(barWidth, barH),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayMs = if (isThisActive && playbackState.positionMs > 0L) {
                        playbackState.positionMs
                    } else {
                        durationMs
                    }
                    Text(
                        text = formatDurationMs(displayMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isThisActive) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onCycleSpeed)
                        ) {
                            Text(
                                text = "${playbackState.speed}x",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        if (transferProgress != null && transferProgress.status != TransferStatus.COMPLETED) {
            Spacer(Modifier.height(6.dp))
            TransferProgressOverlayBar(
                progress = transferProgress,
                onPause = {},
                onResume = {},
                onCancel = {},
                compact = true
            )
        }
    }
}

@Composable
fun ImageMessageBubble(
    localPath: String?,
    microThumbBase64: String?,
    totalBytes: Long,
    transferProgress: TransferProgressUi?,
    onPauseTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onOpenLightbox: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fileExists = remember(localPath, transferProgress?.status, transferProgress?.bytesTransferred) {
        !localPath.isNullOrBlank() && File(localPath).exists() && File(localPath).length() > 0L
    }
    val thumbBitmap = remember(microThumbBase64) {
        microThumbBase64?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(modifier = modifier.widthIn(min = 210.dp, max = 260.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = fileExists) {
                    if (localPath != null) onOpenLightbox(localPath)
                },
            contentAlignment = Alignment.Center
        ) {
            if (fileExists) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(localPath!!))
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.chat_send_photo_title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(12.dp)
                )
            }

            if (transferProgress != null && transferProgress.status != TransferStatus.COMPLETED) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TransferProgressOverlayBar(
                        progress = transferProgress,
                        onPause = { onPauseTransfer(transferProgress.transferId) },
                        onResume = { onResumeTransfer(transferProgress.transferId) },
                        onCancel = { onCancelTransfer(transferProgress.transferId) },
                        compact = false
                    )
                }
            } else if (!fileExists && totalBytes > 0L) {
                Text(
                    text = stringResource(R.string.chat_awaiting_chunks, formatBytes(totalBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TransferProgressOverlayBar(
    progress: TransferProgressUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    compact: Boolean
) {
    val pct = (progress.progressFraction * 100f).toInt()
    val textColor = if (compact) MaterialTheme.colorScheme.onSurface else Color.White

    Surface(
        color = if (compact) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transfer_progress_${progress.transferId}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (progress.status) {
                        TransferStatus.PAUSED -> stringResource(R.string.chat_transfer_paused, pct)
                        TransferStatus.FAILED -> progress.errorReason ?: stringResource(R.string.chat_transfer_failed)
                        TransferStatus.PENDING -> stringResource(R.string.chat_transfer_queued, pct)
                        else -> "$pct% · ${formatBytes(progress.bytesTransferred)} / ${formatBytes(progress.totalBytes)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                if (!compact) {
                    Row {
                        if (progress.status == TransferStatus.ACTIVE) {
                            IconButton(onClick = onPause, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        } else if (progress.status == TransferStatus.PAUSED || progress.status == TransferStatus.FAILED || progress.status == TransferStatus.PENDING) {
                            IconButton(onClick = onResume, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = textColor.copy(alpha = 0.25f)
            )
            if (progress.speedBytesPerSec > 0L && progress.status == TransferStatus.ACTIVE) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.chat_transfer_chunk_speed,
                        formatBytes(progress.speedBytesPerSec),
                        progress.chunksAcked,
                        progress.totalChunks
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
fun VoiceRecordingBar(
    recordingState: RecordingState,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "recPulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "recAlpha"
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_recording_bar")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.testTag("voice_cancel_button")
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = MaterialTheme.colorScheme.error)
            }

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = alpha))
            )

            Text(
                text = formatDurationMs(recordingState.elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            val bars = recordingState.liveAmplitudes.takeLast(24)
            val barColor = MaterialTheme.colorScheme.primary
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
            ) {
                val count = 24
                val slotW = size.width / count
                val w = (slotW * 0.58f).coerceAtLeast(2.dp.toPx())
                for (i in 0 until count) {
                    val amp = bars.getOrNull(i) ?: 15
                    val h = size.height * (amp / 100f).coerceIn(0.15f, 1f)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(i * slotW, (size.height - h) / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w / 2f, w / 2f)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier
                    .size(44.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("voice_send_button")
                    .clip(CircleShape)
                    .clickable(onClick = onSend)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.action_send),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ImageLightboxDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(imagePath))
                    .build(),
                contentDescription = stringResource(R.string.chat_send_photo_title),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = transformState)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}

fun formatBytes(bytes: Long): String {
    val isArabic = Locale.getDefault().language == "ar"
    return when {
        bytes < 1024L -> if (isArabic) "$bytes بايت" else "$bytes B"
        bytes < 1024L * 1024L -> if (isArabic) "${bytes / 1024L} ك.ب" else "${bytes / 1024L} KB"
        else -> {
            val formatted = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
            if (isArabic) "$formatted م.ب" else "$formatted MB"
        }
    }
}
