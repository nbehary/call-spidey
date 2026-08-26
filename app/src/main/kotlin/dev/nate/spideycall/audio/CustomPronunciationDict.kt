package dev.nate.spideycall.audio

import android.util.Log
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Patches word -> phoneme overrides directly into the downloaded
 * `<modelDir>/us_gold.json` pronunciation dictionary, mirroring
 * [SpideyVoice.installVoice]'s "patch a downloaded model asset after fetch,
 * before first use" pattern (there for `af_heart.bin`, here for the
 * phonemizer's dictionary).
 *
 * Why this exists, not a [TtsPronunciation] text respelling: read directly
 * from the vendored `speech-android` checkout's
 * `kokoro_phonemizer.cpp`/`.h` (`KokoroPhonemizer::resolve_word`) — a word
 * only gets real phonemes via dictionary lookup or `-s`/`-ed`/`-ing`
 * suffix-stripped lookup. Anything else (any word not in `us_gold.json`/
 * `us_silver.json`, including a made-up respelling like "spydey") falls to
 * `return lower;`, and `tokenize()` then looks up each raw Latin *character*
 * of that string as if it already were a misaki phoneme symbol — silently
 * wrong, not a reasonable approximation. Confirmed against the real
 * on-device dictionaries (2026-08-24): "name" was already correctly
 * dictionaried as `nˈAm` (misaki's uppercase `A` = the /eɪ/ diphthong) —
 * respelling it to "naym"/"naim" never fixed anything (the residual
 * shortness is downstream in Kokoro's acoustic/duration model, out of
 * reach from text). "Spidey" was never dictionaried at all, so no
 * respelling of it could work either way — hence patching the dictionary
 * directly instead.
 *
 * [SPIDEY_PHONEMES] is built by direct analogy from two *already-correct*
 * real dictionary entries, not guessed: "spider" -> `spˈIdəɹ` (same `I` =
 * /aɪ/) and "tidy" -> `tˈIdi` (same "-dy" -> "di" ending pattern).
 */
object CustomPronunciationDict {
    private const val TAG = "CustomPronunciationDict"
    private const val DICT_FILENAME = "us_gold.json"

    // Misaki phoneme string: s, p, ˈ(primary stress, U+02C8), I (/aɪ/), d, i.
    private const val SPIDEY_PHONEMES = "spˈIdi"

    private val OVERRIDES = mapOf(
        "spidey" to SPIDEY_PHONEMES,
    )

    /**
     * Idempotent and safe to call every launch: only re-parses+rewrites the
     * ~90k-entry dictionary file when an override is actually missing or
     * stale (e.g. first run after this shipped, or a fresh model
     * re-download). Never throws — a failed patch just leaves "Spidey"
     * mispronounced, not worth aborting call setup over.
     */
    fun install(modelDir: String) {
        try {
            val file = File(modelDir, DICT_FILENAME)
            if (!file.isFile) return

            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val upToDate = OVERRIDES.all { (word, phonemes) -> primitiveContentOrNull(root[word]) == phonemes }
            if (upToDate) return

            val patched = JsonObject(root + OVERRIDES.mapValues { (_, phonemes) -> JsonPrimitive(phonemes) })
            file.writeText(Json.encodeToString(JsonObject.serializer(), patched))
        } catch (e: Exception) {
            Log.w(TAG, "failed to patch $DICT_FILENAME (non-fatal, pronunciation only)", e)
        }
    }

    // Heteronym entries are nested objects ({"DEFAULT": ..., "NOUN": ...}),
    // not plain strings — contentOrNull returns null for those (and for a
    // missing key) rather than throwing, which is exactly "not up to date".
    private fun primitiveContentOrNull(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull
}
