package com.kxko.dailybriefing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supabase session tokens encrypted with an AES-256 key held by Android Keystore. */
internal class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val encrypted = context.getSharedPreferences("study_quiz_session_secure", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("study_quiz_auth", Context.MODE_PRIVATE)

    init { migrateLegacySession() }

    @Synchronized
    fun save(accessToken: String, refreshToken: String) {
        encrypted.edit()
            .putString(ACCESS, encrypt(accessToken))
            .putString(REFRESH, encrypt(refreshToken))
            .putBoolean(LOGOUT_PENDING, false)
            .apply()
    }

    @Synchronized fun accessToken(): String? = read(ACCESS)
    @Synchronized fun refreshToken(): String? = read(REFRESH)

    @Synchronized
    fun clear() {
        accessToken()?.let { PersonalPushRegistration.unregister(appContext, it) }
        encrypted.edit().clear().putBoolean(LOGOUT_PENDING, true).apply()
        legacy.edit().clear().apply()
    }

    /** Called when the hosted portal itself signs out; don't echo that logout back to the portal. */
    @Synchronized
    fun clearFromWeb() {
        accessToken()?.let { PersonalPushRegistration.unregister(appContext, it) }
        encrypted.edit().clear().putBoolean(LOGOUT_PENDING, false).apply()
        legacy.edit().clear().apply()
    }

    @Synchronized
    fun consumeLogoutPending(): Boolean {
        val pending = encrypted.getBoolean(LOGOUT_PENDING, false)
        if (pending) encrypted.edit().putBoolean(LOGOUT_PENDING, false).apply()
        return pending
    }

    private fun read(name: String): String? {
        val value = encrypted.getString(name, null) ?: return null
        return try { decrypt(value) } catch (_: Exception) {
            clear()
            null
        }
    }

    private fun migrateLegacySession() {
        val access = legacy.getString(ACCESS, null)
        val refresh = legacy.getString(REFRESH, null)
        if (!access.isNullOrBlank() && !refresh.isNullOrBlank() &&
            encrypted.getString(ACCESS, null) == null &&
            encrypted.getString(REFRESH, null) == null
        ) {
            try { save(access, refresh) } catch (_: Exception) { return }
        }
        if (encrypted.getString(ACCESS, null) != null &&
            encrypted.getString(REFRESH, null) != null
        ) legacy.edit().clear().apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "Invalid encrypted session." }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.kxko.dailybriefing.supabase-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val LOGOUT_PENDING = "logout_pending"
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
    }
}
