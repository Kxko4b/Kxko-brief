package com.kxko.dailybriefing

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** How well one question is known. */
enum class Mastery { FRESH, SHAKY, MASTERED }

data class Badge(val id: String, val emoji: String, val title: String, val hint: String, val unlocked: Boolean)

/**
 * Pure study maths (streaks, readiness, badges). No Android classes, so it can be unit tested on the JVM.
 * Days are "yyyy-MM-dd" strings in the phone's own time zone.
 */
object StudyStatsLogic {

    private fun format() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    fun dayKey(millis: Long): String = format().format(Date(millis))

    private fun parse(key: String): Date? = try { format().parse(key) } catch (_: Exception) { null }

    /** [key] moved by [delta] days. Returns [key] unchanged when it cannot be parsed. */
    fun shiftDay(key: String, delta: Int): String {
        val date = parse(key) ?: return key
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 12)
            add(Calendar.DAY_OF_YEAR, delta)
        }
        return format().format(cal.time)
    }

    /** Whole days from [today] to [target]. Negative when [target] is in the past. Null when a date is unreadable. */
    fun daysBetween(today: String, target: String): Int? {
        val a = parse(today) ?: return null
        val b = parse(target.take(10)) ?: return null
        val ca = Calendar.getInstance().apply { time = a; set(Calendar.HOUR_OF_DAY, 12) }
        val cb = Calendar.getInstance().apply { time = b; set(Calendar.HOUR_OF_DAY, 12) }
        val ms = cb.timeInMillis - ca.timeInMillis
        return Math.round(ms / 86_400_000.0).toInt()
    }

    /**
     * Current streak. A streak stays alive through today even if nothing was answered yet,
     * so it only breaks once a whole day has been skipped.
     */
    fun currentStreak(studyDays: Set<String>, today: String): Int {
        var cursor = if (today in studyDays) today else shiftDay(today, -1)
        var count = 0
        while (cursor in studyDays) {
            count += 1
            cursor = shiftDay(cursor, -1)
        }
        return count
    }

    fun bestStreak(studyDays: Set<String>): Int {
        if (studyDays.isEmpty()) return 0
        var best = 0
        for (day in studyDays) {
            // Only start counting at the first day of a run.
            if (shiftDay(day, -1) in studyDays) continue
            var length = 0
            var cursor = day
            while (cursor in studyDays) {
                length += 1
                cursor = shiftDay(cursor, 1)
            }
            if (length > best) best = length
        }
        return best
    }

    /** The seven days ending [today], oldest first. */
    fun lastSevenDays(today: String): List<String> = (6 downTo 0).map { shiftDay(today, -it) }

    fun weekdayLetter(key: String): String {
        val date = parse(key) ?: return "?"
        val cal = Calendar.getInstance().apply { time = date }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "M"
            Calendar.TUESDAY -> "T"
            Calendar.WEDNESDAY -> "W"
            Calendar.THURSDAY -> "T"
            Calendar.FRIDAY -> "F"
            Calendar.SATURDAY -> "S"
            else -> "S"
        }
    }

    fun mastery(right: Int, wrong: Int): Mastery = when {
        right + wrong == 0 -> Mastery.FRESH
        right >= 2 && right > wrong -> Mastery.MASTERED
        else -> Mastery.SHAKY
    }

    /** Share (0..100) of questions that count as mastered. */
    fun readinessPercent(mastered: Int, total: Int): Int =
        if (total <= 0) 0 else Math.round(100f * mastered / total).coerceIn(0, 100)

    fun goalFraction(answeredToday: Int, goal: Int): Float =
        if (goal <= 0) 0f else (answeredToday.toFloat() / goal).coerceIn(0f, 1f)

    /** Next goal when the user taps the goal ring: 5 -> 10 -> 20 -> 30 -> 5. */
    fun nextGoal(goal: Int): Int = when (goal) {
        5 -> 10
        10 -> 20
        20 -> 30
        else -> 5
    }

    fun badges(totalAnswers: Int, bestStreak: Int, perfectSessions: Int, sessions: Int): List<Badge> = listOf(
        Badge("first", "🚀", "First steps", "Answer your first question", totalAnswers >= 1),
        Badge("sessions3", "🚲", "On the bike", "Finish 3 quiz sessions", sessions >= 3),
        Badge("streak3", "🔥", "Heating up", "Reach a 3-day streak", bestStreak >= 3),
        Badge("streak7", "⚡", "Locked in", "Reach a 7-day streak", bestStreak >= 7),
        Badge("perfect", "💎", "Flawless", "Finish a session with every answer right", perfectSessions >= 1),
        Badge("answers100", "🧠", "Big brain", "Answer 100 questions", totalAnswers >= 100)
    )

    /** A short, human line for the streak card. */
    fun streakMessage(streak: Int, studiedToday: Boolean): String = when {
        streak == 0 -> "No streak yet. One question starts it."
        !studiedToday -> "Your $streak-day streak is on the line. Answer one question to keep it."
        streak >= 7 -> "$streak days in a row. Absolutely locked in."
        streak >= 3 -> "$streak days in a row. Keep it rolling."
        else -> "Streak started. Come back tomorrow."
    }

    /** Colour class for an exam countdown: 0 = calm, 1 = soon, 2 = urgent, 3 = today or past. */
    fun urgency(daysLeft: Int): Int = when {
        daysLeft <= 0 -> 3
        daysLeft <= 3 -> 2
        daysLeft <= 7 -> 1
        else -> 0
    }

    fun daysLabel(daysLeft: Int): String = when {
        daysLeft < 0 -> "past"
        daysLeft == 0 -> "today"
        daysLeft == 1 -> "tomorrow"
        else -> "in $daysLeft days"
    }
}
