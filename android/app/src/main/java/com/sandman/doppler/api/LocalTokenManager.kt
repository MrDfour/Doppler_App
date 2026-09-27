package com.sandman.doppler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

@Serializable
internal data class NonceResponse(val nonce: String)

/**
 * Manages Sandman Doppler local authentication tokens according to the official protocol:
 *
 * 1. Fetches an ephemeral session nonce from `https://<ip>:5443/<dsn>/nonce` (unauthenticated).
 * 2. Computes `SHA-256(nonce + localKey)`.
 * 3. Base64 encodes the SHA-256 hash.
 * 4. Yields Bearer token: `"<nonce>|<base64Hash>"`.
 *
 * Automatically caches the token and provides atomic refresh on expiration or HTTP 410 (Gone).
 */
class LocalTokenManager(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var tokenTimestampMs: Long = 0L

    // Nonce is valid on device for up to 48 hours; refresh proactively after 20 hours
    private val tokenValidityDurationMs = TimeUnit.HOURS.toMillis(20)

    /**
     * Pure function to calculate Doppler Local Bearer Token given a nonce and localKey.
     */
    fun calculateLocalToken(nonce: String, localKey: String): String {
        val payload = nonce + localKey
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        val base64Hash = Base64.getEncoder().encodeToString(digest)
        return "$nonce|$base64Hash"
    }

    /**
     * Invalidate cached token (e.g. upon receiving HTTP 410 Gone or HTTP 401).
     */
    fun invalidateToken() {
        cachedToken = null
        tokenTimestampMs = 0L
    }

    /**
     * Retrieves a valid local Bearer authorization header value.
     */
    suspend fun getBearerHeader(
        baseUrl: String,
        dsn: String,
        localKey: String,
        forceRefresh: Boolean = false
    ): String = mutex.withLock {
        val now = System.currentTimeMillis()
        val token = cachedToken

        if (!forceRefresh && token != null && (now - tokenTimestampMs) < tokenValidityDurationMs) {
            return@withLock "Bearer $token"
        }

        val freshNonce = fetchNonceFromDevice(baseUrl, dsn)
        val freshToken = calculateLocalToken(freshNonce, localKey)

        cachedToken = freshToken
        tokenTimestampMs = now
        return@withLock "Bearer $freshToken"
    }

    /**
     * Fetches fresh nonce directly from clock.
     */
    private suspend fun fetchNonceFromDevice(baseUrl: String, dsn: String): String = withContext(Dispatchers.IO) {
        val url = "$baseUrl/$dsn/nonce"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DopplerException.ProtocolException("Failed to retrieve nonce from Doppler at $url: HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw DopplerException.ProtocolException("Empty response body when fetching nonce")
                val parsed = json.decodeFromString<NonceResponse>(body)
                return@withContext parsed.nonce
            }
        } catch (e: IOException) {
            throw DopplerException.LocalConnectionException(
                "Failed to connect to Doppler at $url to fetch nonce. Verify device is powered and connected to Wi-Fi.",
                e
            )
        }
    }
}
