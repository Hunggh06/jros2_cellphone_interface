package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.jros2.cellphone_interface.ui.theme.SpeakerColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Subscription
import us.ihmc.jros2.ROS2Topic
import std_msgs.String as RosString
import java.util.Locale

class SpeakerSensor : PhoneSensor {
    override val id = "speaker"
    override val name = "Speaker"
    override val icon = "🔊"
    override val color: Color = SpeakerColor
    override var topicName = "speaker/say"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("TTS ready")
    override val displayValue: StateFlow<String> = _value

    private var subscription: ROS2Subscription<RosString>? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val pendingQueue = ArrayDeque<String>()

    companion object {
        private const val TAG = "SpeakerSensor"
        private const val MAX_TTS_CHARS = 4000
    }

    override fun start(node: ROS2Node, context: Context) {
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val vi = Locale("vi", "VN")
                val res = tts?.setLanguage(vi)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        SpeakGuard.setSpeaking(false)
                    }
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "TTS error: $utteranceId")
                        SpeakGuard.setSpeaking(false)
                    }
                })
                ttsReady = true
                _value.value = "TTS ready"
                synchronized(pendingQueue) {
                    while (pendingQueue.isNotEmpty()) {
                        speakNow(pendingQueue.removeFirst())
                    }
                }
            } else {
                _value.value = "TTS init failed: $status"
                Log.e(TAG, "TTS init failed: $status")
            }
        }

        try {
            subscription = node.createSubscription(
                ROS2Topic(topicName, RosString::class.java)
            ) { reader ->
                val raw: String = try {
                    reader.read().dataAsString
                } catch (e: Exception) {
                    Log.w(TAG, "read failed: ${e.message}")
                    return@createSubscription
                }
                onRosText(raw)
            }
        } catch (e: Exception) {
            _value.value = "Sub failed: ${e.message}"
            Log.e(TAG, "createSubscription failed", e)
        }
    }

    private fun onRosText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (!enabled.value) return
        val clipped = if (text.length > MAX_TTS_CHARS) text.take(MAX_TTS_CHARS) else text
        if (!ttsReady) {
            synchronized(pendingQueue) {
                if (pendingQueue.size >= 3) pendingQueue.removeFirst()
                pendingQueue.addLast(clipped)
            }
            _value.value = "Queued: ${clipped.take(30)}"
            return
        }
        speakNow(clipped)
    }

    private fun speakNow(text: String) {
        try {
            val id = "spk-${System.currentTimeMillis()}"
            SpeakGuard.setSpeaking(true)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            _count.value++
            _value.value = if (text.length > 40) text.take(40) + "…" else text
        } catch (e: Exception) {
            Log.w(TAG, "speak failed: ${e.message}")
        }
    }

    override fun stop() {
        subscription = null
        synchronized(pendingQueue) { pendingQueue.clear() }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
    }
}
