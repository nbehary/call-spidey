package dev.nate.spideycall

import audio.soniqo.speech.PipelineState
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import dev.nate.spideycall.eliza.ElizaEngine
import dev.nate.spideycall.eliza.ElizaScript
import dev.nate.spideycall.eliza.KeywordEntry
import dev.nate.spideycall.eliza.Rule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal fake — no audio/JNI — that just records which lifecycle calls happened. */
private class FakeSpeechPipeline : SpeechPipeline {
    override val events: SharedFlow<SpeechEvent> = MutableSharedFlow()
    override val state: PipelineState = PipelineState.Listening
    override val nnapiFallbackReason: String? = null

    var stopped = false
    var resumeListeningCalls = 0

    override fun start() {}
    override fun stop() { stopped = true }
    override fun pushAudio(samples: FloatArray) {}
    override fun resumeListening() { resumeListeningCalls++ }
    override fun close() {}
}

class TurnControllerTest {

    private fun chat() = ChatState(
        ElizaResponder(
            ElizaEngine(
                ElizaScript(
                    greeting = "Hi",
                    greetingSpoken = "Hi!",
                    keywords = listOf(
                        KeywordEntry(ElizaEngine.NONE_KEYWORD, rank = -1, rules = listOf(
                            Rule("*", listOf("fallback")),
                        )),
                    ),
                    farewell = listOf("Bye!"),
                ),
            ),
        ),
        initialGreeting = "Hi!",
    )

    private fun controller(pipeline: FakeSpeechPipeline, chat: ChatState, spoken: MutableList<String>) =
        TurnController(pipeline, chat, speak = { text -> spoken.add(text) })

    @Test
    fun transcriptionIsRoutedThroughChatSendSpokenThenResumesListening() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        val controller = controller(pipeline, chat, spoken)

        // First turn is always the intro name step (see ChatStateTest) — get
        // past it before exercising a normal ELIZA-routed turn.
        controller.onEvent(SpeechEvent.TranscriptionCompleted("Alex", confidence = 0.9f, sttMs = 12f))
        controller.onEvent(SpeechEvent.TranscriptionCompleted("hello there", confidence = 0.9f, sttMs = 12f))

        assertEquals(5, chat.messages.size)
        assertEquals(ChatMessage(fromSpidey = false, text = "hello there"), chat.messages[3])
        assertEquals(ChatMessage(fromSpidey = true, text = "fallback"), chat.messages[4])
        assertEquals("fallback", spoken.last())
        assertEquals(2, pipeline.resumeListeningCalls)
        assertFalse(pipeline.stopped)
    }

    @Test
    fun farewellSpeaksTheFarewellThenStopsInsteadOfResumingListening() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        val controller = controller(pipeline, chat, spoken)

        controller.onEvent(SpeechEvent.TranscriptionCompleted("Alex", confidence = 0.9f, sttMs = 12f)) // intro step
        controller.onEvent(SpeechEvent.TranscriptionCompleted("bye", confidence = 0.9f, sttMs = 12f))

        assertTrue(chat.ended)
        assertEquals("Bye!", spoken.last())
        assertTrue(pipeline.stopped)
        assertEquals(1, pipeline.resumeListeningCalls) // once, after the intro step only
    }

    @Test
    fun errorEventIsSurfacedAsASystemNoteWithoutSpeakingOrEndingTheCall() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        val controller = controller(pipeline, chat, spoken)

        controller.onEvent(SpeechEvent.Error("mic dropped"))

        assertEquals(2, chat.messages.size)
        assertTrue(chat.messages[1].text.contains("mic dropped"))
        assertTrue(spoken.isEmpty())
        assertFalse(chat.ended)
    }

    @Test
    fun reportsSttAndTtsLatencyAfterATurn() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        var reported: TurnLatency? = null
        val controller = TurnController(
            pipeline, chat,
            speak = { text -> spoken.add(text) },
            onLatency = { reported = it },
        )

        controller.onEvent(SpeechEvent.TranscriptionCompleted("hello there", confidence = 0.9f, sttMs = 42f))

        assertEquals(42f, reported?.sttMs)
        assertTrue("ttsMs must be non-negative, was ${reported?.ttsMs}", (reported?.ttsMs ?: -1) >= 0)
        assertEquals((reported!!.sttMs.toLong() + reported!!.ttsMs), reported!!.totalMs)
    }

    @Test
    fun latencyIsNotReportedForNonTranscriptionEvents() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        var reported: TurnLatency? = null
        val controller = TurnController(
            pipeline, chat,
            speak = { text -> spoken.add(text) },
            onLatency = { reported = it },
        )

        controller.onEvent(SpeechEvent.Error("mic dropped"))

        assertEquals(null, reported)
    }

    @Test
    fun blankTranscriptionResumesListeningWithoutSpeakingOrStrandingThePipeline() = runBlocking {
        // The native STT can legitimately emit TranscriptionCompleted(text = "")
        // for a short noise blip (see SpeechEvent.kt's nativeEventToSpeechEvent,
        // `text ?: ""`) — reproduced live on-device: after one such event the
        // app went permanently deaf for the rest of the call. chat.send("")
        // returns null (ChatState.send: `if (userText.isBlank() || ended) return
        // null`), and the pre-fix `?: return` skipped resumeListening() on that
        // path, so the native pipeline never got the signal to keep listening.
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        val controller = controller(pipeline, chat, spoken)

        controller.onEvent(SpeechEvent.TranscriptionCompleted("Alex", confidence = 0.9f, sttMs = 12f)) // intro step
        controller.onEvent(SpeechEvent.TranscriptionCompleted("", confidence = 0.0f, sttMs = 89f))
        controller.onEvent(SpeechEvent.TranscriptionCompleted("hello there", confidence = 0.9f, sttMs = 12f))

        // One resumeListening() per turn: intro, the blank turn itself, "hello there".
        assertEquals(3, pipeline.resumeListeningCalls)
        assertFalse(pipeline.stopped)
        assertEquals("fallback", spoken.last())
        // No chat message from the blank turn: only intro (2), "hello there" (2).
        assertEquals(5, chat.messages.size)
    }

    @Test
    fun blankTranscriptionAfterCallEndedDoesNotResumeListening() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        val controller = controller(pipeline, chat, spoken)

        controller.onEvent(SpeechEvent.TranscriptionCompleted("Alex", confidence = 0.9f, sttMs = 12f)) // intro step
        controller.onEvent(SpeechEvent.TranscriptionCompleted("bye", confidence = 0.9f, sttMs = 12f)) // ends the call
        controller.onEvent(SpeechEvent.TranscriptionCompleted("", confidence = 0.0f, sttMs = 89f))

        assertTrue(chat.ended)
        assertTrue(pipeline.stopped)
        assertEquals(1, pipeline.resumeListeningCalls) // once, after the intro step only
    }

    @Test
    fun unrelatedEventsAreIgnored() = runBlocking {
        val pipeline = FakeSpeechPipeline()
        val chat = chat()
        val spoken = mutableListOf<String>()
        val controller = controller(pipeline, chat, spoken)

        controller.onEvent(SpeechEvent.SpeechStarted)
        controller.onEvent(SpeechEvent.SpeechEnded)
        controller.onEvent(SpeechEvent.PartialTranscription("hel", confidence = 0.4f))

        assertEquals(1, chat.messages.size)
        assertEquals(0, pipeline.resumeListeningCalls)
        assertTrue(spoken.isEmpty())
    }
}
