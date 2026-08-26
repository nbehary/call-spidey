package dev.nate.spideycall.eliza

import kotlin.random.Random

/**
 * Classic Weizenbaum-style pattern matcher: rank -> decompose -> reassemble,
 * plus a small memory stack and two guards (farewell, off-limits) that run
 * before keyword matching. The whole personality lives in [script] — this
 * class is pure logic with no Spidey-specific text in it.
 */
class ElizaEngine(
    private val script: ElizaScript,
    private val random: Random = Random.Default,
) {
    data class Reply(val text: String, val endsCall: Boolean)

    private val ruleCursor = mutableMapOf<String, Int>()
    private val memory = ArrayDeque<String>()

    fun respond(userUtterance: String): Reply {
        val normalized = Normalizer.normalize(userUtterance)

        if (containsAnyPhrase(normalized, FAREWELL_TRIGGERS)) {
            return Reply(pickRoundRobin("\$farewell", script.farewell), endsCall = true)
        }
        if (containsAnyPhrase(normalized, OFF_LIMITS_TRIGGERS)) {
            return Reply(pickRoundRobin("\$offLimits", OFF_LIMITS_REPLIES), endsCall = false)
        }

        val sentence = applyPre(normalized)
        val entry = script.keywords
            .filter { matchesKeyword(it, sentence) }
            .maxByOrNull { it.rank }
            ?: return Reply(fallbackOrMemory(), endsCall = false)

        if (entry.word == NONE_KEYWORD) return Reply(fallbackOrMemory(), endsCall = false)

        for (rule in entry.rules) {
            val match = decompRegex(rule.decomp).matchEntire(sentence) ?: continue
            val groups = match.groupValues.drop(1)
            val line = reassemble(entry.word, rule, groups)
            if (rule.memory) pushMemory(line)
            return Reply(line, endsCall = false)
        }
        return Reply(fallbackOrMemory(), endsCall = false)
    }

    private fun fallbackOrMemory(): String {
        if (memory.isNotEmpty() && random.nextDouble() < MEMORY_RECALL_CHANCE) {
            val recalled = memory.removeLast()
            val prompt = script.memoryPrompts.firstOrNull()
            if (prompt != null) return prompt.replace("(1)", recalled)
        }
        val noneEntry = script.keywords.first { it.word == NONE_KEYWORD }
        val rule = noneEntry.rules.first()
        return pickRoundRobin(NONE_KEYWORD, rule.reassemble)
    }

    private fun matchesKeyword(entry: KeywordEntry, sentence: String): Boolean {
        if (entry.word == NONE_KEYWORD) return false // handled as the guaranteed fallback, not a normal match
        return containsPhrase(sentence, entry.word) ||
            (script.synonyms[entry.word]?.any { containsPhrase(sentence, it) } ?: false)
    }

    private fun applyPre(sentence: String): String {
        if (script.pre.isEmpty()) return sentence
        return sentence.split(" ").joinToString(" ") { token -> script.pre[token] ?: token }
    }

    private fun reflect(text: String): String {
        if (text.isBlank()) return text
        return text.trim().split(" ").joinToString(" ") { token -> script.post[token] ?: token }
    }

    private fun reassemble(word: String, rule: Rule, groups: List<String>): String {
        val template = pickRoundRobin("$word|${rule.decomp}", rule.reassemble)
        return GROUP_REF.replace(template) { m ->
            val index = m.groupValues[1].toInt() - 1
            groups.getOrNull(index)?.let { reflect(it) } ?: m.value
        }
    }

    private fun pickRoundRobin(key: String, options: List<String>): String {
        if (options.isEmpty()) return ""
        val index = ruleCursor.getOrDefault(key, 0) % options.size
        ruleCursor[key] = index + 1
        return options[index]
    }

    private fun pushMemory(line: String) {
        memory.addLast(line)
        while (memory.size > MEMORY_DEPTH) memory.removeFirst()
    }

    private fun decompRegex(decomp: String): Regex {
        val parts = decomp.trim().split("*")
        val pattern = parts.joinToString("(.*)") { Regex.escape(it.trim()) }
        return Regex(pattern)
    }

    private fun containsPhrase(sentence: String, phrase: String): Boolean =
        Regex("\\b${Regex.escape(phrase)}\\b").containsMatchIn(sentence)

    private fun containsAnyPhrase(sentence: String, phrases: Set<String>): Boolean =
        phrases.any { containsPhrase(sentence, it) }

    companion object {
        const val NONE_KEYWORD = "\$none"
        private const val MEMORY_DEPTH = 4
        private const val MEMORY_RECALL_CHANCE = 0.30
        private val GROUP_REF = Regex("""\((\d+)\)""")

        val FAREWELL_TRIGGERS = setOf("bye", "goodbye", "gotta go", "hang up", "see you", "talk later")

        val OFF_LIMITS_TRIGGERS = setOf(
            "kill", "gun", "weapon", "sex", "drugs", "address", "phone number", "password",
        )
        val OFF_LIMITS_REPLIES = listOf(
            "Let's talk about web-slinging instead!",
            "Whoa, let's swing to a different topic!",
        )
    }
}
