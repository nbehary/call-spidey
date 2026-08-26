package dev.nate.spideycall

import dev.nate.spideycall.eliza.ElizaEngine
import dev.nate.spideycall.eliza.ElizaScript
import dev.nate.spideycall.eliza.KeywordEntry
import dev.nate.spideycall.eliza.Rule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTest {

    private fun responder(): SpideyResponder = ElizaResponder(
        ElizaEngine(
            ElizaScript(
                greeting = "Hi",
                greetingSpoken = "Hi!",
                keywords = listOf(
                    KeywordEntry(ElizaEngine.NONE_KEYWORD, rank = -1, rules = listOf(
                        Rule("*", listOf("fallback")),
                    )),
                ),
                farewell = listOf("Bye!"),
            ),
        ),
    )

    @Test
    fun seedsTranscriptWithTheGreeting() {
        val chat = ChatState(responder(), initialGreeting = "Hi, I'm Spidey!")
        assertEquals(1, chat.messages.size)
        assertEquals(ChatMessage(fromSpidey = true, text = "Hi, I'm Spidey!"), chat.messages[0])
    }

    @Test
    fun firstSendIsTreatedAsTheIntroNameStepNotElizaEngine() = runBlocking {
        val chat = ChatState(responder(), initialGreeting = "Hi!")
        val reply = chat.send("my name is Alex")
        assertEquals("Hi, Alex! What should we talk about?", reply)
        assertEquals(3, chat.messages.size)
        assertEquals(ChatMessage(fromSpidey = false, text = "my name is Alex"), chat.messages[1])
        assertEquals(ChatMessage(fromSpidey = true, text = "Hi, Alex! What should we talk about?"), chat.messages[2])
    }

    @Test
    fun secondSendReachesTheElizaEngine() = runBlocking {
        val chat = ChatState(responder(), initialGreeting = "Hi!")
        chat.send("Alex") // intro step, consumed as the name
        val reply = chat.send("hello there")
        assertEquals("fallback", reply)
        assertEquals(5, chat.messages.size)
        assertEquals(ChatMessage(fromSpidey = false, text = "hello there"), chat.messages[3])
        assertEquals(ChatMessage(fromSpidey = true, text = "fallback"), chat.messages[4])
    }

    @Test
    fun nameExtractionStripsCommonSelfIntroPhrasing() = runBlocking {
        assertEquals("Hi, Jordan! What should we talk about?", ChatState(responder(), "Hi!").send("I'm Jordan"))
        assertEquals("Hi, Sam! What should we talk about?", ChatState(responder(), "Hi!").send("it's Sam"))
        assertEquals("Hi, Riley! What should we talk about?", ChatState(responder(), "Hi!").send("call me Riley"))
        assertEquals("Hi, Casey! What should we talk about?", ChatState(responder(), "Hi!").send("Casey"))
    }

    @Test
    fun nameExtractionFallsBackToPalWhenNothingIsLeftToExtract() = runBlocking {
        assertEquals("Hi, Pal! What should we talk about?", ChatState(responder(), "Hi!").send("my name is"))
    }

    @Test
    fun blankInputIsIgnored() = runBlocking {
        val chat = ChatState(responder(), initialGreeting = "Hi!")
        chat.send("   ")
        assertEquals(1, chat.messages.size)
    }

    @Test
    fun farewellEndsTheChatAndFurtherSendsAreIgnored() = runBlocking {
        val chat = ChatState(responder(), initialGreeting = "Hi!")
        chat.send("Alex") // intro step
        chat.send("bye")
        assertTrue(chat.ended)
        val sizeAfterFarewell = chat.messages.size
        chat.send("hello again")
        assertEquals(sizeAfterFarewell, chat.messages.size)
    }

    @Test
    fun notEndedBeforeFarewell() {
        val chat = ChatState(responder(), initialGreeting = "Hi!")
        assertFalse(chat.ended)
    }
}
