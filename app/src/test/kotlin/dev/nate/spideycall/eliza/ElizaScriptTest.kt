package dev.nate.spideycall.eliza

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the actual shipped asset from disk (JVM unit tests run with the
 * module directory as the working directory, so no Android AssetManager is
 * needed) and validates both the parser and the authored content.
 */
class ElizaScriptTest {

    private val scriptText = File("src/main/assets/spidey_eliza.json").readText()
    private val script = parseElizaScript(scriptText)

    @Test
    fun parsesGreetingFields() {
        assertEquals("Hi, I'm Spidey. What is your name?", script.greeting)
        assertEquals("Hi, I'm Spidey! What is your name?", script.greetingSpoken)
    }

    @Test
    fun hasTheGuaranteedFallbackKeyword() {
        val none = script.keywords.firstOrNull { it.word == ElizaEngine.NONE_KEYWORD }
        assertTrue("script must define a \$none catch-all keyword", none != null)
        assertTrue(none!!.rules.isNotEmpty())
    }

    @Test
    fun hasAtLeastFortyKeywordsAndNonEmptyFarewell() {
        assertTrue("expected ~40 keywords, found ${script.keywords.size}", script.keywords.size >= 40)
        assertTrue(script.farewell.isNotEmpty())
    }

    @Test
    fun everyReassembleLineFitsTheShortTurnTtsBudget() {
        val overLong = mutableListOf<String>()
        for (entry in script.keywords) {
            for (rule in entry.rules) {
                for (line in rule.reassemble) {
                    if (wordCount(line) > 9) overLong += "${entry.word}: \"$line\" (${wordCount(line)} words)"
                }
            }
        }
        assertTrue("reassemble lines over the 9-word TTS budget: $overLong", overLong.isEmpty())
    }

    @Test
    fun memoryPromptsAndFarewellLinesFitTheShortTurnTtsBudget() {
        val overLong = (script.memoryPrompts + script.farewell).filter { wordCount(it) > 9 }
        assertTrue("lines over the 9-word TTS budget: $overLong", overLong.isEmpty())
    }

    @Test
    fun everyKeywordHasAtLeastOneRule() {
        val empty = script.keywords.filter { it.rules.isEmpty() }
        assertTrue("keywords with no rules: ${empty.map { it.word }}", empty.isEmpty())
    }

    private fun wordCount(line: String): Int = line.trim().split(Regex("\\s+")).size
}
