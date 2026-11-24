package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.data.local.ThemePreference
import com.example.mesh.location.CompassHeadingState
import com.example.mesh.router.MeshRouter
import com.example.ui.navigation.LanChatRoute
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ContactsScreen
import com.example.ui.screens.CreateGroupScreen
import com.example.ui.screens.FullScreenCallOverlay
import com.example.ui.screens.MeshRadarScreen
import com.example.ui.screens.MinimizedCallBanner
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VerifyPeerScreen
import com.example.ui.theme.LanChatTheme
import com.example.ui.viewmodel.LanChatUiAlert
import com.example.ui.viewmodel.LanChatViewModel

private data class TopDestination(
    val labelRes: Int,
    val route: LanChatRoute,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val detailEnterTransition =
    slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it / 4 } +
        fadeIn(animationSpec = tween(180))

private val detailExitTransition =
    slideOutHorizontally(animationSpec = tween(180, easing = FastOutSlowInEasing)) { -it / 6 } +
        fadeOut(animationSpec = tween(140))

private val detailPopEnterTransition =
    slideInHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { -it / 6 } +
        fadeIn(animationSpec = tween(160))

private val detailPopExitTransition =
    slideOutHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { it / 4 } +
        fadeOut(animationSpec = tween(160))

@Composable
fun LanChatApp(
    viewModel: LanChatViewModel,
    widthSizeClass: WindowWidthSizeClass,
    deepLinkConversationId: String? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val isIdentityLoaded by viewModel.isIdentityLoaded.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val darkTheme = when (identity.themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    LaunchedEffect(isIdentityLoaded, identity.isOnboarded) {
        if (isIdentityLoaded && identity.isOnboarded) {
            viewModel.ensureMeshStartedIfOnboarded(context)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiAlerts.collect { alert ->
            val msgRes = when (alert) {
                LanChatUiAlert.IMAGE_DECODE_FAILED -> R.string.alert_image_failed
                LanChatUiAlert.DISPLAY_NAME_UPDATED -> R.string.alert_name_updated
                LanChatUiAlert.LOCATION_PERMISSION_NEEDED -> R.string.alert_location_needed
            }
            snackbarHostState.showSnackbar(context.getString(msgRes))
        }
    }

    LanChatTheme(darkTheme = darkTheme) {
        if (!isIdentityLoaded) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            return@LanChatTheme
        }

        // Gate onboarding outside the main tabbed NavHost so NavGraph's startDestination
        // is permanently LanChatRoute.Chats and top-level tab switching can never get trapped.
        if (!identity.isOnboarded) {
            OnboardingScreen(
                requiredMeshPermissions = viewModel.requiredMeshPermissions(),
                hasMeshPermissionsInitially = viewModel.hasMeshPermissions(context),
                onInitialize = { name, relay ->
                    viewModel.completeOnboarding(context, name, relay)
                }
            )
            return@LanChatTheme
        }

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val isExpandedLayout = widthSizeClass != WindowWidthSizeClass.Compact
        var splitSelectedConversationId by remember { mutableStateOf(MeshRouter.BROADCAST_CONVERSATION_ID) }

        fun navigateToTopTab(targetRoute: LanChatRoute) {
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        LaunchedEffect(deepLinkConversationId) {
            val targetConv = deepLinkConversationId
            if (!targetConv.isNullOrBlank()) {
                viewModel.markConversationRead(targetConv)
                if (isExpandedLayout) {
                    splitSelectedConversationId = targetConv
                    navigateToTopTab(LanChatRoute.Chats)
                } else {
                    navController.navigate(LanChatRoute.Chat(targetConv)) {
                        launchSingleTop = true
                    }
                }
                onDeepLinkHandled()
            }
        }

        val topDestinations = remember {
            listOf(
                TopDestination(R.string.nav_chats, LanChatRoute.Chats, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "nav_chats"),
                TopDestination(R.string.nav_mesh, LanChatRoute.MeshRadar, Icons.Filled.CellTower, Icons.Outlined.CellTower, "nav_mesh"),
                TopDestination(R.string.nav_contacts, LanChatRoute.Contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts, "nav_contacts"),
                TopDestination(R.string.nav_settings, LanChatRoute.Settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
            )
        }

        val showTopLevelChrome = currentDestination?.let { dest ->
            dest.hasRoute<LanChatRoute.Chats>() ||
                dest.hasRoute<LanChatRoute.MeshRadar>() ||
                dest.hasRoute<LanChatRoute.Contacts>() ||
                dest.hasRoute<LanChatRoute.Settings>()
        } == true

        val callState by viewModel.callState.collectAsStateWithLifecycle()

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (showTopLevelChrome && !isExpandedLayout) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            topDestinations.forEach { dest ->
                                val selected = currentDestination?.hasRoute(dest.route::class) == true
                                val label = stringResource(dest.labelRes)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            navigateToTopTab(dest.route)
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { outerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = if (showTopLevelChrome && !isExpandedLayout) outerPadding.calculateBottomPadding() else 0.dp)
                ) {
                    MinimizedCallBanner(
                        callState = callState,
                        onExpand = { viewModel.setCallMinimized(false) },
                        onHangUp = { viewModel.rejectOrEndCall() }
                    )
                    Row(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (showTopLevelChrome && isExpandedLayout) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        topDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            val label = stringResource(dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        navigateToTopTab(dest.route)
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                    VerticalDivider()
                }

                NavHost(
                    navController = navController,
                    startDestination = LanChatRoute.Chats,
                    enterTransition = { fadeIn(tween(180, easing = FastOutSlowInEasing)) },
                    exitTransition = { fadeOut(tween(130, easing = FastOutSlowInEasing)) },
                    popEnterTransition = { fadeIn(tween(180, easing = FastOutSlowInEasing)) },
                    popExitTransition = { fadeOut(tween(130, easing = FastOutSlowInEasing)) },
                    modifier = Modifier.weight(1f)
                ) {
                    composable<LanChatRoute.Chats> {
                        val conversations by viewModel.conversations.collectAsStateWithLifecycle()
                        val peers by viewModel.peers.collectAsStateWithLifecycle()
                        val meshStats by viewModel.meshStats.collectAsStateWithLifecycle()
                        val transportState by viewModel.transportState.collectAsStateWithLifecycle()

                        if (isExpandedLayout) {
                            val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
                            val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
                            val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

                            DisposableEffect(splitSelectedConversationId) {
                                viewModel.setActiveConversation(splitSelectedConversationId)
                                onDispose { viewModel.setActiveConversation(null) }
                            }
                            Row(modifier = Modifier.fillMaxSize()) {
                                ChatsScreen(
                                    conversations = conversations,
                                    peers = peers,
                                    selectedConversationId = splitSelectedConversationId,
                                    transportState = transportState,
                                    meshStats = meshStats,
                                    relayEnabled = identity.backgroundRelayEnabled,
                                    onOpenConversation = { convId ->
                                        splitSelectedConversationId = convId
                                        viewModel.markConversationRead(convId)
                                    },
                                    onOpenRadar = { navigateToTopTab(LanChatRoute.MeshRadar) },
                                    onOpenCreateGroup = { navController.navigate(LanChatRoute.CreateGroup) },
                                    onOpenVerifyQr = { navController.navigate(LanChatRoute.VerifyPeer()) },
                                    onOpenDirectChat = { peerId ->
                                        viewModel.openDirectChat(peerId) { convId ->
                                            splitSelectedConversationId = convId
                                            viewModel.markConversationRead(convId)
                                        }
                                    },
                                    onDeleteConversation = { convId ->
                                        viewModel.deleteConversation(convId)
                                        if (splitSelectedConversationId == convId) {
                                            splitSelectedConversationId = MeshRouter.BROADCAST_CONVERSATION_ID
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(0.42f)
                                        .fillMaxHeight()
                                )
                                VerticalDivider()
                                Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                                    val conv by viewModel.observeConversation(splitSelectedConversationId)
                                        .collectAsStateWithLifecycle(initialValue = null)
                                    val msgs by viewModel.observeMessages(splitSelectedConversationId)
                                        .collectAsStateWithLifecycle(initialValue = emptyList())
                                    val targetPeerId = conv?.peerNodeId
                                    val peer = targetPeerId?.let { pid -> peers.find { it.nodeId == pid } }
                                    if (conv != null) {
                                        ChatScreen(
                                            conversation = conv,
                                            peer = peer,
                                            messages = msgs,
                                            transferProgress = transferProgress,
                                            recordingState = recordingState,
                                            playbackState = playbackState,
                                            showBackButton = false,
                                            onBack = {},
                                            onOpenVerifyPeer = { nodeId ->
                                                navController.navigate(LanChatRoute.VerifyPeer(nodeId))
                                            },
                                            onSendText = { text, rId, rPrev ->
                                                viewModel.sendText(splitSelectedConversationId, text, rId, rPrev)
                                            },
                                            onSendImageUri = { uri, caption ->
                                                viewModel.sendImageFromUri(splitSelectedConversationId, uri, caption)
                                            },
                                            onStartVoiceRecording = { viewModel.startVoiceRecording() },
                                            onStopAndSendVoice = {
                                                viewModel.stopAndSendVoiceNote(splitSelectedConversationId)
                                            },
                                            onCancelVoice = { viewModel.cancelVoiceRecording() },
                                            onTogglePlayVoice = viewModel::togglePlayVoice,
                                            onSeekVoice = viewModel::seekVoice,
                                            onCycleVoiceSpeed = viewModel::cycleVoiceSpeed,
                                            onPauseTransfer = viewModel::pauseTransfer,
                                            onResumeTransfer = viewModel::resumeTransfer,
                                            onCancelTransfer = viewModel::cancelTransfer,
                                            onSendReaction = { mId, emoji ->
                                                viewModel.sendReaction(splitSelectedConversationId, mId, emoji)
                                            },
                                            onCallPeer = { peerId ->
                                                viewModel.startVoiceCall(peerId)
                                            },
                                            onMessagesViewed = { viewed ->
                                                viewModel.markIncomingMessagesRead(splitSelectedConversationId, viewed)
                                            }
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = stringResource(R.string.chats_select_prompt),
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            ChatsScreen(
                                conversations = conversations,
                                peers = peers,
                                selectedConversationId = null,
                                transportState = transportState,
                                meshStats = meshStats,
                                relayEnabled = identity.backgroundRelayEnabled,
                                onOpenConversation = { convId ->
                                    viewModel.markConversationRead(convId)
                                    navController.navigate(LanChatRoute.Chat(convId))
                                },
                                onOpenRadar = { navigateToTopTab(LanChatRoute.MeshRadar) },
                                onOpenCreateGroup = { navController.navigate(LanChatRoute.CreateGroup) },
                                onOpenVerifyQr = { navController.navigate(LanChatRoute.VerifyPeer()) },
                                onOpenDirectChat = { peerId ->
                                    viewModel.openDirectChat(peerId) { convId ->
                                        navController.navigate(LanChatRoute.Chat(convId))
                                    }
                                },
                                onDeleteConversation = viewModel::deleteConversation
                            )
                        }
                    }

                    composable<LanChatRoute.Chat>(
                        enterTransition = { detailEnterTransition },
                        exitTransition = { detailExitTransition },
                        popEnterTransition = { detailPopEnterTransition },
                        popExitTransition = { detailPopExitTransition }
                    ) { entry ->
                        val route = entry.toRoute<LanChatRoute.Chat>()
                        val peers by viewModel.peers.collectAsStateWithLifecycle()
                        val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
                        val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
                        val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

                        val conv by viewModel.observeConversation(route.conversationId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        val msgs by viewModel.observeMessages(route.conversationId)
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        val peer = conv?.peerNodeId?.let { pid -> peers.find { it.nodeId == pid } }

                        DisposableEffect(route.conversationId) {
                            viewModel.setActiveConversation(route.conversationId)
                            onDispose { viewModel.setActiveConversation(null) }
                        }

                        LaunchedEffect(route.conversationId) {
                            viewModel.markConversationRead(route.conversationId)
                        }

                        ChatScreen(
                            conversation = conv,
                            peer = peer,
                            messages = msgs,
                            transferProgress = transferProgress,
                            recordingState = recordingState,
                            playbackState = playbackState,
                            showBackButton = true,
                            onBack = { navController.popBackStack() },
                            onOpenVerifyPeer = { nodeId ->
                                navController.navigate(LanChatRoute.VerifyPeer(nodeId))
                            },
                            onSendText = { text, rId, rPrev ->
                                viewModel.sendText(route.conversationId, text, rId, rPrev)
                            },
                            onSendImageUri = { uri, caption ->
                                viewModel.sendImageFromUri(route.conversationId, uri, caption)
                            },
                            onStartVoiceRecording = { viewModel.startVoiceRecording() },
                            onStopAndSendVoice = { viewModel.stopAndSendVoiceNote(route.conversationId) },
                            onCancelVoice = { viewModel.cancelVoiceRecording() },
                            onTogglePlayVoice = viewModel::togglePlayVoice,
                            onSeekVoice = viewModel::seekVoice,
                            onCycleVoiceSpeed = viewModel::cycleVoiceSpeed,
                            onPauseTransfer = viewModel::pauseTransfer,
                            onResumeTransfer = viewModel::resumeTransfer,
                            onCancelTransfer = viewModel::cancelTransfer,
                            onSendReaction = { mId, emoji ->
                                viewModel.sendReaction(route.conversationId, mId, emoji)
                            },
                            onCallPeer = { peerId ->
                                viewModel.startVoiceCall(peerId)
                            },
                            onMessagesViewed = { viewed ->
                                viewModel.markIncomingMessagesRead(route.conversationId, viewed)
                            }
                        )
                    }

                    composable<LanChatRoute.MeshRadar> {
                        val peers by viewModel.peers.collectAsStateWithLifecycle()
                        val routes by viewModel.routes.collectAsStateWithLifecycle()
                        val meshStats by viewModel.meshStats.collectAsStateWithLifecycle()
                        val pendingDtnCount by viewModel.pendingDtnCount.collectAsStateWithLifecycle()
                        val transportState by viewModel.transportState.collectAsStateWithLifecycle()
                        val localLocation by viewModel.localLocation.collectAsStateWithLifecycle()
                        val locationFixStatus by viewModel.locationFixStatus.collectAsStateWithLifecycle()
                        val compassHeading by viewModel.compassHeadingFlow.collectAsStateWithLifecycle(
                            initialValue = CompassHeadingState()
                        )

                        MeshRadarScreen(
                            localNodeId = identity.nodeId,
                            localName = identity.displayName,
                            peers = peers,
                            routes = routes,
                            stats = meshStats,
                            pendingDtnCount = pendingDtnCount,
                            transportState = transportState,
                            compassHeading = compassHeading,
                            localLocation = localLocation,
                            locationFixStatus = locationFixStatus,
                            shareLocationOnRadar = identity.shareLocationOnRadar,
                            onToggleShareLocation = viewModel::setShareLocationOnRadar,
                            onRefreshDiscovery = { viewModel.restartMeshDiscovery() },
                            onMessagePeer = { peerId ->
                                viewModel.openDirectChat(peerId) { convId ->
                                    if (isExpandedLayout) {
                                        splitSelectedConversationId = convId
                                        navigateToTopTab(LanChatRoute.Chats)
                                    } else {
                                        navController.navigate(LanChatRoute.Chat(convId))
                                    }
                                }
                            },
                            onCallPeer = viewModel::startVoiceCall,
                            onVerifyPeer = { peerId ->
                                navController.navigate(LanChatRoute.VerifyPeer(peerId))
                            }
                        )
                    }

                    composable<LanChatRoute.Contacts> {
                        val peers by viewModel.peers.collectAsStateWithLifecycle()
                        val routes by viewModel.routes.collectAsStateWithLifecycle()
                        ContactsScreen(
                            peers = peers,
                            reachableNodeIds = routes.keys,
                            onOpenDirectChat = { peerId ->
                                viewModel.openDirectChat(peerId) { convId ->
                                    if (isExpandedLayout) {
                                        splitSelectedConversationId = convId
                                        navigateToTopTab(LanChatRoute.Chats)
                                    } else {
                                        navController.navigate(LanChatRoute.Chat(convId))
                                    }
                                }
                            },
                            onCallPeer = viewModel::startVoiceCall,
                            onOpenVerifyQr = { nodeId ->
                                navController.navigate(LanChatRoute.VerifyPeer(nodeId))
                            },
                            onOpenCreateGroup = {
                                navController.navigate(LanChatRoute.CreateGroup)
                            }
                        )
                    }

                    composable<LanChatRoute.CreateGroup>(
                        enterTransition = { detailEnterTransition },
                        exitTransition = { detailExitTransition },
                        popEnterTransition = { detailPopEnterTransition },
                        popExitTransition = { detailPopExitTransition }
                    ) {
                        val peers by viewModel.peers.collectAsStateWithLifecycle()
                        CreateGroupScreen(
                            peers = peers,
                            onBack = { navController.popBackStack() },
                            onCreateGroup = { title, memberIds ->
                                viewModel.createGroup(title, memberIds) { groupId ->
                                    navController.popBackStack()
                                    if (isExpandedLayout) {
                                        splitSelectedConversationId = groupId
                                        navigateToTopTab(LanChatRoute.Chats)
                                    } else {
                                        navController.navigate(LanChatRoute.Chat(groupId))
                                    }
                                }
                            }
                        )
                    }

                    composable<LanChatRoute.VerifyPeer>(
                        enterTransition = { detailEnterTransition },
                        exitTransition = { detailExitTransition },
                        popEnterTransition = { detailPopEnterTransition },
                        popExitTransition = { detailPopExitTransition }
                    ) { entry ->
                        val route = entry.toRoute<LanChatRoute.VerifyPeer>()
                        val peers by viewModel.peers.collectAsStateWithLifecycle()
                        val targetPeer = route.targetNodeId?.let { id -> peers.find { it.nodeId == id } }
                        val safety = targetPeer?.let { viewModel.computeSafetyNumber(it) }
                        val myQrPayload = remember(identity.nodeId, identity.displayName, identity.signingPubB64, identity.dhPubB64) {
                            viewModel.buildMyQrString()
                        }
                        VerifyPeerScreen(
                            identity = identity,
                            myQrPayload = myQrPayload,
                            targetPeer = targetPeer,
                            safetyNumber = safety,
                            onBack = { navController.popBackStack() },
                            onQrScanned = { raw, done ->
                                viewModel.verifyScannedQr(raw, done)
                            },
                            onManualVerify = { nodeId ->
                                viewModel.verifyPeerManually(nodeId)
                            }
                        )
                    }

                    composable<LanChatRoute.Settings> {
                        val diagnosticLogs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
                        SettingsScreen(
                            identity = identity,
                            diagnosticLogs = diagnosticLogs,
                            onUpdateDisplayName = viewModel::updateDisplayName,
                            onToggleBackgroundRelay = { enabled ->
                                viewModel.toggleBackgroundRelay(context, enabled)
                            },
                            onToggleShareLocation = viewModel::setShareLocationOnRadar,
                            onSetMaxHops = viewModel::setMaxHops,
                            onSetTheme = viewModel::setThemePreference,
                            onClearHistory = viewModel::clearChatHistory
                        )
                    }
                }
            }
            }
        }

        FullScreenCallOverlay(
            callState = callState,
            onAccept = viewModel::acceptIncomingCall,
            onDeclineOrEnd = viewModel::rejectOrEndCall,
            onToggleMute = viewModel::toggleCallMute,
            onToggleSpeaker = viewModel::toggleCallSpeaker,
            onMinimize = { viewModel.setCallMinimized(true) }
        )
        }
    }
}
