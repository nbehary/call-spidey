package dev.nate.spideycall

import dev.nate.spideycall.eliza.ElizaEngine

/**
 * Wraps the existing [ElizaEngine] behind [SpideyResponder] — same
 * deterministic behavior as before this abstraction existed, just a
 * suspend-shaped interface so [ChatState] can talk to either this or an
 * LLM-backed responder uniformly.
 */
class ElizaResponder(private val engine: ElizaEngine) : SpideyResponder {
    override suspend fun respond(userUtterance: String): SpideyResponder.Reply {
        val reply = engine.respond(userUtterance)
        return SpideyResponder.Reply(text = reply.text, endsCall = reply.endsCall)
    }
}
