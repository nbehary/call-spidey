package dev.nate.spideycall.eliza

/**
 * Cleans up raw STT transcripts before the ELIZA engine sees them: lowercase,
 * strip punctuation, collapse whitespace, then correct known mishearings of
 * character names into one canonical spelling the keyword table can match.
 */
object Normalizer {

    // Longest phrases first so e.g. "spider man" is consumed before a
    // shorter overlapping pattern could partially match it.
    private val mishearings: List<Pair<Regex, String>> = listOf(
        Regex("""\b(spider man|spiderman|spidey)\b""") to "spiderman",
        Regex("""\b(doctor octopus|doc oc|dock ock)\b""") to "doc ock",
        Regex("""\bmiles morales\b""") to "spin",
        Regex("""\bgwen\b""") to "ghost spider",
    )

    fun normalize(raw: String): String {
        var text = raw.lowercase()
        // Apostrophes are deleted, not turned into a separator, so "don't" -> "dont"
        // stays one token the [pre] contraction map can look up.
        text = text.replace("'", "")
        text = text.replace(Regex("[^a-z0-9\\s]"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()
        for ((pattern, replacement) in mishearings) {
            text = pattern.replace(text, replacement)
        }
        return text
    }
}
