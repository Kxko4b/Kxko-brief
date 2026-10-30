package com.kxko.dailybriefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyStatsLogicTest {

    @Test
    fun shiftDayCrossesMonthAndYear() {
        assertEquals("2026-11-01", StudyStatsLogic.shiftDay("2026-10-31", 1))
        assertEquals("2025-12-31", StudyStatsLogic.shiftDay("2026-01-01", -1))
        assertEquals("2026-03-01", StudyStatsLogic.shiftDay("2026-02-28", 1))
    }

    @Test
    fun shiftDayIsStableAcrossTheClockChange() {
        // Clock change in Europe: 2026-10-25 has 25 hours. Day arithmetic must not skip or repeat a day.
        assertEquals("2026-10-26", StudyStatsLogic.shiftDay("2026-10-25", 1))
        assertEquals("2026-10-25", StudyStatsLogic.shiftDay("2026-10-26", -1))
        assertEquals("2026-10-24", StudyStatsLogic.shiftDay("2026-10-25", -1))
    }

    @Test
    fun daysBetweenCountsWholeDays() {
        assertEquals(0, StudyStatsLogic.daysBetween("2026-11-10", "2026-11-10"))
        assertEquals(12, StudyStatsLogic.daysBetween("2026-10-29", "2026-11-10"))
        assertEquals(-1, StudyStatsLogic.daysBetween("2026-11-10", "2026-11-09"))
        assertEquals(3, StudyStatsLogic.daysBetween("2026-10-24", "2026-10-27"))
        assertEquals(null, StudyStatsLogic.daysBetween("nope", "2026-10-27"))
    }

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        val days = setOf("2026-10-27", "2026-10-28", "2026-10-29")
        assertEquals(3, StudyStatsLogic.currentStreak(days, "2026-10-29"))
    }

    @Test
    fun streakSurvivesUntilEndOfTodayThenBreaks() {
        val days = setOf("2026-10-27", "2026-10-28")
        // Nothing yet today: still alive.
        assertEquals(2, StudyStatsLogic.currentStreak(days, "2026-10-29"))
        // A whole day skipped: gone.
        assertEquals(0, StudyStatsLogic.currentStreak(days, "2026-10-30"))
    }

    @Test
    fun streakIgnoresOlderGaps() {
        val days = setOf("2026-10-20", "2026-10-21", "2026-10-28", "2026-10-29")
        assertEquals(2, StudyStatsLogic.currentStreak(days, "2026-10-29"))
        assertEquals(2, StudyStatsLogic.bestStreak(days))
    }

    @Test
    fun bestStreakFindsTheLongestRun() {
        val days = setOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-10", "2026-10-11")
        assertEquals(4, StudyStatsLogic.bestStreak(days))
        assertEquals(0, StudyStatsLogic.bestStreak(emptySet()))
    }

    @Test
    fun lastSevenDaysEndsToday() {
        val week = StudyStatsLogic.lastSevenDays("2026-10-29")
        assertEquals(7, week.size)
        assertEquals("2026-10-23", week.first())
        assertEquals("2026-10-29", week.last())
    }

    @Test
    fun weekdayLetterMatchesTheCalendar() {
        // 2026-10-29 is a Thursday.
        assertEquals("T", StudyStatsLogic.weekdayLetter("2026-10-29"))
        assertEquals("F", StudyStatsLogic.weekdayLetter("2026-10-30"))
        assertEquals("S", StudyStatsLogic.weekdayLetter("2026-10-31"))
    }

    @Test
    fun masteryRules() {
        assertEquals(Mastery.FRESH, StudyStatsLogic.mastery(0, 0))
        assertEquals(Mastery.SHAKY, StudyStatsLogic.mastery(1, 0))
        assertEquals(Mastery.SHAKY, StudyStatsLogic.mastery(2, 2))
        assertEquals(Mastery.MASTERED, StudyStatsLogic.mastery(2, 1))
        assertEquals(Mastery.SHAKY, StudyStatsLogic.mastery(0, 3))
    }

    @Test
    fun readinessAndGoalAreClamped() {
        assertEquals(0, StudyStatsLogic.readinessPercent(0, 0))
        assertEquals(50, StudyStatsLogic.readinessPercent(5, 10))
        assertEquals(100, StudyStatsLogic.readinessPercent(12, 10))
        assertEquals(0.5f, StudyStatsLogic.goalFraction(5, 10), 0.001f)
        assertEquals(1f, StudyStatsLogic.goalFraction(25, 10), 0.001f)
        assertEquals(0f, StudyStatsLogic.goalFraction(3, 0), 0.001f)
    }

    @Test
    fun goalCyclesThroughPresets() {
        assertEquals(10, StudyStatsLogic.nextGoal(5))
        assertEquals(20, StudyStatsLogic.nextGoal(10))
        assertEquals(30, StudyStatsLogic.nextGoal(20))
        assertEquals(5, StudyStatsLogic.nextGoal(30))
        assertEquals(5, StudyStatsLogic.nextGoal(7))
    }

    @Test
    fun badgesUnlockAtTheirThresholds() {
        val none = StudyStatsLogic.badges(0, 0, 0, 0)
        assertTrue(none.none { it.unlocked })
        val some = StudyStatsLogic.badges(120, 7, 1, 3).associateBy { it.id }
        assertTrue(some.getValue("first").unlocked)
        assertTrue(some.getValue("streak7").unlocked)
        assertTrue(some.getValue("perfect").unlocked)
        assertTrue(some.getValue("answers100").unlocked)
        assertTrue(some.getValue("sessions3").unlocked)
        assertFalse(StudyStatsLogic.badges(5, 2, 0, 1).first { it.id == "streak3" }.unlocked)
    }

    @Test
    fun urgencyAndLabels() {
        assertEquals(3, StudyStatsLogic.urgency(0))
        assertEquals(2, StudyStatsLogic.urgency(2))
        assertEquals(1, StudyStatsLogic.urgency(7))
        assertEquals(0, StudyStatsLogic.urgency(8))
        assertEquals("tomorrow", StudyStatsLogic.daysLabel(1))
        assertEquals("in 12 days", StudyStatsLogic.daysLabel(12))
        assertEquals("today", StudyStatsLogic.daysLabel(0))
    }
}
