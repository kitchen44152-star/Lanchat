package com.example

import android.app.Application
import com.example.crypto.CryptoManager
import com.example.data.LanChatRepository
import com.example.data.local.LanChatDatabase
import com.example.data.local.UserPreferencesStore
import com.example.media.ImageProcessor
import com.example.media.VoiceCallAudioEngine
import com.example.media.VoicePlayerController
import com.example.media.VoiceRecorderController
import com.example.mesh.call.MeshCallManager
import com.example.mesh.location.CompassHeadingTracker
import com.example.mesh.location.OfflineLocationProvider
import com.example.mesh.router.MeshRouter
import com.example.mesh.transport.NearbyMeshTransport
import java.io.File

class AppContainer(private val application: Application) {
    @Volatile
    var isAppInForeground: Boolean = false
    @Volatile
    var activeConversationId: String? = null

    val mediaRootDir: File by lazy { File(application.filesDir, "media").apply { mkdirs() } }
    val database: LanChatDatabase by lazy { LanChatDatabase.build(application) }
    val preferencesStore: UserPreferencesStore by lazy { UserPreferencesStore(application) }
    val cryptoManager: CryptoManager by lazy { CryptoManager() }
    val transport: NearbyMeshTransport by lazy { NearbyMeshTransport(application) }
    val compassTracker: CompassHeadingTracker by lazy { CompassHeadingTracker(application) }
    val offlineLocationProvider: OfflineLocationProvider by lazy { OfflineLocationProvider(application) }

    val meshRouter: MeshRouter by lazy {
        MeshRouter(
            transport = transport,
            crypto = cryptoManager,
            peerDao = database.peerDao(),
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            mediaTransferDao = database.mediaTransferDao(),
            dtnRelayDao = database.dtnRelayDao(),
            seenPacketDao = database.seenPacketDao(),
            mediaRootDir = mediaRootDir
        ).also { router ->
            router.localLocationProvider = { offlineLocationProvider.currentLocation.value }
        }
    }

    val repository: LanChatRepository by lazy {
        LanChatRepository(
            preferencesStore = preferencesStore,
            cryptoManager = cryptoManager,
            peerDao = database.peerDao(),
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            mediaTransferDao = database.mediaTransferDao(),
            dtnRelayDao = database.dtnRelayDao(),
            seenPacketDao = database.seenPacketDao(),
            meshRouter = meshRouter
        )
    }

    val voiceRecorder: VoiceRecorderController by lazy {
        VoiceRecorderController(application, mediaRootDir)
    }

    val voicePlayer: VoicePlayerController by lazy {
        VoicePlayerController(application)
    }

    val voiceCallAudioEngine: VoiceCallAudioEngine by lazy {
        VoiceCallAudioEngine(application)
    }

    val callManager: MeshCallManager by lazy {
        MeshCallManager(
            context = application,
            router = meshRouter,
            audioEngine = voiceCallAudioEngine
        )
    }

    val imageProcessor: ImageProcessor by lazy {
        ImageProcessor(application, mediaRootDir)
    }
}

class LanChatApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
