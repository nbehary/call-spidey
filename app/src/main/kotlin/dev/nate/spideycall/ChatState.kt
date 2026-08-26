package dev.nate.spideycall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.nate.spideycall.eliza.Normalizer

data class ChatMessage(val fromSpidey: Boolean, val text: String)

/**
 * One call's transcript + reply session: type a line or feed a
 * transcription, get Spidey's reply. The very first [send] after
 * construction is a scripted intro step (the greeting ends by asking for a
 * name), not a normal [responder] turn — [send] answers with "Hi, <name>!
 * What should we talk about?" and only *then* hands subsequent turns to
 * [responder]. Both the mic path (TurnController) and the typed-input debug
 * path call [send], so the intro flow applies identically either way.
 */
class ChatState(private val responder: SpideyResponder, initialGreeting: String) {
    val messages: SnapshotStateList<ChatMessage> =
        mutableStateListOf(ChatMessage(fromSpidey = true, text = initialGreeting))

    // Compose state (not a plain var): CallActivity's call-phase machine needs
    // to react when a farewell keyword ends the call, not just when the
    // *next* unrelated recomposition happens to re-read this value.
    var ended: Boolean by mutableStateOf(false)
        private set

    private var awaitingName = true

    /** Returns Spidey's reply text, or null if [userText] was ignored (blank, or the call already ended). */
    suspend fun send(userText: String): String? {
        if (userText.isBlank() || ended) return null
        messages.add(ChatMessage(fromSpidey = false, text = userText))

        val replyText: String
        val endsCall: Boolean
        if (awaitingName) {
            awaitingName = false
            replyText = "Hi, ${extractName(userText)}! What should we talk about?"
            endsCall = false
        } else {
            val reply = responder.respond(userText)
            replyText = reply.text
            endsCall = reply.endsCall
        }

        messages.add(ChatMessage(fromSpidey = true, text = replyText))
        if (endsCall) ended = true
        return replyText
    }

    /** A Spidey line not driven by [engine] — e.g. surfacing a mic error. */
    fun addSystemNote(text: String) {
        if (ended) return
        messages.add(ChatMessage(fromSpidey = true, text = text))
    }

    /**
     * "My name is Alex" / "I'm Alex" / "Alex" all become "Alex". Strips the
     * common self-introduction phrasings a kid (or STT) might produce, then
     * takes the first remaining word — good enough for a first name, and
     * keeps the reply short regardless of how much the caller says. Matches
     * whole words (not a raw string prefix): a prefix present with nothing
     * after it (just "my name is") falls back to [FALLBACK_NAME] instead of
     * grabbing a word off the prefix itself.
     */
    private fun extractName(rawText: String): String {
        val words = Normalizer.normalize(rawText).split(" ").filter { it.isNotBlank() }
        for (prefixWords in NAME_PREFIX_WORDS) {
            if (words.take(prefixWords.size) == prefixWords) {
                return words.drop(prefixWords.size).firstOrNull()?.capitalize() ?: FALLBACK_NAME
            }
        }
        return words.firstOrNull()?.capitalize() ?: FALLBACK_NAME
    }

    private fun String.capitalize(): String = replaceFirstChar { it.uppercase() }

    private companion object {
        const val FALLBACK_NAME = "Pal"

        // Checked in order; Normalizer already lowercased and stripped
        // apostrophes/punctuation, so "I'm"/"it's" arrive as "im"/"its".
        val NAME_PREFIX_WORDS = listOf(
            "my name is", "names", "call me", "this is",
            "it is", "its", "i am", "im",
        ).map { it.split(" ") }
    }
}
