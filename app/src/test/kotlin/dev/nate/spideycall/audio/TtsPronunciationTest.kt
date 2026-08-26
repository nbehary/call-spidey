package dev.nate.spideycall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPronunciationTest {

    private val sample = mapOf("name" to "naym")

    // The shipped map is empty by design — "name", the only entry this ever
    // held, is fixed properly in CustomPronunciationDict now. Locking that in
    // so a text respelling doesn't quietly come back and shadow the
    // dictionary entry: any respelling of a word makes the phonemizer look
    // up a *different* word, so it would undo the real fix.
    @Test
    fun shipsNoRespellingsSoDictionaryEntriesAreNotShadowed() {
        assertEquals("What is your name?", TtsPronunciation.ttsFriendly("What is your name?"))
        assertTrue(TtsPronunciation.ttsFriendly("Thwip! Great question, pal!")
            .contains("question"))
    }

    @Test
    fun respellsAWholeWord() {
        assertEquals("What is your naym?", TtsPronunciation.respell("What is your name?", sample))
    }

    @Test
    fun matchesCaseInsensitively() {
        // Replacement keeps the map's own casing — fine since this only ever
        // feeds the TTS engine, never anything displayed on screen.
        assertEquals("naym's the game, pal!", TtsPronunciation.respell("Name's the game, pal!", sample))
    }

    @Test
    fun doesNotTouchWordsThatOnlyContainTheKeyAsASubstring() {
        assertEquals("That's my nickname.", TtsPronunciation.respell("That's my nickname.", sample))
    }

    @Test
    fun leavesTextWithNoKnownOffendersUnchanged() {
        assertEquals("Thwip! Great question, pal!", TtsPronunciation.respell("Thwip! Great question, pal!", sample))
    }
}
