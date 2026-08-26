package dev.nate.spideycall.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * Dial + ring-back tones for the call screen — no audio assets needed.
 *
 * Uses [AudioManager.STREAM_MUSIC], not `STREAM_VOICE_CALL`: the latter is
 * meant for an actual in-progress telephony call and, for a regular app,
 * routes to the earpiece at near-minimum volume by default rather than the
 * main speaker — confirmed via `adb shell dumpsys audio` on-device
 * (`STREAM_VOICE_CALL`'s active route was `earpiece`, `streamVolume:1` of a
 * max 8) — which is why the ring was inaudible held away from the ear.
 * `STREAM_MUSIC` matches [StreamingPcmPlayer]'s `AudioAttributes`, so the
 * ring and Spidey's actual voice share the same audible, speaker-routed path.
 */
class CallTones {
    private var toneGen: ToneGenerator? = null
    private var stopped = false

    /** Plays a standard dial tone for 800 ms, then returns. */
    suspend fun playDialTone() {
        val gen = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME_PERCENT)
        toneGen = gen
        gen.startTone(ToneGenerator.TONE_SUP_DIAL, DIAL_TONE_MS)
        delay(DIAL_TONE_MS.toLong())
    }

    /** Starts the ring-back cadence; it loops until [stop] is called. */
    fun startRinging() {
        if (stopped) return
        val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME_PERCENT).also { toneGen = it }
        gen.startTone(ToneGenerator.TONE_SUP_RINGTONE)
    }

    /** Stops any tone and releases the generator. Safe to call more than once. */
    fun stop() {
        if (stopped) return
        stopped = true
        toneGen?.let {
            it.stopTone()
            it.release()
        }
        toneGen = null
    }

    private companion object {
        const val VOLUME_PERCENT = 80
        const val DIAL_TONE_MS = 800
    }
}
