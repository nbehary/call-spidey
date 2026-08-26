package dev.nate.spideycall.audio

/**
 * Last-resort text respellings for words the Kokoro phonemizer/acoustic model
 * gets wrong, applied right before text reaches `synthesizeStreaming` —
 * TTS-only, never touching what's shown on screen.
 *
 * **Currently empty on purpose, and the bar for adding an entry is high.** A
 * respelling only changes which *word* the phonemizer looks up; it cannot
 * choose the phonemes. Anything not in `us_gold.json`/`us_silver.json`
 * (a made-up spelling always included) falls through
 * `KokoroPhonemizer::resolve_word`'s `return lower;`, and `tokenize()` then
 * reads each raw Latin *character* as if it were already a misaki phoneme
 * symbol — so a respelling either hits some *other* real dictionary entry,
 * or produces garbage that only sounds better by luck.
 *
 * That is exactly what happened here. "name" carried a `"name" -> "naym"`
 * respelling for most of this project's life, on the strength of sounding
 * longer than the dictionary's `nˈAm`; it did, but only because the OOV path
 * happens to tokenize "naym" as /n/ + /a/ + /y/ + /m/, two vowel tokens
 * where the dictionary had one. Fixing the dictionary entry itself (see
 * [CustomPronunciationDict]) gets the same length from the *correct*
 * phonemes, so the respelling was retired 2026-08-26.
 *
 * Reach for [CustomPronunciationDict] first: it patches real phonemes into
 * the real dictionary and is the only way to actually control how a word is
 * pronounced. Use this class only for something a dictionary entry can't
 * express — expanding an abbreviation, say, or splitting a token the word
 * splitter mishandles.
 */
object TtsPronunciation {
    private val RESPELLINGS = emptyMap<String, String>()

    fun ttsFriendly(text: String): String = respell(text, RESPELLINGS)

    /** [RESPELLINGS] is empty, so tests drive the mechanism through here. */
    internal fun respell(text: String, respellings: Map<String, String>): String {
        var result = text
        for ((word, respelling) in respellings) {
            result = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).replace(result, respelling)
        }
        return result
    }
}
