package com.kxko.dailybriefing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * User-armed foreground GPS tracker. It waits for Mi Fitness's workout signal,
 * then records distance and speed without starting a location service from the background.
 */
class CyclingTrackerService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: android.content.SharedPreferences
    private var lastLocation: Location? = null
    private var distanceMetres = 0f
    private var lastNotificationUpdate = 0L
    private var locationUpdatesRunning = false

    private val workoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchWorkoutNotificationListener.ACTION_WORKOUT_STARTED &&
                prefs.getBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false) &&
                prefs.getBoolean(KEY_ARMED, false)
            ) beginLocationTracking()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(WatchWorkoutNotificationListener.PREFS, Context.MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val filter = IntentFilter(WatchWorkoutNotificationListener.ACTION_WORKOUT_STARTED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(workoutReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(workoutReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_ARM -> armForWorkout()
            ACTION_START -> {
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification("Starting GPS…"))
                beginLocationTracking()
            }
            null -> if (prefs.getBoolean(KEY_ARMED, false)) armForWorkout()
        }
        return START_STICKY
    }

    private fun armForWorkout() {
        createChannel()
        prefs.edit()
            .putBoolean(KEY_ARMED, true)
            .putFloat(KEY_DISTANCE, 0f)
            .putFloat(KEY_SPEED, 0f)
            .putLong(KEY_TRACKING_STARTED_AT, 0L)
            .apply()
        distanceMetres = 0f
        lastLocation = null
        startForeground(NOTIFICATION_ID, buildNotification("Armed — waiting for Mi Fitness workout…"))
        if (prefs.getBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false)) beginLocationTracking()
    }

    private fun beginLocationTracking() {
        if (locationUpdatesRunning) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            updateNotification("Location permission needed — open Kxko Cycling")
            return
        }

        distanceMetres = prefs.getFloat(KEY_DISTANCE, 0f)
        if (prefs.getLong(KEY_TRACKING_STARTED_AT, 0L) == 0L) {
            prefs.edit().putLong(KEY_TRACKING_STARTED_AT, System.currentTimeMillis()).apply()
        }
        prefs.edit().putBoolean(KEY_TRACKING, true).apply()
        try {
            val provider = if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            locationManager.requestLocationUpdates(provider, 1000L, 1f, this)
            locationUpdatesRunning = true
            locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
            updateNotification("GPS active — waiting for location fix…")
        } catch (_: SecurityException) {
            updateNotification("Location permission needed — open Kxko Cycling")
        } catch (_: IllegalArgumentException) {
            updateNotification("Location provider unavailable")
        }
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        if (previous != null) {
            val segment = previous.distanceTo(location)
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
            updateNotification(String.format(Locale.getDefault(), "%.2f km · %.1f km/h", distanceMetres / 1000f, speedKmh))
        }
        sendBroadcast(Intent(ACTION_STATS_UPDATED).setPackage(packageName))
    }

    private fun stopTracking() {
        runCatching { locationManager.removeUpdates(this) }
        locationUpdatesRunning = false
        prefs.edit()
            .putBoolean(KEY_TRACKING, false)
            .putBoolean(KEY_ARMED, false)
            .putFloat(KEY_SPEED, 0f)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cycling tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when automatic cycling tracking is armed or active"
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

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { unregisterReceiver(workoutReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM = "com.kxko.dailybriefing.CYCLING_ARM"
        const val ACTION_START = "com.kxko.dailybriefing.CYCLING_START"
        const val ACTION_STOP = "com.kxko.dailybriefing.CYCLING_STOP"
        const val ACTION_STATS_UPDATED = "com.kxko.dailybriefing.CYCLING_STATS_UPDATED"
        const val KEY_ARMED = "cycling_auto_tracking_armed"
        const val KEY_TRACKING = "cycling_gps_tracking"
        const val KEY_DISTANCE = "cycling_distance_metres"
        const val KEY_SPEED = "cycling_speed_kmh"
        const val KEY_LAST_FIX = "cycling_last_fix"
        const val KEY_TRACKING_STARTED_AT = "cycling_tracking_started_at"
        private const val CHANNEL_ID = "cycling_tracking"
        private const val NOTIFICATION_ID = 648
    }
}
