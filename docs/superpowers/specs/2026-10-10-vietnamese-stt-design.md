# Thiết kế: Mic STT on-device tiếng Việt (sherpa-onnx) thay audio thô

Ngày: 2026-10-10 | Thiết bị mục tiêu: Xiaomi 14T (Dimensity 8300-Ultra, arm64-v8a, Android 14+)
Trạng thái: đã duyệt kiến trúc 3/3, chờ review spec → plan implementation

## 1. Bối cảnh & mục tiêu

- Hiện tại `sensors/MicrophoneSensor.kt` thu `AudioRecord` 16 kHz mono PCM16, gửi header WAV 44B rồi stream `audio_common_msgs/AudioData` lên `/phone/audio`. Tốn băng thông Wi-Fi/DDS, phía ROS phải tự STT.
- Mục tiêu: xử lý ngôn ngữ ở tầng điện thoại bằng `k2-fsa/sherpa-onnx`, chỉ gửi **text** lên ROS.
- Quyết định đã chốt với user:
  - Ngôn ngữ: **tiếng Việt**.
  - Chế độ: **streaming liên tục** (rảnh tay cho robot).
  - Topic: **thay hẳn bằng text**, bỏ `/phone/audio`.

## 2. Phát hiện quan trọng (từ research sherpa-onnx v1.13.8)

- Tích hợp Android chính thức: AAR qua JitPack `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`, không cần build NDK thủ công.
- **Không tồn tại model streaming (Online) tiếng Việt.** Mọi model VI chỉ là Offline. Muốn "liên tục" phải dùng pattern VAD-simulated-streaming của demo chính thức (`SherpaOnnxVadAsr` / `SimulateStreamingAsr`): silero VAD cắt câu → `OfflineRecognizer` decode từng câu.
- `AudioRecord` 16 kHz mono PCM16 của app khớp native với `FeatureConfig(sampleRate=16000, featureDim=80)` — giữ nguyên path thu âm, chỉ đổi consumer.
- Kotlin API không có NNAPI/GPU cho ASR; trên Dimensity chỉ dùng `provider="cpu"` + `numThreads=2..4`. QNN chỉ cho Snapdragon.
- Model đã chọn (phương án A): `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` (~25 MB tải → ~32 MB bung: encoder.int8.onnx + decoder.onnx + joiner.int8.onnx + tokens.txt) + `silero_vad.onnx` (~1–2 MB) + AAR (~47 MB đủ ABI, giảm khi lọc arm64-v8a). RAM dự kiến 200–400 MB, cần đo thực tế bằng Android Profiler.

## 3. Kiến trúc & luồng dữ liệu (đã duyệt)

- Xóa `MicrophoneSensor.kt` (publish `AudioData`).
- Thêm `sensors/SpeechTextSensor.kt : PhoneSensor`, giữ `id="microphone"` để `SettingsManager`/namespace cũ tương thích, topic mặc định `/phone/speech_text`, type `std_msgs/String`.
- Luồng:
  1. `AudioRecord` 16 kHz mono PCM16 giữ nguyên → chuyển `Short→Float (/32768f)` theo frame 100 ms.
  2. silero VAD phát hiện voice, cắt câu khi im lặng ~0.5 s.
  3. Mỗi segment → `OfflineRecognizer` (zipformer-vi-30M-int8, `numThreads=4`, `provider=cpu`) decode → `result.text.trim()`, bỏ rỗng.
  4. Publish `std_msgs/String` + cập nhật card UI (text cuối, messageCount).
- Không còn WAV header, không còn `/phone/audio`.
- Model load async trong `start()`: card hiện `Loading model…` → `Nghe…`.

## 4. Tích hợp & xử lý lỗi (đã duyệt)

- Gradle: thêm repo `https://jitpack.io`, `implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")`, giữ `abiFilters arm64-v8a`.
- Assets: `app/src/main/assets/sherpa-onnx-zipformer-vi-30M-int8/{encoder.int8.onnx,decoder.onnx,joiner.int8.onnx,tokens.txt}` + `silero_vad.onnx`. Lần đầu copy sang `filesDir` rồi init bằng file path.
- Threading: 1 coroutine IO đọc mic → VAD, 1 worker decode offline (không block mic), publish trên cùng worker.
- Lỗi: model thiếu → card `Model missing` + log, không crash bridge; mic busy → `Mic unavailable`; text rỗng/conf thấp → bỏ qua; `stop()` release stream + recognizer + AudioRecord.
- Quyền `RECORD_AUDIO` đã có trong `AndroidManifest.xml`, không cần thêm.

## 5. Kiểm thử & phạm vi (đã duyệt)

- Kiểm thử:
  1. `gradlew assembleDebug`, cài lên Xiaomi 14T.
  2. `ros2 topic list` thấy `/phone/speech_text`, không còn `/phone/audio`.
  3. `ros2 topic echo /phone/speech_text`, nói tiếng Việt → text hiện < 2 s sau dứt câu.
  4. Android Profiler đo RAM/CPU khi nghe liên tục 5 phút.
  5. Tắt card mic → không publish.
- Phạm vi: chỉ mic→text (xóa `MicrophoneSensor`, thêm `SpeechTextSensor`, sửa `SensorBridge` registry + `README`). Không đụng `SpeakerSensor` TTS, không wake-word, không download model runtime (bundle sẵn trong APK).
- YAGNI: bỏ QNN/GPU, bỏ partial-result realtime (model offline không có).

## 6. Self-review

- Placeholder: không còn TBD — model, version AAR (v1.13.8), topic, threading đã cụ thể.
- Nhất quán: id giữ `microphone` + topic mới `/phone/speech_text` khớp `SettingsManager` (namespace prefix + custom topic override vẫn hoạt động).
- Phạm vi: một plan duy nhất, không cần tách sub-project.
- Mơ hồ đã chốt: "streaming liên tục" = VAD-simulated-streaming offline (đã giải thích và user đồng ý phương án A); latency mục tiêu < 2 s/câu.
