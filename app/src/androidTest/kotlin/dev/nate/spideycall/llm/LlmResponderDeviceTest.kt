package dev.nate.spideycall.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real on-device confirmation of [LlmResponder]'s Phase D harness (system
 * prompt + word-count truncation) against the actual model — distinct from
 * [LlmResponderTest]'s pure-logic coverage of [LlmResponder.validateAndTruncate]
 * alone. Does **not** judge whether the persona/replies are good — that's a
 * live-speech, human-ear judgment (docs/PLAN.md §10.8 Phase D's own
 * DONE-WHEN: "20 real spoken turns"), out of reach for an automated test.
 * This only proves the harness mechanism holds up against real output.
 */
@RunWith(AndroidJUnit4::class)
class LlmResponderDeviceTest {

    @Test
    fun realReplyStaysWithinTheWordBudgetEvenWhenAskedForALongAnswer() = runBlocking {
        val modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
        require(File(modelPath).isFile) { "Push the model first: adb push gemma-4-E2B-it.litertlm $modelPath" }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val responder = LlmResponder(context)
        try {
            val reply = responder.respond(
                "Please describe every one of your superpowers in as much detail as you possibly can.",
            )

            Log.i("LlmResponderDeviceTest", "reply text: \"${reply.text}\"")
            assertTrue("expected non-blank reply text", reply.text.isNotBlank())
            val wordCount = reply.text.trim().split(Regex("\\s+")).size
            assertTrue(
                "expected reply truncated to <= ${LlmResponder.MAX_REPLY_WORDS} words, was $wordCount: \"${reply.text}\"",
                wordCount <= LlmResponder.MAX_REPLY_WORDS,
            )
            assertTrue("LLM path should never end the call on its own", !reply.endsCall)
        } finally {
            responder.close()
        }
    }
}
