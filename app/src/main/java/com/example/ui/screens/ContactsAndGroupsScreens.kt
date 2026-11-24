package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.PeerEntity
import com.example.ui.components.PeerAvatar
import com.example.ui.components.TrustBadge
import com.example.ui.components.isolateBidi
import com.example.ui.theme.LocalLanChatColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    peers: List<PeerEntity>,
    reachableNodeIds: Set<String> = emptySet(),
    onOpenDirectChat: (String) -> Unit,
    onCallPeer: (String) -> Unit = {},
    onOpenVerifyQr: (String?) -> Unit,
    onOpenCreateGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ext = LocalLanChatColors.current
    var pendingCallPeerId by remember { mutableStateOf<String?>(null) }
    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingCallPeerId
        pendingCallPeerId = null
        if (granted && target != null) {
            onCallPeer(target)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(
                        onClick = { onOpenVerifyQr(null) },
                        modifier = Modifier.testTag("contacts_scan_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.contacts_scan_show_qr)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenCreateGroup,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Filled.GroupAdd,
                    contentDescription = stringResource(R.string.chats_new_group_title)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (peers.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.contacts_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(R.string.contacts_empty_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { onOpenVerifyQr(null) }) {
                                Text(stringResource(R.string.contacts_scan_show_qr))
                            }
                        }
                    }
                }
            } else {
                items(peers, key = { it.nodeId }, contentType = { "contact_peer" }) { peer ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDirectChat(peer.nodeId) }
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
                                Text(
                                    text = stringResource(R.string.contacts_node_prefix, isolateBidi(peer.nodeId.take(12))),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (peer.isDirectlyConnected || reachableNodeIds.contains(peer.nodeId)) {
                                IconButton(
                                    onClick = {
                                        val hasMic = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasMic) {
                                            onCallPeer(peer.nodeId)
                                        } else {
                                            pendingCallPeerId = peer.nodeId
                                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    modifier = Modifier.testTag("contact_call_${peer.nodeId.take(6)}")
                                ) {
                                    Icon(
                                        Icons.Filled.Call,
                                        contentDescription = stringResource(R.string.action_call),
                                        tint = ext.directGreen
                                    )
                                }
                            }
                            IconButton(onClick = { onOpenVerifyQr(peer.nodeId) }) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.action_verify))
                            }
                            IconButton(onClick = { onOpenDirectChat(peer.nodeId) }) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    peers: List<PeerEntity>,
    onBack: () -> Unit,
    onCreateGroup: (title: String, memberIds: List<String>) -> Unit
) {
    var groupTitle by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    val defaultGroupName = stringResource(R.string.create_group_default_name)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_chats))
                    }
                },
                title = { Text(stringResource(R.string.chats_new_group_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = groupTitle,
                onValueChange = { groupTitle = it },
                label = { Text(stringResource(R.string.create_group_name_label)) },
                placeholder = { Text(stringResource(R.string.create_group_name_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_group_name_input")
            )

            Text(
                text = stringResource(R.string.create_group_select_peers, selectedIds.size),
                style = MaterialTheme.typography.titleSmall
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(peers, key = { it.nodeId }, contentType = { "group_peer" }) { peer ->
                    val checked = selectedIds.contains(peer.nodeId)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .clickable {
                                if (checked) selectedIds.remove(peer.nodeId) else selectedIds.add(peer.nodeId)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selectedIds.add(peer.nodeId) else selectedIds.remove(peer.nodeId)
                                }
                            )
                            PeerAvatar(
                                title = peer.displayName,
                                trustStatus = peer.trustStatus,
                                isOnline = peer.isDirectlyConnected,
                                size = 40.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = peer.nodeId.take(10),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val title = groupTitle.trim().ifBlank { defaultGroupName }
                    onCreateGroup(title, selectedIds.toList())
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("create_group_submit_button")
            ) {
                Text(stringResource(R.string.create_group_submit))
            }
        }
    }
}
