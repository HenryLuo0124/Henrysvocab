package com.eng.henrysvocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewScheduleTest {
    @Test
    fun onTimeReviewsUseAllIntervalsAndGraduate() {
        var day = 20_000L
        var schedule: ReviewSchedule? = ReviewSchedule.start(day)
        listOf(1L, 2L, 3L, 7L, 15L, 30L, 60L).forEachIndexed { step, interval ->
            val current = checkNotNull(schedule)
            assertEquals(ReviewSchedule(day, step, day + interval), current)
            day = current.dueDay
            schedule = current.grade(day, correct = true)
        }
        assertNull(schedule)
    }

    @Test
    fun lateReviewsUseTheirActualSuccessDayWithoutSkippingSteps() {
        val first = ReviewSchedule.start(100)
        val second = checkNotNull(first.grade(104, correct = true))
        assertEquals(ReviewSchedule(104, 1, 106), second)
        val third = checkNotNull(second.grade(110, correct = true))
        assertEquals(ReviewSchedule(110, 2, 113), third)
        assertEquals(ReviewSchedule(113, 3, 120), third.grade(113, correct = true))
    }

    @Test
    fun incorrectFinalReviewResetsAnchorAndUsesOneDayInterval() {
        val final = ReviewSchedule(100, ReviewSchedule.lastStep, 160)
        val reset = checkNotNull(final.grade(170, correct = false))
        assertEquals(ReviewSchedule(170, 0, 171), reset)
        assertEquals(ReviewSchedule(171, 1, 173), reset.grade(171, correct = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotGradeBeforeDueDay() {
        ReviewSchedule.start(100).grade(100, correct = true)
    }
}
