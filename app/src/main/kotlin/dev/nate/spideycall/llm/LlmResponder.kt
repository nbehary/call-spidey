package dev.nate.spideycall.llm

import android.content.Context
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import dev.nate.spideycall.SpideyResponder
import dev.nate.spideycall.eliza.ElizaEngine
import dev.nate.spideycall.eliza.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thrown when the LLM's own output fails the Phase D safety bar (blank, or
 * trips the same [ElizaEngine.OFF_LIMITS_TRIGGERS] word list user input is
 * checked against). Signals [dev.nate.spideycall.HybridResponder] to fall
 * back to [dev.nate.spideycall.ElizaResponder] for that turn.
 */
class UnsafeLlmOutputException(message: String) : Exception(message)

/**
 * LLM-backed [SpideyResponder] (docs/PLAN.md §10.3/§10.5/§10.6, Phase D).
 * Talks to the warm [LlmEngine], keeping one [Conversation] per instance
 * (i.e. per call) so turn-to-turn context persists.
 *
 * Carries Phase D's safety harness:
 * - [SYSTEM_PROMPT] gives the model the Spidey persona + brevity/kid-safe
 *   instructions ELIZA got for free from hand-authored script lines.
 * - [MAX_OUTPUT_TOKEN] bounds decode length technically; [validateAndTruncate]
 *   applies the hard word-count truncation §10.6 decided on (keep the
 *   existing Kokoro short-turn TTS budget rather than switching TTS graphs),
 *   and rejects blank or off-limits output. [MAX_REPLY_WORDS] raised from 9
 *   to 15 on 2026-08-21 after the user heard the 9-word cutoff land
 *   mid-sentence on a live call, then 15 to 25 on 2026-08-22 (still a
 *   truncation, not a redesign) — [MAX_OUTPUT_TOKEN] scaled alongside it
 *   both times (32->56, now 56->96, ~3.7 tokens/word) since it gates decode
 *   *before* the word truncation ever runs; leaving it at 56 would have
 *   capped real output around 15 words regardless of the new word limit.
 *   Still meant to fit within what `KOKORO_SHORT_TURN` can handle (~2.5s);
 *   if this proves too long and starts clipping the *audio* instead of the
 *   text, that's the signal to lower it again.
 *
 * Farewell/off-limits detection on the *user's* input, and the actual
 * fallback-to-Eliza-on-failure, are deliberately NOT here — they run ahead
 * of this class in [dev.nate.spideycall.HybridResponder] so the
 * deterministic guards never touch the LLM at all (docs/PLAN.md §10.3).
 */
class LlmResponder(private val context: Context) : SpideyResponder {
    private var conversation: Conversation? = null

    override suspend fun respond(userUtterance: String): SpideyResponder.Reply {
        val engine = LlmEngine.ensureReady(context)
        val active = conversation ?: engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                maxOutputToken = MAX_OUTPUT_TOKEN,
            ),
        ).also { conversation = it }

        val rawText = withContext(Dispatchers.Default) {
            active.sendMessage(userUtterance).toString()
        }
        return SpideyResponder.Reply(text = validateAndTruncate(rawText), endsCall = false)
    }

    override fun close() {
        conversation?.close()
        conversation = null
    }


    companion object {
        const val MAX_REPLY_WORDS = 25
        const val MAX_OUTPUT_TOKEN = 96

        const val SYSTEM_PROMPT = "You are Spidey, talking on the phone with a kid. " +
            "Stay in character: upbeat, brave, friendly, a little playful. Keep every reply short " +
            "- one or two sentences, ideally under 15 words. Never discuss anything violent, scary, " +
            "or inappropriate for a child - gently steer back to web-slinging, friendly " +
            "neighborhood heroics, or whatever the kid brought up instead. You are not a real " +
            "assistant giving real-world advice; you are just Spidey having a fun chat. " +
            "Doc Ock is female - use she/her pronouns for her."

        /**
         * Pure validation/truncation logic, pulled out of [respond] so it can
         * be unit-tested (JVM, no device) without a real [LlmEngine].
         * Throws [UnsafeLlmOutputException] on blank or off-limits output;
         * otherwise hard-truncates to [MAX_REPLY_WORDS] words, preserving the
         * model's own casing/punctuation.
         */
        internal fun validateAndTruncate(rawText: String): String {
            val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) throw UnsafeLlmOutputException("blank reply")

            val normalized = Normalizer.normalize(rawText)
            val tripped = ElizaEngine.OFF_LIMITS_TRIGGERS.firstOrNull { trigger ->
                Regex("\\b${Regex.escape(trigger)}\\b").containsMatchIn(normalized)
            }
            if (tripped != null) throw UnsafeLlmOutputException("output tripped off-limits word: $tripped")

            return words.take(MAX_REPLY_WORDS).joinToString(" ")
        }
    }
}
