package com.eng.henrysvocab

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabParserTest {
    @Test
    fun preservesWordAndMeaningOrderIncludingRepeatedTerms() {
        val input = " bank / ( river edge / financial institution ) / Walk by the river.\n" +
            "café/(咖啡馆)/Meet me at the café.\n" +
            "bank/(depend (informal))/You can bank on it."

        assertEquals(
            listOf(
                Word("bank", listOf("river edge", "financial institution"), "Walk by the river."),
                Word("café", listOf("咖啡馆"), "Meet me at the café."),
                Word("bank", listOf("depend (informal)"), "You can bank on it."),
            ),
            parseWords(StringReader(input)),
        )
    }

    @Test
    fun ignoresBomAndBlankLinesAndPreservesSlashesInExamples() {
        val input = "\uFEFF\r\n  \rpath/(route)/Use the A/B route.\r\n\t\n"
        assertEquals(
            listOf(Word("path", listOf("route"), "Use the A/B route.")),
            parseWords(StringReader(input)),
        )
        assertTrue(parseWords(StringReader("\uFEFF\n \t\n")).isEmpty())
    }

    @Test
    fun reportsPhysicalLineNumberForMalformedRows() {
        val malformed = listOf(
            "word only",
            "/(meaning)/Example.",
            "word/meaning/Example.",
            "word/(meaning/Example.",
            "word/(meaning) Example.",
            "word/()/Example.",
            "word/(a//b)/Example.",
            "word/(a/ )/Example.",
            "word/(meaning)/ ",
        )
        for (row in malformed) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                parseWords(StringReader("\uFEFFvalid/(fine)/Example.\r\n\r\n$row"))
            }
            assertTrue(error.message, error.message.orEmpty().startsWith("Line 3:"))
        }
    }

    @Test
    fun rejectsAnOverlongLine() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseWords(StringReader("\nword/(meaning)/${"x".repeat(9_000)}"))
        }
        assertTrue(error.message, error.message.orEmpty().startsWith("Line 2:"))
    }

    @Test
    fun rejectsTooManyWordsOrMeanings() {
        val wordsError = assertThrows(IllegalArgumentException::class.java) {
            parseWords(StringReader("word/(meaning)/Example.\n".repeat(20_001)))
        }
        assertTrue(wordsError.message, wordsError.message.orEmpty().startsWith("Line 20001:"))

        val meanings = List(65) { "meaning" }.joinToString("/")
        val meaningsError = assertThrows(IllegalArgumentException::class.java) {
            parseWords(StringReader("word/($meanings)/Example."))
        }
        assertTrue(meaningsError.message, meaningsError.message.orEmpty().startsWith("Line 1:"))
    }
}
