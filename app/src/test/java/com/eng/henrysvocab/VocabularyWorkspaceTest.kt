package com.eng.henrysvocab

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyWorkspaceTest {
    @Test
    fun importPreservesDotPlaceholdersButReviewIgnoresThem() {
        val word = parseWords(StringReader("bank/(riverbank/.)/.")).single()
        assertEquals(Word("bank", listOf("riverbank", "."), "."), word)
        assertEquals(listOf("riverbank"), word.reviewMeanings)
        assertEquals("", word.reviewExample)
        assertEquals(emptyList<String>(), word.copy(meanings = listOf(".")).reviewMeanings)
    }

    @Test
    fun nestedDotsRemainAuthoredContentIncludingLegacySplitMeanings() {
        val word = Word("bank", listOf("(a/./b)", "(a", ".", "b)", "abbr.", "..."), "Look at the bank.")
        assertEquals(word.meanings, word.reviewMeanings)
        assertEquals(word.example, word.reviewExample)
    }
}
