package dev.nate.spideycall.eliza

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One decomposition/reassembly rule within a [KeywordEntry]. */
@Serializable
data class Rule(
    /** `*` is a greedy wildcard group; multiple `*` in one pattern capture multiple groups. */
    val decomp: String,
    /** Cycled round-robin per rule. `(n)` refers to the nth captured wildcard group. */
    val reassemble: List<String>,
    /** Push the reassembled line onto the memory stack for later recall. */
    val memory: Boolean = false,
)

@Serializable
data class KeywordEntry(
    val word: String,
    /** Higher rank wins when multiple keywords match the same sentence. */
    val rank: Int,
    val rules: List<Rule>,
)

@Serializable
data class ElizaScript(
    /** Shown on screen. */
    val greeting: String,
    /** Passed to TTS — may differ in spelling/punctuation for correct prosody. */
    val greetingSpoken: String,
    /** Grammar pre-substitutions (contractions) applied before matching. */
    val pre: Map<String, String> = emptyMap(),
    /** Pronoun-reflection substitutions applied to captured wildcard groups. */
    val post: Map<String, String> = emptyMap(),
    val synonyms: Map<String, List<String>> = emptyMap(),
    val keywords: List<KeywordEntry>,
    val memoryPrompts: List<String> = emptyList(),
    val farewell: List<String>,
)

private val elizaScriptJson = Json { ignoreUnknownKeys = true }

fun parseElizaScript(json: String): ElizaScript = elizaScriptJson.decodeFromString(json)
