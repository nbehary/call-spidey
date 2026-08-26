package dev.nate.spideycall

/**
 * Turn-by-turn reply source for [ChatState] (docs/PLAN.md §10.3). Two
 * implementations: [ElizaResponder] (the existing deterministic engine,
 * unchanged behavior) and [dev.nate.spideycall.llm.LlmResponder] (a Phase C
 * skeleton — no system prompt, output validation, or fallback yet; that
 * harness is Phase D, not built). `respond` is suspend because the LLM path
 * is real, possibly slow, async work; [ElizaResponder] just wraps its
 * synchronous call to fit the same shape.
 */
interface SpideyResponder {
    data class Reply(val text: String, val endsCall: Boolean)

    suspend fun respond(userUtterance: String): Reply

    /** Release any resources held for this call (e.g. an LLM's native conversation). No-op by default. */
    fun close() {}
}
