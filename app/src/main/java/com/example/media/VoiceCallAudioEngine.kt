package com.example.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

data class EncodedAdpcmFrame(
    val data: ByteArray,
    val initialPredictor: Int,
    val initialStepIndex: Int,
    val sampleCount: Int
)

/**
 * Stateless per-frame IMA-ADPCM 4:1 codec (16-bit PCM <-> 4-bit ADPCM)
 * carrying initial predictor & step index in each packet so Bluetooth/Wi-Fi packet drops
 * never desynchronize subsequent frames.
 */
object ImaAdpcmCodec {
    private val indexTable = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    fun encode(pcm: ShortArray, count: Int = pcm.size): EncodedAdpcmFrame {
        if (count <= 0) return EncodedAdpcmFrame(ByteArray(0), 0, 0, 0)
        val initialPredictor = pcm[0].toInt()
        val initialStepIndex = 0
        var predictor = initialPredictor
        var stepIndex = initialStepIndex
        val out = ByteArray((count + 1) / 2)
        var highNibble = false
        var outIdx = 0

        for (i in 0 until count) {
            val sample = pcm[i].toInt()
            var diff = sample - predictor
            var nibble = 0
            if (diff < 0) {
                nibble = 8
                diff = -diff
            }
            val step = stepTable[stepIndex]
            var delta = step shr 3
            if (diff >= step) {
                nibble = nibble or 4
                diff -= step
                delta += step
            }
            if (diff >= (step shr 1)) {
                nibble = nibble or 2
                diff -= (step shr 1)
                delta += (step shr 1)
            }
            if (diff >= (step shr 2)) {
                nibble = nibble or 1
                delta += (step shr 2)
            }
            predictor = if ((nibble and 8) != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            stepIndex = (stepIndex + indexTable[nibble]).coerceIn(0, stepTable.lastIndex)

            if (!highNibble) {
                out[outIdx] = (nibble and 0x0F).toByte()
                highNibble = true
            } else {
                out[outIdx] = (out[outIdx].toInt() or ((nibble and 0x0F) shl 4)).toByte()
                outIdx++
                highNibble = false
            }
        }
        return EncodedAdpcmFrame(
            data = out,
            initialPredictor = initialPredictor,
            initialStepIndex = initialStepIndex,
            sampleCount = count
        )
    }

    fun decode(frame: EncodedAdpcmFrame): ShortArray {
        if (frame.sampleCount <= 0 || frame.data.isEmpty()) return ShortArray(0)
        val pcm = ShortArray(frame.sampleCount)
        var predictor = frame.initialPredictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        var stepIndex = frame.initialStepIndex.coerceIn(0, stepTable.lastIndex)

        for (i in 0 until frame.sampleCount) {
            val byteVal = frame.data[i / 2].toInt() and 0xFF
            val nibble = if (i % 2 == 0) (byteVal and 0x0F) else ((byteVal ushr 4) and 0x0F)
            val step = stepTable[stepIndex]
            var delta = step shr 3
            if ((nibble and 4) != 0) delta += step
            if ((nibble and 2) != 0) delta += (step shr 1)
            if ((nibble and 1) != 0) delta += (step shr 2)
            predictor = if ((nibble and 8) != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            stepIndex = (stepIndex + indexTable[nibble]).coerceIn(0, stepTable.lastIndex)
            pcm[i] = predictor.toShort()
        }
        return pcm
    }
}

class VoiceCallAudioEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 640 // 40ms @ 16kHz
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var ringJob: Job? = null

    private var playbackQueue: Channel<EncodedAdpcmFrame>? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var activeRingtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isMuted: Boolean = false

    @Volatile
    private var isRunning: Boolean = false

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun setSpeakerphone(enabled: Boolean) {
        val mgr = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = mgr.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        mgr.setCommunicationDevice(speaker)
                    }
                } else {
                    val earpieceOrHeadset = mgr.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    if (earpieceOrHeadset != null) {
                        mgr.setCommunicationDevice(earpieceOrHeadset)
                    } else {
                        mgr.clearCommunicationDevice()
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                mgr.isSpeakerphoneOn = enabled
            }
        }
    }

    fun startIncomingRingtone() {
        stopTones()
        ringJob = scope.launch {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ring = RingtoneManager.getRingtone(context, uri)
                activeRingtone = ring
                ring?.play()
            }
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            while (isActive) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(450L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(450L)
                    }
                }
                delay(1800L)
            }
        }
    }

    fun startOutgoingRingback() {
        stopTones()
        ringJob = scope.launch {
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55)
                toneGenerator = tg
                while (isActive) {
                    tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                    delay(2800L)
                }
            }
        }
    }

    fun stopTones() {
        ringJob?.cancel()
        ringJob = null
        runCatching { activeRingtone?.stop() }
        activeRingtone = null
        runCatching {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        }
        toneGenerator = null
    }

    @SuppressLint("MissingPermission")
    fun startAudioStream(
        speakerEnabled: Boolean,
        onLocalFrame: (EncodedAdpcmFrame, Float) -> Unit,
        onRemoteLevel: (Float) -> Unit
    ) {
        stopAudioStream()
        stopTones()
        isRunning = true

        val mgr = audioManager
        if (mgr != null) {
            runCatching {
                previousAudioMode = mgr.mode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    focusRequest = req
                    mgr.requestAudioFocus(req)
                }
                mgr.mode = AudioManager.MODE_IN_COMMUNICATION
                setSpeakerphone(speakerEnabled)
            }
        }

        val queue = Channel<EncodedAdpcmFrame>(capacity = 6)
        playbackQueue = queue

        playbackJob = scope.launch {
            runCatching {
                val minTrackBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(FRAME_SAMPLES * 4)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minTrackBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack = track
                track.play()

                for (frame in queue) {
                    if (!isRunning || !isActive) break
                    val pcm = ImaAdpcmCodec.decode(frame)
                    if (pcm.isNotEmpty()) {
                        track.write(pcm, 0, pcm.size)
                        onRemoteLevel(computeNormalizedRms(pcm, pcm.size))
                    }
                }
            }
        }

        captureJob = scope.launch {
            runCatching {
                val minRecBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(FRAME_SAMPLES * 4)

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minRecBuf
                )
                audioRecord = record
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = runCatching {
                            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                        }.getOrNull()
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        ns = runCatching {
                            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                        }.getOrNull()
                    }
                    record.startRecording()
                    val buf = ShortArray(FRAME_SAMPLES)
                    while (isRunning && isActive) {
                        val read = record.read(buf, 0, buf.size)
                        if (read > 0) {
                            if (isMuted) {
                                delay(20L)
                                continue
                            }
                            val level = computeNormalizedRms(buf, read)
                            val encoded = ImaAdpcmCodec.encode(buf, read)
                            onLocalFrame(encoded, level)
                        }
                    }
                }
            }
        }
    }

    fun enqueueRemoteFrame(frame: EncodedAdpcmFrame) {
        val q = playbackQueue ?: return
        if (!q.trySend(frame).isSuccess) {
            // Drop oldest frame if jitter buffer is full to keep call latency tight
            q.tryReceive()
            q.trySend(frame)
        }
    }

    fun stopAudioStream() {
        isRunning = false
        stopTones()
        captureJob?.cancel()
        playbackJob?.cancel()
        captureJob = null
        playbackJob = null
        playbackQueue?.close()
        playbackQueue = null

        runCatching { aec?.release() }
        runCatching { ns?.release() }
        aec = null
        ns = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        val mgr = audioManager
        if (mgr != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    mgr.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    mgr.isSpeakerphoneOn = false
                }
                mgr.mode = previousAudioMode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { mgr.abandonAudioFocusRequest(it) }
                }
            }
        }
        focusRequest = null
    }

    private fun computeNormalizedRms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / count)
        return (abs(rms) / 8000.0).toFloat().coerceIn(0f, 1f)
    }
}
