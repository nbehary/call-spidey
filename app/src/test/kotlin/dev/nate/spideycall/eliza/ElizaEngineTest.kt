package dev.nate.spideycall.eliza

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Always returns 0.0 from nextDouble() — forces the memory-recall branch. */
private object AlwaysRecall : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

/** Always returns close to 1.0 from nextDouble() — forces the recall branch off. */
private object NeverRecall : Random() {
    override fun nextBits(bitCount: Int): Int = Int.MAX_VALUE
}

class ElizaEngineTest {

    private fun testScript() = ElizaScript(
        greeting = "Hi",
        greetingSpoken = "Hi!",
        pre = mapOf("im" to "i am"),
        post = mapOf("i" to "you", "my" to "your", "you" to "i", "your" to "my", "am" to "are"),
        synonyms = mapOf("dog" to listOf("puppy")),
        keywords = listOf(
            KeywordEntry("doc ock", rank = 10, rules = listOf(
                Rule("*", listOf("Ock line A", "Ock line B")),
            )),
            KeywordEntry("you", rank = 2, rules = listOf(
                Rule("*", listOf("generic you line")),
            )),
            KeywordEntry("scared", rank = 5, rules = listOf(
                Rule("* i am scared of *", listOf("Being scared of (2) is okay!"), memory = true),
                Rule("*", listOf("It's okay to feel scared!")),
            )),
            KeywordEntry("dog", rank = 3, rules = listOf(
                Rule("*", listOf("Dog line")),
            )),
            KeywordEntry(ElizaEngine.NONE_KEYWORD, rank = -1, rules = listOf(
                Rule("*", listOf("fallback A", "fallback B")),
            )),
        ),
        memoryPrompts = listOf("Earlier you said (1)."),
        farewell = listOf("Bye!"),
    )

    @Test
    fun higherRankKeywordWinsWhenMultipleMatch() {
        val engine = ElizaEngine(testScript())
        val reply = engine.respond("doc ock, you are great")
        assertTrue(reply.text in setOf("Ock line A", "Ock line B"))
    }

    @Test
    fun wildcardCaptureIsReflectedThroughPostMap() {
        val engine = ElizaEngine(testScript())
        val reply = engine.respond("I am scared of my dog")
        assertEquals("Being scared of your dog is okay!", reply.text)
    }

    @Test
    fun preSubstitutionExpandsContractionsBeforeMatching() {
        val engine = ElizaEngine(testScript())
        // "I'm" -> normalized "im" -> pre-substituted "i am", matching the same decomp.
        val reply = engine.respond("I'm scared of the dark")
        assertEquals("Being scared of the dark is okay!", reply.text)
    }

    @Test
    fun synonymsMatchTheirCanonicalKeyword() {
        val engine = ElizaEngine(testScript())
        val reply = engine.respond("I love my puppy")
        assertEquals("Dog line", reply.text)
    }

    @Test
    fun roundRobinCyclesThroughAllOptionsBeforeRepeating() {
        val engine = ElizaEngine(testScript())
        val first = engine.respond("doc ock").text
        val second = engine.respond("doc ock").text
        val third = engine.respond("doc ock").text
        assertTrue(setOf(first, second) == setOf("Ock line A", "Ock line B"))
        assertTrue("second reply must differ from the first", first != second)
        assertEquals("cycle of 2 wraps back to the first option on the third call", first, third)
    }

    @Test
    fun memoryRuleIsRecalledOnAFallbackWhenChanceHits() {
        val engine = ElizaEngine(testScript(), random = AlwaysRecall)
        engine.respond("I am scared of my dog") // pushes "Being scared of your dog is okay!"
        val reply = engine.respond("purple elephants dance") // no keyword match -> fallback
        assertEquals("Earlier you said Being scared of your dog is okay!.", reply.text)
        assertFalse(reply.endsCall)
    }

    @Test
    fun fallbackUsesNoneKeywordWhenRecallChanceMisses() {
        val engine = ElizaEngine(testScript(), random = NeverRecall)
        engine.respond("I am scared of my dog") // pushes memory, but recall chance will miss
        val reply = engine.respond("purple elephants dance")
        assertTrue(reply.text in setOf("fallback A", "fallback B"))
    }

    @Test
    fun fallbackUsesNoneKeywordWhenMemoryIsEmpty() {
        val engine = ElizaEngine(testScript(), random = AlwaysRecall)
        val reply = engine.respond("purple elephants dance")
        assertTrue(reply.text in setOf("fallback A", "fallback B"))
    }

    @Test
    fun farewellTriggerEndsTheCall() {
        val engine = ElizaEngine(testScript())
        val reply = engine.respond("okay bye")
        assertEquals("Bye!", reply.text)
        assertTrue(reply.endsCall)
    }

    @Test
    fun offLimitsTriggerDeflectsWithoutEndingTheCall() {
        val engine = ElizaEngine(testScript())
        val reply = engine.respond("do you have a gun")
        assertTrue(reply.text in ElizaEngine.OFF_LIMITS_REPLIES)
        assertFalse(reply.endsCall)
    }
}
