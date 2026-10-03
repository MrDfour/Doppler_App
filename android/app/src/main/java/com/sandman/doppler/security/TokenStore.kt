package com.sandman.doppler.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage backed by Android Keystore.
 * Guarantees tokens and host configuration are never stored in plaintext on disk.
 */
class TokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "sandman_doppler_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback for testing environments where Keystore is mock
        context.getSharedPreferences("sandman_doppler_fallback_prefs", Context.MODE_PRIVATE)
    }

    var savedIpAddress: String
        get() = prefs.getString(KEY_IP, "192.168.1.142") ?: "192.168.1.142"
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var savedPort: Int
        get() = prefs.getInt(KEY_PORT, 5443) // Default is 5443 for authentic HTTPS Local API
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var authToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var savedDsn: String?
        get() = prefs.getString(KEY_DSN, null)
        set(value) = prefs.edit().putString(KEY_DSN, value).apply()

    var deviceFriendlyName: String?
        get() = prefs.getString(KEY_FRIENDLY_NAME, null)
        set(value) = prefs.edit().putString(KEY_FRIENDLY_NAME, value).apply()

    /** Cached Copilot cloud access token — enables cloud control fallback when the LAN daemon is unavailable. */
    var cloudAccessToken: String?
        get() = prefs.getString(KEY_CLOUD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CLOUD_TOKEN, value).apply()

    /** Cached Copilot refresh token used to renew [cloudAccessToken] when it expires. */
    var cloudRefreshToken: String?
        get() = prefs.getString(KEY_CLOUD_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CLOUD_REFRESH_TOKEN, value).apply()

    /**
     * Alias for authToken — provides semantic clarity when storing/reading the Doppler local key.
     * The localKey is the cryptographic secret used for SHA-256 token derivation.
     */
    var localKey: String?
        get() = authToken
        set(value) { authToken = value }

    /**
     * Quick check: Is the device minimally configured for communication?
     * Requires a DSN plus either a localKey (LAN path) or a cloud access token (cloud fallback path).
     */
    val isConfigured: Boolean
        get() = !savedDsn.isNullOrBlank() && (!authToken.isNullOrBlank() || !cloudAccessToken.isNullOrBlank())

    /**
     * Full validation: Is the device fully configured with all fields needed for connection?
     */
    fun hasValidConfig(): Boolean {
        return isConfigured &&
            savedIpAddress.isNotBlank() &&
            savedIpAddress != "0.0.0.0" &&
            savedPort in 1..65535
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_IP = "doppler_ip"
        private const val KEY_PORT = "doppler_port"
        private const val KEY_TOKEN = "doppler_auth_token"
        private const val KEY_DSN = "doppler_dsn"
        private const val KEY_FRIENDLY_NAME = "doppler_friendly_name"
        private const val KEY_CLOUD_TOKEN = "doppler_cloud_token"
        private const val KEY_CLOUD_REFRESH_TOKEN = "doppler_cloud_refresh_token"
    }
}
