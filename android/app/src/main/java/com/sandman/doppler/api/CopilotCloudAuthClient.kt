package com.sandman.doppler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class CloudLoginRequest(val email: String, val pass: String)

@Serializable
data class CloudLoginResponse(val accessToken: String, val refreshToken: String? = null)

@Serializable
data class CloudThingItem(
    val dsn: String,
    val name: String? = null,
    val modelNum: String? = null,
    val firmware: String? = null,
    val ipAddie: String? = null
)

@Serializable
data class CloudThingsResponse(val things: List<CloudThingItem> = emptyList())

@Serializable
data class CloudLocalKeyResponse(
    val localKey: String,
    val ipAddie: String? = null
)

/**
 * Result of a successful cloud provisioning flow.
 * Contains all data needed for 100% offline local communication.
 */
data class ProvisionResult(
    val dsn: String,
    val localKey: String,
    val ipAddress: String?,
    val deviceName: String?
)

/**
 * Client responsible for one-time Cloud provisioning (direct from Android phone to PAI Cloud)
 * to retrieve the secure localKey and DSN for 100% offline local communication.
 *
 * After provisioning is complete, all cloud credentials are immediately scrubbed from memory.
 * The app never contacts the cloud again during normal operation.
 */
class CopilotCloudAuthClient(
    private val customClient: OkHttpClient? = null
) {
    private val client = customClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val BASE_AUTH_URL = "https://api.sandmandoppler.bycopilot.com"
        const val CONTROL_URL = "https://control.sandmandoppler.com"
    }

    /**
     * Authenticate with Sandman Doppler PAI Cloud account.
     * Runs on IO dispatcher for network safety.
     */
    suspend fun login(email: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(CloudLoginRequest.serializer(), CloudLoginRequest(email, pass))
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$BASE_AUTH_URL/v4/auth/login")
                .post(reqBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Cloud login failed: HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                val loginRes = json.decodeFromString<CloudLoginResponse>(bodyStr)
                Result.success(loginRes.accessToken)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch list of registered Sandman Doppler devices linked to the user account.
     * Runs on IO dispatcher for network safety.
     */
    suspend fun fetchThings(accessToken: String): Result<List<CloudThingItem>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_AUTH_URL/v4/things")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to fetch things: HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                // Some APIs return list directly or wrapped in { things: [...] }
                val things = try {
                    json.decodeFromString<CloudThingsResponse>(bodyStr).things
                } catch (e: Exception) {
                    json.decodeFromString<List<CloudThingItem>>(bodyStr)
                }
                Result.success(things)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch local cryptographic key and reported IP for a specific DSN.
     * Runs on IO dispatcher for network safety.
     */
    suspend fun fetchLocalKey(dsn: String, accessToken: String): Result<CloudLocalKeyResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$CONTROL_URL/$dsn/localkey")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to fetch localKey for $dsn: HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                val keyRes = json.decodeFromString<CloudLocalKeyResponse>(bodyStr)
                Result.success(keyRes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Full provisioning orchestration: login → fetchThings → fetchLocalKey for a specific DSN.
     *
     * This is the primary entry point for the onboarding wizard. It chains the three
     * cloud API calls and returns a [ProvisionResult] containing everything needed for
     * 100% offline local communication.
     *
     * **Security:** Credentials (email/password) are never stored. The cloud accessToken
     * is used only during this call and discarded immediately. Only the localKey and DSN
     * are retained for local storage.
     *
     * @param email User's Sandman Doppler account email.
     * @param password User's Sandman Doppler account password.
     * @param targetDsn If specified, directly provisions this DSN. If null, provisions the first device found.
     * @return [Result] containing [ProvisionResult] on success, or the first error encountered.
     */
    suspend fun provisionDevice(
        email: String,
        password: String,
        targetDsn: String? = null
    ): Result<ProvisionResult> {
        var accessToken: String? = null
        try {
            // Step 1: Authenticate
            val loginResult = login(email, password)
            if (loginResult.isFailure) {
                return Result.failure(loginResult.exceptionOrNull()
                    ?: IOException("Cloud login failed"))
            }
            accessToken = loginResult.getOrNull()!!

            // Step 2: Fetch device list
            val thingsResult = fetchThings(accessToken)
            if (thingsResult.isFailure) {
                return Result.failure(thingsResult.exceptionOrNull()
                    ?: IOException("Failed to fetch device list"))
            }
            val things = thingsResult.getOrNull()!!
            if (things.isEmpty()) {
                return Result.failure(IOException("No Sandman Doppler devices found on this account"))
            }

            // Select target device
            val selectedThing = if (targetDsn != null) {
                things.find { it.dsn == targetDsn }
                    ?: return Result.failure(IOException("Device $targetDsn not found on this account"))
            } else {
                things.first()
            }

            // Step 3: Fetch local cryptographic key
            val keyResult = fetchLocalKey(selectedThing.dsn, accessToken)
            if (keyResult.isFailure) {
                return Result.failure(keyResult.exceptionOrNull()
                    ?: IOException("Failed to fetch localKey for ${selectedThing.dsn}"))
            }
            val keyData = keyResult.getOrNull()!!

            return Result.success(
                ProvisionResult(
                    dsn = selectedThing.dsn,
                    localKey = keyData.localKey,
                    ipAddress = keyData.ipAddie ?: selectedThing.ipAddie,
                    deviceName = selectedThing.name
                )
            )
        } finally {
            // Security: Scrub cloud credentials from memory
            // JVM strings are immutable, but we null the reference to allow GC
            @Suppress("UNUSED_VALUE")
            accessToken = null
        }
    }
}
