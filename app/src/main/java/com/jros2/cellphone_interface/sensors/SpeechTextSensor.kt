package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.jros2.cellphone_interface.ui.theme.MicrophoneColor
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import std_msgs.String as RosString

/**
 * On-device Vietnamese speech-to-text. Replaces the old raw-audio microphone
 * publisher: keeps the same 16 kHz AudioRecord path, runs silero VAD to cut utterances and
 * decodes each one with an offline zipformer transducer, publishing only text
 * as std_msgs/String (no raw audio leaves the phone).
 */
class SpeechTextSensor : PhoneSensor {
    override val id = "microphone"
    override val name = "Speech Text"
    override val icon = "📝"
    override val color: Color = MicrophoneColor
    override var topicName = "speech_text"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("Loading model…")
    override val displayValue: StateFlow<String> = _value

    private var publisher: ROS2Publisher<RosString>? = null
    private var audioRecord: AudioRecord? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var micJob: Job? = null

    private val sampleRateHz = 16_000

    companion object {
        private const val TAG = "SpeechTextSensor"
        private const val MODEL_SUBDIR = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09"
        private const val FILES_SUBDIR = "sherpa-vi-30m"
    }

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, RosString::class.java))
        _value.value = "Loading model…"
        scope.launch {
            try {
                val dir = copyAssetsIfNeeded(context)
                recognizer = buildRecognizer(dir)
                vad = buildVad(dir)
                withContext(Dispatchers.Main) { startMicLoop() }
                _value.value = "Nghe…"
            } catch (e: Exception) {
                Log.e(TAG, "Model init failed", e)
                _value.value = "Model missing: ${e.message?.take(60)}"
            }
        }
    }

    private fun copyAssetsIfNeeded(context: Context): java.io.File {
        val outDir = java.io.File(context.filesDir, FILES_SUBDIR)
        if (!outDir.exists()) outDir.mkdirs()
        val names = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        for (n in names) {
            val dst = java.io.File(outDir, n)
            if (!dst.exists() || dst.length() == 0L) {
                context.assets.open("$MODEL_SUBDIR/$n").use { inp ->
                    dst.outputStream().use { inp.copyTo(it) }
                }
            }
        }
        val vadDst = java.io.File(outDir, "silero_vad.onnx")
        if (!vadDst.exists() || vadDst.length() == 0L) {
            context.assets.open("silero_vad.onnx").use { inp ->
                vadDst.outputStream().use { inp.copyTo(it) }
            }
        }
        return outDir
    }

    private fun buildRecognizer(dir: java.io.File): OfflineRecognizer {
        val feat: FeatureConfig = getFeatureConfig(sampleRate = sampleRateHz, featureDim = 80)
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = java.io.File(dir, "encoder.int8.onnx").absolutePath,
                decoder = java.io.File(dir, "decoder.onnx").absolutePath,
                joiner = java.io.File(dir, "joiner.int8.onnx").absolutePath
            ),
            tokens = java.io.File(dir, "tokens.txt").absolutePath,
            numThreads = 4,
            provider = "cpu",
            modelType = "transducer"
        )
        val config = OfflineRecognizerConfig(
            featConfig = feat,
            modelConfig = model,
            decodingMethod = "greedy_search"
        )
        return OfflineRecognizer(config = config)
    }

    private fun buildVad(dir: java.io.File): Vad {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = java.io.File(dir, "silero_vad.onnx").absolutePath,
                threshold = 0.35f,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.3f,
                windowSize = 512
            ),
            sampleRate = sampleRateHz,
            numThreads = 1,
            provider = "cpu"
        )
        return Vad(config = config)
    }

    private fun startMicLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _value.value = "Audio init error"
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 2).coerceAtLeast(sampleRateHz / 10)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            _value.value = "Mic unavailable"
            recorder.release()
            return
        }
        audioRecord = recorder
        recorder.startRecording()

        val frameSamples = (0.1 * sampleRateHz).toInt()
        val shortBuf = ShortArray(frameSamples)
        val floatBuf = FloatArray(frameSamples)
        micJob = scope.launch {
            while (isActive) {
                if (!enabled.value) {
                    delay(50)
                    continue
                }
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                for (i in 0 until n) {
                    floatBuf[i] = shortBuf[i] / 32768.0f
                }
                val samples = if (n == floatBuf.size) floatBuf else floatBuf.copyOf(n)
                try {
                    val v = vad ?: continue
                    v.acceptWaveform(samples)
                    while (!v.empty()) {
                        val segment = v.front()
                        v.pop()
                        // Skip tiny fragments (<0.4s): not enough context, decode garbage
                        if (segment.samples.size < (sampleRateHz * 0.4).toInt()) continue
                        val text = decodeUtterance(segment.samples)
                        if (text.isNotBlank()) publishText(text)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "VAD loop failed: ${e.message}")
                }
            }
        }
    }

    private fun decodeUtterance(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        if (samples.isEmpty()) return ""
        var stream: OfflineStream? = null
        return try {
            stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRateHz)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            ""
        } finally {
            try {
                stream?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun publishText(text: String) {
        try {
            val msg = RosString()
            msg.setData(text)
            publisher?.publish(msg)
            _count.value++
            _value.value = if (text.length > 40) text.take(40) + "…" else text
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: ${e.message}")
        }
    }

    override fun stop() {
        micJob?.cancel()
        micJob = null

        audioRecord?.let { recorder ->
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
            }
            recorder.release()
        }
        audioRecord = null
        try {
            vad?.release()
        } catch (_: Exception) {
        }
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        vad = null
        recognizer = null
        publisher = null
    }
}
