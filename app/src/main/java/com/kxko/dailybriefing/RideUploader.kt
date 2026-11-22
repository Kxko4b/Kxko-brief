package com.kxko.dailybriefing

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Sends a finished cycling ride to Supabase (table cycling_rides) through the save-ride function. */
object RideUploader {
    private const val ENDPOINT = "https://ggewdpazpeoielbqrcgm.supabase.co/functions/v1/save-ride"
    // Must match APP_KEY in the save-ride edge function.
    private const val APP_KEY = "kxko-7f3a9c2e5b8d41f6a0e9d2c7b4f1a835"

    fun upload(ride: JSONObject) {
        thread {
            try {
                val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("x-kxko-key", APP_KEY)
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.doOutput = true
                connection.outputStream.use { it.write(ride.toString().toByteArray(Charsets.UTF_8)) }
                println("SAVE RIDE RESPONSE: ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                println("SAVE RIDE ERROR: ${e.message}")
            }
        }
    }
}
