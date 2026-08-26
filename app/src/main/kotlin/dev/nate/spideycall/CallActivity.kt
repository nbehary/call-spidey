package dev.nate.spideycall

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.nate.spideycall.audio.CallTones
import dev.nate.spideycall.eliza.ElizaEngine
import dev.nate.spideycall.eliza.ElizaScript
import dev.nate.spideycall.eliza.parseElizaScript
import dev.nate.spideycall.llm.LlmEngine
import dev.nate.spideycall.llm.LlmResponder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val VOICE_TEST_LONG_PRESS_MS = 5000L

enum class CallPhase { IDLE, DIALING, RINGING, CONNECTED, ENDED }

class CallActivity : ComponentActivity() {
    private lateinit var script: ElizaScript
    private lateinit var voiceSession: VoiceSession
    private val tones = CallTones()

    private val callPhase = mutableStateOf(CallPhase.IDLE)
    private val chat = mutableStateOf<ChatState?>(null)
    private val voiceStatus = mutableStateOf<String?>(null)
    private val showVoiceTest = mutableStateOf(false)
    private var currentResponder: SpideyResponder? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // POST_NOTIFICATIONS is best-effort (lets ModelDownloadWorker show
        // its progress notification) — only RECORD_AUDIO actually blocks the call.
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            beginCall()
        } else {
            voiceStatus.value = "Microphone permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        script = assets.open("spidey_eliza.json").bufferedReader().use { it.readText() }
            .let(::parseElizaScript)
        voiceSession = VoiceSession(applicationContext)

        // Start warming the LLM engine the moment the app opens — before the
        // user has done anything, and before voiceSession.start()'s STT/TTS
        // load (which only begins once Call is tapped, see beginCall()) so
        // it gets a head start rather than contending with them for CPU
        // during Dialing/Ringing. GPU backend (docs/PLAN.md §10.14): ~6s
        // total instead of CPU's ~27-31s. beginCall()'s HybridResponder
        // awaits this (via LlmEngine.ensureReady()) on the first real turn,
        // not here — Dialing/Ringing itself is not gated on it (only STT/TTS
        // is), so a call can already be Connected while the LLM finishes
        // warming in the background.
        LlmEngine.warmUpAsync(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showVoiceTest.value) {
                        VoiceTestScreen(onBack = { showVoiceTest.value = false })
                    } else {
                        CallScreen(
                            phase = callPhase.value,
                            chat = chat.value,
                            voiceStatus = voiceStatus.value,
                            latency = voiceSession.latency.value,
                            onCall = ::onCallTapped,
                            onTestVoices = { showVoiceTest.value = true },
                            onHangUp = ::hangUp,
                        )
                    }
                }
            }
        }
    }

    private fun onCallTapped() {
        if (callPhase.value != CallPhase.IDLE && callPhase.value != CallPhase.ENDED) return
        if (voiceSession.hasMicPermission()) {
            beginCall()
        } else {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions.launch(permissions.toTypedArray())
        }
    }

    private fun beginCall() {
        // Phase D (docs/PLAN.md §10.3): hybrid responder — LLM for natural
        // replies, ElizaEngine's deterministic guards ahead of it and as the
        // fallback on any LLM failure or unsafe output. Held separately from
        // ChatState so its LlmResponder's Conversation can be closed on
        // hangup/onDestroy without ChatState needing to know about it.
        val responder = HybridResponder(LlmResponder(applicationContext), ElizaResponder(ElizaEngine(script)))
        currentResponder = responder
        val newChat = ChatState(responder, initialGreeting = script.greetingSpoken)
        chat.value = newChat
        voiceStatus.value = null
        callPhase.value = CallPhase.DIALING

        lifecycleScope.launch {
            try {
                tones.playDialTone()
                callPhase.value = CallPhase.RINGING
                tones.startRinging()

                // Models load and the pipeline starts during Dialing/Ringing
                // so the ring masks warm-up (plan §6) — but always ring at
                // least MIN_RING_MS even when everything is already cached.
                coroutineScope {
                    val minRing = async { delay(MIN_RING_MS) }
                    val ready = async {
                        voiceSession.start(scope = lifecycleScope, chat = newChat) { status ->
                            voiceStatus.value = status
                        }
                    }
                    minRing.await()
                    ready.await()
                }

                tones.stop()
                callPhase.value = CallPhase.CONNECTED
                voiceSession.speakGreeting(script.greetingSpoken)
            } catch (e: Exception) {
                tones.stop()
                voiceStatus.value = "Voice unavailable: ${e.message}"
                callPhase.value = CallPhase.ENDED
            }
        }
    }

    private fun hangUp() {
        tones.stop()
        voiceSession.stop()
        currentResponder?.close()
        callPhase.value = CallPhase.ENDED
    }

    override fun onDestroy() {
        tones.stop()
        voiceSession.stop()
        currentResponder?.close()
        super.onDestroy()
    }

    private companion object {
        const val MIN_RING_MS = 2000L
    }
}

