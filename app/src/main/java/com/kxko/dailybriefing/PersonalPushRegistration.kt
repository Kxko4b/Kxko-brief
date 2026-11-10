package com.kxko.dailybriefing

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Registers a device only against the currently signed-in personal account.
 * This endpoint/table is separate from the legacy administrator notification path.
 */
internal object PersonalPushRegistration {
    private const val ENDPOINT =
        "https://ggewdpazpeoielbqrcgm.supabase.co/functions/v1/register-personal-device"

    fun sync(context: Context, accessToken: String) {
        if (accessToken.isBlank()) return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(context, token, accessToken) }
            .addOnFailureListener { error -> println("PERSONAL PUSH TOKEN ERROR: ${error.message}") }
    }

    fun register(context: Context, token: String, accessToken: String) {
        if (token.isBlank() || accessToken.isBlank()) return
        thread(name = "personal-push-registration") {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                }
                val escaped = token.replace("\\", "\\\\").replace("\"", "\\\"")
                connection.outputStream.use {
                    it.write("{\"token\":\"$escaped\",\"platform\":\"android\"}".toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    println("PERSONAL PUSH REGISTRATION FAILED: HTTP $status")
                }
            } catch (error: Exception) {
                println("PERSONAL PUSH REGISTRATION ERROR: ${error.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
