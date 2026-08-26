package dev.nate.spideycall.audio

/**
 * Text respellings for words the Kokoro phonemizer/acoustic model gets
 * wrong — kept as an *empirical, by-ear* fix, not a linguistically
 * "correct" one. Full investigation trail in `docs/STATE.md` (2026-08-24);
 * the short version:
 *
 * Reading the vendored `speech-android` phonemizer source directly
 * (`kokoro_phonemizer.cpp`'s `resolve_word`) showed a word only gets real
 * phonemes via dictionary lookup (`us_gold.json`/`us_silver.json`,
 * downloaded on-device) or `-s`/`-ed`/`-ing` suffix-stripped lookup —
 * anything else falls to `return lower;`, and `tokenize()` then looks up
 * each raw Latin *character* of that as if it already were a misaki
 * phoneme symbol. Cross-checking the real on-device dictionary confirmed
 * "name" was already correctly dictionaried as `nˈAm` (misaki's uppercase
 * `A` = the /eɪ/ diphthong, genuinely long-a) — so "naym" below is
 * phonemically *wrong*, not the fixed pronunciation, and initially looked
 * like a pointless respelling worth reverting to plain "name" for the
 * "correct" dictionary phonemes.
 *
 * Reverting it was a mistake: the user confirmed by ear that plain "name"
 * (the dictionary-correct `nˈAm`) sounds *worse* — shorter — than "naym"'s
 * broken OOV rendering. Whatever "naym"'s raw-character fallback actually
 * decodes to on this specific voice apparently renders with a more
 * satisfying vowel length than the textbook-correct diphthong does. Kept
 * as "naym", not further tinkered with ("naim" tried and rejected — turned
 * out to be a real dictionary word mapping to the same `nˈAm`, i.e.
 * indistinguishable from plain "name" by ear). Lesson: judge these purely
 * by ear, not by whether the phoneme string looks "correct" — the
 * acoustic model's actual rendering is what matters, not the dictionary.
 *
 * "Spidey" needed the opposite fix — it was never dictionaried at all, so
 * no respelling here could reach it either way; patched into the real
 * dictionary instead, see [dev.nate.spideycall.audio.CustomPronunciationDict].
 */
object TtsPronunciation {
    private val RESPELLINGS = mapOf(
        "name" to "naym",
    )

    fun ttsFriendly(text: String): String {
        var result = text
        for ((word, respelling) in RESPELLINGS) {
            result = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).replace(result, respelling)
        }
        return result
    }
}
