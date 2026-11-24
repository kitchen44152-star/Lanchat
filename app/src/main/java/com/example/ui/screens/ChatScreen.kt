package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationType
import com.example.data.local.DeliveryStatus
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.data.local.PeerEntity
import com.example.data.local.TrustStatus
import com.example.media.RecordingState
import com.example.media.VoicePlaybackState
import com.example.mesh.router.TransferProgressUi
import com.example.ui.components.ImageLightboxDialog
import com.example.ui.components.ImageMessageBubble
import com.example.ui.components.MeshWallpaperCanvas
import com.example.ui.components.PeerAvatar
import com.example.ui.components.TrustBadge
import com.example.ui.components.VoiceNoteBubble
import com.example.ui.components.VoiceRecordingBar
import com.example.ui.components.isolateBidi
import com.example.ui.theme.LocalLanChatColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversation: ConversationEntity?,
    peer: PeerEntity?,
    messages: List<MessageEntity>,
    transferProgress: Map<String, TransferProgressUi>,
    recordingState: RecordingState,
    playbackState: VoicePlaybackState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onOpenVerifyPeer: (String) -> Unit,
    onSendText: (text: String, replyToId: String?, replyPreview: String?) -> Unit,
    onSendImageUri: (Uri, String) -> Unit,
    onStartVoiceRecording: () -> Boolean,
    onStopAndSendVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onTogglePlayVoice: (String, String) -> Unit,
    onSeekVoice: (String, Float) -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onPauseTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onSendReaction: (messageId: String, emoji: String) -> Unit,
    onCallPeer: (String) -> Unit = {},
    onMessagesViewed: (List<MessageEntity>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var textInput by rememberSaveable(conversation?.conversationId) { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var lightboxImagePath by remember { mutableStateOf<String?>(null) }
    var pendingImagePreviewUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var imageCaptionDraft by rememberSaveable { mutableStateOf("") }
    var micRationale by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val listState = rememberLazyListState()
    var initialScrollDone by remember(conversation?.conversationId) { mutableStateOf(false) }
    val showScrollToBottom by remember(messages.size) {
        derivedStateOf {
            if (messages.isEmpty()) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < messages.lastIndex - 1
            }
        }
    }

    LaunchedEffect(messages.size, conversation?.conversationId) {
        if (messages.isNotEmpty()) {
            if (!initialScrollDone) {
                listState.scrollToItem(messages.lastIndex)
                initialScrollDone = true
            } else {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    LaunchedEffect(messages) {
        if (messages.any { it.isIncoming && it.deliveryStatus != DeliveryStatus.READ }) {
            onMessagesViewed(messages)
        }
    }

    LaunchedEffect(recordingState.maxDurationReached) {
        if (recordingState.maxDurationReached && recordingState.isRecording) {
            onStopAndSendVoice()
        }
    }

    // Zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            imageCaptionDraft = ""
            pendingImagePreviewUri = uri
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            imageCaptionDraft = ""
            pendingImagePreviewUri = uri
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "cap_${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    var pendingCallNodeId by remember { mutableStateOf<String?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val callPeer = pendingCallNodeId
        pendingCallNodeId = null
        if (granted) {
            micRationale = false
            if (callPeer != null) {
                onCallPeer(callPeer)
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStartVoiceRecording()
            }
        } else {
            micRationale = true
        }
    }

    val rawTitle = conversation?.title ?: stringResource(R.string.chat_title_default)
    val displayTitle = if (rawTitle.startsWith("#")) "\u200E$rawTitle\u200E" else rawTitle

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.nav_chats)
                            )
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PeerAvatar(
                            title = rawTitle,
                            conversationType = conversation?.type ?: ConversationType.DIRECT,
                            trustStatus = peer?.trustStatus,
                            isOnline = peer?.isDirectlyConnected == true,
                            size = 38.dp
                        )
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = when (conversation?.type) {
                                ConversationType.BROADCAST -> stringResource(R.string.mesh_broadcast_subtitle)
                                ConversationType.GROUP -> stringResource(R.string.chat_subtitle_group)
                                else -> when {
                                    peer == null -> stringResource(R.string.chat_subtitle_dtn_ready)
                                    peer.isDirectlyConnected -> stringResource(R.string.chat_subtitle_direct_e2e)
                                    peer.bestHopCount < 20 -> stringResource(R.string.chat_subtitle_multihop_e2e, peer.bestHopCount)
                                    else -> stringResource(R.string.chat_subtitle_offline_dtn)
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (conversation?.type != ConversationType.BROADCAST) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (peer != null) {
                        TrustBadge(
                            trustStatus = peer.trustStatus,
                            compact = true,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                        if (conversation?.type == ConversationType.DIRECT) {
                            IconButton(
                                onClick = {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        onCallPeer(peer.nodeId)
                                    } else {
                                        pendingCallNodeId = peer.nodeId
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier.testTag("chat_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Call,
                                    contentDescription = stringResource(R.string.action_call),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(
                            onClick = { onOpenVerifyPeer(peer.nodeId) },
                            modifier = Modifier.testTag("chat_verify_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QrCode2,
                                contentDescription = stringResource(R.string.action_verify_qr)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                AnimatedVisibility(visible = replyTarget != null) {
                    replyTarget?.let { target ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.chat_replying_to, isolateBidi(target.senderName)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = target.bodyText.ifBlank { target.type.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                if (micRationale) {
                    Text(
                        text = stringResource(R.string.chat_mic_rationale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (recordingState.isRecording) {
                    VoiceRecordingBar(
                        recordingState = recordingState,
                        onCancel = onCancelVoice,
                        onSend = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStopAndSendVoice()
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box {
                            IconButton(
                                onClick = { showAttachMenu = true },
                                modifier = Modifier.testTag("chat_attach_button")
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.chat_attach_choose_photo))
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_choose_photo)) },
                                    leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                                    leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.CAMERA
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
                                            val file = File(dir, "cap_${UUID.randomUUID()}.jpg")
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            pendingCameraUri = uri
                                            cameraLauncher.launch(uri)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = { Text(stringResource(R.string.chat_composer_placeholder)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input")
                        )

                        val showSend = textInput.isNotBlank()
                        AnimatedContent(
                            targetState = showSend,
                            transitionSpec = {
                                (fadeIn(tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)))
                                    .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.85f, animationSpec = tween(120)))
                            },
                            label = "composerCta"
                        ) { isSend ->
                            if (isSend) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("chat_send_button")
                                        .clip(CircleShape)
                                        .clickable {
                                            val payload = textInput.trim()
                                            textInput = ""
                                            val reply = replyTarget
                                            replyTarget = null
                                            onSendText(
                                                payload,
                                                reply?.messageId,
                                                reply?.bodyText?.take(60)
                                            )
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.action_send),
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("chat_mic_button")
                                        .clip(CircleShape)
                                        .clickable {
                                            val granted = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (granted) {
                                                micRationale = false
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onStartVoiceRecording()
                                            } else {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Mic,
                                            contentDescription = "Voice note",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            MeshWallpaperCanvas()

            Column(modifier = Modifier.fillMaxSize()) {
                if (peer?.trustStatus == TrustStatus.KEY_CHANGED_WARNING) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = stringResource(R.string.chat_key_changed_warning, isolateBidi(peer.displayName)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onOpenVerifyPeer(peer.nodeId) }) {
                                Text(stringResource(R.string.action_verify))
                            }
                        }
                    }
                }

                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_empty_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.chat_empty_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = messages,
                            key = { _, msg -> msg.messageId },
                            contentType = { _, msg -> msg.type }
                        ) { index, msg ->
                            val msgDisplayTs = msg.localOrderTs.takeIf { it > 0L } ?: msg.timestamp
                            val prevDisplayTs = if (index > 0) {
                                val prev = messages[index - 1]
                                prev.localOrderTs.takeIf { it > 0L } ?: prev.timestamp
                            } else 0L
                            val showDatePill = index == 0 || !isSameCalendarDay(prevDisplayTs, msgDisplayTs)
                            val transfer = msg.transferId?.let { transferProgress[it] }
                            val bubblePlayback = if (playbackState.activeMessageId == msg.messageId) {
                                playbackState
                            } else {
                                IdleVoicePlaybackState
                            }
                            Column(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(160),
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            ) {
                                if (showDatePill) {
                                    DateSeparatorPill(timestamp = msgDisplayTs)
                                    Spacer(Modifier.height(6.dp))
                                }

                                MessageBubbleItem(
                                    message = msg,
                                    displayTimestamp = msgDisplayTs,
                                    showSenderName = conversation?.type != ConversationType.DIRECT && msg.isIncoming,
                                    transferProgress = transfer,
                                    playbackState = bubblePlayback,
                                    onReply = { replyTarget = msg },
                                    onJumpToReply = { targetId ->
                                        val targetIdx = messages.indexOfFirst { it.messageId == targetId }
                                        if (targetIdx >= 0) {
                                            coroutineScope.launch { listState.animateScrollToItem(targetIdx) }
                                        }
                                    },
                                    onReact = { emoji ->
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSendReaction(msg.messageId, emoji)
                                    },
                                    onTogglePlayVoice = onTogglePlayVoice,
                                    onSeekVoice = onSeekVoice,
                                    onCycleVoiceSpeed = onCycleVoiceSpeed,
                                    onPauseTransfer = onPauseTransfer,
                                    onResumeTransfer = onResumeTransfer,
                                    onCancelTransfer = onCancelTransfer,
                                    onOpenLightbox = { lightboxImagePath = it }
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        if (messages.isNotEmpty()) {
                            coroutineScope.launch { listState.animateScrollToItem(messages.lastIndex) }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    }

    pendingImagePreviewUri?.let { previewUri ->
        AlertDialog(
            onDismissRequest = { pendingImagePreviewUri = null },
            title = { Text(stringResource(R.string.chat_send_photo_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = previewUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    OutlinedTextField(
                        value = imageCaptionDraft,
                        onValueChange = { imageCaptionDraft = it },
                        label = { Text(stringResource(R.string.chat_photo_caption_label)) },
                        placeholder = { Text(stringResource(R.string.chat_photo_caption_hint)) },
                        maxLines = 2,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = previewUri
                        val cap = imageCaptionDraft.trim()
                        pendingImagePreviewUri = null
                        imageCaptionDraft = ""
                        onSendImageUri(uri, cap)
                    }
                ) {
                    Text(stringResource(R.string.action_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImagePreviewUri = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    lightboxImagePath?.let { path ->
        ImageLightboxDialog(
            imagePath = path,
            onDismiss = { lightboxImagePath = null }
        )
    }
}

private val IdleVoicePlaybackState = VoicePlaybackState()
private val ChatUiJson = Json { ignoreUnknownKeys = true }

private val dateSeparatorFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
}

@Composable
private fun DateSeparatorPill(timestamp: Long) {
    val label = formatDateSeparator(timestamp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

private fun isSameCalendarDay(ts1: Long, ts2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = ts1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = ts2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
        c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun formatDateSeparator(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
        val diff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
        if (diff == 0) return stringResource(R.string.chat_date_today)
        if (diff == 1) return stringResource(R.string.chat_date_yesterday)
    }
    return dateSeparatorFormatter.get()?.format(Date(timestamp)) ?: ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleItem(
    message: MessageEntity,
    displayTimestamp: Long,
    showSenderName: Boolean,
    transferProgress: TransferProgressUi?,
    playbackState: VoicePlaybackState,
    onReply: () -> Unit,
    onJumpToReply: (String) -> Unit,
    onReact: (String) -> Unit,
    onTogglePlayVoice: (String, String) -> Unit,
    onSeekVoice: (String, Float) -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onPauseTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onOpenLightbox: (String) -> Unit
) {
    val ext = LocalLanChatColors.current
    val haptics = LocalHapticFeedback.current
    var showActionsMenu by remember { mutableStateOf(false) }

    val bubbleColor = if (message.isIncoming) ext.incomingBubble else ext.outgoingBubble
    val alignment = if (message.isIncoming) Alignment.CenterStart else Alignment.CenterEnd
    val bubbleShape = if (message.isIncoming) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    }

    val waveform = remember(message.waveformSamplesJson) {
        if (message.waveformSamplesJson == "[]") emptyList()
        else runCatching { ChatUiJson.decodeFromString<List<Int>>(message.waveformSamplesJson) }.getOrDefault(emptyList())
    }
    val reactions = remember(message.reactionsJson) {
        if (message.reactionsJson == "{}") emptyMap()
        else runCatching { ChatUiJson.decodeFromString<Map<String, List<String>>>(message.reactionsJson) }.getOrDefault(emptyMap())
    }
    val formattedTime = remember(displayTimestamp) { formatChatTime(displayTimestamp) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(horizontalAlignment = if (message.isIncoming) Alignment.Start else Alignment.End) {
            Box {
                Surface(
                    color = bubbleColor,
                    shape = bubbleShape,
                    tonalElevation = 1.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 310.dp)
                        .testTag("message_bubble_${message.messageId}")
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showActionsMenu = true
                            }
                        )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (showSenderName) {
                            Text(
                                text = message.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                        }

                        if (!message.replyToPreview.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = message.replyToMessageId != null) {
                                        message.replyToMessageId?.let(onJumpToReply)
                                    }
                            ) {
                                Text(
                                    text = message.replyToPreview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }

                        when (message.type) {
                            MessageType.TEXT -> {
                                Text(
                                    text = message.bodyText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            MessageType.VOICE -> {
                                VoiceNoteBubble(
                                    messageId = message.messageId,
                                    localPath = message.mediaLocalPath,
                                    durationMs = message.mediaDurationMs,
                                    waveformSamples = waveform,
                                    playbackState = playbackState,
                                    transferProgress = transferProgress,
                                    onTogglePlay = onTogglePlayVoice,
                                    onSeek = onSeekVoice,
                                    onCycleSpeed = onCycleVoiceSpeed
                                )
                            }
                            MessageType.IMAGE -> {
                                ImageMessageBubble(
                                    localPath = message.mediaLocalPath,
                                    microThumbBase64 = message.microThumbBase64,
                                    totalBytes = message.mediaTotalBytes,
                                    transferProgress = transferProgress,
                                    onPauseTransfer = onPauseTransfer,
                                    onResumeTransfer = onResumeTransfer,
                                    onCancelTransfer = onCancelTransfer,
                                    onOpenLightbox = onOpenLightbox
                                )
                                if (message.bodyText.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = message.bodyText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            if (message.hopCount > 1) {
                                Text(
                                    text = stringResource(R.string.chat_hops_badge, message.hopCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.multiHopAmber
                                )
                            }
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!message.isIncoming) {
                                DeliveryStatusIcon(status = message.deliveryStatus)
                            }
                        }
                    }
                }

                DropdownMenu(
                    expanded = showActionsMenu,
                    onDismissRequest = { showActionsMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                        onClick = {
                            showActionsMenu = false
                            onReply()
                        }
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf("👍", "❤️", "🔥", "✅", "📍").forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        showActionsMenu = false
                                        onReact(emoji)
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            if (reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reactions.forEach { (emoji, users) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onReact(emoji) }
                        ) {
                            Text(
                                text = "$emoji ${users.size}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus) {
    val ext = LocalLanChatColors.current
    when (status) {
        DeliveryStatus.QUEUED -> Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = "Queued locally",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        DeliveryStatus.RELAYED -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Relayed to mesh",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        DeliveryStatus.DELIVERED -> Icon(
            imageVector = Icons.Filled.DoneAll,
            contentDescription = "Delivered end-to-end",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        DeliveryStatus.READ -> Icon(
            imageVector = Icons.Filled.DoneAll,
            contentDescription = "Read",
            tint = ext.verifiedTeal,
            modifier = Modifier.size(15.dp)
        )
        DeliveryStatus.FAILED -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Delivery failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
    }
}
