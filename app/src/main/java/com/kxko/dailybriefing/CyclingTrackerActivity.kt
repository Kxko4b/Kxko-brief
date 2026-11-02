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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kxko.dailybriefing.ui.theme.Kx
import com.kxko.dailybriefing.ui.theme.KxButton
import com.kxko.dailybriefing.ui.theme.KxCard
import com.kxko.dailybriefing.ui.theme.KxLabel
import kotlinx.coroutines.delay
import java.util.Locale

class CyclingTrackerActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
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
        val filter = android.content.IntentFilter().apply {
            addAction(WatchWorkoutNotificationListener.ACTION_WORKOUT_STARTED)
            addAction(CyclingTrackerService.ACTION_STATS_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)

        setContent {
            MaterialTheme {
                val prefs = getSharedPreferences(WatchWorkoutNotificationListener.PREFS, MODE_PRIVATE)
                val hasAccess = remember(refresh) { WatchWorkoutNotificationListener.hasAccess(this) }
                val active = remember(refresh) { prefs.getBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false) }
                val startedAt = remember(refresh) { prefs.getLong(WatchWorkoutNotificationListener.KEY_STARTED_AT, 0L) }
                val tracking = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_TRACKING, false) }
                val armed = remember(refresh) { prefs.getBoolean(CyclingTrackerService.KEY_ARMED, false) }
                val distance = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_DISTANCE, 0f) }
                val speed = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_SPEED, 0f) }
                val trackingStartedAt = remember(refresh) { prefs.getLong(CyclingTrackerService.KEY_TRACKING_STARTED_AT, 0L) }
                val elapsedSeconds = if (tracking && trackingStartedAt > 0L) ((System.currentTimeMillis() - trackingStartedAt) / 1000L).coerceAtLeast(0L) else 0L
                val hasLocation = hasLocationPermission()

                LaunchedEffect(tracking) {
                    while (tracking) {
                        delay(1000)
                        refresh++
                    }
                }

                Column(
                    Modifier.fillMaxSize().background(Kx.Bg).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Cycling", color = Kx.Cream, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("WATCH CONNECTION", color = Kx.Brass, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    KxCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(9.dp).background(if (hasAccess) Kx.Good else Kx.Warn, androidx.compose.foundation.shape.CircleShape))
                            Text(if (hasAccess) "Notification access enabled" else "Notification access required", color = Kx.Cream, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Kxko checks Mi Fitness notifications for the “Trainieren...” workout signal. Notifications from other apps are not inspected.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        KxButton(
                            text = if (hasAccess) "Notification access settings" else "Enable Mi Fitness detection",
                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    KxCard(accent = true) {
                        KxLabel("WORKOUT DETECTION")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                tracking -> "GPS tracking active"
                                active -> "Watch workout detected"
                                armed -> "Automatic tracking armed"
                                else -> "Waiting for your watch"
                            },
                            color = Kx.Cream, fontSize = 21.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                tracking -> "Recording your ride using the phone's GPS."
                                active && startedAt > 0L -> "Mi Fitness notification detected at " + java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(startedAt))
                                armed -> "Ready. Start a cycling workout on your Redmi Watch; GPS will start when Mi Fitness posts “Trainieren...”."
                                else -> "Arm automatic tracking before your ride. Kxko will then wait for the Mi Fitness workout notification."
                            },
                            color = Kx.Muted, fontSize = 13.sp
                        )

                        if (tracking) {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Metric("DISTANCE", String.format(Locale.getDefault(), "%.2f km", distance / 1000f), Modifier.weight(1f))
                                Metric("SPEED", String.format(Locale.getDefault(), "%.1f km/h", speed), Modifier.weight(1f))
                                Metric("TIME", String.format(Locale.getDefault(), "%02d:%02d:%02d", elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(14.dp))
                            KxButton(
                                text = "Finish workout",
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
                            Spacer(Modifier.height(12.dp))
                            KxButton(
                                text = if (!hasLocation) "Grant location & arm tracking" else if (armed) "Re-arm automatic tracking" else "Arm automatic GPS tracking",
                                onClick = { requestLocationAndArm() },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (active) {
                                Spacer(Modifier.height(8.dp))
                                KxButton(
                                    text = "Reset detected workout",
                                    onClick = {
                                        prefs.edit()
                                            .putBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false)
                                            .putBoolean(WatchWorkoutNotificationListener.KEY_PENDING_START, false)
                                            .apply()
                                        refresh++
                                    },
                                    primary = false,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    KxCard {
                        KxLabel("LOCATION")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (hasLocation) "Location permission is enabled. Arm the tracker while Kxko is open; it will wait in the background and start GPS when the watch workout begins."
                            else "Location permission is needed to calculate distance and speed. It is only used while the cycling tracker is armed or recording.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                    }

                    KxCard {
                        KxLabel("HOW IT WORKS")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "1. Enable Mi Fitness notification access.\n2. Grant location permission.\n3. Tap “Arm automatic GPS tracking” before your ride.\n4. Start cycling on your Redmi Watch. When Mi Fitness posts “Trainieren...”, GPS recording starts automatically.\n\nAndroid requires the tracker service to be started while the app is visible, so arming it before the ride is necessary. A persistent notification shows when it is armed or recording.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                    }
                    KxButton(text = "Back", onClick = { finish() }, primary = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationAndArm() {
        if (hasLocationPermission()) armAutomaticTracking()
        else locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun armAutomaticTracking() {
        val intent = Intent(this, CyclingTrackerService::class.java).setAction(CyclingTrackerService.ACTION_ARM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        refresh++
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    KxCard(modifier = modifier) {
        KxLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Kx.Cream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