@Composable
private fun CallScreen(
    phase: CallPhase,
    chat: ChatState?,
    voiceStatus: String?,
    latency: TurnLatency?,
    onCall: () -> Unit,
    onTestVoices: () -> Unit,
    onHangUp: () -> Unit,
) {
    // A farewell keyword ends the call from inside the ELIZA turn loop, not
    // just from the Hang up button — react to it here.
    LaunchedEffect(chat?.ended) {
        if (chat?.ended == true && phase == CallPhase.CONNECTED) onHangUp()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Undocumented: hold the header 5s to reach the temporary voice-test
        // screen (pitch/speed + candidate voices) — no visible entry point.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("call-header").pointerInput(onTestVoices) {
                detectTapGestures(
                    onPress = {
                        try {
                            withTimeout(VOICE_TEST_LONG_PRESS_MS) { tryAwaitRelease() }
                        } catch (_: TimeoutCancellationException) {
                            onTestVoices()
                        }
                    },
                )
            },
        ) {
            Image(
                painter = painterResource(R.drawable.spidey),
                contentDescription = null,
                modifier = Modifier.size(40.dp).padding(end = 8.dp),
            )
            Text(text = "Call Spidey", style = MaterialTheme.typography.headlineSmall)
        }

        when (phase) {
            CallPhase.IDLE, CallPhase.ENDED -> IdleOrEndedBody(phase, onCall)
            CallPhase.DIALING -> StatusBody("Dialing…")
            CallPhase.RINGING -> StatusBody(voiceStatus ?: "Ringing…")
            CallPhase.CONNECTED -> ConnectedBody(chat!!, voiceStatus, latency, onHangUp)
        }
    }
}

@Composable
private fun IdleOrEndedBody(phase: CallPhase, onCall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (phase == CallPhase.ENDED) {
            Text(text = "Call ended", modifier = Modifier.padding(bottom = 16.dp))
        }
        Button(onClick = onCall, modifier = Modifier.testTag("call-button")) {
            Image(
                painter = painterResource(R.drawable.spidey),
                contentDescription = null,
                modifier = Modifier.size(24.dp).padding(end = 8.dp),
            )
            Text("Call Spidey")
        }
    }
}

@Composable
private fun StatusBody(status: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = status, modifier = Modifier.testTag("voice-status"))
    }
}

@Composable
private fun ColumnScope.ConnectedBody(
    chat: ChatState,
    voiceStatus: String?,
    latency: TurnLatency?,
    onHangUp: () -> Unit,
) {
    val context = LocalContext.current
    val chatVisible = remember { AppSettings.chatVisible(context) }
    var showTypedInput by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    if (voiceStatus != null) {
        Text(text = voiceStatus, modifier = Modifier.testTag("voice-status"))
    }
    if (latency != null) {
        Text(
            text = "STT ${latency.sttMs.toInt()}ms · TTS ${latency.ttsMs}ms · Total ${latency.totalMs}ms",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("latency-hud"),
        )
    }

    if (chatVisible) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
        ) {
            items(chat.messages) { message ->
                val speaker = if (message.fromSpidey) "Spidey" else "You"
                Text(
                    text = "$speaker: ${message.text}",
                    modifier = Modifier.padding(vertical = 4.dp).testTag("message"),
                )
            }
        }

        LaunchedEffect(chat.messages.size) {
            if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.size - 1)
        }

        if (showTypedInput) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).testTag("input-field"),
                    enabled = !chat.ended,
                    label = { Text("Say something") },
                )
                Button(
                    modifier = Modifier.padding(start = 8.dp).testTag("send-button"),
                    enabled = !chat.ended,
                    onClick = {
                        val text = input
                        input = ""
                        coroutineScope.launch { chat.send(text) }
                    },
                ) {
                    Text("Send")
                }
            }
        }
    } else {
        Spacer(modifier = Modifier.weight(1f))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (chatVisible) Arrangement.SpaceBetween else Arrangement.End,
    ) {
        if (chatVisible) {
            TextButton(
                onClick = { showTypedInput = !showTypedInput },
                modifier = Modifier.testTag("toggle-typed-input"),
            ) {
                Text(if (showTypedInput) "Hide keyboard" else "⌨️ Type instead")
            }
        }
        Button(onClick = onHangUp, modifier = Modifier.testTag("hang-up-button")) {
            Text("Hang up")
        }
    }
}
