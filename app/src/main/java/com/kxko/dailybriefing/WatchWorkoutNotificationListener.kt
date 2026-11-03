package com.kxko.dailybriefing

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Watches Mi Fitness workout notifications. Notification contents are inspected in memory only.
 * The German "Trainieren..." notification is treated as the active-workout signal.
 */
class WatchWorkoutNotificationListener : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MI_FITNESS_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "")
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach {
                append(' ')
                append(it)
            }
        }
        val text = "$title $body".lowercase().replace("…", "...")

        // Mi Fitness currently labels the active workout notification "Trainieren...".
        // Keep this narrowly scoped to that label plus known active-workout wording.
        val germanTrainieren = text.contains("trainier")
        val workoutTerms = listOf(
            "workout", "training", "activity", "cycling", "bike", "biking",
            "radfahren", "radfahrt", "fahrrad", "sport"
        )
        val startTerms = listOf(
            "start", "started", "begin", "began", "in progress", "läuft",
            "gestartet", "begonnen", "training läuft", "trainieren"
        )
        val sportTitle = listOf(
            "cycling", "outdoor cycling", "indoor cycling", "radfahren",
            "radfahrt", "fahrrad"
        )
        val hasWorkout = workoutTerms.any(text::contains) || germanTrainieren
        val hasStart = startTerms.any(text::contains) || germanTrainieren
        val looksLikeActiveSport = sportTitle.any(text::contains) &&
            !listOf("finished", "completed", "beendet", "abgeschlossen").any(text::contains)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val finishedWorkout = listOf("finished", "completed", "beendet", "abgeschlossen", "training beendet").any(text::contains)
        if (hasWorkout && (hasStart || looksLikeActiveSport) && !finishedWorkout) {
            val wasActive = prefs.getBoolean(KEY_ACTIVE, false)
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_PENDING_START, !wasActive)
                .putLong(KEY_STARTED_AT, if (wasActive) prefs.getLong(KEY_STARTED_AT, System.currentTimeMillis()) else System.currentTimeMillis())
                .putString(KEY_LAST_EVENT, "Mi Fitness: $title")
                .putString(KEY_NOTIFICATION_KEY, sbn.key)
                .apply()
            if (!wasActive) sendBroadcast(Intent(ACTION_WORKOUT_STARTED).setPackage(packageName))
        } else if (finishedWorkout && prefs.getBoolean(KEY_ACTIVE, false) &&
            prefs.getString(KEY_NOTIFICATION_KEY, "") == sbn.key) {
            prefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putBoolean(KEY_PENDING_START, false)
                .putString(KEY_LAST_EVENT, "Mi Fitness workout completed")
                .remove(KEY_NOTIFICATION_KEY)
                .apply()
            sendBroadcast(Intent(ACTION_WORKOUT_ENDED).setPackage(packageName))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MI_FITNESS_PACKAGE) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val removedKey = sbn.key
        if (!prefs.getBoolean(KEY_ACTIVE, false) || prefs.getString(KEY_NOTIFICATION_KEY, "") != removedKey) return

        // Mi Fitness can replace/update a notification by briefly removing it.
        // Wait briefly and only finish if that exact workout notification is still absent.
        mainHandler.postDelayed({
            val stillPresent = activeNotifications?.any { it.packageName == MI_FITNESS_PACKAGE && it.key == removedKey } == true
            val currentPrefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!stillPresent && currentPrefs.getString(KEY_NOTIFICATION_KEY, "") == removedKey &&
                currentPrefs.getBoolean(KEY_ACTIVE, false)) {
                currentPrefs.edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .putBoolean(KEY_PENDING_START, false)
                    .putString(KEY_LAST_EVENT, "Mi Fitness workout notification disappeared")
                    .remove(KEY_NOTIFICATION_KEY)
                    .apply()
                sendBroadcast(Intent(ACTION_WORKOUT_ENDED).setPackage(packageName))
            }
        }, 2000L)
    }

    companion object {
        const val MI_FITNESS_PACKAGE = "com.xiaomi.wearable"
        const val ACTION_WORKOUT_STARTED = "com.kxko.dailybriefing.WATCH_WORKOUT_STARTED"
        const val ACTION_WORKOUT_ENDED = "com.kxko.dailybriefing.WATCH_WORKOUT_ENDED"
        const val PREFS = "watch_workout_detection"
        const val KEY_ACTIVE = "watch_workout_active"
        const val KEY_PENDING_START = "watch_workout_pending_start"
        const val KEY_STARTED_AT = "watch_workout_started_at"
        const val KEY_LAST_EVENT = "watch_workout_last_event"
        const val KEY_NOTIFICATION_KEY = "watch_workout_notification_key"

        fun hasAccess(context: Context): Boolean {
            val expected = ComponentName(context, WatchWorkoutNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
