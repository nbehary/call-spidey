package dev.nate.spideycall.eliza

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizerTest {

    @Test
    fun lowercasesStripsPunctuationAndCollapsesWhitespace() {
        assertEquals("hello there pal", Normalizer.normalize("  Hello,  there -- PAL!!  "))
    }

    @Test
    fun stripsApostrophesForDownstreamContractionMatching() {
        assertEquals("dont go", Normalizer.normalize("Don't go"))
    }

    @Test
    fun mapsSpiderManVariantsToSpiderman() {
        assertEquals("spiderman is here", Normalizer.normalize("Spider-Man is here"))
        assertEquals("spiderman is here", Normalizer.normalize("Spider Man is here"))
        assertEquals("hey spiderman", Normalizer.normalize("hey spidey"))
    }

    @Test
    fun mapsDocOckVariantsToDocOck() {
        assertEquals("doc ock is back", Normalizer.normalize("Doctor Octopus is back"))
        assertEquals("doc ock is back", Normalizer.normalize("Doc Oc is back"))
        assertEquals("doc ock is back", Normalizer.normalize("Dock Ock is back"))
    }

    @Test
    fun mapsMilesMoralesToSpin() {
        assertEquals("spin is cool", Normalizer.normalize("Miles Morales is cool"))
    }

    @Test
    fun mapsGwenToGhostSpider() {
        assertEquals("is that ghost spider", Normalizer.normalize("is that Gwen"))
    }

    @Test
    fun leavesUnrelatedTextAlone() {
        assertEquals("i am scared of the dark", Normalizer.normalize("I am scared of the dark"))
    }
}
