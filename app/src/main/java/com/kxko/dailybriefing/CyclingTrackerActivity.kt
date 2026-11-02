package com.kxko.dailybriefing

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.kxko.dailybriefing.ui.theme.Kx
import com.kxko.dailybriefing.ui.theme.KxButton
import com.kxko.dailybriefing.ui.theme.KxCard
import com.kxko.dailybriefing.ui.theme.KxLabel

class CyclingTrackerActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { refresh++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = android.content.IntentFilter(WatchWorkoutNotificationListener.ACTION_WORKOUT_STARTED)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        setContent {
            MaterialTheme {
                val prefs = getSharedPreferences(WatchWorkoutNotificationListener.PREFS, MODE_PRIVATE)
                val hasAccess = remember(refresh) { WatchWorkoutNotificationListener.hasAccess(this) }
                val active = remember(refresh) { prefs.getBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false) }
                val startedAt = remember(refresh) { prefs.getLong(WatchWorkoutNotificationListener.KEY_STARTED_AT, 0L) }
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
                            "Kxko checks notifications from Mi Fitness only. It does not read or save notifications from your other apps.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        KxButton(
                            text = if (hasAccess) "Open notification access settings" else "Enable Mi Fitness detection",
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
                            if (active && startedAt > 0L) "Detected at " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(startedAt))
                            else "Start a cycling workout on your Redmi Watch. If Mi Fitness posts a matching notification, it will appear here.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                        if (active) {
                            Spacer(Modifier.height(14.dp))
                            KxButton(
                                text = "Finish workout",
                                onClick = {
                                    prefs.edit().putBoolean(WatchWorkoutNotificationListener.KEY_ACTIVE, false).apply()
                                    refresh++
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    KxCard {
                        KxLabel("IMPORTANT")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This first integration detects the Mi Fitness workout notification. Xiaomi may not publish a notification when a workout starts on the watch; if that happens, the app cannot detect it through notifications and we'll need another integration.",
                            color = Kx.Muted, fontSize = 13.sp
                        )
                    }
                    KxButton(text = "Back", onClick = { finish() }, primary = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
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
