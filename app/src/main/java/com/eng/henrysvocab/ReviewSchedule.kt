package com.eng.henrysvocab

internal data class ReviewCard(val bookId: String, val wordIndex: Int, val step: Int, val dueDay: Long)

/** A zero-based interval measured from the most recent learning, correct review, or reset day. */
internal data class ReviewSchedule(val anchorDay: Long, val step: Int, val dueDay: Long) {
    /** Returns null after the final successful review. */
    fun grade(day: Long, correct: Boolean): ReviewSchedule? {
        require(day >= dueDay) { "Cannot grade a review before it is due" }
        if (!correct) return start(day)
        if (step == lastStep) return null
        return atStep(day, step + 1)
    }

    companion object {
        private val intervals = longArrayOf(1, 2, 3, 7, 15, 30, 60)
        val lastStep: Int get() = intervals.lastIndex

        fun start(day: Long): ReviewSchedule = atStep(day, 0)

        private fun atStep(day: Long, step: Int) = ReviewSchedule(day, step, Math.addExact(day, intervals[step]))
    }
}
