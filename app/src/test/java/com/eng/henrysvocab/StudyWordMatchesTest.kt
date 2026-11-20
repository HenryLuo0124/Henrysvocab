package com.eng.henrysvocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyWordMatchesTest {
    @Test
    fun keepsInternalApostrophesAndHyphensButExcludesSurroundingPunctuation() {
        val term = "‘Don’t,’ (well-known) — can't / re‑enter!"
        assertEquals(
            listOf("Don’t", "well-known", "can't", "re‑enter"),
            studyWordMatches(term).map { it.value }.toList(),
        )
    }

    @Test
    fun returnsSeparateRangesForRepeatedWordsAndPreservesAccents() {
        val term = "café,  cafe\u0301 / café"
        assertEquals(
            listOf("café" to (0..3), "cafe\u0301" to (7..11), "café" to (15..18)),
            studyWordMatches(term).map { it.value to it.range }.toList(),
        )
    }

    @Test
    fun makesNoTargetsForPunctuationOrWhitespace() {
        assertTrue(studyWordMatches(" … — / ‘’ ! ").none())
    }
}
