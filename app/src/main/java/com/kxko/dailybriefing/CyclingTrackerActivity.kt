package com.kxko.dailybriefing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kxko.dailybriefing.ui.theme.Kx
import com.kxko.dailybriefing.ui.theme.KxButton
import com.kxko.dailybriefing.ui.theme.KxChip
import com.kxko.dailybriefing.ui.theme.KxkoTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File

private data class RidePoint(val latitude: Double, val longitude: Double, val timestamp: Long = 0L, val altitude: Float = Float.NaN, val speedKmh: Float = 0f, val accuracy: Float = -1f, val bearing: Float = 0f, val verticalAccuracy: Float = -1f)
private data class RideSummary(
    val startedAt: Long,
    val distanceMetres: Float,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val maxSpeed: Float,
    val elevationGain: Float,
    val averageSpeed: Float = 0f,
    val stoppedSeconds: Long = 0L,
    val endedAt: Long = 0L,
    val route: List<RidePoint> = emptyList(),
    val speedHistory: List<Pair<Float, Float>> = emptyList(),
    val splits: List<KmSplit> = emptyList(),
    val minimumAltitude: Float = 0f,
    val maximumAltitude: Float = 0f,
    val maximumGrade: Float = 0f,
    val gpsFixCount: Int = 0,
    val averageGpsAccuracy: Float = -1f,
    val lastGpsAccuracy: Float = -1f,
    val lastSpeedAccuracy: Float = -1f,
    val lastVerticalAccuracy: Float = -1f,
    val lastHeading: Float = 0f,
    val satellitesVisible: Int = 0,
    val satellitesUsed: Int = 0,
    val batteryPercent: Int = -1,
    val startCoordinates: String = "",
    val endCoordinates: String = "",
    val endReason: String = ""
)
private data class KmSplit(val kilometre: Int, val movingSeconds: Long, val maxSpeed: Float, val partial: Boolean = false)

class CyclingTrackerActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private var cyclingMap: MapView? = null
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { refresh++ }
    }
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            refresh++
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) armAutomaticTracking()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 19, 21)
        window.navigationBarColor = android.graphics.Color.rgb(17, 19, 21)
        Configuration.getInstance().userAgentValue = packageName
        val filter = android.content.IntentFilter().apply {
            addAction(WatchWorkoutNotificationListener.ACTION_WORKOUT_STARTED)
            addAction(CyclingTrackerService.ACTION_STATS_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)

        if (hasLocationPermission() && WatchWorkoutNotificationListener.hasAccess(this)) armAutomaticTracking()

        setContent {
            KxkoTheme {
                val prefs = getSharedPreferences(WatchWorkoutNotificationListener.PREFS, MODE_PRIVATE)
                val hasAccess = remember(refresh) { WatchWorkoutNotificationListener.hasAccess(this) }
                val active = remember(refresh) { prefs.getBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false) }
                val tracking = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_TRACKING, false) }
                val isMoving = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_IS_MOVING, false) }
                val armed = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_ARMED, false) }
                val distance = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_DISTANCE, 0f) }
                val speed = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_SPEED, 0f) }
                val maxSpeed = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_MAX_SPEED, 0f) }
                val movingSeconds = remember(refresh) { prefs.getLong(CyclingTrackerService.KEY_MOVING_SECONDS, 0L) }
                val elevationGain = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_ELEVATION_GAIN, 0f) }
                val altitude = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_CURRENT_ALTITUDE, 0f) }
                val gpsAccuracy = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_GPS_ACCURACY, -1f) }
                val speedAccuracy = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_SPEED_ACCURACY, -1f) }
                val verticalAccuracy = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_VERTICAL_ACCURACY, -1f) }
                val grade = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_CURRENT_GRADE, 0f) }
                val bearing = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_BEARING, 0f) }
                val coordinates = remember(refresh) { prefs.getString(CyclingTrackerService.KEY_COORDINATES, "") ?: "" }
                val satellitesVisible = remember(refresh) { prefs.getInt(CyclingTrackerService.KEY_SATELLITES_VISIBLE, 0) }
                val satellitesUsed = remember(refresh) { prefs.getInt(CyclingTrackerService.KEY_SATELLITES_USED, 0) }
                val splits = remember(refresh) { readSplits(prefs.getString(CyclingTrackerService.KEY_SPLITS, "[]")) }
                val speedSamples = remember(refresh) { readSpeedSamples(prefs.getString(CyclingTrackerService.KEY_SPEED_HISTORY, "[]")) }
                val rideHistory = remember(refresh) { readRideHistory(prefs.getString(CyclingTrackerService.KEY_RIDE_HISTORY, "[]")) }
                val trackingStartedAt = remember(refresh) { prefs.getLong(CyclingTrackerService.KEY_TRACKING_STARTED_AT, 0L) }
                val trackingEndedAt = remember(refresh) { prefs.getLong(CyclingTrackerService.KEY_TRACKING_ENDED_AT, 0L) }
                val route = remember(refresh) { readRoute(prefs.getString(CyclingTrackerService.KEY_ROUTE_POINTS, "[]")) }
                val elapsed = when {
                    tracking && trackingStartedAt > 0L -> ((System.currentTimeMillis() - trackingStartedAt) / 1000L).coerceAtLeast(0L)
                    !tracking && trackingStartedAt > 0L && trackingEndedAt >= trackingStartedAt -> ((trackingEndedAt - trackingStartedAt) / 1000L).coerceAtLeast(0L)
                    else -> 0L
                }
                val averageSpeed = if (movingSeconds > 0L) distance / movingSeconds.toFloat() * 3.6f else 0f
                val stoppedSeconds = (elapsed - movingSeconds).coerceAtLeast(0L)
                val pace = if (speed > 0.5f) 60f / speed else 0f
                val hasLocation = hasLocationPermission()
                var followLocation by remember { mutableStateOf(true) }
                var showDetails by remember { mutableStateOf(false) }
                var showHistory by remember { mutableStateOf(false) }
                var selectedRide by remember { mutableStateOf<RideSummary?>(null) }

                LaunchedEffect(tracking) {
                    while (tracking) {
                        delay(1000)
                        refresh++
                    }
                }

                Box(Modifier.fillMaxSize().background(Kx.Bg)) {
                    val mapView = remember {
                        MapView(this@CyclingTrackerActivity).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            setBuiltInZoomControls(false)
                            controller.setZoom(6.0)
                            controller.setCenter(GeoPoint(51.1657, 10.4515))
                        }.also { cyclingMap = it }
                    }
                    AndroidView(
                        factory = { mapView },
                        modifier = Modifier.fillMaxSize(),
                        update = { map ->
                            map.overlays.clear()
                            val geoPoints = route.map { GeoPoint(it.latitude, it.longitude) }
                            if (geoPoints.size > 1) {
                                map.overlays.add(Polyline(map).apply {
                                    setPoints(geoPoints)
                                    outlinePaint.color = android.graphics.Color.rgb(191, 145, 82)
                                    outlinePaint.strokeWidth = 13f
                                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                })
                            }
                            geoPoints.lastOrNull()?.let { current ->
                                map.overlays.add(Marker(map).apply {
                                    position = current
                                    title = "Current position"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                })
                                if (followLocation && tracking) {
                                    if (geoPoints.size == 1) map.controller.setZoom(16.0)
                                    map.controller.setCenter(current)
                                }
                            }
                            map.invalidate()
                        }
                    )
                    DisposableEffect(mapView) {
                        onDispose { mapView.onPause(); mapView.onDetach(); cyclingMap = null }
                    }

                    Column(
                        Modifier.fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Kx.Bg.copy(alpha = 0.94f), Kx.Bg.copy(alpha = 0.72f), Color.Transparent)
                            ))
                            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 34.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("KXKO  /  OUTDOOR", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text("Cycling", color = Kx.Cream, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            }
                            KxChip(
                                text = when { tracking -> "● LIVE"; armed -> "● ARMED"; else -> "○ READY" },
                                background = when { tracking -> Kx.Good.copy(alpha = 0.18f); armed -> Kx.BrassSoft; else -> Kx.SurfaceHi },
                                foreground = when { tracking -> Kx.Good; armed -> Kx.Brass; else -> Kx.Muted }
                            )
                        }
                        if (!tracking && !armed) {
                            Spacer(Modifier.height(8.dp))
                            Text("Your ride map will appear here when GPS tracking starts.", color = Kx.Muted, fontSize = 12.sp)
                        }
                    }

                    if (route.isNotEmpty()) {
                        KxChip(
                            text = "● GPS ROUTE",
                            background = Kx.Surface.copy(alpha = 0.94f),
                            foreground = Kx.Good,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, top = 82.dp)
                        )
                    }

                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            .background(Kx.Surface.copy(alpha = 0.98f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                            .padding(start = 20.dp, end = 20.dp, top = 17.dp, bottom = 18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when { tracking -> "RIDE IN PROGRESS"; armed -> "READY WHEN YOU ARE"; else -> "YOUR NEXT RIDE" },
                                    color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp
                                )
                                Text(
                                    when {
                                        tracking && isMoving -> "MOVING · route recording"
                                        tracking -> "STOPPED · waiting for movement"
                                        armed -> "Waiting for your Redmi Watch"
                                        else -> "Track every kilometre."
                                    },
                                    color = Kx.Cream, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (tracking) KxButton(
                                text = if (followLocation) "Following" else "Follow me",
                                onClick = { followLocation = !followLocation },
                                primary = false
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RideMetric("DISTANCE", String.format(Locale.getDefault(), "%.2f", distance / 1000f), "km", Modifier.weight(1.15f))
                            RideMetric("SPEED", String.format(Locale.getDefault(), "%.1f", speed), "km/h", Modifier.weight(1f), highlight = tracking)
                            RideMetric("DURATION", formatDuration(elapsed), "", Modifier.weight(1.15f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryMetric("AVG MOVING", String.format(Locale.getDefault(), "%.1f km/h", averageSpeed), Modifier.weight(1f))
                            SecondaryMetric("MOVING TIME", formatDuration(movingSeconds), Modifier.weight(1f))
                            SecondaryMetric("CLIMBED", String.format(Locale.getDefault(), "%.0f m", elevationGain), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KxButton(text = if (showDetails) "Hide details" else "More ride data", onClick = { showDetails = !showDetails }, primary = false, modifier = Modifier.weight(1f))
                            KxButton(text = "Ride history (${rideHistory.size})", onClick = { showHistory = true }, primary = false, modifier = Modifier.weight(1f))
                        }
                        if (showDetails) {
                            Spacer(Modifier.height(14.dp))
                            Text("SPEED OVER TIME", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Spacer(Modifier.height(7.dp))
                            SpeedHistoryGraph(speedSamples)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryMetric("TOP SPEED", String.format(Locale.getDefault(), "%.1f km/h", maxSpeed), Modifier.weight(1f))
                                SecondaryMetric("ALTITUDE", if (altitude > 0f) String.format(Locale.getDefault(), "%.0f m", altitude) else "—", Modifier.weight(1f))
                                SecondaryMetric("GPS ACCURACY", if (gpsAccuracy >= 0f) String.format(Locale.getDefault(), "±%.0f m", gpsAccuracy) else "—", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when { gpsAccuracy < 0f -> "Waiting for a GPS fix"; gpsAccuracy <= 10f -> "GPS signal: excellent"; gpsAccuracy <= 25f -> "GPS signal: good"; gpsAccuracy <= 50f -> "GPS signal: fair"; else -> "GPS signal: weak — distance may be less accurate" },
                                color = Kx.Muted, fontSize = 11.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("LIVE TELEMETRY", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Spacer(Modifier.height(7.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryMetric("CURRENT PACE", if (pace > 0f) String.format(Locale.getDefault(), "%.1f min/km", pace) else "—", Modifier.weight(1f))
                                SecondaryMetric("STOPPED TIME", formatDuration(stoppedSeconds), Modifier.weight(1f))
                                SecondaryMetric("ROAD GRADE", String.format(Locale.getDefault(), "%+.1f%%", grade), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryMetric("GPS SATELLITES", if (satellitesVisible > 0) "$satellitesUsed / $satellitesVisible used" else "—", Modifier.weight(1f))
                                SecondaryMetric("SPEED ACCURACY", if (speedAccuracy >= 0f) String.format(Locale.getDefault(), "±%.1f m/s", speedAccuracy) else "—", Modifier.weight(1f))
                                SecondaryMetric("ALTITUDE ACC.", if (verticalAccuracy >= 0f) String.format(Locale.getDefault(), "±%.0f m", verticalAccuracy) else "—", Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            SecondaryMetric("HEADING", compassDirection(bearing), Modifier.fillMaxWidth())
                            if (coordinates.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("GPS: $coordinates", color = Kx.Dim, fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("KILOMETRE SPLITS", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            if (splits.size <= 1) {
                                Text("Split times appear after each completed kilometre.", color = Kx.Muted, fontSize = 11.sp)
                            } else {
                                splits.drop(1).asReversed().forEach { split ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("KM ${split.kilometre}", color = Kx.Cream, fontSize = 12.sp)
                                        Text(formatDuration(split.movingSeconds), color = Kx.Muted, fontSize = 12.sp)
                                        Text(String.format(Locale.getDefault(), "%.1f km/h max", split.maxSpeed), color = Kx.Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        if (tracking) {
                            KxButton(
                                text = "Finish ride",
                                onClick = {
                                    startService(Intent(this@CyclingTrackerActivity, CyclingTrackerService::class.java).setAction(CyclingTrackerService.ACTION_STOP))
                                    prefs.edit()
                                        .putBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false)
                                        .putBoolean(WatchWorkoutNotificationListener.KEY_PENDING_START, false)
                                        .apply()
                                    refresh++
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            if (!hasLocation) {
                                KxButton(
                                    text = "Grant location permission",
                                    onClick = { requestLocationAndArm() },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (!hasAccess) {
                                if (!hasLocation) Spacer(Modifier.height(8.dp))
                                KxButton(
                                    text = "Enable Mi Fitness notification access",
                                    onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(5.dp))
                                Text("Allow notification access so Kxko can detect Mi Fitness's “Trainieren...” workout notification.", color = Kx.Muted, fontSize = 11.sp)
                            }
                            if (hasLocation && hasAccess) {
                                Text("Automatic detection is always armed. Start a workout on your watch to begin GPS tracking.", color = Kx.Muted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (tracking) "GPS active · Route saved on this device" else if (armed) "Always armed · Waiting for Mi Fitness" else "Grant permissions to enable automatic detection",
                                color = Kx.Dim, fontSize = 10.sp, modifier = Modifier.weight(1f)
                            )
                            Text("© OpenStreetMap", color = Kx.Dim, fontSize = 10.sp)
                        }
                    }
                    if (showHistory) {
                        AlertDialog(
                            onDismissRequest = { showHistory = false },
                            title = { Text("Ride history") },
                            text = {
                                if (rideHistory.isEmpty()) {
                                    Text("Your completed rides will appear here. Finish a tracked ride to save its statistics.")
                                } else {
                                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                        rideHistory.asReversed().forEach { ride ->
                                            Column(
                                                Modifier.fillMaxWidth()
                                                    .background(Kx.Surface, RoundedCornerShape(14.dp))
                                                    .clickable { selectedRide = ride; showHistory = false }
                                                    .padding(13.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(ride.startedAt)), color = Kx.Brass, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        Text(String.format(Locale.getDefault(), "%.2f km", ride.distanceMetres / 1000f), color = Kx.Cream, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                    KxChip(text = if (ride.route.size > 1) "ROUTE SAVED" else "NO ROUTE", background = if (ride.route.size > 1) Kx.Good.copy(alpha = 0.13f) else Kx.SurfaceHi, foreground = if (ride.route.size > 1) Kx.Good else Kx.Muted)
                                                }
                                                Spacer(Modifier.height(5.dp))
                                                Text(String.format(Locale.getDefault(), "%s elapsed  ·  %s moving  ·  %.1f km/h max  ·  +%.0f m", formatDuration(ride.elapsedSeconds), formatDuration(ride.movingSeconds), ride.maxSpeed, ride.elevationGain), color = Kx.Muted, fontSize = 11.sp)
                                                Spacer(Modifier.height(7.dp))
                                                Text(if (ride.route.size > 1) "Tap to open the route on the map and view ride data  →" else "Tap to view ride statistics  →", color = Kx.BrassBright, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
                        )
                    }
                    selectedRide?.let { ride ->
                        AlertDialog(
                            onDismissRequest = { selectedRide = null },
                            title = {
                                Text(SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(ride.startedAt)))
                            },
                            text = {
                                Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                                    if (ride.route.size > 1) {
                                        Text("ROUTE MAP", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                        Spacer(Modifier.height(6.dp))
                                        AndroidView(
                                            factory = {
                                                MapView(this@CyclingTrackerActivity).apply {
                                                    setTileSource(TileSourceFactory.MAPNIK)
                                                    setMultiTouchControls(true)
                                                    setBuiltInZoomControls(false)
                                                    onResume()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().height(270.dp),
                                            update = { map ->
                                                runCatching {
                                                map.overlays.clear()
                                                val points = ride.route.map { GeoPoint(it.latitude, it.longitude) }
                                                if (points.size > 1) map.overlays.add(Polyline(map).apply {
                                                    setPoints(points)
                                                    outlinePaint.color = android.graphics.Color.rgb(191, 145, 82)
                                                    outlinePaint.strokeWidth = 10f
                                                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                                })
                                                points.firstOrNull()?.let { start ->
                                                    map.overlays.add(Marker(map).apply {
                                                        position = start
                                                        title = "Start"
                                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                                    })
                                                }
                                                points.lastOrNull()?.let { finish ->
                                                    map.overlays.add(Marker(map).apply {
                                                        position = finish
                                                        title = "Finish"
                                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                                    })
                                                }
                                                if (points.size > 1) {
                                                    val north = points.maxOf { it.latitude }
                                                    val south = points.minOf { it.latitude }
                                                    val east = points.maxOf { it.longitude }
                                                    val west = points.minOf { it.longitude }
                                                    if (north - south > 0.00001 || east - west > 0.00001) map.zoomToBoundingBox(org.osmdroid.util.BoundingBox(north, east, south, west), false, 48)
                                                    else { map.controller.setZoom(16.0); map.controller.setCenter(points.first()) }
                                                }
                                                map.invalidate()
                                                }.onFailure { android.util.Log.w("KxkoCycling", "Could not render saved ride map", it) }
                                            }
                                        )
                                    } else {
                                        Text("No route points were saved for this ride.", color = Kx.Muted, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text("RIDE SUMMARY", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                    Text(String.format(Locale.getDefault(), "Distance: %.2f km", ride.distanceMetres / 1000f), color = Kx.Cream, fontSize = 13.sp)
                                    Text("Elapsed: " + formatDuration(ride.elapsedSeconds) + " · Moving: " + formatDuration(ride.movingSeconds) + " · Stopped: " + formatDuration(ride.stoppedSeconds), color = Kx.Cream, fontSize = 12.sp)
                                    Text(String.format(Locale.getDefault(), "Average: %.1f km/h · Maximum: %.1f km/h", ride.averageSpeed, ride.maxSpeed), color = Kx.Cream, fontSize = 12.sp)
                                    Text(String.format(Locale.getDefault(), "Climb: +%.0f m · Altitude range: %.0f–%.0f m", ride.elevationGain, ride.minimumAltitude, ride.maximumAltitude), color = Kx.Cream, fontSize = 12.sp)
                                    Text(String.format(Locale.getDefault(), "Maximum estimated grade: %.1f%%", ride.maximumGrade), color = Kx.Cream, fontSize = 12.sp)
                                    Text("DATA RECORDED", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                    Text("Route samples: ${ride.route.size} · Speed timeline: ${ride.speedHistory.size} samples", color = Kx.Muted, fontSize = 11.sp)
                                    val routeAltitudes = ride.route.map { it.altitude }.filter { it.isFinite() && it > 0f }
                                    if (routeAltitudes.size > 1) {
                                        Text(String.format(Locale.getDefault(), "Recorded route altitude: %.0f–%.0f m", routeAltitudes.minOrNull() ?: 0f, routeAltitudes.maxOrNull() ?: 0f), color = Kx.Cream, fontSize = 12.sp)
                                    }
                                    val routeSpeeds = ride.route.map { it.speedKmh }.filter { it.isFinite() && it > 0f }
                                    if (routeSpeeds.isNotEmpty()) {
                                        Text(String.format(Locale.getDefault(), "Route-point speed: %.1f km/h average · %.1f km/h peak", routeSpeeds.average(), routeSpeeds.maxOrNull() ?: 0f), color = Kx.Cream, fontSize = 12.sp)
                                    }
                                    if (ride.endReason.isNotBlank()) Text("Session ended: " + ride.endReason, color = Kx.Muted, fontSize = 11.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Text("SPEED OVER TIME", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                    SpeedHistoryGraph(ride.speedHistory)
                                    Spacer(Modifier.height(10.dp))
                                    Text("GPS & DEVICE TELEMETRY", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                    Text("GPS fixes: " + ride.gpsFixCount + " · Average accuracy: " + (if (ride.averageGpsAccuracy >= 0f) String.format(Locale.getDefault(), "±%.1f m", ride.averageGpsAccuracy) else "—"), color = Kx.Muted, fontSize = 11.sp)
                                    Text("Last fix: " + (if (ride.lastGpsAccuracy >= 0f) String.format(Locale.getDefault(), "±%.1f m", ride.lastGpsAccuracy) else "—") + " · Speed accuracy: " + (if (ride.lastSpeedAccuracy >= 0f) String.format(Locale.getDefault(), "±%.1f m/s", ride.lastSpeedAccuracy) else "—"), color = Kx.Muted, fontSize = 11.sp)
                                    Text("Vertical accuracy: " + (if (ride.lastVerticalAccuracy >= 0f) String.format(Locale.getDefault(), "±%.1f m", ride.lastVerticalAccuracy) else "—") + " · Heading: " + compassDirection(ride.lastHeading), color = Kx.Muted, fontSize = 11.sp)
                                    Text("Satellites: " + ride.satellitesUsed + "/" + ride.satellitesVisible + " used · Battery at finish: " + (if (ride.batteryPercent >= 0) ride.batteryPercent.toString() + "%" else "—"), color = Kx.Muted, fontSize = 11.sp)
                                    if (ride.startCoordinates.isNotBlank()) Text("Start: " + ride.startCoordinates, color = Kx.Dim, fontSize = 10.sp)
                                    if (ride.endCoordinates.isNotBlank()) Text("Finish: " + ride.endCoordinates, color = Kx.Dim, fontSize = 10.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Text("KILOMETRE SPLITS", color = Kx.Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                    if (ride.splits.isEmpty()) Text("No kilometre splits recorded.", color = Kx.Muted, fontSize = 11.sp)
                                    ride.splits.forEach { split ->
                                        Text((if (split.partial) "PARTIAL " else "") + "KM " + split.kilometre + " · " + formatDuration(split.movingSeconds) + " · " + String.format(Locale.getDefault(), "%.1f km/h max", split.maxSpeed), color = Kx.Muted, fontSize = 11.sp)
                                    }
                                }
                            },
                            confirmButton = {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { shareRide(ride) }, enabled = ride.route.size > 1) { Text("Share GPX") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { selectedRide = null }) { Text("Close") }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /** Export the saved GPS track as GPX and open Android’s native share sheet. */
    private fun shareRide(ride: RideSummary) {
        if (ride.route.size < 2) return
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(ride.startedAt))
            val safeName = "Kxko_Ride_" + stamp
            val gpx = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append("<gpx version=\"1.1\" creator=\"Kxko Cycling\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
                append("<metadata><name>" + safeName + "</name></metadata><trk><name>" + safeName + "</name><trkseg>")
                ride.route.forEach { point ->
                    append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"></trkpt>", point.latitude, point.longitude))
                }
                append("</trkseg></trk></gpx>")
            }
            val directory = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(directory, safeName + ".gpx")
            file.writeText(gpx, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val summary = buildString {
                append("My cycling ride · ").append(String.format(Locale.getDefault(), "%.2f km", ride.distanceMetres / 1000f))
                append("\nTime: ").append(formatDuration(ride.elapsedSeconds))
                append("\nMoving: ").append(formatDuration(ride.movingSeconds))
                append(String.format(Locale.getDefault(), "\nAverage speed: %.1f km/h", ride.averageSpeed))
                append(String.format(Locale.getDefault(), "\nMaximum speed: %.1f km/h", ride.maxSpeed))
                append(String.format(Locale.getDefault(), "\nElevation gain: +%.0f m", ride.elevationGain))
                append("\nRecorded with Kxko Cycling. GPX route attached.")
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_SUBJECT, "Cycling ride · " + String.format(Locale.getDefault(), "%.2f km", ride.distanceMetres / 1000f))
                putExtra(Intent.EXTRA_TEXT, summary)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, "Cycling route GPX", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share cycling ride"))
        }.onFailure {
            android.widget.Toast.makeText(this, "Couldn’t export this ride. Please try again.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun readSpeedSamples(raw: String?): List<Pair<Float, Float>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val point = array.optJSONArray(i) ?: return@mapNotNull null
                if (point.length() < 2) return@mapNotNull null
                point.optDouble(0, Double.NaN).toFloat() to point.optDouble(1, Double.NaN).toFloat()
            }.filter { it.first.isFinite() && it.second.isFinite() }
        }.getOrDefault(emptyList())
    }

    private fun readSplits(raw: String?): List<KmSplit> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                KmSplit(item.optInt("km"), item.optLong("timeSeconds"), item.optDouble("maxSpeed").toFloat(), item.optBoolean("partial", false))
            }
        }.getOrDefault(emptyList())
    }

    private fun readRideHistory(raw: String?): List<RideSummary> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                RideSummary(
                    startedAt = item.optLong("startedAt"),
                    distanceMetres = item.optDouble("distance").toFloat(),
                    elapsedSeconds = item.optLong("elapsedSeconds"),
                    movingSeconds = item.optLong("movingSeconds"),
                    maxSpeed = item.optDouble("maxSpeed").toFloat(),
                    elevationGain = item.optDouble("elevationGain").toFloat(),
                    averageSpeed = item.optDouble("averageSpeed").toFloat(),
                    stoppedSeconds = item.optLong("stoppedSeconds"),
                    endedAt = item.optLong("endedAt"),
                    route = readRoute(item.optJSONArray("route")?.toString()),
                    speedHistory = readSpeedSamples(item.optJSONArray("speedHistory")?.toString()),
                    splits = readSplits(item.optJSONArray("splits")?.toString()),
                    minimumAltitude = item.optDouble("minimumAltitude").toFloat(),
                    maximumAltitude = item.optDouble("maximumAltitude").toFloat(),
                    maximumGrade = item.optDouble("maximumGrade").toFloat(),
                    gpsFixCount = item.optInt("gpsFixCount"),
                    averageGpsAccuracy = item.optDouble("averageGpsAccuracy", -1.0).toFloat(),
                    lastGpsAccuracy = item.optDouble("lastGpsAccuracy", -1.0).toFloat(),
                    lastSpeedAccuracy = item.optDouble("lastSpeedAccuracy", -1.0).toFloat(),
                    lastVerticalAccuracy = item.optDouble("lastVerticalAccuracy", -1.0).toFloat(),
                    lastHeading = item.optDouble("lastHeading").toFloat(),
                    satellitesVisible = item.optInt("satellitesVisible"),
                    satellitesUsed = item.optInt("satellitesUsed"),
                    batteryPercent = item.optInt("batteryPercent", -1),
                    startCoordinates = item.optString("startCoordinates"),
                    endCoordinates = item.optString("endCoordinates"),
                    endReason = item.optString("endReason")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun readRoute(raw: String?): List<RidePoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val point = array.optJSONArray(index) ?: return@mapNotNull null
                if (point.length() < 2) return@mapNotNull null
                RidePoint(point.optDouble(0, Double.NaN), point.optDouble(1, Double.NaN), point.optLong(2, 0L), point.optDouble(3, Double.NaN).toFloat(), point.optDouble(4, 0.0).toFloat(), point.optDouble(5, -1.0).toFloat(), point.optDouble(6, 0.0).toFloat(), point.optDouble(7, -1.0).toFloat()).takeIf { it.latitude.isFinite() && it.longitude.isFinite() && kotlin.math.abs(it.latitude) <= 90 && kotlin.math.abs(it.longitude) <= 180 } ?: return@mapNotNull null
            }
        }.getOrDefault(emptyList())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationAndArm() {
        if (hasLocationPermission()) armAutomaticTracking()
        else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun armAutomaticTracking() {
        val intent = Intent(this, CyclingTrackerService::class.java).setAction(CyclingTrackerService.ACTION_ARM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refresh++
    }

    override fun onResume() {
        super.onResume()
        cyclingMap?.onResume()
        if (hasLocationPermission() && WatchWorkoutNotificationListener.hasAccess(this)) armAutomaticTracking()
        refresh++
    }

    override fun onPause() {
        cyclingMap?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}

@Composable
private fun RideMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier.background(Kx.SurfaceHi, RoundedCornerShape(17.dp)).padding(horizontal = 11.dp, vertical = 13.dp)) {
        Text(label, color = Kx.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = if (highlight) Kx.Good else Kx.Cream, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Kx.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun SecondaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Kx.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Kx.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeedHistoryGraph(samples: List<Pair<Float, Float>>) {
    if (samples.size < 2) {
        Text("The graph will build as GPS confirms movement.", color = Kx.Muted, fontSize = 11.sp)
        return
    }
    Canvas(Modifier.fillMaxWidth().height(86.dp).background(Kx.SurfaceHi, RoundedCornerShape(12.dp)).padding(8.dp)) {
        val maxSpeed = (samples.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(5f)
        val minTime = samples.first().first
        val timeSpan = (samples.last().first - minTime).coerceAtLeast(1f)
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = (sample.first - minTime) / timeSpan * size.width
            val y = size.height - (sample.second / maxSpeed).coerceIn(0f, 1f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Kx.Brass, style = Stroke(width = 3.dp.toPx()))
    }
}

private fun compassDirection(degrees: Float): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((degrees + 22.5f) / 45f).toInt().mod(8)] + String.format(Locale.getDefault(), " · %.0f°", degrees)
}

private fun formatDuration(seconds: Long): String =
    String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
