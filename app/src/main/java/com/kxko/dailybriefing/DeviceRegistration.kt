package com.kxko.dailybriefing

import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Tells the backend about this phone's push token (used at start-up and whenever Firebase issues a new token). */
object DeviceRegistration {
    private const val ENDPOINT = "https://ggewdpazpeoielbqrcgm.supabase.co/functions/v1/register-device"

    fun register(token: String) {
        thread {
            try {
                val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                val escaped = token.replace("\\", "\\\\").replace("\"", "\\\"")
                connection.outputStream.use { it.write("{\"token\":\"$escaped\"}".toByteArray(Charsets.UTF_8)) }
                println("REGISTER DEVICE RESPONSE: ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                println("REGISTER DEVICE ERROR: ${e.message}")
            }
        }
    }
}
