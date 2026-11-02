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
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                startGpsTracking()
            }
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
                val distance = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_DISTANCE, 0f) }
                val speed = remember(refresh) { prefs.getFloat(CyclingTrackerService.KEY_SPEED, 0f) }
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
                            "Kxko checks Mi Fitness notifications for the “Trainieren...” workout signal. Other apps' notifications are not read.",
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
                        Text(if (active) "Watch workout detected" else "Waiting for your watch", color = Kx.Cream, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (active && startedAt > 0L) "Detected at " + java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(startedAt))
                            else "Start a cycling workout on your Redmi Watch. Kxko will detect Mi Fitness's active-workout notification.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                        if (active && !tracking) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (hasLocation) "Workout detected. GPS tracking is waiting to start." else "Workout detected. Allow location once to enable automatic GPS tracking.",
                                color = Kx.Warn, fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            KxButton(
                                text = if (hasLocation) "Start GPS tracking" else "Allow location & start tracking",
                                onClick = { requestLocationAndStart() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (tracking) {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Metric("DISTANCE", String.format(Locale.getDefault(), "%.2f km", distance / 1000f), Modifier.weight(1f))
                                Metric("SPEED", String.format(Locale.getDefault(), "%.1f km/h", speed), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("GPS tracking is running in the background.", color = Kx.Good, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            KxButton(
                                text = "Finish workout",
                                onClick = {
                                    startService(Intent(this@CyclingTrackerActivity, CyclingTrackerService::class.java).setAction(CyclingTrackerService.ACTION_STOP))
                                    prefs.edit().putBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false)
                                        .putBoolean(WatchWorkoutNotificationListener.KEY_PENDING_START, false).apply()
                                    refresh++
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (active) {
                            Spacer(Modifier.height(10.dp))
                            KxButton(
                                text = "Reset detected workout",
                                onClick = {
                                    prefs.edit().putBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false)
                                        .putBoolean(WatchWorkoutNotificationListener.KEY_PENDING_START, false).apply()
                                    refresh++
                                },
                                primary = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    KxCard {
                        KxLabel("LOCATION PERMISSION")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (hasLocation) "Location permission is enabled. The tracker uses GPS while a workout is active."
                            else "Location permission is needed for distance and speed. Grant it before your ride so the watch notification can start tracking automatically.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                        if (!hasLocation) {
                            Spacer(Modifier.height(12.dp))
                            KxButton(text = "Grant location permission", onClick = { requestLocationAndStart() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    KxCard {
                        KxLabel("HOW IT WORKS")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "When Mi Fitness posts “Trainieren...”, Kxko starts a foreground GPS tracker (if location permission has already been granted). It records distance and current speed on this phone. Keep notification access and location permission enabled.",
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

    private fun requestLocationAndStart() {
        if (hasLocationPermission()) {
            startGpsTracking()
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun startGpsTracking() {
        val intent = Intent(this, CyclingTrackerService::class.java).setAction(CyclingTrackerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        getSharedPreferences(WatchWorkoutNotificationListener.PREFS, MODE_PRIVATE)
            .edit().putBoolean(WatchWorkoutNotificationListener.KEY_PENDING_START, false).apply()
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
