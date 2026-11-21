package com.eng.henrysvocab

internal data class LearningEntry(val index: Int, val word: Word)

// Dots are stored and displayed as written. Review grading ignores only whole-field placeholders.
// Older imports split nested meanings across entries, so preserve dots inside those parentheses.
internal val Word.reviewMeanings: List<String> get() {
    var depth = 0
    return meanings.filter { meaning ->
        val authored = depth != 0 || meaning != "."
        for (ch in meaning) {
            if (ch == '(') depth++
            if (ch == ')') depth--
        }
        authored
    }
}

internal val Word.reviewExample: String get() = example.takeUnless { it == "." }.orEmpty()
