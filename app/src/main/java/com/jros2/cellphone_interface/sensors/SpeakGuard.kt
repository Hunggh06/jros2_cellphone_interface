package com.jros2.cellphone_interface.sensors

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Half-duplex guard between the speaker (TTS) and the mic (STT).
 * SpeakerSensor sets speaking=true for the whole utterance; SpeechTextSensor
 * skips VAD input while true so the mic never transcribes the phone's own
 * voice back onto /phone/speech_text.
 */
object SpeakGuard {
    private val speaking = AtomicBoolean(false)
    @Volatile var mutedUntilMs: Long = 0L

    fun setSpeaking(active: Boolean) {
        speaking.set(active)
        if (!active) mutedUntilMs = System.currentTimeMillis() + 500L
    }

    fun isMuted(): Boolean =
        speaking.get() || System.currentTimeMillis() < mutedUntilMs
}
