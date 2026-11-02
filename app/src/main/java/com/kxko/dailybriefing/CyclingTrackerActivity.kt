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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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

private data class RidePoint(val latitude: Double, val longitude: Double)

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

        setContent {
            KxkoTheme {
                val prefs = getSharedPreferences(WatchWorkoutNotificationListener.PREFS, MODE_PRIVATE)
                val hasAccess = remember(refresh) { WatchWorkoutNotificationListener.hasAccess(this) }
                val active = remember(refresh) { prefs.getBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false) }
                val tracking = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_TRACKING, false) }
                val armed = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_ARMED, false) }
                val distance = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_DISTANCE, 0f) }
                val speed = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_SPEED, 0f) }
                val maxSpeed = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_MAX_SPEED, 0f) }
                val trackingStartedAt = remember(refresh) { prefs.getLong(CyclingTrackerService.KEY_TRACKING_STARTED_AT, 0L) }
                val route = remember(refresh) { readRoute(prefs.getString(CyclingTrackerService.KEY_ROUTE_POINTS, "[]")) }
                val elapsed = if (tracking && trackingStartedAt > 0L)
                    ((System.currentTimeMillis() - trackingStartedAt) / 1000L).coerceAtLeast(0L) else 0L
                val averageSpeed = if (elapsed > 0L) distance / elapsed.toFloat() * 3.6f else 0f
                val hasLocation = hasLocationPermission()
                var followLocation by remember { mutableStateOf(true) }

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
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
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
                                        tracking && speed > 0.5f -> "Movement confirmed · route recording"
                                        tracking -> "GPS is filtering small location shifts"
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
                            SecondaryMetric("AVG SPEED", String.format(Locale.getDefault(), "%.1f km/h", averageSpeed), Modifier.weight(1f))
                            SecondaryMetric("TOP SPEED", String.format(Locale.getDefault(), "%.1f km/h", maxSpeed), Modifier.weight(1f))
                            SecondaryMetric("GPS POINTS", route.size.toString(), Modifier.weight(1f))
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
                            if (!hasAccess) {
                                KxButton(
                                    text = "Enable Mi Fitness notification access",
                                    onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            KxButton(
                                text = if (!hasLocation) "Grant location & arm tracking" else if (armed) "Re-arm automatic tracking" else "Arm automatic GPS tracking",
                                onClick = { requestLocationAndArm() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = hasAccess
                            )
                            if (!hasAccess) {
                                Spacer(Modifier.height(5.dp))
                                Text("Allow notification access so Kxko can detect Mi Fitness's “Trainieren...” notification.", color = Kx.Muted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (tracking) "GPS active · Route saved on this device" else if (armed) "Watch detection armed · Keep tracker notification enabled" else "OpenStreetMap · Mi Fitness watch detection",
                                color = Kx.Dim, fontSize = 10.sp, modifier = Modifier.weight(1f)
                            )
                            Text("© OpenStreetMap", color = Kx.Dim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    private fun readRoute(raw: String?): List<RidePoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val point = array.optJSONArray(index) ?: return@mapNotNull null
                if (point.length() < 2) return@mapNotNull null
                RidePoint(point.getDouble(0), point.getDouble(1))
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

private fun formatDuration(seconds: Long): String =
    String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
