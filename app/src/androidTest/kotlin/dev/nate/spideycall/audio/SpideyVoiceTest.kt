package dev.nate.spideycall.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SpideyVoice`'s SharedPreferences are the SAME store the real app (and its
 * user) reads/writes — there is no test-only override. A prior version of
 * this test cleared and repopulated that store without restoring it,
 * which silently overwrote a real device's actual voice pick with a test
 * value ("am_eric") that stuck around after the suite finished. [backupPrefs]/
 * [restorePrefs] save and put back whatever was really there, the same way
 * VoiceAuditionTest backs up and restores the real af_heart.bin bytes.
 */
@RunWith(AndroidJUnit4::class)
class SpideyVoiceTest {

    private lateinit var modelDir: File
    private lateinit var originalPrefs: Map<String, *>

    @Before
    fun backupPrefsClearOverrideAndPrepareModelDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("spidey_voice", Context.MODE_PRIVATE)
        originalPrefs = prefs.all
        prefs.edit().clear().commit()
        modelDir = File(context.cacheDir, "spidey_voice_test").apply {
            deleteRecursively()
            File(this, "voices").mkdirs()
        }
    }

    @After
    fun restorePrefs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val editor = context.getSharedPreferences("spidey_voice", Context.MODE_PRIVATE).edit().clear()
        for ((key, value) in originalPrefs) {
            when (value) {
                is String -> editor.putString(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.commit()
    }

    @Test
    fun installVoiceUsesTheDefaultWhenNoOverrideIsSet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        SpideyVoice.installVoice(context, modelDir.absolutePath)

        assertEquals(SpideyVoice.DEFAULT_VOICE, SpideyVoice.selectedVoice(context))
        assertVoiceFileMatches(context, SpideyVoice.DEFAULT_VOICE)
    }

    @Test
    fun setSelectedVoicePersistsAndInstallVoicePicksItUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        SpideyVoice.setSelectedVoice(context, "am_eric")
        assertEquals("am_eric", SpideyVoice.selectedVoice(context))

        SpideyVoice.installVoice(context, modelDir.absolutePath)
        assertVoiceFileMatches(context, "am_eric")
    }

    @Test
    fun copyVoiceInstallsAnArbitraryCandidateRegardlessOfSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        SpideyVoice.copyVoice(context, modelDir.absolutePath, "bm_fable")

        assertVoiceFileMatches(context, "bm_fable")
    }

    @Test
    fun playbackParamsUsesDefaultPitchAndSpeedWhenNoOverrideIsSet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val params = SpideyVoice.playbackParams(context)

        assertEquals(SpideyVoice.DEFAULT_PITCH, params.pitch)
        assertEquals(SpideyVoice.DEFAULT_SPEED, params.speed)
    }

    @Test
    fun setSelectedPitchAndSpeedPersistAndPlaybackParamsPicksThemUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        SpideyVoice.setSelectedPitch(context, 1.30f)
        SpideyVoice.setSelectedSpeed(context, 1.15f)

        val params = SpideyVoice.playbackParams(context)
        assertEquals(1.30f, SpideyVoice.selectedPitch(context))
        assertEquals(1.15f, SpideyVoice.selectedSpeed(context))
        assertEquals(1.30f, params.pitch)
        assertEquals(1.15f, params.speed)
    }

    private fun assertVoiceFileMatches(context: Context, voiceName: String) {
        val installed = File(modelDir, "voices/af_heart.bin")
        assertEquals(1024L, installed.length())
        val expected = context.assets.open("voices/candidates/$voiceName.bin").use { it.readBytes() }
        assertArrayEquals(expected, installed.readBytes())
    }
}
