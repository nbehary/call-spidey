package dev.nate.spideycall

import dev.nate.spideycall.eliza.ElizaEngine
import dev.nate.spideycall.eliza.Normalizer
import kotlinx.coroutines.CancellationException

/**
 * The Phase D "hybrid" responder (docs/PLAN.md §10.3, hybrid decision
 * confirmed by the user 2026-08-21): tries [llm] for a natural reply, but:
 * - Farewell/off-limits detection on the *user's* input runs first and
 *   stays fully deterministic — mirrors [ElizaEngine]'s own guard check
 *   exactly (same word lists, same word-boundary matching) so those two
 *   categories never reach the LLM at all, preserving the original plan's
 *   safety argument for at least these cases.
 * - Any other turn goes to [llm]; if it throws for *any* reason (technical
 *   failure, or its own output failing validation — see
 *   [dev.nate.spideycall.llm.UnsafeLlmOutputException]), falls back to
 *   [eliza] for that turn rather than surfacing an error or unsafe text.
 *   [CancellationException] is deliberately rethrown, not caught here —
 *   swallowing it would break structured concurrency for whatever coroutine
 *   is calling [respond].
 */
class HybridResponder(
    private val llm: SpideyResponder,
    private val eliza: SpideyResponder,
) : SpideyResponder {

    override suspend fun respond(userUtterance: String): SpideyResponder.Reply {
        val normalized = Normalizer.normalize(userUtterance)
        if (containsAnyPhrase(normalized, ElizaEngine.FAREWELL_TRIGGERS) ||
            containsAnyPhrase(normalized, ElizaEngine.OFF_LIMITS_TRIGGERS)
        ) {
            return eliza.respond(userUtterance)
        }

        return try {
            llm.respond(userUtterance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eliza.respond(userUtterance)
        }
    }

    override fun close() {
        llm.close()
        eliza.close()
    }

    private fun containsAnyPhrase(sentence: String, phrases: Set<String>): Boolean =
        phrases.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(sentence) }
}
