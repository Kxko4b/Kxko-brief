package com.kxko.dailybriefing

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String)

/**
 * Checks GitHub Releases, downloads the signed APK with DownloadManager and opens
 * Android's package installer. Android itself must confirm the final installation.
 */
class UpdateController(private val activity: ComponentActivity) {
    var info by mutableStateOf<UpdateInfo?>(null)
        private set
    /** 0 = update available, 1 = downloading, 2 = downloaded and ready to install. */
    var stage by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    var onUpdateAvailable: ((UpdateInfo) -> Unit)? = null

    private val prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private var downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
    private var registered = false

    val installedCode: Long
        get() = PackageInfoCompat.getLongVersionCode(
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        )

    val installedName: String
        get() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    private val apkFile: File
        get() = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: activity.filesDir,
            "kxko-update.apk"
        )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id != downloadId) return
            activity.runOnUiThread { inspectDownload() }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        inspectDownload()
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }

    /** Checks quietly at most every six hours; force=true bypasses the interval. */
    fun check(force: Boolean) {
        if (checking || stage == 1 || stage == 2 || info != null) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        checking = true
        Thread {
            var found: UpdateInfo? = null
            var failed = false
            try {
                found = fetchLatest()
            } catch (_: Exception) {
                failed = true
            }
            activity.runOnUiThread {
                checking = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!failed) prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val newer = found?.takeIf { it.versionCode > installedCode }
                if (newer != null) {
                    info = newer
                    stage = 0
                    message = null
                    onUpdateAvailable?.invoke(newer)
                } else if (force) {
                    message = if (failed) "Couldn't reach GitHub. Try again later." else "You're on the latest version."
                }
            }
        }.start()
    }

    private fun fetchLatest(): UpdateInfo? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Kxko-Daily-Briefing-Android")
        }
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val code = json.optString("tag_name").removePrefix("v").toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val assetName = asset.optString("name")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    val displayName = json.optString("name").ifBlank { "Build $code" }
                    return UpdateInfo(code, displayName, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    fun dismiss() {
        info = null
        message = null
    }

    fun startDownload() {
        val target = info ?: return
        if (stage == 1) return
        try {
            apkFile.delete()
            val request = DownloadManager.Request(Uri.parse(target.apkUrl))
                .setTitle("Kxko update ${target.versionName}")
                .setDescription("Downloading the latest Kxko Daily Briefing update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "kxko-update.apk"
                )
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = manager.enqueue(request)
            prefs.edit().putLong(KEY_DOWNLOAD_ID, downloadId).apply()
            stage = 1
            message = "Downloading update…"
        } catch (error: Exception) {
            stage = 0
            message = "Download failed: ${error.message ?: "unknown error"}"
        }
    }

    /** Re-checks DownloadManager after activity/process recreation. */
    private fun inspectDownload() {
        if (downloadId < 0L) return
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (it == null || !it.moveToFirst()) return
            when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> onDownloadFinished()
                DownloadManager.STATUS_FAILED -> {
                    stage = 0
                    message = "Download failed. Please try again."
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    downloadId = -1L
                }
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    stage = 1
                    message = "Downloading update…"
                }
            }
        }
    }

    private fun onDownloadFinished() {
        if (apkFile.exists() && apkFile.length() > MIN_APK_BYTES) {
            stage = 2
            message = "Download complete. Opening Android installer…"
            install()
        } else {
            stage = 0
            message = "The downloaded APK is missing or incomplete. Try again."
            prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
            downloadId = -1L
        }
    }

    /** Called after returning from Android's 'install unknown apps' settings page. */
    fun resumePendingInstall() {
        if (stage == 2 && apkFile.exists() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls())
        ) {
            install()
        } else if (downloadId >= 0L && stage == 0) {
            inspectDownload()
        }
    }

    fun install() {
        if (!apkFile.exists() || apkFile.length() <= MIN_APK_BYTES) {
            stage = 0
            message = "The update file is no longer available. Download it again."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Allow Kxko to install updates, then return to the app.",
                Toast.LENGTH_LONG
            ).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(installIntent)
        } catch (error: Exception) {
            message = "Couldn't open Android installer: ${error.message ?: "unknown error"}"
        }
    }

    companion object {
        private const val LATEST_URL =
            "https://api.github.com/repos/Kxko4b/Kxko-brief/releases/latest"
        private const val CHECK_EVERY_MS = 6L * 60L * 60L * 1000L
        private const val MIN_APK_BYTES = 100_000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DOWNLOAD_ID = "download_id"
    }
}
