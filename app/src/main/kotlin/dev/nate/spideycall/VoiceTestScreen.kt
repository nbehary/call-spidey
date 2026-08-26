package dev.nate.spideycall

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import dev.nate.spideycall.audio.CustomPronunciationDict
import dev.nate.spideycall.audio.SpideyVoice
import dev.nate.spideycall.audio.StreamingPcmPlayer
import dev.nate.spideycall.audio.TtsPronunciation
import dev.nate.spideycall.eliza.parseElizaScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CANDIDATES_DIR = "voices/candidates"

/**
 * Temporary settings screen: play each candidate voice (see
 * `tools/make_voice.py`) through the real TTS pipeline, tune pitch/speed
 * live, and pick which voice Spidey uses. Every choice persists via
 * [SpideyVoice] (`SharedPreferences`) — no rebuild needed to try a
 * different call afterward.
 */
@Composable
fun VoiceTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var candidates by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf(SpideyVoice.selectedVoice(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var pitch by remember { mutableStateOf(SpideyVoice.selectedPitch(context)) }
    var speed by remember { mutableStateOf(SpideyVoice.selectedSpeed(context)) }
    var chatVisible by remember { mutableStateOf(AppSettings.chatVisible(context)) }
    // Read straight from the same asset CallActivity parses, rather than a
    // second hardcoded copy of the greeting — a prior hardcoded copy here
    // silently drifted stale after the scripted name-capture intro landed
    // (it was missing "What is your name?" entirely), so this can't drift again.
    var testPhrase by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        candidates = context.assets.list(CANDIDATES_DIR)
            ?.filter { it.endsWith(".bin") }
            ?.map { it.removeSuffix(".bin") }
            ?.sorted()
            ?: emptyList()
        testPhrase = context.assets.open("spidey_eliza.json").bufferedReader().use { it.readText() }
            .let(::parseElizaScript).greetingSpoken
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("voice-test-back")) {
                Text("← Back")
            }
            Text("Voice test", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "\"$testPhrase\" — tap ▶️ to hear a candidate, Use to make it Spidey's voice.",
            style = MaterialTheme.typography.labelSmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show chat during calls", modifier = Modifier.weight(1f))
            Switch(
                checked = chatVisible,
                onCheckedChange = {
                    chatVisible = it
                    AppSettings.setChatVisible(context, it)
                },
                modifier = Modifier.testTag("chat-visible-toggle"),
            )
        }

        Text("Pitch: %.2f".format(pitch), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = pitch,
            onValueChange = {
                pitch = it
                SpideyVoice.setSelectedPitch(context, it)
            },
            valueRange = 0.85f..1.6f,
            modifier = Modifier.testTag("pitch-slider"),
        )
        Text("Speed: %.2f".format(speed), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = speed,
            onValueChange = {
                speed = it
                SpideyVoice.setSelectedSpeed(context, it)
            },
            valueRange = 0.85f..1.3f,
            modifier = Modifier.testTag("speed-slider"),
        )

        if (status != null) {
            Text(text = status ?: "", modifier = Modifier.testTag("voice-test-status"))
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            items(candidates) { name ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (name == selected) "$name ✓" else name, modifier = Modifier.testTag("voice-name-$name"))
                    Row {
                        Button(
                            enabled = playing == null,
                            modifier = Modifier.testTag("voice-play-$name"),
                            onClick = {
                                playing = name
                                scope.launch {
                                    try {
                                        val modelDir = downloadSpeechModels(context) { s -> status = s }
                                        SpideyVoice.copyVoice(context, modelDir, name)
                                        CustomPronunciationDict.install(modelDir)
                                        playTestPhrase(context, modelDir, testPhrase)
                                        status = null
                                    } catch (e: Exception) {
                                        status = "Couldn't play $name: ${e.message}"
                                    } finally {
                                        playing = null
                                    }
                                }
                            },
                        ) {
                            Text(if (playing == name) "▶️…" else "▶️")
                        }
                        Button(
                            modifier = Modifier.padding(start = 4.dp).testTag("voice-use-$name"),
                            onClick = {
                                SpideyVoice.setSelectedVoice(context, name)
                                selected = name
                            },
                        ) {
                            Text("Use")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun playTestPhrase(context: Context, modelDir: String, testPhrase: String) = withContext(Dispatchers.IO) {
    val synth = SpeechSynthesizer(SpeechSynthesizerConfig(modelDir = modelDir))
    try {
        val player = StreamingPcmPlayer(synth.sampleRate).also {
            it.track.playbackParams = SpideyVoice.playbackParams(context)
        }
        try {
            // Same TtsPronunciation.ttsFriendly() pass VoiceSession.speak()
            // applies for a real call — otherwise this screen would audition
            // different audio than what a real call actually plays.
            val result = synth.synthesize(TtsPronunciation.ttsFriendly(testPhrase), "en")
            player.start(result.pcm16)
            player.awaitDrained()
        } finally {
            player.close()
        }
    } finally {
        synth.close()
    }
}
