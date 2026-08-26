package dev.nate.spideycall.audio

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CustomPronunciationDictTest {

    private fun modelDirWith(dictJson: String): File {
        val dir = kotlin.io.path.createTempDirectory("custom_pronunciation_dict_test").toFile()
        File(dir, "us_gold.json").writeText(dictJson)
        return dir
    }

    private fun readGoldDict(dir: File): Map<String, String> =
        Json.parseToJsonElement(File(dir, "us_gold.json").readText()).jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content }

    @Test
    fun addsTheSpideyOverrideWhenMissing() {
        val dir = modelDirWith("""{"spider": "spˈIdəɹ"}""")

        CustomPronunciationDict.install(dir.absolutePath)

        val dict = readGoldDict(dir)
        assertEquals("spˈIdi", dict["spidey"])
    }

    @Test
    fun leavesUnrelatedEntriesUntouched() {
        val dir = modelDirWith("""{"spider": "spider-phonemes", "name": "nˈAm"}""")

        CustomPronunciationDict.install(dir.absolutePath)

        val dict = readGoldDict(dir)
        assertEquals("spider-phonemes", dict["spider"])
        assertEquals("nˈAm", dict["name"])
    }

    @Test
    fun isIdempotentAcrossRepeatedCalls() {
        val dir = modelDirWith("""{"spider": "spˈIdəɹ"}""")

        CustomPronunciationDict.install(dir.absolutePath)
        CustomPronunciationDict.install(dir.absolutePath)

        val dict = readGoldDict(dir)
        assertEquals("spˈIdi", dict["spidey"])
        assertEquals(2, dict.size) // "spider" plus the one added "spidey" entry, no duplicates
    }

    @Test
    fun doesNothingWhenTheDictionaryFileIsMissing() {
        val dir = kotlin.io.path.createTempDirectory("custom_pronunciation_dict_test_missing").toFile()

        CustomPronunciationDict.install(dir.absolutePath) // must not throw

        assertFalse(File(dir, "us_gold.json").isFile)
    }
}
