package dev.nate.spideycall

import dev.nate.spideycall.eliza.ElizaEngine
import dev.nate.spideycall.eliza.ElizaScript
import dev.nate.spideycall.eliza.KeywordEntry
import dev.nate.spideycall.eliza.Rule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [HybridResponder] logic in isolation — no real
 * [dev.nate.spideycall.llm.LlmEngine]/model involved. A fake
 * [SpideyResponder] stands in for the LLM path (the constructor takes the
 * interface, not the concrete [dev.nate.spideycall.llm.LlmResponder]) so
 * "does the guard check run before the LLM is ever called" and "does any
 * LLM failure fall back to Eliza" can be asserted directly.
 */
class HybridResponderTest {

    private class FakeResponder(
        private val onRespond: suspend (String) -> SpideyResponder.Reply,
    ) : SpideyResponder {
        var callCount = 0
            private set

        override suspend fun respond(userUtterance: String): SpideyResponder.Reply {
            callCount++
            return onRespond(userUtterance)
        }
    }

    private fun eliza(): ElizaResponder = ElizaResponder(
        ElizaEngine(
            ElizaScript(
                greeting = "Hi",
                greetingSpoken = "Hi!",
                keywords = listOf(
                    KeywordEntry(ElizaEngine.NONE_KEYWORD, rank = -1, rules = listOf(
                        Rule("*", listOf("eliza-fallback")),
                    )),
                ),
                farewell = listOf("Bye!"),
            ),
        ),
    )

    @Test
    fun farewellInputNeverReachesTheLlmAndEndsTheCall() = runBlocking {
        val llm = FakeResponder { error("LLM must not be called for a farewell input") }
        val hybrid = HybridResponder(llm, eliza())

        val reply = hybrid.respond("gotta go, bye!")

        assertEquals("Bye!", reply.text)
        assertEquals(true, reply.endsCall)
        assertEquals(0, llm.callCount)
    }

    @Test
    fun offLimitsInputNeverReachesTheLlm() = runBlocking {
        val llm = FakeResponder { error("LLM must not be called for an off-limits input") }
        val hybrid = HybridResponder(llm, eliza())

        val reply = hybrid.respond("do you have a gun")

        assertEquals(false, reply.endsCall)
        assertEquals(0, llm.callCount)
    }

    @Test
    fun normalInputGoesToTheLlmWhenItSucceeds() = runBlocking {
        val llm = FakeResponder { SpideyResponder.Reply(text = "Thwip! Great question, pal!", endsCall = false) }
        val hybrid = HybridResponder(llm, eliza())

        val reply = hybrid.respond("what are your powers")

        assertEquals(1, llm.callCount)
        assertEquals("Thwip! Great question, pal!", reply.text)
    }

    @Test
    fun llmFailureFallsBackToEliza() = runBlocking {
        val llm = FakeResponder { throw RuntimeException("engine not ready") }
        val hybrid = HybridResponder(llm, eliza())

        val reply = hybrid.respond("what are your powers")

        assertEquals("eliza-fallback", reply.text)
    }

    @Test
    fun cancellationIsNotSwallowedAsAFallback() {
        val llm = FakeResponder { throw CancellationException("cancelled") }
        val hybrid = HybridResponder(llm, eliza())

        assertThrows(CancellationException::class.java) {
            runBlocking { hybrid.respond("what are your powers") }
        }
    }

    @Test
    fun closeDelegatesToBothUnderlyingResponders() {
        var llmClosed = false
        var elizaClosed = false
        val llm = object : SpideyResponder {
            override suspend fun respond(userUtterance: String) = error("unused")
            override fun close() { llmClosed = true }
        }
        val eliza = object : SpideyResponder {
            override suspend fun respond(userUtterance: String) = error("unused")
            override fun close() { elizaClosed = true }
        }

        HybridResponder(llm, eliza).close()

        assertEquals(true, llmClosed)
        assertEquals(true, elizaClosed)
    }
}
