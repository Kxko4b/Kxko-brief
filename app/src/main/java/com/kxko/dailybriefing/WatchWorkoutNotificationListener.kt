package com.kxko.dailybriefing

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

/**
 * Watches Mi Fitness workout notifications. Notification contents are inspected in memory only.
 * The German "Trainieren..." notification is treated as the active-workout signal.
 */
class WatchWorkoutNotificationListener : NotificationListenerService() {
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

        if (hasWorkout && (hasStart || looksLikeActiveSport)) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                prefs.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putBoolean(KEY_PENDING_START, true)
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .putString(KEY_LAST_EVENT, "Mi Fitness: $title")
                    .apply()

                // Start GPS immediately if permission was granted beforehand.
                val hasLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                if (hasLocation) {
                    val serviceIntent = Intent(this, CyclingTrackerService::class.java)
                        .setAction(CyclingTrackerService.ACTION_START)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                        else startService(serviceIntent)
                        prefs.edit().putBoolean(KEY_PENDING_START, false).apply()
                    } catch (_: Exception) {
                        // The tracker screen will offer a manual start if Android blocks the launch.
                    }
                }
                sendBroadcast(Intent(ACTION_WORKOUT_STARTED).setPackage(packageName))
            }
        }
    }

    companion object {
        const val MI_FITNESS_PACKAGE = "com.xiaomi.wearable"
        const val ACTION_WORKOUT_STARTED = "com.kxko.dailybriefing.WATCH_WORKOUT_STARTED"
        const val PREFS = "watch_workout_detection"
        const val KEY_ACTIVE = "watch_workout_active"
        const val KEY_PENDING_START = "watch_workout_pending_start"
        const val KEY_STARTED_AT = "watch_workout_started_at"
        const val KEY_LAST_EVENT = "watch_workout_last_event"

        fun hasAccess(context: Context): Boolean {
            val expected = ComponentName(context, WatchWorkoutNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
