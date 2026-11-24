package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationType
import com.example.data.local.PeerEntity
import com.example.mesh.router.MeshStatsUi
import com.example.mesh.transport.TransportState
import com.example.ui.components.MeshStatusPill
import com.example.ui.components.PeerAvatar
import com.example.ui.components.TrustBadge
import com.example.ui.components.isolateBidi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    conversations: List<ConversationEntity>,
    peers: List<PeerEntity>,
    selectedConversationId: String?,
    transportState: TransportState,
    meshStats: MeshStatsUi,
    relayEnabled: Boolean,
    onOpenConversation: (String) -> Unit,
    onOpenRadar: () -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenVerifyQr: () -> Unit,
    onOpenDirectChat: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    val peerMap = remember(peers) { peers.associateBy { it.nodeId } }

    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.lastMessagePreview.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        MeshStatusPill(
                            transportState = transportState,
                            stats = meshStats,
                            relayEnabled = relayEnabled,
                            onClick = onOpenRadar,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenVerifyQr,
                        modifier = Modifier.testTag("chats_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.chats_verify_qr_title)
                        )
                    }
                    IconButton(
                        onClick = onOpenCreateGroup,
                        modifier = Modifier.testTag("chats_create_group_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GroupAdd,
                            contentDescription = stringResource(R.string.chats_new_group_title)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (peers.isEmpty()) onOpenCreateGroup() else showNewChatSheet = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("chats_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.AddComment,
                    contentDescription = stringResource(R.string.chats_new_sheet_title)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.chats_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("chats_search_input")
            )

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chats_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.chats_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(
                        items = filtered,
                        key = { it.conversationId },
                        contentType = { it.type }
                    ) { conv ->
                        val peer = conv.peerNodeId?.let { peerMap[it] }
                        val isSelected = conv.conversationId == selectedConversationId
                        Column(modifier = Modifier.animateItem()) {
                            ConversationRow(
                                conversation = conv,
                                peer = peer,
                                isSelected = isSelected,
                                onClick = { onOpenConversation(conv.conversationId) },
                                onLongClick = {
                                    if (conversationIsDeletable(conv)) {
                                        conversationToDelete = conv
                                    }
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.chats_new_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chats_new_group_title)) },
                    supportingContent = { Text(stringResource(R.string.chats_new_group_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showNewChatSheet = false
                            onOpenCreateGroup()
                        }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chats_verify_qr_title)) },
                    supportingContent = { Text(stringResource(R.string.chats_verify_qr_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showNewChatSheet = false
                            onOpenVerifyQr()
                        }
                )
                if (peers.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = stringResource(R.string.chats_discovered_peers),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    peers.take(6).forEach { peer ->
                        ListItem(
                            headlineContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = peer.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    TrustBadge(trustStatus = peer.trustStatus, compact = true)
                                }
                            },
                            supportingContent = {
                                Text(
                                    when {
                                        peer.isDirectlyConnected -> stringResource(R.string.chats_direct_link)
                                        peer.bestHopCount < 20 -> stringResource(R.string.chats_hops_away, peer.bestHopCount)
                                        else -> stringResource(R.string.chats_dtn_queue)
                                    }
                                )
                            },
                            leadingContent = {
                                PeerAvatar(
                                    title = peer.displayName,
                                    trustStatus = peer.trustStatus,
                                    isOnline = peer.isDirectlyConnected,
                                    size = 40.dp
                                )
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showNewChatSheet = false
                                    onOpenDirectChat(peer.nodeId)
                                }
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    conversationToDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            icon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.chats_delete_title, isolateBidi(conv.title))) },
            text = { Text(stringResource(R.string.chats_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = conv.conversationId
                        conversationToDelete = null
                        onDeleteConversation(id)
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    peer: PeerEntity?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val bg = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val displayTitle = if (conversation.title.startsWith("#")) {
        "\u200E${conversation.title}\u200E"
    } else {
        conversation.title
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (conversation.type != ConversationType.BROADCAST) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                }
            )
            .testTag("conversation_row_${conversation.conversationId}")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            title = conversation.title,
            conversationType = conversation.type,
            trustStatus = peer?.trustStatus,
            isOnline = peer?.isDirectlyConnected == true,
            size = 52.dp
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (conversation.type != ConversationType.BROADCAST) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                if (conversation.lastMessageTimestamp > 0L) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatChatTime(conversation.lastMessageTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val preview = if (peer != null && peer.bestHopCount < 20) {
                    if (peer.bestHopCount <= 1) {
                        stringResource(R.string.chats_hop_prefix_direct, conversation.lastMessagePreview)
                    } else {
                        stringResource(R.string.chats_hop_prefix_multi, peer.bestHopCount, conversation.lastMessagePreview)
                    }
                } else {
                    conversation.lastMessagePreview
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun conversationIsDeletable(conv: ConversationEntity): Boolean =
    conv.type != ConversationType.BROADCAST

private val chatTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
}

fun formatChatTime(timestamp: Long): String {
    return chatTimeFormatter.get()?.format(Date(timestamp)) ?: ""
}
