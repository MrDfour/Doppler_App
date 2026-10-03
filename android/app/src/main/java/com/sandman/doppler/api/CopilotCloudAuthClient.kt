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
data class AuthenticationDetails(
    val applicationId: String,
    val email: String,
    val password: String
)

@Serializable
data class DeviceTimezone(
    val currentTimeInClientInMilliseconds: Long,
    val offsetFromUTCInMilliseconds: Long,
    val timeZoneId: String
)

@Serializable
data class DeviceDetails(
    val applicationVersion: String,
    val deviceId: String,
    val deviceModel: String,
    val deviceType: String,
    val osType: String,
    val osVersion: String,
    val timezone: DeviceTimezone
)

@Serializable
data class CloudLoginRequest(
    val authenticationDetails: AuthenticationDetails,
    val deviceDetails: DeviceDetails
)

@Serializable
data class CloudLoginResponse(val accessToken: String, val refreshToken: String? = null)

@Serializable
data class CloudThingItem(
    val dsn: String,
    val name: String? = null,
    val modelNum: String? = null,
    val firmware: String? = null,
    val ipAddie: String? = null,
    val lastSeen: Long? = null
)

// Shape of a single item in the real /v4/things response:
// { "id": "...", "info": { "name", "firmware", "physicalId", "model" }, "status": ... }
@Serializable
data class CloudThingInfo(
    val name: String? = null,
    val firmware: String? = null,
    val physicalId: String? = null,
    val model: String? = null
)

@Serializable
data class CloudThingStatus(
    val lastSeen: Long? = null
)

@Serializable
data class CloudThingApiItem(
    val id: String? = null,
    val info: CloudThingInfo? = null,
    val status: CloudThingStatus? = null,
    // Older/alternate shapes may surface these fields directly
    val dsn: String? = null,
    val name: String? = null,
    val modelNum: String? = null,
    val firmware: String? = null,
    val ipAddie: String? = null
)

@Serializable
data class CloudThingsResponse(val things: List<CloudThingApiItem> = emptyList())

private fun CloudThingApiItem.toThingItem(): CloudThingItem? {
    val resolvedDsn = dsn ?: info?.physicalId ?: return null
    return CloudThingItem(
        dsn = resolvedDsn,
        name = name ?: info?.name,
        modelNum = modelNum ?: info?.model,
        firmware = firmware ?: info?.firmware,
        ipAddie = ipAddie,
        lastSeen = status?.lastSeen
    )
}

@Serializable
data class CloudLocalKeyResponse(
    @kotlinx.serialization.SerialName("localkey") val localKey: String,
    val ipAddie: String? = null,
    val port: Int? = null
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
    suspend fun login(email: String, pass: String): Result<CloudLoginResponse> = withContext(Dispatchers.IO) {
        try {
            val tz = java.util.TimeZone.getDefault()
            val reqBody = json.encodeToString(
                CloudLoginRequest.serializer(),
                CloudLoginRequest(
                    authenticationDetails = AuthenticationDetails(
                        applicationId = "SANDMANDOPPLER",
                        email = email,
                        password = pass
                    ),
                    deviceDetails = DeviceDetails(
                        applicationVersion = "154",
                        deviceId = android.os.Build.ID,
                        deviceModel = android.os.Build.MODEL,
                        deviceType = "PHONE",
                        osType = "ANDROID",
                        osVersion = android.os.Build.VERSION.RELEASE,
                        timezone = DeviceTimezone(
                            currentTimeInClientInMilliseconds = System.currentTimeMillis(),
                            offsetFromUTCInMilliseconds = tz.getOffset(System.currentTimeMillis()).toLong(),
                            timeZoneId = tz.id
                        )
                    )
                )
            ).toRequestBody(jsonMediaType)

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
                Result.success(loginRes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(refreshToken: String): Result<CloudLoginResponse> = withContext(Dispatchers.IO) {
        try {
            val body = """{"refreshToken":"$refreshToken"}"""
                .toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$BASE_AUTH_URL/v4/auth/refresh")
                .put(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Token refresh failed: HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                Result.success(json.decodeFromString<CloudLoginResponse>(bodyStr))
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
                // Real API returns {"things": [...]}; some variants return a bare array
                val rawItems = try {
                    json.decodeFromString<CloudThingsResponse>(bodyStr).things
                } catch (e: Exception) {
                    json.decodeFromString<List<CloudThingApiItem>>(bodyStr)
                }
                Result.success(rawItems.mapNotNull { it.toThingItem() })
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
                .addHeader("Accept", "application/json")
                .get()
                .build()

            var lastError: IOException? = null
            for (attempt in 1..2) {
                var retryOn408 = false
                try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code == 408 && attempt < 2 -> {
                                retryOn408 = true
                                lastError = IOException("Failed to fetch localKey for $dsn: HTTP 408")
                            }
                            !response.isSuccessful -> {
                                return@withContext Result.failure(
                                    IOException("Failed to fetch localKey for $dsn: HTTP ${response.code}")
                                )
                            }
                            else -> {
                                val bodyStr = response.body?.string()
                                    ?: return@withContext Result.failure(IOException("Empty response"))
                                return@withContext Result.success(
                                    json.decodeFromString<CloudLocalKeyResponse>(bodyStr)
                                )
                            }
                        }
                    }
                    if (retryOn408) continue
                } catch (e: IOException) {
                    lastError = e
                    if (attempt < 2) continue
                }
            }
            Result.failure(lastError ?: IOException("Failed to fetch localKey for $dsn"))
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
            accessToken = loginResult.getOrNull()!!.accessToken

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
