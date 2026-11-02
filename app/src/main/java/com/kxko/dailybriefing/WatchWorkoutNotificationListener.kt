package com.kxko.dailybriefing

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Watches only Mi Fitness notifications. Notification text is inspected in memory and never stored.
 * A ride is started once per workout; finishing remains a deliberate user action in the tracker.
 */
class WatchWorkoutNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MI_FITNESS_PACKAGE) return

        val extras = sbn.notification.extras
        val text = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TITLE).orEmpty())
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_TEXT).orEmpty())
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT).orEmpty())
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach {
                append(' ')
                append(it)
            }
        }.lowercase()

        // Require a workout/sport context to avoid reacting to unrelated Mi Fitness alerts.
        val workoutTerms = listOf("workout", "training", "activity", "cycling", "bike", "biking", "radfahren", "radfahrt", "rad-training", "fahrrad")
        val startTerms = listOf("start", "started", "begin", "began", "in progress", "läuft", "gestartet", "begonnen", "training läuft")
        val sportTitle = listOf("cycling", "outdoor cycling", "indoor cycling", "radfahren", "radfahrt", "fahrrad")
        val hasWorkout = workoutTerms.any(text::contains)
        val hasStart = startTerms.any(text::contains)
        val looksLikeActiveSport = sportTitle.any(text::contains) && !listOf("finished", "completed", "beendet", "abgeschlossen").any(text::contains)

        if (hasWorkout && (hasStart || looksLikeActiveSport)) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                prefs.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .putString(KEY_LAST_EVENT, "Mi Fitness detected a workout start")
                    .apply()
                sendBroadcast(Intent(ACTION_WORKOUT_STARTED).setPackage(packageName))
            }
        }
    }

    companion object {
        const val MI_FITNESS_PACKAGE = "com.xiaomi.wearable"
        const val ACTION_WORKOUT_STARTED = "com.kxko.dailybriefing.WATCH_WORKOUT_STARTED"
        const val PREFS = "watch_workout_detection"
        const val KEY_ACTIVE = "watch_workout_active"
        const val KEY_STARTED_AT = "watch_workout_started_at"
        const val KEY_LAST_EVENT = "watch_workout_last_event"

        fun hasAccess(context: Context): Boolean {
            val expected = ComponentName(context, WatchWorkoutNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
