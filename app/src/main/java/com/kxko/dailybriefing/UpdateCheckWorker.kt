package com.kxko.dailybriefing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Periodically checks GitHub Releases even when the app is not open.
 * Android/WorkManager controls the exact execution time to preserve battery.
 */
class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        return try {
            val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Kxko-Daily-Briefing-Android")
            }
            val json = try {
                if (connection.responseCode == 404) return Result.success()
                if (connection.responseCode !in 200..299) return Result.retry()
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }

            val latestCode = json.optString("tag_name").removePrefix("v").toLongOrNull()
                ?: return Result.success()
            val installedCode = PackageInfoCompat.getLongVersionCode(
                applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            )
            val hasApk = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).any { assets.getJSONObject(it).optString("name").endsWith(".apk", true) }
            } ?: false

            if (latestCode > installedCode && hasApk) {
                showUpdateNotification(json.optString("name").ifBlank { "A new version is available" })
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showUpdateNotification(versionName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications when a new Kxko app version is available"
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            applicationContext,
            4102,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Kxko update available")
            .setContentText("$versionName is ready. Open Kxko to download it.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$versionName is available. Open Kxko and use the update prompt to download and install it."))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "kxko-periodic-update-check"
        private const val CHANNEL_ID = "kxko_app_updates"
        private const val NOTIFICATION_ID = 4102
        private const val LATEST_URL = "https://api.github.com/repos/Kxko4b/Kxko-brief/releases/latest"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
