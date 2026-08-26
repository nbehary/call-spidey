package dev.nate.spideycall.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure-logic coverage for [LlmResponder.validateAndTruncate] — the Phase D safety bar, testable without a device. */
class LlmResponderTest {

    @Test
    fun blankOutputThrows() {
        assertThrows(UnsafeLlmOutputException::class.java) {
            LlmResponder.validateAndTruncate("   ")
        }
    }

    @Test
    fun offLimitsWordInOutputThrows() {
        assertThrows(UnsafeLlmOutputException::class.java) {
            LlmResponder.validateAndTruncate("I have a gun in my web-shooter!")
        }
    }

    @Test
    fun shortSafeReplyPassesThroughUnchanged() {
        assertEquals("Whoa, nice one, pal!", LlmResponder.validateAndTruncate("Whoa, nice one, pal!"))
    }

    @Test
    fun longReplyIsTruncatedToMaxReplyWords() {
        val result = LlmResponder.validateAndTruncate(
            "This is a much longer reply than Spidey should ever actually say out loud on a real " +
                "phone call to a kid who just wants to chat for a while",
        )
        assertEquals(LlmResponder.MAX_REPLY_WORDS, result.split(" ").size)
        assertEquals(
            "This is a much longer reply than Spidey should ever actually say out loud on a real " +
                "phone call to a kid who just wants",
            result,
        )
    }
}
