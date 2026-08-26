package dev.nate.spideycall.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a pass/fail test — a generation harness. Synthesizes the same fixed
 * phrase through each candidate voice in assets/voices/candidates/ and
 * writes a playable WAV per candidate to app-private storage, so the
 * candidates can be compared (by ear, or by acoustic analysis) without
 * installing/reinstalling the app per voice. Pull with:
 *   adb shell run-as dev.nate.spideycall cat files/voice_audition/<name>.wav > <name>.wav
 */
@RunWith(AndroidJUnit4::class)
class VoiceAuditionTest {

    @Test
    fun generateAuditionClipsForAllCandidateVoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = ModelManager.modelDir(context)
        val afHeart = File(modelDir, "voices/af_heart.bin")
        require(afHeart.parentFile?.isDirectory == true) {
            "Run CallFlowTest first so models are downloaded to $modelDir"
        }

        val outDir = File(context.filesDir, "voice_audition").apply { mkdirs() }
        val candidatesDir = "voices/candidates"
        val candidateNames = context.assets.list(candidatesDir)?.map { it.removeSuffix(".bin") }
            ?: emptyList()
        assertTrue("expected candidate voice files in assets/$candidatesDir", candidateNames.isNotEmpty())

        val originalAfHeart = afHeart.readBytes()
        try {
            for (name in candidateNames) {
                context.assets.open("$candidatesDir/$name.bin").use { input ->
                    afHeart.outputStream().use { output -> input.copyTo(output) }
                }

                val synth = SpeechSynthesizer(SpeechSynthesizerConfig(modelDir = modelDir))
                try {
                    val result = synth.synthesize(PHRASE, "en")
                    writeWav(File(outDir, "$name.wav"), result.sampleRate, result.pcm16)
                } finally {
                    synth.close()
                }
            }
        } finally {
            afHeart.writeBytes(originalAfHeart)
        }
    }

    private fun writeWav(file: File, sampleRate: Int, pcm16: ByteArray) {
        val byteRate = sampleRate * 2 // mono, 16-bit
        file.outputStream().use { out ->
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            )
            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

            out.write("RIFF".toByteArray())
            out.write(le32(36 + pcm16.size))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1)) // PCM
            out.write(le16(1)) // mono
            out.write(le32(sampleRate))
            out.write(le32(byteRate))
            out.write(le16(2)) // block align
            out.write(le16(16)) // bits per sample
            out.write("data".toByteArray())
            out.write(le32(pcm16.size))
            out.write(pcm16)
        }
    }

    private companion object {
        const val PHRASE = "Hi, I'm Spidey! Thwip!"
    }
}
