package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.mesh.call.CallEndReason
import com.example.mesh.call.CallPhase
import com.example.mesh.call.MeshCallManager
import com.example.mesh.call.MeshCallUiState
import com.example.ui.components.PeerAvatar
import com.example.ui.components.TrustBadge
import com.example.ui.theme.LocalLanChatColors

@Composable
fun FullScreenCallOverlay(
    callState: MeshCallUiState,
    onAccept: () -> Unit,
    onDeclineOrEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ext = LocalLanChatColors.current

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onAccept()
        } else {
            onDeclineOrEnd()
        }
    }

    val visible = callState.hasLiveOverlay && !callState.isMinimized

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 6 } + fadeOut(tween(180)),
        modifier = modifier
    ) {
        val pulseTransition = rememberInfiniteTransition(label = "callAura")
        val ringPulse by pulseTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.28f,
            animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "callRingPulse"
        )
        val audioBoost by animateFloatAsState(
            targetValue = ((callState.localAudioLevel + callState.remoteAudioLevel) * 0.25f).coerceIn(0f, 0.35f),
            animationSpec = tween(120),
            label = "audioLevelBoost"
        )
        val effectiveScale = if (callState.phase == CallPhase.ACTIVE) {
            1f + audioBoost
        } else if (callState.phase == CallPhase.INCOMING_RINGING || callState.phase == CallPhase.OUTGOING_RINGING) {
            ringPulse
        } else {
            1f
        }

        val statusLabel = when (callState.phase) {
            CallPhase.OUTGOING_RINGING -> stringResource(R.string.call_status_outgoing)
            CallPhase.INCOMING_RINGING -> stringResource(R.string.call_status_incoming)
            CallPhase.CONNECTING -> stringResource(R.string.call_status_connecting)
            CallPhase.ACTIVE -> if (callState.hopCount <= 1) {
                stringResource(R.string.call_status_active_direct)
            } else {
                stringResource(R.string.call_status_active_multihop, callState.hopCount)
            }
            CallPhase.ENDED -> when (callState.endReason) {
                CallEndReason.DECLINED -> stringResource(R.string.call_ended_declined)
                CallEndReason.BUSY -> stringResource(R.string.call_ended_busy)
                CallEndReason.NO_ANSWER -> stringResource(R.string.call_ended_no_answer)
                CallEndReason.UNREACHABLE -> stringResource(R.string.call_ended_unreachable)
                CallEndReason.LINK_LOST -> stringResource(R.string.call_ended_link_lost)
                CallEndReason.MIC_PERMISSION_DENIED -> stringResource(R.string.call_ended_mic_denied)
                else -> stringResource(R.string.call_ended_hangup)
            }
            CallPhase.IDLE -> ""
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0B1424),
                            Color(0xFF0F2138),
                            Color(0xFF09121F)
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .testTag("full_screen_call_overlay")
        ) {
            // Top bar: minimize button & E2E security header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (callState.phase == CallPhase.ACTIVE || callState.phase == CallPhase.OUTGOING_RINGING || callState.phase == CallPhase.CONNECTING) {
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("call_minimize_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.call_action_minimize),
                            tint = Color.White
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                Surface(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.call_title_e2e),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                }

                TrustBadge(trustStatus = callState.trustStatus, compact = true)
            }

            // Center peer identity & animated voice halo
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val base = size.minDimension * 0.30f
                        drawCircle(
                            color = Color(0xFF2AABEE).copy(alpha = 0.12f),
                            radius = base * effectiveScale * 1.35f,
                            center = center
                        )
                        drawCircle(
                            color = Color(0xFF2AABEE).copy(alpha = 0.22f),
                            radius = base * effectiveScale,
                            center = center
                        )
                    }
                    PeerAvatar(
                        title = callState.peerName.ifBlank { "Peer" },
                        trustStatus = callState.trustStatus,
                        isOnline = callState.phase == CallPhase.ACTIVE,
                        size = 104.dp
                    )
                }

                Text(
                    text = callState.peerName.ifBlank { callState.peerNodeId.take(8) },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (callState.phase == CallPhase.ENDED && callState.endReason != CallEndReason.HANGUP) {
                        Color(0xFFFCA5A5)
                    } else {
                        Color(0xFF7DD3FC)
                    }
                )

                if (callState.phase == CallPhase.ACTIVE || (callState.phase == CallPhase.ENDED && callState.durationSec > 0)) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = MeshCallManager.formatCallDuration(callState.durationSec),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier
                                .padding(horizontal = 18.dp, vertical = 6.dp)
                                .testTag("call_duration_text")
                        )
                    }
                }
            }

            // Bottom action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (callState.phase) {
                    CallPhase.INCOMING_RINGING -> {
                        CallRoundAction(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.call_action_decline),
                            backgroundColor = Color(0xFFEF4444),
                            contentColor = Color.White,
                            onClick = onDeclineOrEnd,
                            testTag = "call_decline_button"
                        )
                        CallRoundAction(
                            icon = Icons.Filled.Call,
                            label = stringResource(R.string.call_action_accept),
                            backgroundColor = ext.directGreen,
                            contentColor = Color.White,
                            onClick = {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    onAccept()
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            testTag = "call_accept_button"
                        )
                    }
                    CallPhase.OUTGOING_RINGING, CallPhase.CONNECTING, CallPhase.ACTIVE -> {
                        CallRoundAction(
                            icon = if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (callState.isMuted) {
                                stringResource(R.string.call_action_unmute)
                            } else {
                                stringResource(R.string.call_action_mute)
                            },
                            backgroundColor = if (callState.isMuted) Color.White else Color.White.copy(alpha = 0.16f),
                            contentColor = if (callState.isMuted) Color(0xFF0F172A) else Color.White,
                            onClick = onToggleMute,
                            testTag = "call_mute_button"
                        )
                        CallRoundAction(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.call_action_hangup),
                            backgroundColor = Color(0xFFEF4444),
                            contentColor = Color.White,
                            buttonSizeDp = 70,
                            onClick = onDeclineOrEnd,
                            testTag = "call_hangup_button"
                        )
                        CallRoundAction(
                            icon = if (callState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Outlined.VolumeDown,
                            label = stringResource(R.string.call_action_speaker),
                            backgroundColor = if (callState.isSpeakerOn) Color.White else Color.White.copy(alpha = 0.16f),
                            contentColor = if (callState.isSpeakerOn) Color(0xFF0F172A) else Color.White,
                            onClick = onToggleSpeaker,
                            testTag = "call_speaker_button"
                        )
                    }
                    CallPhase.ENDED -> {
                        CallRoundAction(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.call_action_hangup),
                            backgroundColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White,
                            onClick = onDeclineOrEnd,
                            testTag = "call_ended_close_button"
                        )
                    }
                    CallPhase.IDLE -> Unit
                }
            }
        }
    }
}

@Composable
private fun CallRoundAction(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    testTag: String,
    buttonSizeDp: Int = 62
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = backgroundColor,
            shape = CircleShape,
            modifier = Modifier
                .size(buttonSizeDp.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag(testTag)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1
        )
    }
}

@Composable
fun MinimizedCallBanner(
    callState: MeshCallUiState,
    onExpand: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalLanChatColors.current
    val show = callState.hasLiveOverlay && callState.isMinimized
    AnimatedVisibility(visible = show, modifier = modifier) {
        Surface(
            color = ext.directGreen,
            contentColor = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .clickable(onClick = onExpand)
                .testTag("minimized_call_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhoneInTalk,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.call_minimized_banner,
                            callState.peerName,
                            MeshCallManager.formatCallDuration(callState.durationSec)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = Color(0xFFEF4444),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onHangUp)
                        .testTag("minimized_call_hangup")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = stringResource(R.string.call_action_hangup),
                        tint = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(18.dp)
                    )
                }
            }
        }
    }
}
