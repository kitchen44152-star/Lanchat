package com.example.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f
) {
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

class VoicePlayerController(
    private val context: Context? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager? by lazy {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pauseIfPlaying()
        }
    }

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    fun pauseIfPlaying() {
        val current = _state.value
        if (current.isPlaying && mediaPlayer != null) {
            runCatching { mediaPlayer?.pause() }
            progressJob?.cancel()
            abandonFocus()
            _state.value = current.copy(isPlaying = false)
        }
    }

    fun togglePlay(messageId: String, filePath: String) {
        val current = _state.value
        if (current.activeMessageId == messageId && mediaPlayer != null) {
            if (current.isPlaying) {
                runCatching { mediaPlayer?.pause() }
                progressJob?.cancel()
                abandonFocus()
                _state.value = current.copy(isPlaying = false)
            } else {
                requestFocus()
                runCatching {
                    applySpeed(mediaPlayer, current.speed)
                    mediaPlayer?.start()
                }
                _state.value = current.copy(isPlaying = true)
                startProgressLoop()
            }
            return
        }

        stopAndRelease()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(filePath)
            player.prepare()
            val duration = player.duration.toLong().coerceAtLeast(1L)
            applySpeed(player, current.speed)
            player.setOnCompletionListener {
                progressJob?.cancel()
                abandonFocus()
                _state.value = _state.value.copy(isPlaying = false, positionMs = 0L)
                runCatching { player.seekTo(0) }
            }
            player.setOnErrorListener { _, _, _ ->
                stopAndRelease()
                true
            }
            requestFocus()
            player.start()
            mediaPlayer = player
            _state.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                positionMs = 0L,
                durationMs = duration,
                speed = current.speed
            )
            startProgressLoop()
        } catch (_: Exception) {
            abandonFocus()
            runCatching { player.release() }
            _state.value = VoicePlaybackState(speed = current.speed)
        }
    }

    fun seekTo(messageId: String, fraction: Float) {
        val current = _state.value
        if (current.activeMessageId != messageId || mediaPlayer == null) return
        val targetMs = (current.durationMs * fraction.coerceIn(0f, 1f)).toInt()
        runCatching { mediaPlayer?.seekTo(targetMs) }
        _state.value = current.copy(positionMs = targetMs.toLong())
    }

    fun cycleSpeed() {
        val nextSpeed = when (_state.value.speed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        applySpeed(mediaPlayer, nextSpeed)
        _state.value = _state.value.copy(speed = nextSpeed)
    }

    private fun applySpeed(player: MediaPlayer?, speed: Float) {
        if (player == null) return
        runCatching {
            val params = PlaybackParams().setSpeed(speed)
            player.playbackParams = params
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _state.value.isPlaying) {
                val pos = runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
                _state.value = _state.value.copy(positionMs = pos)
                delay(60)
            }
        }
    }

    private fun requestFocus() {
        val mgr = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                focusRequest = req
                mgr.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                mgr.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonFocus() {
        val mgr = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { mgr.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                mgr.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    fun stopAndRelease() {
        progressJob?.cancel()
        progressJob = null
        abandonFocus()
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        _state.value = VoicePlaybackState(speed = _state.value.speed)
    }
}
