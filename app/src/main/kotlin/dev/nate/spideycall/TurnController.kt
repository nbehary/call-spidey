package dev.nate.spideycall

import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline

/** One turn's round-trip timing, for the Phase 7 latency HUD. */
data class TurnLatency(val sttMs: Float, val ttsMs: Long) {
    val totalMs: Long get() = sttMs.toLong() + ttsMs
}

/**
 * Bridges [SpeechPipeline] events into the same [ChatState] the Phase 2 text
 * loop uses, so a transcribed utterance and a typed line are handled
 * identically. After a completed transcription gets its ELIZA reply, [speak]
 * synthesizes and plays it (muting the mic for the duration — see
 * [VoiceSession]), then the pipeline is told to keep listening.
 *
 * [onEvent] is called by the caller's `pipeline.events.collect { }` loop, so
 * awaiting [speak] inside it naturally serializes turns: the next event
 * isn't processed until this one — transcript, reply, and playback — is
 * done. Pure event-in, no state of its own — testable against a fake
 * [SpeechPipeline] and a no-op [speak] with no real audio/JNI involved.
 */
class TurnController(
    private val pipeline: SpeechPipeline,
    private val chat: ChatState,
    private val speak: suspend (String) -> Unit,
    private val onLatency: (TurnLatency) -> Unit = {},
) {
    suspend fun onEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.TranscriptionCompleted -> {
                if (chat.ended) return
                // chat.send returns null for a blank transcript — the native STT
                // can legitimately emit TranscriptionCompleted("") for a short
                // noise blip (SpeechEvent.kt: `text ?: ""`). resumeListening()
                // still has to fire on that path or the pipeline never gets the
                // signal to keep listening and goes silently deaf for the rest
                // of the call (reproduced live on-device 2026-08-22).
                val reply = chat.send(event.text) ?: run { pipeline.resumeListening(); return }
                val ttsStart = System.currentTimeMillis()
                speak(reply)
                val ttsMs = System.currentTimeMillis() - ttsStart
                onLatency(TurnLatency(sttMs = event.sttMs, ttsMs = ttsMs))
                if (chat.ended) pipeline.stop() else pipeline.resumeListening()
            }
            is SpeechEvent.Error -> chat.addSystemNote("(trouble hearing you: ${event.message})")
            else -> Unit // SpeechStarted/SpeechEnded/PartialTranscription: no UI reaction needed.
        }
    }
}
