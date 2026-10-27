package com.kxko.dailybriefing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "Kxko Daily Briefing"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: "You have a new briefing."
        val soundMode = remoteMessage.data["sound_mode"] ?: "loud"
        val vibrationEnabled = remoteMessage.data["vibration_enabled"] != "false"

        showNotification(title, body, soundMode, vibrationEnabled)
        Handler(Looper.getMainLooper()).post { onPushWhileOpen?.invoke() }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DeviceRegistration.register(token)
    }

    private fun showNotification(title: String, body: String, soundMode: String, vibrationEnabled: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val silent = soundMode == "silent"
        val channelId = "daily_briefing_${if (silent) "silent" else "loud"}_${if (vibrationEnabled) "vibrate" else "still"}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                if (silent) "Daily Briefing (silent)" else "Daily Briefing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Kxko Daily Briefing notifications"
                enableVibration(vibrationEnabled)
                if (silent) setSound(null, null)
                else setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_k)
            .setColor(ContextCompat.getColor(this, R.color.kxko_brass))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        /** Set by MainActivity while it is on screen. */
        @Volatile
        var onPushWhileOpen: (() -> Unit)? = null
    }
}
