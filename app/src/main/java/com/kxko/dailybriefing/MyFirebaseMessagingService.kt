package com.kxko.dailybriefing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService :
    FirebaseMessagingService() {

    override fun onMessageReceived(
        remoteMessage: RemoteMessage
    ) {
        val title =
            remoteMessage.notification?.title
                ?: "Kxko Daily Briefing"

        val body =
            remoteMessage.notification?.body
                ?: remoteMessage.data["body"]
                ?: "You have a new briefing."

        showNotification(title, body)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        println("FCM TOKEN: $token")

        // We'll send this token to Supabase later.
    }

    private fun showNotification(
        title: String,
        body: String
    ) {
        val channelId =
            "daily_briefing"

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    channelId,
                    "Daily Briefing",
                    NotificationManager.IMPORTANCE_DEFAULT
                )

            manager.createNotificationChannel(channel)
        }

        val notification =
            NotificationCompat.Builder(
                this,
                channelId
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(body)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_DEFAULT
                )
                .setAutoCancel(true)
                .build()

        manager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }
}