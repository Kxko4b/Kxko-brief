package com.kxko.dailybriefing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Permission result
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseMessaging.getInstance()
            .subscribeToTopic("Kxko-Daily-Briefing")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    println("Subscribed to Kxko-Daily-Briefing")
                } else {
                    println("Topic subscription failed: ${task.exception}")
                }
            }
        // Android 13+ notification permission
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        // Get FCM token and register the device
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->

                if (task.isSuccessful) {

                    val token = task.result

                    println("FCM TOKEN: $token")

                    registerDevice(token)

                } else {

                    println(
                        "FCM TOKEN ERROR: ${task.exception}"
                    )
                }
            }

        setContent {
            Greeting()
        }
    }

    private fun registerDevice(token: String) {

        thread {

            try {

                val url = URL(
                    "https://ggewdpazpeoielbqrcgm.supabase.co/functions/v1/register-device"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.doOutput = true

                val body =
                    """{"token":"$token"}"""

                connection.outputStream.use { output ->
                    output.write(
                        body.toByteArray()
                    )
                }

                val responseCode =
                    connection.responseCode

                println(
                    "REGISTER DEVICE RESPONSE: $responseCode"
                )

                connection.disconnect()

            } catch (e: Exception) {

                println(
                    "REGISTER DEVICE ERROR: ${e.message}"
                )
            }
        }
    }
}

@Composable
fun Greeting() {
    Text("Kxko Daily Briefing")
}