package com.example.mesh.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.LanChatApplication
import com.example.MainActivity
import com.example.R
import com.example.mesh.call.CallPhase
import com.example.mesh.call.MeshCallManager
import com.example.mesh.call.MeshCallUiState
import com.example.mesh.router.IncomingMessageAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MeshRelayService : Service() {

    companion object {
        const val CHANNEL_ID = "lanchat_mesh_relay"
        const val CHANNEL_MESSAGES_ID = "lanchat_incoming_messages"
        const val CHANNEL_CALLS_ID = "lanchat_incoming_calls"
        const val NOTIFICATION_ID = 4101
        const val CALL_NOTIFICATION_ID = 4301
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val ACTION_START_RELAY = "com.example.mesh.ACTION_START_RELAY"
        const val ACTION_STOP_RELAY = "com.example.mesh.ACTION_STOP_RELAY"
        const val ACTION_ACCEPT_CALL = "com.example.mesh.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.example.mesh.ACTION_DECLINE_CALL"
        const val ACTION_END_CALL = "com.example.mesh.ACTION_END_CALL"

        fun startRelay(context: Context): Boolean {
            val intent = Intent(context, MeshRelayService::class.java).apply {
                action = ACTION_START_RELAY
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun stopRelay(context: Context) {
            val intent = Intent(context, MeshRelayService::class.java).apply {
                action = ACTION_STOP_RELAY
            }
            runCatching { context.startService(intent) }
        }

        fun hasConnectedDevicePrerequisite(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var messageAlertsJob: Job? = null
    private var callStateJob: Job? = null
    private var lastFgsHasMic = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? LanChatApplication
        when (intent?.action) {
            ACTION_STOP_RELAY -> {
                serviceScope.launch {
                    app?.container?.preferencesStore?.setBackgroundRelayEnabled(false)
                    if (app?.container?.isAppInForeground == false) {
                        app.container.meshRouter.stop()
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_ACCEPT_CALL -> {
                app?.container?.callManager?.acceptIncomingCall()
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                runCatching { startActivity(openIntent) }
                return START_STICKY
            }
            ACTION_DECLINE_CALL, ACTION_END_CALL -> {
                app?.container?.callManager?.rejectOrEndCall()
                runCatching {
                    getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
                }
                return START_STICKY
            }
        }

        if (!hasConnectedDevicePrerequisite(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(directPeers = 0, reachablePeers = 0, relayedPackets = 0)
        if (!promoteForeground(initialNotification, includeMic = false)) {
            stopSelf()
            return START_NOT_STICKY
        }

        app?.let { lanChatApp ->
            serviceScope.launch {
                val id = lanChatApp.container.preferencesStore.getIdentitySnapshot()
                if (id.isOnboarded) {
                    lanChatApp.container.meshRouter.start(id)
                }
            }
            observeMeshStats(lanChatApp)
            observeIncomingMessages(lanChatApp)
            observeCallState(lanChatApp)
        }

        return START_STICKY
    }

    private fun promoteForeground(notification: Notification, includeMic: Boolean): Boolean {
        val micAllowed = includeMic && ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val types = if (micAllowed) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, types)
                lastFgsHasMic = micAllowed
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun observeMeshStats(app: LanChatApplication) {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            combine(
                app.container.meshRouter.meshStats,
                app.container.transport.state
            ) { stats, _ -> stats }
                .collect { stats ->
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            directPeers = stats.directPeerCount,
                            reachablePeers = stats.reachablePeerCount,
                            relayedPackets = stats.relayedPacketCount
                        )
                    )
                }
        }
    }

    private fun observeIncomingMessages(app: LanChatApplication) {
        messageAlertsJob?.cancel()
        messageAlertsJob = serviceScope.launch {
            app.container.meshRouter.incomingMessageAlerts.collect { alert ->
                val viewingSameChat = app.container.isAppInForeground &&
                    app.container.activeConversationId == alert.conversationId
                if (!viewingSameChat && canPostNotifications()) {
                    postIncomingMessageNotification(alert)
                }
            }
        }
    }

    private fun observeCallState(app: LanChatApplication) {
        callStateJob?.cancel()
        callStateJob = serviceScope.launch {
            app.container.callManager.callState.collect { call ->
                val wantsMic = call.phase == CallPhase.ACTIVE &&
                    ContextCompat.checkSelfPermission(this@MeshRelayService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (wantsMic != lastFgsHasMic) {
                    val stats = app.container.meshRouter.meshStats.value
                    promoteForeground(
                        buildNotification(stats.directPeerCount, stats.reachablePeerCount, stats.relayedPacketCount),
                        includeMic = wantsMic
                    )
                }

                val mgr = getSystemService(NotificationManager::class.java)
                val showCallNotification = !app.container.isAppInForeground &&
                    canPostNotifications() &&
                    (call.phase == CallPhase.INCOMING_RINGING || call.phase == CallPhase.ACTIVE)

                if (showCallNotification) {
                    postCallNotification(call)
                } else {
                    runCatching { mgr.cancel(CALL_NOTIFICATION_ID) }
                }
            }
        }
    }

    private fun postCallNotification(call: MeshCallUiState) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            51,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_CALLS_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentTitle(call.peerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openAppIntent)

        if (call.phase == CallPhase.INCOMING_RINGING) {
            val acceptIntent = PendingIntent.getService(
                this,
                52,
                Intent(this, MeshRelayService::class.java).apply { action = ACTION_ACCEPT_CALL },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val declineIntent = PendingIntent.getService(
                this,
                53,
                Intent(this, MeshRelayService::class.java).apply { action = ACTION_DECLINE_CALL },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder
                .setContentText(getString(R.string.notification_call_incoming_body))
                .setFullScreenIntent(openAppIntent, true)
                .addAction(0, getString(R.string.call_action_accept), acceptIntent)
                .addAction(0, getString(R.string.call_action_decline), declineIntent)
        } else {
            val endIntent = PendingIntent.getService(
                this,
                54,
                Intent(this, MeshRelayService::class.java).apply { action = ACTION_END_CALL },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val duration = MeshCallManager.formatCallDuration(call.durationSec)
            builder
                .setContentText(getString(R.string.notification_call_active_body, duration))
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.call_action_hangup), endIntent)
        }

        runCatching {
            getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, builder.build())
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun postIncomingMessageNotification(alert: IncomingMessageAlert) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, alert.conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            alert.conversationId.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentTitle(alert.senderName)
            .setContentText(alert.preview)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(4200 + (alert.conversationId.hashCode() and 0x0FFF), notification)
        }
    }

    private fun buildNotification(directPeers: Int, reachablePeers: Int, relayedPackets: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MeshRelayService::class.java).apply { action = ACTION_STOP_RELAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val summary = if (directPeers == 0 && reachablePeers == 0) {
            getString(R.string.notification_relay_scanning)
        } else {
            getString(R.string.notification_relay_summary, directPeers, reachablePeers, relayedPackets)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentTitle(getString(R.string.notification_relay_title))
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.action_pause_relay), pauseIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val relayChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_mesh_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_mesh_desc)
                setShowBadge(false)
            }
            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES_ID,
                getString(R.string.notification_channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_messages_desc)
                setShowBadge(true)
            }
            val callChannel = NotificationChannel(
                CHANNEL_CALLS_ID,
                getString(R.string.notification_channel_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_calls_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(relayChannel)
            manager.createNotificationChannel(msgChannel)
            manager.createNotificationChannel(callChannel)
        }
    }

    override fun onDestroy() {
        statusJob?.cancel()
        messageAlertsJob?.cancel()
        callStateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
