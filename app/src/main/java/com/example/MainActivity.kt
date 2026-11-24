package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.mesh.service.MeshRelayService
import com.example.ui.LanChatApp
import com.example.ui.viewmodel.LanChatViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: LanChatViewModel by viewModels {
        LanChatViewModel.Factory((application as LanChatApplication).container)
    }

    private var deepLinkConversationId by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkConversationId = intent?.getStringExtra(MeshRelayService.EXTRA_CONVERSATION_ID)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            LanChatApp(
                viewModel = viewModel,
                widthSizeClass = windowSizeClass.widthSizeClass,
                deepLinkConversationId = deepLinkConversationId,
                onDeepLinkHandled = { deepLinkConversationId = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkConversationId = intent.getStringExtra(MeshRelayService.EXTRA_CONVERSATION_ID)
    }

    override fun onStart() {
        super.onStart()
        (application as? LanChatApplication)?.container?.isAppInForeground = true
    }

    override fun onStop() {
        (application as? LanChatApplication)?.container?.isAppInForeground = false
        super.onStop()
    }
}
