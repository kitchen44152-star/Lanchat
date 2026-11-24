package com.example.media

import android.content.Context
import android.media.MediaRecorder
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
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class RecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val liveAmplitudes: List<Int> = emptyList(),
    val maxDurationReached: Boolean = false
)

data class RecordedVoiceNote(
    val file: File,
    val durationMs: Long,
    val waveformBars: List<Int>
)

class VoiceRecorderController(
    private val context: Context,
    private val outputDir: File
) {
    companion object {
        const val MAX_DURATION_MS = 5 * 60 * 1000L
        const val TARGET_BAR_COUNT = 40
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtMs: Long = 0L
    private var sampleJob: Job? = null
    private val rawSamples = mutableListOf<Int>()

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun startRecording(): Boolean {
        if (_state.value.isRecording) return false
        outputDir.mkdirs()
        val file = File(outputDir, "voice_${UUID.randomUUID()}.m4a")
        rawSamples.clear()

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(24_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            currentFile = file
            startedAtMs = System.currentTimeMillis()
            _state.value = RecordingState(isRecording = true, elapsedMs = 0L, liveAmplitudes = emptyList())

            sampleJob = scope.launch {
                while (isActive && _state.value.isRecording) {
                    delay(50)
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = ((amp / 32767f) * 100f).roundToInt().coerceIn(8, 100)
                    rawSamples.add(normalized)
                    val elapsed = System.currentTimeMillis() - startedAtMs
                    val limitHit = elapsed >= MAX_DURATION_MS
                    _state.value = RecordingState(
                        isRecording = true,
                        elapsedMs = elapsed.coerceAtMost(MAX_DURATION_MS),
                        liveAmplitudes = rawSamples.takeLast(TARGET_BAR_COUNT),
                        maxDurationReached = limitHit
                    )
                    if (limitHit) {
                        break
                    }
                }
            }
            true
        } catch (_: Exception) {
            runCatching { mr.release() }
            runCatching { file.delete() }
            recorder = null
            currentFile = null
            _state.value = RecordingState()
            false
        }
    }

    fun stopAndFinish(): RecordedVoiceNote? {
        if (!_state.value.isRecording) return null
        sampleJob?.cancel()
        sampleJob = null

        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(300L)
        val file = currentFile
        val mr = recorder
        recorder = null
        currentFile = null
        _state.value = RecordingState()

        val stoppedCleanly = runCatching {
            mr?.stop()
            true
        }.getOrDefault(false)
        runCatching { mr?.release() }

        if (!stoppedCleanly || file == null || !file.exists() || file.length() < 256L || elapsed < 500L) {
            runCatching { file?.delete() }
            return null
        }

        val bars = normalizeWaveform(rawSamples, TARGET_BAR_COUNT)
        return RecordedVoiceNote(file = file, durationMs = elapsed, waveformBars = bars)
    }

    fun cancelRecording() {
        sampleJob?.cancel()
        sampleJob = null
        val mr = recorder
        val file = currentFile
        recorder = null
        currentFile = null
        _state.value = RecordingState()
        runCatching { mr?.stop() }
        runCatching { mr?.release() }
        runCatching { file?.delete() }
    }

    fun normalizeWaveform(samples: List<Int>, targetBars: Int = TARGET_BAR_COUNT): List<Int> {
        if (samples.isEmpty()) return List(targetBars) { 24 }
        if (samples.size <= targetBars) {
            return List(targetBars) { idx ->
                samples[(idx * samples.size) / targetBars].coerceIn(10, 100)
            }
        }
        val step = samples.size.toFloat() / targetBars.toFloat()
        return List(targetBars) { bar ->
            val start = (bar * step).toInt().coerceIn(0, samples.lastIndex)
            val end = (((bar + 1) * step).toInt()).coerceIn(start + 1, samples.size)
            val slice = samples.subList(start, end)
            (slice.maxOrNull() ?: 20).coerceIn(10, 100)
        }
    }
}
