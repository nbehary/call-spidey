package dev.nate.spideycall

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 DONE-WHEN: cold launch -> tap Call -> greeting plays -> conversation
 * -> hang up. RECORD_AUDIO is pre-granted (no system dialog to drive) so this
 * exercises the real dial/ring/answer flow — model bundle is already cached
 * on this device from earlier phases, so it runs fast.
 */
@RunWith(AndroidJUnit4::class)
class CallFlowTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val composeRule = createAndroidComposeRule<CallActivity>()

    // AppSettings' SharedPreferences are the SAME store the real app (and its
    // user) reads/writes — no test-only override (same caveat SpideyVoiceTest
    // already documents for its own prefs file). Back up and restore whatever
    // was really there so this suite never leaves a test value stuck on a
    // real device.
    private lateinit var originalAppSettingsPrefs: Map<String, *>

    @Before
    fun backupAppSettingsPrefs() {
        originalAppSettingsPrefs = appSettingsPrefs().all
    }

    @After
    fun restoreAppSettingsPrefs() {
        val editor = appSettingsPrefs().edit().clear()
        for ((key, value) in originalAppSettingsPrefs) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.commit()
    }

    private fun appSettingsPrefs() =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    @Test
    fun idleScreenShowsTheCallButton() {
        composeRule.onNodeWithTag("call-button").assertExists()
    }

    @Test
    fun callingReachesConnectedWithGreetingInTranscriptThenHangUpEndsIt() {
        appSettingsPrefs().edit().putBoolean("chat_visible", true).commit()
        composeRule.onNodeWithTag("call-button").performClick()

        // Dialing -> Ringing (models/pipeline warm up behind the ring) -> Connected.
        composeRule.waitUntil(timeoutMillis = 900_000) {
            composeRule.onAllNodesWithTag("hang-up-button").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Spidey: Hi, I'm Spidey! What is your name?").assertExists()

        composeRule.onNodeWithTag("hang-up-button").performClick()
        composeRule.onNodeWithText("Call ended").assertExists()
        composeRule.onNodeWithTag("call-button").assertExists()
    }

    @Test
    fun typedInputToggleWorksOnceConnected() {
        appSettingsPrefs().edit().putBoolean("chat_visible", true).commit()
        composeRule.onNodeWithTag("call-button").performClick()
        composeRule.waitUntil(timeoutMillis = 900_000) {
            composeRule.onAllNodesWithTag("hang-up-button").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("toggle-typed-input").performClick()
        composeRule.onNodeWithTag("input-field").performTextInput("hello there")
        composeRule.onNodeWithTag("send-button").performClick()
        composeRule.waitForIdle()

        val messages = composeRule.onAllNodesWithTag("message").fetchSemanticsNodes()
        assertTrue("expected at least 3 messages (greeting + you + reply), got ${messages.size}", messages.size >= 3)
        composeRule.onNodeWithText("You: hello there").assertExists()
    }

    /**
     * The chat transcript + typed-input UI is opt-in, off by default
     * ([AppSettings.DEFAULT_CHAT_VISIBLE]) — a kid on a call with Spidey
     * doesn't need a visible chat log. Explicitly clears any leftover
     * override so this exercises the real default, not a value some earlier
     * test left behind.
     */
    @Test
    fun chatUiIsHiddenByDefaultButTheCallStillWorks() {
        appSettingsPrefs().edit().clear().commit()
        composeRule.onNodeWithTag("call-button").performClick()
        composeRule.waitUntil(timeoutMillis = 900_000) {
            composeRule.onAllNodesWithTag("hang-up-button").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithTag("toggle-typed-input").assertCountEquals(0)
        composeRule.onAllNodesWithTag("message").assertCountEquals(0)

        composeRule.onNodeWithTag("hang-up-button").performClick()
        composeRule.onNodeWithText("Call ended").assertExists()
    }

    /**
     * Hardening check: `CallActivity` holds the live call (mic pipeline, TTS,
     * LLM conversation, transcript) in plain instance fields, not
     * `rememberSaveable`/a `ViewModel` — surviving a rotation depends entirely
     * on `AndroidManifest.xml`'s `android:configChanges` stopping the system
     * from destroying/recreating the Activity. Toggling `requestedOrientation`
     * drives the same real configuration-change path a physical rotation
     * would; asserting the same Activity instance proves recreation didn't
     * happen (rather than happening to reconstruct equal-looking state).
     */
    @Test
    fun rotatingDuringACallKeepsTheSameActivityAndTranscript() {
        appSettingsPrefs().edit().putBoolean("chat_visible", true).commit()
        composeRule.onNodeWithTag("call-button").performClick()
        composeRule.waitUntil(timeoutMillis = 900_000) {
            composeRule.onAllNodesWithTag("hang-up-button").fetchSemanticsNodes().isNotEmpty()
        }

        val activityBeforeRotation = composeRule.activity
        val messagesBefore = composeRule.onAllNodesWithTag("message").fetchSemanticsNodes().size

        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitForIdle()
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        composeRule.waitForIdle()

        assertTrue(
            "CallActivity was destroyed/recreated across a rotation - the live call would have been dropped",
            composeRule.activity === activityBeforeRotation,
        )
        assertEquals(messagesBefore, composeRule.onAllNodesWithTag("message").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("hang-up-button").assertExists()
    }
}
