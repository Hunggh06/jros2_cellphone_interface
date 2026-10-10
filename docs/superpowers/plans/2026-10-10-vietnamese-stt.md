# Vietnamese On-Device STT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thay `MicrophoneSensor` stream audio thô bằng `SpeechTextSensor` STT tiếng Việt on-device (sherpa-onnx), chỉ publish `std_msgs/String` lên ROS.

**Architecture:** Giữ `AudioRecord` 16 kHz hiện có; thêm VAD-simulated-streaming: silero VAD cắt câu → `OfflineRecognizer` zipformer-vi-30M-int8 decode từng câu → publish text. Model load async, 2 coroutine (mic→VAD, decode→publish).

**Tech Stack:** Kotlin, sherpa-onnx v1.13.8 (JitPack AAR), silero VAD, jros2-android 1.1.7, `std_msgs/String`, Xiaomi 14T arm64-v8a.

---

## File Structure

- Modify: `app/build.gradle.kts` — thêm dependency sherpa-onnx (JitPack đã có sẵn trong `settings.gradle.kts` dòng 20).
- Create (user tải thủ công): `app/src/main/assets/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/{encoder.int8.onnx,decoder.onnx,joiner.int8.onnx,tokens.txt}` + `app/src/main/assets/silero_vad.onnx`.
- Create: `app/src/main/java/com/jros2/cellphone_interface/sensors/SpeechTextSensor.kt` — PhoneSensor mới, id=`microphone`, topic mặc định `speech_text`.
- Delete: `app/src/main/java/com/jros2/cellphone_interface/sensors/MicrophoneSensor.kt`.
- Modify: `app/src/main/java/com/jros2/cellphone_interface/SensorBridge.kt:27-40` — thay `MicrophoneSensor()` bằng `SpeechTextSensor()`.
- Modify: `README.md:80-95` — bảng sensor: mic từ `audio_common_msgs/AudioData` → `std_msgs/String /phone/speech_text`.
- Không đụng: `SpeakerSensor.kt`, `MainActivity.kt` (quyền RECORD_AUDIO đã có), `SettingsManager.kt` (id giữ nguyên nên settings cũ tương thích).

---

### Task 1: Thêm dependency sherpa-onnx

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Thêm implementation dòng duy nhất**

Mở `app/build.gradle.kts`, trong block `dependencies { ... }` sau dòng `implementation("us.ihmc:jros2-android:1.1.7")` thêm:

```kotlin
implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
```

