package com.kxko.dailybriefing

import android.content.Context
import org.json.JSONObject

data class StudySnapshot(
    val today: String,
    val answeredToday: Int,
    val correctToday: Int,
    val goal: Int,
    val streak: Int,
    val bestStreak: Int,
    val studiedToday: Boolean,
    val week: List<Pair<String, Int>>,
    val totalAnswers: Int,
    val totalCorrect: Int,
    val sessions: Int,
    val perfectSessions: Int
)

/**
 * Keeps the study streak and daily numbers on the phone (SharedPreferences, no account needed).
 * Every answer in the Cycling Quiz counts. All maths lives in [StudyStatsLogic].
 */
class StudyStats(context: Context) {
    private val prefs = context.getSharedPreferences("study_stats", Context.MODE_PRIVATE)

    @Synchronized
    private fun readDays(): JSONObject = try {
        JSONObject(prefs.getString("days", "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    @Synchronized
    fun recordAnswer(correct: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val key = StudyStatsLogic.dayKey(nowMillis)
        val days = readDays()
        val day = days.optJSONObject(key) ?: JSONObject()
        day.put("a", day.optInt("a", 0) + 1)
        if (correct) day.put("c", day.optInt("c", 0) + 1)
        days.put(key, day)
        // Keep roughly a year of history so the preference file stays small.
        val keys = days.keys().asSequence().toList().sorted()
        if (keys.size > 400) keys.take(keys.size - 400).forEach { days.remove(it) }
        prefs.edit()
            .putString("days", days.toString())
            .putInt("total_answers", prefs.getInt("total_answers", 0) + 1)
            .putInt("total_correct", prefs.getInt("total_correct", 0) + if (correct) 1 else 0)
            .apply()
    }

    /** Call once when a quiz session ends (only sessions with at least one answer count). */
    @Synchronized
    fun recordSession(answered: Int, correct: Int) {
        if (answered <= 0) return
        prefs.edit()
            .putInt("sessions", prefs.getInt("sessions", 0) + 1)
            .putInt("perfect_sessions", prefs.getInt("perfect_sessions", 0) + if (answered >= 3 && answered == correct) 1 else 0)
            .apply()
    }

    fun goal(): Int = prefs.getInt("goal", 10)

    fun setGoal(value: Int) {
        prefs.edit().putInt("goal", value).apply()
    }

    @Synchronized
    fun snapshot(nowMillis: Long = System.currentTimeMillis()): StudySnapshot {
        val today = StudyStatsLogic.dayKey(nowMillis)
        val days = readDays()
        val studied = HashSet<String>()
        val iterator = days.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if ((days.optJSONObject(key)?.optInt("a", 0) ?: 0) > 0) studied.add(key)
        }
        val todayObj = days.optJSONObject(today)
        val week = StudyStatsLogic.lastSevenDays(today).map { it to (days.optJSONObject(it)?.optInt("a", 0) ?: 0) }
        return StudySnapshot(
            today = today,
            answeredToday = todayObj?.optInt("a", 0) ?: 0,
            correctToday = todayObj?.optInt("c", 0) ?: 0,
            goal = goal(),
            streak = StudyStatsLogic.currentStreak(studied, today),
            bestStreak = StudyStatsLogic.bestStreak(studied),
            studiedToday = today in studied,
            week = week,
            totalAnswers = prefs.getInt("total_answers", 0),
            totalCorrect = prefs.getInt("total_correct", 0),
            sessions = prefs.getInt("sessions", 0),
            perfectSessions = prefs.getInt("perfect_sessions", 0)
        )
    }
}
