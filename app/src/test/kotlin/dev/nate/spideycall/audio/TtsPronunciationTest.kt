package dev.nate.spideycall.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPronunciationTest {

    // "naym" is phonemically *wrong* (it's OOV, doesn't reach the
    // dictionary-correct nˈAm) but empirically sounds better by ear than
    // plain "name" does — see TtsPronunciation.kt's class doc. Locking this
    // in so a future "fix" doesn't revert it back to the dictionary-correct
    // spelling on the assumption that's obviously better.
    @Test
    fun respellsNameAsAWholeWord() {
        assertEquals("What is your naym?", TtsPronunciation.ttsFriendly("What is your name?"))
    }

    @Test
    fun matchesCaseInsensitively() {
        // Replacement is always lowercase "naym" — fine since this only ever
        // feeds the TTS engine, never anything displayed on screen.
        assertEquals("naym's the game, pal!", TtsPronunciation.ttsFriendly("Name's the game, pal!"))
    }

    @Test
    fun doesNotTouchWordsThatOnlyContainNameAsASubstring() {
        assertEquals("That's my nickname.", TtsPronunciation.ttsFriendly("That's my nickname."))
    }

    @Test
    fun leavesTextWithNoKnownOffendersUnchanged() {
        assertEquals("Thwip! Great question, pal!", TtsPronunciation.ttsFriendly("Thwip! Great question, pal!"))
    }
}