- [ ] **Step 2: Sync và xác minh resolve**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i sherpa`
Expected: PASS — thấy `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat: add sherpa-onnx v1.13.8 dependency for on-device STT"
```

---

### Task 2: Tải model vào assets (thủ công, không code)

**Files:**
- Create: `app/src/main/assets/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/encoder.int8.onnx`
- Create: `app/src/main/assets/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/decoder.onnx`
- Create: `app/src/main/assets/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/joiner.int8.onnx`
- Create: `app/src/main/assets/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/tokens.txt`
- Create: `app/src/main/assets/silero_vad.onnx`

- [ ] **Step 1: Tải và giải nén model VI**

```bash
cd /tmp
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2
tar xvf sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
ls sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/ silero_vad.onnx
```

Expected: thấy `encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt` và `silero_vad.onnx`.

- [ ] **Step 2: Copy vào assets của app**

```bash
mkdir -p app/src/main/assets
cp -r /tmp/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09 app/src/main/assets/
cp /tmp/silero_vad.onnx app/src/main/assets/
ls app/src/main/assets/
```

Expected: PASS — đủ 2 entries trên.

- [ ] **Step 3: Commit (dùng Git LFS nếu repo đã bật, nếu không cân nhắc download-runtime sau này)**

```bash
git add app/src/main/assets/
git commit -m "feat: bundle zipformer-vi-30M-int8 + silero vad models"
```

---

### Task 3: Tạo SpeechTextSensor (VAD + OfflineRecognizer)

**Files:**
- Create: `app/src/main/java/com/jros2/cellphone_interface/sensors/SpeechTextSensor.kt`
- Reuse: `app/src/main/java/com/jros2/cellphone_interface/sensors/MicrophoneSensor.kt:41-73` (cấu hình AudioRecord 16 kHz) làm mẫu — không copy WAV header.

- [ ] **Step 1: Tạo file sensor mới với khung PhoneSensor**

```kotlin
package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.ui.graphics.Color
import com.jros2.cellphone_interface.ui.theme.MicrophoneColor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import std_msgs.String as RosString
import com.k2fsa.sherpa.onnx.*

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

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, RosString::class.java))
        _value.value = "Loading model…"
        scope.launch {
            try {
                val filesDir = copyAssetsIfNeeded(context)
                recognizer = buildRecognizer(filesDir)
                vad = buildVad(filesDir)
                withContext(Dispatchers.Main) { startMicLoop() }
                _value.value = "Nghe…"
            } catch (e: Exception) {
                _value.value = "Model missing: ${e.message?.take(60)}"
            }
        }
    }

    private fun copyAssetsIfNeeded(context: Context): java.io.File {
        val outDir = java.io.File(context.filesDir, "sherpa-vi-30m")
        if (!outDir.exists()) outDir.mkdirs()
        val names = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
        val modelSub = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09"
        for (n in names) {
            val dst = java.io.File(outDir, n)
            if (!dst.exists() || dst.length() == 0L) {
                context.assets.open("$modelSub/$n").use { inp ->
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
        val feat = getFeatureConfig(sampleRate = sampleRateHz, featureDim = 80)
        val transducer = getOfflineTransducerModelConfig(
            encoder = "${dir}/encoder.int8.onnx",
            decoder = "${dir}/decoder.onnx",
            joiner = "${dir}/joiner.int8.onnx"
        )
        val model = getOfflineModelConfig(
            transducer = transducer,
            tokens = "${dir}/tokens.txt",
            numThreads = 4,
            provider = "cpu",
            modelType = "zipformer2"
        )
        val config = getOfflineRecognizerConfig(
            featConfig = feat,
            modelConfig = model,
            decodingMethod = "greedy_search"
        )
        return OfflineRecognizer(config = config)
    }

    private fun buildVad(dir: java.io.File): Vad {
        val silero = getSileroVadModelConfig(
            model = "${dir}/silero_vad.onnx",
            threshold = 0.5f,
            minSilenceDuration = 0.5f,
            minSpeechDuration = 0.25f,
            windowSize = 512
        )
        val vadConfig = getVadModelConfig(sileroVad = silero, sampleRate = sampleRateHz)
        return Vad(config = vadConfig, bufferSizeInSeconds = 30.0f)
    }

    private fun startMicLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { _value.value = "Audio init error"; return }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 2).coerceAtLeast(sampleRateHz / 10))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            _value.value = "Mic unavailable"; recorder.release(); return
        }
        audioRecord = recorder
        recorder.startRecording()
        val frameSamples = (0.1 * sampleRateHz).toInt()
        val shortBuf = ShortArray(frameSamples)
        micJob = scope.launch {
            val speechBuffer = mutableListOf<Float>()
            while (isActive) {
                if (!enabled.value) { delay(50); continue }
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { shortBuf[it] / 32768.0f }
                vad?.acceptWaveform(samples)
                if (vad?.isSpeechDetected() == true) {
                    for (s in samples) speechBuffer.add(s)
                } else if (speechBuffer.isNotEmpty()) {
                    val utterance = speechBuffer.toFloatArray()
                    speechBuffer.clear()
                    val text = decodeUtterance(utterance)
                    if (text.isNotBlank()) publishText(text)
                    vad?.reset()
                }
            }
        }
    }

    private fun decodeUtterance(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate = sampleRateHz)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            text
        } catch (_: Exception) { "" }
    }

    private fun publishText(text: String) {
        val msg = RosString()
        msg.dataAsString = text
        publisher?.publish(msg)
        _count.value++
        _value.value = if (text.length > 40) text.take(40) + "…" else text
    }

    override fun stop() {
        micJob?.cancel(); micJob = null
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            it.release()
        }
        audioRecord = null
        try { vad?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        vad = null; recognizer = null; publisher = null
    }
}
```

Lưu ý: tên hàm helper (`getFeatureConfig`, `getOfflineTransducerModelConfig`, ...) theo đúng `sherpa-onnx/kotlin-api/*.kt` v1.13.8. Nếu IDE báo khác tên, mở `OfflineRecognizer.kt` / `Vad.kt` trong AAR sources để sửa tên cho khớp — không dùng `as any`/suppress, sửa đúng API.

- [ ] **Step 2: Build kiểm tra compile**

Run: `./gradlew :app:assembleDebug -x test`
Expected: PASS — BUILD SUCCESSFUL. Nếu lỗi tên API sherpa, sửa theo sources AAR rồi build lại.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jros2/cellphone_interface/sensors/SpeechTextSensor.kt
git commit -m "feat: add SpeechTextSensor VAD + zipformer-vi offline STT"
```

---

### Task 4: Thay registry + xóa mic cũ + cập nhật README

**Files:**
- Modify: `app/src/main/java/com/jros2/cellphone_interface/SensorBridge.kt:27-40`
- Delete: `app/src/main/java/com/jros2/cellphone_interface/sensors/MicrophoneSensor.kt`
- Modify: `README.md:80-95`

- [ ] **Step 1: Đổi registry trong SensorBridge**

```kotlin
val sensors = listOf(
    ImuSensor(),
    MagnetometerSensor(),
    PressureSensor(),
    LightSensor(),
    GpsSensor(),
    ProximitySensor(),
    BatterySensor(),
    SpeechTextSensor(),
    BiometricAuthSensor(),
    TouchScreenSensor(),
    DualCameraSensor(),
    SpeakerSensor()
)
```

- [ ] **Step 2: Xóa file mic cũ**

```bash
git rm app/src/main/java/com/jros2/cellphone_interface/sensors/MicrophoneSensor.kt
```

- [ ] **Step 3: Sửa README bảng sensor (dòng mic)**

Từ: `| **Microphone** | `/phone/audio` | `audio_common_msgs/AudioData` | Streaming (16 kHz mono PCM16) | `MicrophoneSensor.kt` |`
Thành: `| **Speech Text** | `/phone/speech_text` | `std_msgs/String` | Event (câu sau VAD) | `SpeechTextSensor.kt` |`

- [ ] **Step 4: Build + commit**

Run: `./gradlew :app:assembleDebug -x test`
Expected: PASS.

```bash
git add app/src/main/java/com/jros2/cellphone_interface/SensorBridge.kt README.md
git commit -m "feat: replace raw audio mic with speech-text publisher"
```

---

### Task 5: Kiểm thử on-device Xiaomi 14T + ROS

- [ ] **Step 1: Cài APK**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Success.

- [ ] **Step 2: Xác minh topic**

```bash
ros2 topic list | grep phone
ros2 topic info /phone/speech_text
```

Expected: thấy `/phone/speech_text` type `std_msgs/String`; không còn `/phone/audio`.

- [ ] **Step 3: Echo tiếng Việt**

```bash
ros2 topic echo /phone/speech_text
```

Nói tiếng Việt vào điện thoại → text hiện < 2 s sau dứt câu. Tắt card mic → không còn message mới.

- [ ] **Step 4: Đo hiệu năng 5 phút (Android Profiler)**

Bật bridge, nghe liên tục 5 phút. Ghi nhận: RAM peak, CPU %, độ trễ trung bình/câu. Ngưỡng chấp nhận: RAM < 500 MB, không crash, không bỏ câu liên tiếp.

---

## Self-Review

1. **Spec coverage:** Kiến trúc (Task 3+4) ✓, tích hợp AAR/assets/threading/lỗi (Task 1+2+3) ✓, kiểm thử 5 bước + phạm vi/YAGNI (Task 5, File Structure) ✓. Không thiếu yêu cầu nào.
2. **Placeholder scan:** Không có TBD/TODO — mọi step có code, lệnh, expected output cụ thể. Tên API sherpa có chốt cơ chế fallback (đối chiếu sources AAR) thay vì để mơ hồ.
3. **Type consistency:** `id="microphone"` giữ nguyên xuyên suốt → `SettingsManager.getTopicName("microphone", ...)` và namespace prefix trong `SensorBridge` vẫn đúng; `topicName` mặc định `speech_text`; `RosString.dataAsString` dùng nhất quán ở publish; `release()` cho stream/recognizer/vad đã có ở `stop()`.
