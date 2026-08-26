package dev.nate.spideycall.audio

import android.content.Context
import android.media.PlaybackParams
import java.io.File

/**
 * Kokoro's Android default voice is `af_heart` — an adult female — hardcoded
 * by language in speech-core's `KokoroTts::auto_switch_voice`
 * (`kokoro_tts.cpp:196`), with no JNI path to call `set_voice()` directly
 * (see docs/PLAN.md §5.1). The swap is a file overwrite instead: after
 * models download, [installVoice] copies a pre-extracted 1024-byte embedding
 * (see `tools/make_voice.py`) over `<modelDir>/voices/af_heart.bin`.
 *
 * The actual voice/pitch/speed used are [selectedVoice]/[selectedPitch]/
 * [selectedSpeed] — `SharedPreferences` overrides (set by the in-app
 * voice-test screen, including its live pitch/speed sliders) falling back to
 * [DEFAULT_VOICE]/[DEFAULT_PITCH]/[DEFAULT_SPEED] when unset, so tuning by
 * ear doesn't need a rebuild.
 */
object SpideyVoice {
    /** Compiled-in fallbacks. [DEFAULT_VOICE] must have a matching file at
     *  `assets/voices/candidates/<name>.bin`. */
    const val DEFAULT_VOICE = "spidey_v3_blend"
    const val DEFAULT_PITCH = 0.95f
    const val DEFAULT_SPEED = 0.98f

    private const val PREFS_NAME = "spidey_voice"
    private const val KEY_SELECTED_VOICE = "selected_voice"
    private const val KEY_PITCH = "pitch"
    private const val KEY_SPEED = "speed"

    fun selectedVoice(context: Context): String =
        prefs(context).getString(KEY_SELECTED_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    fun setSelectedVoice(context: Context, voice: String) {
        prefs(context).edit().putString(KEY_SELECTED_VOICE, voice).apply()
    }

    fun selectedPitch(context: Context): Float = prefs(context).getFloat(KEY_PITCH, DEFAULT_PITCH)
    fun selectedSpeed(context: Context): Float = prefs(context).getFloat(KEY_SPEED, DEFAULT_SPEED)

    fun setSelectedPitch(context: Context, pitch: Float) {
        prefs(context).edit().putFloat(KEY_PITCH, pitch).apply()
    }

    fun setSelectedSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_SPEED, speed).apply()
    }

    /** Overwrites af_heart.bin with [selectedVoice]'s embedding. Safe to call every launch. */
    fun installVoice(context: Context, modelDir: String) {
        copyVoice(context, modelDir, selectedVoice(context))
    }

    /** Overwrites af_heart.bin with an arbitrary candidate's embedding — for the voice-test screen. */
    fun copyVoice(context: Context, modelDir: String, voiceName: String) {
        val dest = File(modelDir, "voices/af_heart.bin")
        context.assets.open("voices/candidates/$voiceName.bin").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    fun playbackParams(context: Context): PlaybackParams =
        PlaybackParams().setPitch(selectedPitch(context)).setSpeed(selectedSpeed(context))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
