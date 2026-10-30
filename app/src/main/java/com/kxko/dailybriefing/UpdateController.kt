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
 * Checks the GitHub Releases of Kxko-brief for a newer build, downloads it and hands it to the Android installer.
 * Releases are tagged "v<versionCode>" by CI, so comparing the tag with the installed versionCode is enough.
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

    private val prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private var downloadId = -1L
    private var registered = false

    val installedCode: Long
        get() = PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))

    val installedName: String
        get() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    private val apkFile: File
        get() = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "kxko-update.apk")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id != downloadId) return
            activity.runOnUiThread { onDownloadFinished() }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) { }
        registered = false
    }

    /** Looks for a newer release. Quiet checks run at most every 3 hours; [force] always asks. */
    fun check(force: Boolean) {
        if (checking || stage != 0 && info != null) return
        val last = prefs.getLong("last_check", 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        checking = true
        if (force) message = null
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
                if (!failed) prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val newer = found?.takeIf { it.versionCode > installedCode }
                if (newer != null) {
                    info = newer
                    stage = 0
                    message = null
                } else if (force) {
                    message = if (failed) "Couldn't reach GitHub. Try again in a bit." else "You're on the latest version."
                }
            }
        }.start()
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode == 404) return null // no release published yet
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = json.optString("tag_name").removePrefix("v").toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    val name = json.optString("name").ifBlank { "build $code" }
                    return UpdateInfo(code, name, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
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
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(apkFile))
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = manager.enqueue(request)
            stage = 1
            message = "Downloading …"
        } catch (e: Exception) {
            message = "Download failed: ${e.message ?: "unknown error"}"
        }
    }

    private fun onDownloadFinished() {
        if (apkFile.exists() && apkFile.length() > 100_000L) {
            stage = 2
            message = "Downloaded. Tap Install."
            install()
        } else {
            stage = 0
            message = "Download didn't finish. Try again."
        }
    }

    fun install() {
        if (!apkFile.exists()) {
            stage = 0
            message = "The file is gone. Download it again."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow Kxko to install updates, then come back and tap Install.", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/Kxko4b/Kxko-brief/releases/latest"
        private const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
    }
}
