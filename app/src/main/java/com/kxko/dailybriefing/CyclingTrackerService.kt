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
import android.location.GnssStatus
import android.os.Handler
import android.os.Looper
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-armed foreground GPS tracker. It waits for Mi Fitness's workout signal,
 * then records distance and speed without starting a location service from the background.
 */
class CyclingTrackerService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: android.content.SharedPreferences
    private var lastLocation: Location? = null
    private var lastAcceptedMovementAt = 0L
    private var isMoving = false
    private var movementCandidateCount = 0
    private var lastMovementCandidateAt = 0L
    private var pendingMovementDistance = 0f
    private var pendingMovementSeconds = 0L
    private var pendingMovementMaxSpeed = 0f
    private var lastRouteLocation: Location? = null
    private var distanceMetres = 0f
    private var movingSeconds = 0L
    private var elevationGainMetres = 0f
    private var lastAltitude: Double? = null
    private var lastAcceptedAltitude: Double? = null
    private var currentGradePercent = 0f
    private var satellitesVisible = 0
    private var satellitesUsed = 0
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0
    private var currentBearing = 0f
    private var speedAccuracy = -1f
    private var verticalAccuracy = -1f
    private var currentSplitDistance = 0f
    private var currentSplitMovingSeconds = 0L
    private var currentSplitMaxSpeed = 0f
    private var nextSplitKm = 1
    private val splitRecords = JSONArray()
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var visible = 0
            var used = 0
            for (index in 0 until status.satelliteCount) {
                visible++
                if (status.usedInFix(index)) used++
            }
            satellitesVisible = visible
            satellitesUsed = used
            prefs.edit().putInt(KEY_SATELLITES_VISIBLE, visible).putInt(KEY_SATELLITES_USED, used).apply()
            sendBroadcast(Intent(ACTION_STATS_UPDATED).setPackage(packageName))
        }
    }
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
            .putFloat(KEY_MAX_SPEED, 0f)
            .putLong(KEY_MOVING_SECONDS, 0L)
            .putFloat(KEY_ELEVATION_GAIN, 0f)
            .putFloat(KEY_CURRENT_ALTITUDE, 0f)
            .putFloat(KEY_GPS_ACCURACY, -1f)
            .putFloat(KEY_SPEED_ACCURACY, -1f)
            .putFloat(KEY_VERTICAL_ACCURACY, -1f)
            .putFloat(KEY_CURRENT_GRADE, 0f)
            .putFloat(KEY_BEARING, 0f)
            .putString(KEY_COORDINATES, "")
            .putInt(KEY_SATELLITES_VISIBLE, 0)
            .putInt(KEY_SATELLITES_USED, 0)
            .putString(KEY_SPLITS, "[]")
            .putString(KEY_START_COORDINATES, "")
            .putString(KEY_SPEED_HISTORY, "[]")
            .putString(KEY_ROUTE_POINTS, "[]")
            .putLong(KEY_TRACKING_STARTED_AT, 0L)
            .putLong(KEY_TRACKING_ENDED_AT, 0L)
            .putBoolean(KEY_TRACKING, false)
            .putBoolean(KEY_IS_MOVING, false)
            .apply()
        distanceMetres = 0f
        movingSeconds = 0L
        elevationGainMetres = 0f
        lastAltitude = null
        currentGradePercent = 0f
        currentSplitDistance = 0f
        currentSplitMovingSeconds = 0L
        currentSplitMaxSpeed = 0f
        nextSplitKm = 1
        while (splitRecords.length() > 0) splitRecords.remove(0)
        splitRecords.put(JSONObject().put("km", 0).put("timeSeconds", 0).put("maxSpeed", 0))
        lastLocation = null
        lastAcceptedMovementAt = 0L
        isMoving = false
        movementCandidateCount = 0
        lastMovementCandidateAt = 0L
        pendingMovementDistance = 0f
        pendingMovementSeconds = 0L
        pendingMovementMaxSpeed = 0f
        lastRouteLocation = null
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
        movingSeconds = prefs.getLong(KEY_MOVING_SECONDS, 0L)
        elevationGainMetres = prefs.getFloat(KEY_ELEVATION_GAIN, 0f)
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
            if (Build.VERSION.SDK_INT >= 24 && provider == LocationManager.GPS_PROVIDER) {
                runCatching { locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper())) }
            }
            locationUpdatesRunning = true
            // Cached fixes can be minutes old and create fake distance jumps at startup.
            updateNotification("GPS active — waiting for a fresh location fix…")
        } catch (_: SecurityException) {
            updateNotification("Location permission needed — open Kxko Cycling")
        } catch (_: IllegalArgumentException) {
            updateNotification("Location provider unavailable")
        }
    }

    private fun appendRoutePoint(location: Location) {
        val route = runCatching { JSONArray(prefs.getString(KEY_ROUTE_POINTS, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        val previous = lastRouteLocation
        if (previous != null && previous.distanceTo(location) < 4f) return
        route.put(JSONArray().put(location.latitude).put(location.longitude))
        lastRouteLocation = location
        val bounded = if (route.length() > 2500) {
            JSONArray().also { trimmed ->
                for (index in route.length() - 2500 until route.length()) trimmed.put(route.getJSONArray(index))
            }
        } else route
        prefs.edit().putString(KEY_ROUTE_POINTS, bounded.toString()).apply()
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        val now = System.currentTimeMillis()
        var speedKmh = 0f
        if (previous != null && location.accuracy <= 25f && previous.accuracy <= 35f) {
            // Compare against the last accepted fix, not every noisy one-second fix.
            // This lets slow cycling accumulate enough displacement to be recognised.
            val segment = previous.distanceTo(location)
            val elapsedSeconds = ((location.time - previous.time).coerceAtLeast(1L)) / 1000f
            val measuredSpeed = segment / elapsedSeconds
            val movementThreshold = maxOf(8f, location.accuracy * 1.2f)
            val movementCandidate = segment >= movementThreshold &&
                measuredSpeed in 0.8f..16.7f && segment <= 120f
            if (movementCandidate) {
                val candidateSpeedKmh = measuredSpeed * 3.6f
                val candidateSeconds = elapsedSeconds.toLong().coerceAtLeast(1L)
                if (!isMoving) {
                    if (lastMovementCandidateAt == 0L || now - lastMovementCandidateAt > 6000L) {
                        movementCandidateCount = 0
                        pendingMovementDistance = 0f
                        pendingMovementSeconds = 0L
                        pendingMovementMaxSpeed = 0f
                    }
                    movementCandidateCount++
                    pendingMovementDistance += segment
                    pendingMovementSeconds += candidateSeconds
                    pendingMovementMaxSpeed = maxOf(pendingMovementMaxSpeed, candidateSpeedKmh)
                    lastMovementCandidateAt = now
                    lastLocation = location
                    // Require two consecutive plausible movement segments before declaring GO.
                    if (movementCandidateCount < 2) {
                        speedKmh = 0f
                    } else {
                        isMoving = true
                        distanceMetres += pendingMovementDistance
                        movingSeconds += pendingMovementSeconds
                        currentSplitDistance += pendingMovementDistance
                        currentSplitMovingSeconds += pendingMovementSeconds
                        speedKmh = candidateSpeedKmh
                        currentSplitMaxSpeed = maxOf(currentSplitMaxSpeed, pendingMovementMaxSpeed)
                        pendingMovementDistance = 0f
                        pendingMovementSeconds = 0L
                        pendingMovementMaxSpeed = 0f
                    }
                } else {
                    distanceMetres += segment
                    movingSeconds += candidateSeconds
                    currentSplitDistance += segment
                    currentSplitMovingSeconds += candidateSeconds
                    speedKmh = candidateSpeedKmh
                    currentSplitMaxSpeed = maxOf(currentSplitMaxSpeed, speedKmh)
                }
                while (distanceMetres >= nextSplitKm * 1000f) {
                    splitRecords.put(JSONObject()
                        .put("km", nextSplitKm)
                        .put("timeSeconds", currentSplitMovingSeconds)
                        .put("maxSpeed", currentSplitMaxSpeed))
                    nextSplitKm++
                    currentSplitDistance = 0f
                    currentSplitMovingSeconds = 0L
                    currentSplitMaxSpeed = 0f
                }
                if (isMoving) lastAcceptedMovementAt = now
                if (isMoving && location.hasAltitude() && location.accuracy <= 20f) {
                    val altitude = location.altitude
                    val previousAltitude = lastAltitude
                    if (previousAltitude != null) {
                        val climb = altitude - previousAltitude
                        val verticalAccuracyGood = Build.VERSION.SDK_INT < 26 || (location.hasVerticalAccuracy() && location.verticalAccuracyMeters <= 10f)
                        if (verticalAccuracyGood && climb in 2.5..25.0) {
                            elevationGainMetres += climb.toFloat()
                            val horizontal = maxOf(segment.toDouble(), 1.0)
                            currentGradePercent = ((climb / horizontal) * 100.0).toFloat().coerceIn(-20f, 20f)
                        } else currentGradePercent = 0f
                    }
                    lastAltitude = altitude
                }
                val startedAt = prefs.getLong(KEY_TRACKING_STARTED_AT, now)
                val speedHistory = runCatching { JSONArray(prefs.getString(KEY_SPEED_HISTORY, "[]") ?: "[]") }
                    .getOrDefault(JSONArray())
                speedHistory.put(JSONArray().put(((now - startedAt).coerceAtLeast(0L)) / 1000L).put(speedKmh))
                val trimmedSpeedHistory = if (speedHistory.length() > 120) JSONArray().also { trimmed ->
                    for (index in speedHistory.length() - 120 until speedHistory.length()) trimmed.put(speedHistory.getJSONArray(index))
                } else speedHistory
                prefs.edit().putString(KEY_SPEED_HISTORY, trimmedSpeedHistory.toString()).apply()
                if (isMoving && location.accuracy <= 20f) appendRoutePoint(location)
                if (isMoving) lastLocation = location
            } else if (segment >= movementThreshold || elapsedSeconds > 30f) {
                // Large but implausibly slow drift is GPS wander; reset the anchor.
                lastLocation = location
                if (!isMoving) {
                    movementCandidateCount = 0
                    lastMovementCandidateAt = 0L
                    pendingMovementDistance = 0f
                    pendingMovementSeconds = 0L
                    pendingMovementMaxSpeed = 0f
                }
            }
        } else {
            lastLocation = location
        }
        // Stop only after a sustained quiet window; brief GPS gaps won't flicker STOP/GO.
        if (isMoving && lastAcceptedMovementAt > 0L && now - lastAcceptedMovementAt > 10000L) {
            isMoving = false
            movementCandidateCount = 0
            lastMovementCandidateAt = 0L
            pendingMovementDistance = 0f
            pendingMovementSeconds = 0L
            pendingMovementMaxSpeed = 0f
            speedKmh = 0f
        }
        if (!isMoving || lastAcceptedMovementAt == 0L || now - lastAcceptedMovementAt > 10000L) speedKmh = 0f
        prefs.edit().putBoolean(KEY_IS_MOVING, isMoving).apply()
        val maxSpeed = maxOf(prefs.getFloat(KEY_MAX_SPEED, 0f), speedKmh)
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        if (prefs.getString(KEY_START_COORDINATES, "").isNullOrEmpty()) {
            prefs.edit().putString(KEY_START_COORDINATES, String.format(Locale.US, "%.5f, %.5f", currentLatitude, currentLongitude)).apply()
        }
        if (location.hasBearing()) currentBearing = location.bearing
        speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else -1f
        verticalAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters else -1f
        prefs.edit()
            .putFloat(KEY_DISTANCE, distanceMetres)
            .putFloat(KEY_SPEED, speedKmh)
            .putFloat(KEY_MAX_SPEED, maxSpeed)
            .putLong(KEY_MOVING_SECONDS, movingSeconds)
            .putFloat(KEY_ELEVATION_GAIN, elevationGainMetres)
            .putFloat(KEY_CURRENT_ALTITUDE, if (location.hasAltitude()) location.altitude.toFloat() else prefs.getFloat(KEY_CURRENT_ALTITUDE, 0f))
            .putFloat(KEY_GPS_ACCURACY, location.accuracy)
            .putFloat(KEY_SPEED_ACCURACY, speedAccuracy)
            .putFloat(KEY_VERTICAL_ACCURACY, verticalAccuracy)
            .putFloat(KEY_CURRENT_GRADE, currentGradePercent)
            .putFloat(KEY_BEARING, currentBearing)
            .putString(KEY_COORDINATES, String.format(Locale.US, "%.5f, %.5f", currentLatitude, currentLongitude))
            .putInt(KEY_SATELLITES_VISIBLE, satellitesVisible)
            .putInt(KEY_SATELLITES_USED, satellitesUsed)
            .putString(KEY_SPLITS, splitRecords.toString())
            .putLong(KEY_LAST_FIX, System.currentTimeMillis())
            .apply()

        val monotonicNow = SystemClock.elapsedRealtime()
        if (monotonicNow - lastNotificationUpdate >= 5000L) {
            lastNotificationUpdate = monotonicNow
            updateNotification(String.format(Locale.getDefault(), "%.2f km · %.1f km/h", distanceMetres / 1000f, speedKmh))
        }
        sendBroadcast(Intent(ACTION_STATS_UPDATED).setPackage(packageName))
    }

    private fun saveCompletedRide() {
        if (distanceMetres < 50f) return
        val history = runCatching { JSONArray(prefs.getString(KEY_RIDE_HISTORY, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        val route = runCatching { JSONArray(prefs.getString(KEY_ROUTE_POINTS, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        val compactRoute = JSONArray()
        val step = maxOf(1, route.length() / 600)
        for (index in 0 until route.length() step step) compactRoute.put(route.getJSONArray(index))
        val startedAt = prefs.getLong(KEY_TRACKING_STARTED_AT, System.currentTimeMillis())
        val endedAt = System.currentTimeMillis()
        val ride = JSONObject()
            .put("startedAt", startedAt)
            .put("endedAt", endedAt)
            .put("elapsedSeconds", ((endedAt - startedAt).coerceAtLeast(0L)) / 1000L)
            .put("distance", distanceMetres)
            .put("movingSeconds", movingSeconds)
            .put("maxSpeed", prefs.getFloat(KEY_MAX_SPEED, 0f))
            .put("elevationGain", elevationGainMetres)
            .put("route", compactRoute)
            .put("speedHistory", JSONArray(prefs.getString(KEY_SPEED_HISTORY, "[]") ?: "[]"))
        history.put(ride)
        val bounded = JSONArray()
        for (index in maxOf(0, history.length() - 15) until history.length()) bounded.put(history.getJSONObject(index))
        prefs.edit().putString(KEY_RIDE_HISTORY, bounded.toString()).apply()
    }

    private fun stopTracking() {
        saveCompletedRide()
        runCatching { locationManager.removeUpdates(this) }
        if (Build.VERSION.SDK_INT >= 24) runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        locationUpdatesRunning = false
        prefs.edit()
            .putBoolean(KEY_TRACKING, false)
            .putBoolean(KEY_ARMED, false)
            .putBoolean(KEY_IS_MOVING, false)
            .putLong(KEY_TRACKING_ENDED_AT, System.currentTimeMillis())
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
        const val KEY_IS_MOVING = "cycling_is_moving"
        const val KEY_DISTANCE = "cycling_distance_metres"
        const val KEY_SPEED = "cycling_speed_kmh"
        const val KEY_MAX_SPEED = "cycling_max_speed_kmh"
        const val KEY_ROUTE_POINTS = "cycling_route_points"
        const val KEY_LAST_FIX = "cycling_last_fix"
        const val KEY_TRACKING_STARTED_AT = "cycling_tracking_started_at"
        const val KEY_TRACKING_ENDED_AT = "cycling_tracking_ended_at"
        const val KEY_MOVING_SECONDS = "cycling_moving_seconds"
        const val KEY_ELEVATION_GAIN = "cycling_elevation_gain"
        const val KEY_CURRENT_ALTITUDE = "cycling_current_altitude"
        const val KEY_GPS_ACCURACY = "cycling_gps_accuracy"
        const val KEY_SPEED_ACCURACY = "cycling_speed_accuracy"
        const val KEY_VERTICAL_ACCURACY = "cycling_vertical_accuracy"
        const val KEY_CURRENT_GRADE = "cycling_current_grade"
        const val KEY_BEARING = "cycling_bearing"
        const val KEY_COORDINATES = "cycling_coordinates"
        const val KEY_START_COORDINATES = "cycling_start_coordinates"
        const val KEY_SATELLITES_VISIBLE = "cycling_satellites_visible"
        const val KEY_SATELLITES_USED = "cycling_satellites_used"
        const val KEY_SPLITS = "cycling_splits"
        const val KEY_SPEED_HISTORY = "cycling_speed_history"
        const val KEY_RIDE_HISTORY = "cycling_ride_history"
        private const val CHANNEL_ID = "cycling_tracking"
        private const val NOTIFICATION_ID = 648
    }
}
