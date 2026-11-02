package com.kxko.dailybriefing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Foreground GPS tracker. It records distance and current speed locally on the device.
 */
class CyclingTrackerService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: android.content.SharedPreferences
    private var lastLocation: Location? = null
    private var distanceMetres = 0f
    private var startedElapsed = 0L
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(WatchWorkoutNotificationListener.PREFS, Context.MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START || intent == null) startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for GPS signal…"))

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSelf()
            return
        }

        distanceMetres = prefs.getFloat(KEY_DISTANCE, 0f)
        startedElapsed = SystemClock.elapsedRealtime()
        prefs.edit().putBoolean(KEY_TRACKING, true).apply()

        try {
            val provider = if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            locationManager.requestLocationUpdates(provider, 1000L, 1f, this)
            locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalArgumentException) {
            updateNotification("Location provider unavailable")
        }
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        if (previous != null) {
            val segment = previous.distanceTo(location)
            // Ignore implausible GPS jumps rather than corrupting the trip distance.
            if (segment in 1f..150f && location.accuracy <= 50f) distanceMetres += segment
        }
        lastLocation = location
        val speedKmh = if (location.hasSpeed() && location.accuracy <= 50f) location.speed * 3.6f else 0f
        prefs.edit()
            .putFloat(KEY_DISTANCE, distanceMetres)
            .putFloat(KEY_SPEED, speedKmh)
            .putLong(KEY_LAST_FIX, System.currentTimeMillis())
            .apply()

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdate >= 5000L) {
            lastNotificationUpdate = now
            updateNotification(String.format(Locale.getDefault(), "%.1f km · %.1f km/h", distanceMetres / 1000f, speedKmh))
        }
        sendBroadcast(Intent(ACTION_STATS_UPDATED).setPackage(packageName))
    }

    private fun stopTracking() {
        runCatching { locationManager.removeUpdates(this) }
        prefs.edit().putBoolean(KEY_TRACKING, false).putFloat(KEY_SPEED, 0f).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cycling tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when GPS cycling tracking is active"
                }
            )
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Kxko cycling tracker")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { updateNotification("GPS unavailable — enable location") }
    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.kxko.dailybriefing.CYCLING_START"
        const val ACTION_STOP = "com.kxko.dailybriefing.CYCLING_STOP"
        const val ACTION_STATS_UPDATED = "com.kxko.dailybriefing.CYCLING_STATS_UPDATED"
        const val KEY_TRACKING = "cycling_gps_tracking"
        const val KEY_DISTANCE = "cycling_distance_metres"
        const val KEY_SPEED = "cycling_speed_kmh"
        const val KEY_LAST_FIX = "cycling_last_fix"
        private const val CHANNEL_ID = "cycling_tracking"
        private const val NOTIFICATION_ID = 648
    }
}
