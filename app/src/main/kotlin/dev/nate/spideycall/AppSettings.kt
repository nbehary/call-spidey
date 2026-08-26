package dev.nate.spideycall

import android.content.Context

/**
 * Small persisted app-level settings, toggled from the hidden Voice Test /
 * settings screen ([VoiceTestScreen]) — mirrors the `SharedPreferences`
 * pattern [dev.nate.spideycall.audio.SpideyVoice] already uses for
 * voice/pitch/speed, so tuning by hand doesn't need a rebuild.
 */
object AppSettings {
    /** Chat transcript + typed-input UI during a call. Off by default — a
     *  kid on a call with Spidey doesn't need a visible chat log; a
     *  parent/dev can turn it on from the hidden settings screen. */
    const val DEFAULT_CHAT_VISIBLE = false

    private const val PREFS_NAME = "app_settings"
    private const val KEY_CHAT_VISIBLE = "chat_visible"

    fun chatVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHAT_VISIBLE, DEFAULT_CHAT_VISIBLE)

    fun setChatVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHAT_VISIBLE, visible).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
