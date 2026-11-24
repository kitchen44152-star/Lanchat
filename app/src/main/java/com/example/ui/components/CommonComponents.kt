package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ConversationType
import com.example.data.local.TrustStatus
import com.example.mesh.router.MeshStatsUi
import com.example.mesh.transport.TransportState
import com.example.mesh.transport.TransportStatus
import com.example.ui.theme.LocalLanChatColors
import kotlin.math.absoluteValue

/**
 * Isolates bidirectional text (names, node IDs, hashtags) so mixed RTL/LTR strings
 * never reverse punctuation inside formatted labels.
 */
fun isolateBidi(text: String): String = "\u2068$text\u2069"

@Composable
fun PeerAvatar(
    title: String,
    conversationType: ConversationType = ConversationType.DIRECT,
    trustStatus: TrustStatus? = null,
    isOnline: Boolean = false,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val avatarBrush = remember(title) {
        val palette = listOf(
            Color(0xFF0D9488),
            Color(0xFF0284C7),
            Color(0xFF4F46E5),
            Color(0xFF059669),
            Color(0xFFD97706),
            Color(0xFF7C3AED)
        )
        val avatarBg = palette[(title.hashCode().absoluteValue) % palette.size]
        Brush.linearGradient(listOf(avatarBg, avatarBg.copy(alpha = 0.78f)))
    }
    val initials = remember(title, conversationType) {
        if (conversationType == ConversationType.DIRECT) {
            title.trim().split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifEmpty { "L" }
        } else ""
    }
    val ext = LocalLanChatColors.current

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(avatarBrush),
            contentAlignment = Alignment.Center
        ) {
            when (conversationType) {
                ConversationType.BROADCAST -> Icon(
                    imageVector = Icons.Filled.CellTower,
                    contentDescription = stringResource(R.string.mesh_broadcast_title),
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
                ConversationType.GROUP -> Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = stringResource(R.string.chats_new_group_title),
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
                ConversationType.DIRECT -> {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(ext.directGreen)
            )
        } else if (trustStatus == TrustStatus.VERIFIED_QR) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = stringResource(R.string.badge_verified),
                    tint = ext.verifiedTeal,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
fun MeshStatusPill(
    transportState: TransportState,
    stats: MeshStatsUi,
    relayEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalLanChatColors.current
    val isActive = transportState.status == TransportStatus.ACTIVE || transportState.status == TransportStatus.STARTING
    val containerColor by animateColorAsState(
        targetValue = when {
            transportState.status == TransportStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.errorContainer
            isActive && stats.directPeerCount > 0 -> ext.directGreen.copy(alpha = 0.14f)
            isActive -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "meshPillColor"
    )
    val contentColor = when {
        transportState.status == TransportStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.error
        isActive && stats.directPeerCount > 0 -> ext.directGreen
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val label = when (transportState.status) {
        TransportStatus.PERMISSION_REQUIRED -> stringResource(R.string.mesh_status_permission_needed)
        TransportStatus.STARTING -> stringResource(R.string.mesh_status_starting)
        TransportStatus.ACTIVE -> if (stats.directPeerCount == 0) {
            if (relayEnabled) {
                stringResource(R.string.mesh_status_scanning_relay_on)
            } else {
                stringResource(R.string.mesh_status_scanning_fg_only)
            }
        } else {
            stringResource(R.string.mesh_status_links_format, stats.directPeerCount, stats.reachablePeerCount)
        }
        TransportStatus.ERROR -> stringResource(R.string.mesh_status_error_retry)
        TransportStatus.STOPPED -> stringResource(R.string.mesh_status_paused)
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .testTag("mesh_status_pill")
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (isActive) Icons.Filled.CellTower else Icons.Filled.PauseCircle,
                contentDescription = stringResource(R.string.mesh_status_active),
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TrustBadge(
    trustStatus: TrustStatus,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val ext = LocalLanChatColors.current
    val (labelRes, icon, tint) = when (trustStatus) {
        TrustStatus.VERIFIED_QR -> Triple(R.string.badge_verified, Icons.Filled.VerifiedUser, ext.verifiedTeal)
        TrustStatus.UNVERIFIED_TOFU -> Triple(R.string.badge_unverified, Icons.Outlined.Shield, ext.multiHopAmber)
        TrustStatus.KEY_CHANGED_WARNING -> Triple(R.string.badge_key_changed, Icons.Filled.Warning, MaterialTheme.colorScheme.error)
    }
    val label = stringResource(labelRes)

    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(if (compact) 12.dp else 13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MeshWallpaperCanvas(modifier: Modifier = Modifier) {
    val dotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val lineColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.045f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val spacing = 34.dp.toPx()
                val radius = 2.dp.toPx()
                val strokeW = 1.dp.toPx()
                val cols = (size.width / spacing).toInt() + 1
                val rows = (size.height / spacing).toInt() + 1
                val dotsPath = Path()
                val linesPath = Path()
                for (r in 0..rows) {
                    for (c in 0..cols) {
                        val x = c * spacing + if (r % 2 == 0) 0f else spacing * 0.5f
                        val y = r * spacing
                        dotsPath.addOval(
                            androidx.compose.ui.geometry.Rect(
                                x - radius,
                                y - radius,
                                x + radius,
                                y + radius
                            )
                        )
                        if ((r + c) % 5 == 0) {
                            linesPath.moveTo(x, y)
                            linesPath.lineTo(x + spacing * 0.8f, y + spacing * 0.6f)
                        }
                    }
                }
                onDrawBehind {
                    drawPath(path = linesPath, color = lineColor, style = Stroke(width = strokeW))
                    drawPath(path = dotsPath, color = dotColor, style = Fill)
                }
            }
    )
}
