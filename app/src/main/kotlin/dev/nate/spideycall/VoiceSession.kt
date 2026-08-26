package dev.nate.spideycall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SpeechSynthesisResult
import audio.soniqo.speech.SttBackend
import audio.soniqo.speech.SttModel
import audio.soniqo.speech.TtsModel
import dev.nate.spideycall.audio.CustomPronunciationDict
import dev.nate.spideycall.audio.SpideyVoice
import dev.nate.spideycall.audio.StreamingPcmPlayer
import dev.nate.spideycall.audio.TtsPronunciation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Downloads the STT/TTS model bundle, starts [SpeechPipeline] in
 * transcribe-only mode, feeds 16 kHz mic audio into it, and speaks each
 * ELIZA reply back through a [StreamingPcmPlayer] — the Phase 3/4 voice
 * path. Mirrors control-demo's proven AudioRecord loop
 * (VOICE_RECOGNITION source, PCM_FLOAT, 512-sample blocking reads,
 * `ControlAgentActivity.kt:1030-1120`) and its warm-reused AudioTrack
 * (`StreamingPcmPlayer.kt`).
 *
 * The mic is muted (reads continue, but audio isn't pushed to the pipeline)
 * while Spidey is speaking, so he doesn't transcribe his own voice.
 */
class VoiceSession(private val context: Context) {
    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var player: StreamingPcmPlayer? = null
    private var eventsJob: Job? = null
    // speak() holds this for its whole real duration — including the
    // blocking native synthesizeStreaming call, which has no suspension
    // points and so cannot be interrupted by coroutine cancellation. stop()
    // waits to acquire it before touching the pipeline. See stop()'s doc.
    private val speakMutex = Mutex()
    @Volatile private var recording = false
    @Volatile private var micPaused = false

    /** Latest turn's STT/TTS timing, for the Phase 7 latency HUD. Null before the first turn. */
    val latency = mutableStateOf<TurnLatency?>(null)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Downloads models (if needed), starts the pipeline, and starts the mic loop. */
    suspend fun start(scope: CoroutineScope, chat: ChatState, onStatus: (String) -> Unit) {
        val modelDir = downloadSpeechModels(context, onStatus)
        withContext(Dispatchers.IO) {
            SpideyVoice.installVoice(context, modelDir)
            CustomPronunciationDict.install(modelDir)
        }

        onStatus("Waking up…")
        val p = SpeechPipeline(
            SpeechConfig(
                modelDir = modelDir,
                useNnapi = false,
                sttModel = SttModel.PARAKEET_EOU,
                sttBackend = SttBackend.ONNX,
                ttsModel = TtsModel.KOKORO_SHORT_TURN,
                pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                // 0.5 s default cuts speakers off mid-thought; control-demo
                // moved off it for the same reason.
                endOfSpeechSilenceSec = 0.8f,
            ),
        )
        pipeline = p
        player = StreamingPcmPlayer(p.ttsSampleRate).also {
            it.track.playbackParams = SpideyVoice.playbackParams(context)
        }

        val controller = TurnController(
            p, chat,
            speak = { text -> speak(p, text) },
            onLatency = { latency.value = it },
        )
        eventsJob = scope.launch {
            try {
                p.events.collect { controller.onEvent(it) }
            } catch (e: Exception) {
                onStatus("Voice error: ${e.message}")
            }
        }
        p.start()

        onStatus("Listening")
        startMic(scope, p)
    }

    /** Speaks [text] through the pipeline [start] set up. No-op before/after that. */
    suspend fun speakGreeting(text: String) {
        val p = pipeline ?: return
        speak(p, text)
    }

    /**
     * Generation and playback run as separate coroutines connected by a
     * channel — same shape as control-demo's turn loop
     * (`ControlAgentActivity.kt:627-650`). A single synchronous
     * generate-then-write loop measurably underran on this device: Kokoro's
     * per-chunk inference time is close enough to per-chunk playback time
     * that the `AudioTrack` buffer can run dry between chunks unless the
     * next chunk is already being generated while the current one plays.
     */
    private suspend fun speak(pipeline: SpeechPipeline, text: String): Unit = speakMutex.withLock {
        val streamPlayer = player ?: return@withLock
        micPaused = true
        var started = false
        val spokenText = TtsPronunciation.ttsFriendly(text)
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val audio = Channel<SpeechSynthesisResult>(Channel.UNLIMITED)
                    val producer = launch(Dispatchers.Default) {
                        try {
                            pipeline.synthesizeStreaming(spokenText, "en") { chunk, _ ->
                                if (chunk.pcm16.isNotEmpty()) {
                                    check(audio.trySend(chunk).isSuccess) { "TTS playback channel closed" }
                                }
                            }
                        } catch (e: Throwable) {
                            audio.close(e)
                            throw e
                        } finally {
                            audio.close()
                        }
                    }
                    for (chunk in audio) {
                        if (!started) {
                            streamPlayer.start(chunk.pcm16)
                            started = true
                        } else {
                            streamPlayer.write(chunk.pcm16)
                        }
                    }
                    producer.join()
                    if (started) streamPlayer.awaitDrained()
                }
            }
        } finally {
            if (started) streamPlayer.resetForNextUtterance()
            micPaused = false
        }
    }

    private fun startMic(scope: CoroutineScope, pipeline: SpeechPipeline) {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(bufferSize > 0) { "AudioRecord rejected $sampleRate Hz PCM float: $bufferSize" }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferSize,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        audioRecord = record
        recording = true
        try {
            record.startRecording()
        } catch (e: SecurityException) {
            recording = false
            record.release()
            audioRecord = null
            throw e
        }

        scope.launch(Dispatchers.IO) {
            val buffer = FloatArray(512)
            while (recording) {
                val read = try {
                    record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                } catch (_: IllegalStateException) {
                    break
                }
                // Keep draining the hardware buffer even while muted, so we
                // don't fall behind and cause overflow once unmuted.
                if (read > 0 && !micPaused) {
                    pipeline.pushAudio(if (read == buffer.size) buffer else buffer.copyOf(read))
                }
            }
        }
    }

    /**
     * A [speak] call may still be inside the blocking native
     * `synthesizeStreaming` when the caller (e.g. Activity teardown) decides
     * to stop — closing the pipeline out from under that call is a
     * use-after-free in ONNX Runtime, not a cooperative-cancellation-only
     * problem: it crashed the process on-device
     * (`KokoroTts::synthesize_chunk` inside `libonnxruntime.so`).
     *
     * `eventsJob.cancelAndJoin()` looked like the obvious fix but does NOT
     * actually wait here: cancelling `eventsJob` unwinds `collect{}` at its
     * own suspension point while `speak`'s `producer` child is still
     * genuinely running the blocking native call on its own thread (no
     * suspension point inside a JNI call for cancellation to land on) — and
     * `eventsJob` was empirically observed reporting `Cancelled`/inactive
     * within ~80 ms of cancellation, long before the native call could have
     * returned. [speakMutex] sidesteps that entirely: [speak] holds it for
     * its whole real duration and releases it in its own `finally`, so
     * acquiring it here can only succeed once that duration has genuinely
     * ended, independent of coroutine cancellation semantics.
     */
    fun stop() {
        recording = false
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Exception) {
            }
            record.release()
        }
        audioRecord = null

        eventsJob?.cancel() // stop *new* turns from starting; doesn't wait, see doc above.
        eventsJob = null
        runBlocking { withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { speakMutex.withLock {} } }

        player?.close()
        player = null
        pipeline?.stop()
        pipeline?.close()
        pipeline = null
    }

    private companion object {
        // Generous, not tight: a full generate-then-drain cycle for one
        // utterance is the thing being waited out, and Kokoro short-turn's
        // RTF (0.75-0.88, see docs/PLAN.md §2) means a ~2.5 s reply can
        // itself take a couple of real seconds to generate before playback
        // even starts draining. 1500 ms was measured too short — it expired
        // before a real in-flight greeting finished, so `pipeline.close()`
        // still ran concurrently with it and crashed the same way.
        // Bounds the wait, not a target: a full generate-then-drain cycle
        // for one utterance is what's being waited out, measured up to
        // ~9 s under CallFlowTest's concurrent load (several pipelines
        // loading back-to-back across test methods). Generous on purpose;
        // this only ever blocks during teardown/hang-up.
        const val STOP_JOIN_TIMEOUT_MS = 20_000L
    }
}
