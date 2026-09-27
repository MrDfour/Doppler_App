package com.sandman.doppler.api

import com.sandman.doppler.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class DopplerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class LocalConnectionException(message: String, cause: Throwable? = null) : DopplerException(message, cause)
    class TimeoutException(message: String, cause: Throwable? = null) : DopplerException(message, cause)
    class ProtocolException(message: String, cause: Throwable? = null) : DopplerException(message, cause)
    class DeviceNotFoundException(message: String) : DopplerException(message)
}

/**
 * Direct Local HTTP Client for communicating with the Sandman Doppler clock over LAN.
 * Requires no external servers, cloud bridges, or Home Assistant instances.
 */
class DopplerLocalApi(
    private var host: String = "192.168.1.142",
    private var port: Int = 3000,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val requestMutex = Mutex() // Serializes writes to prevent simultaneous conflicting commands

    fun updateEndpoint(newHost: String, newPort: Int) {
        this.host = newHost
        this.port = newPort
    }

    private val baseUrl: String
        get() = "http://$host:$port"

    /**
     * Executes an HTTP request with exponential backoff retry.
     */
    private suspend fun executeWithRetry(
        request: Request,
        maxRetries: Int = 2
    ): Response = withContext(Dispatchers.IO) {
        var lastException: IOException? = null
        for (attempt in 0..maxRetries) {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful && response.code >= 500 && attempt < maxRetries) {
                    response.close()
                    delay(300L * (1 shl attempt))
                    continue
                }
                return@withContext response
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(300L * (1 shl attempt))
                }
            }
        }
        throw DopplerException.LocalConnectionException(
            "Unable to reach Doppler clock at $baseUrl. Ensure phone is on the same local Wi-Fi.",
            lastException
        )
    }

    suspend fun getDeviceStatus(deviceId: String = "doppler-radar-01"): DopplerDeviceState {
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId")
            .get()
            .build()

        val response = executeWithRetry(request)
        response.use {
            if (!it.isSuccessful) {
                throw DopplerException.ProtocolException("Failed to fetch device status: HTTP ${it.code}")
            }
            val body = it.body?.string() ?: throw DopplerException.ProtocolException("Empty response body")
            return json.decodeFromString<DopplerDeviceState>(body)
        }
    }

    suspend fun updateSettings(deviceId: String, jsonPatch: String): DopplerDeviceState = requestMutex.withLock {
        val body = jsonPatch.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId")
            .patch(body)
            .build()

        val response = executeWithRetry(request)
        response.use {
            if (!it.isSuccessful) {
                throw DopplerException.ProtocolException("Settings update rejected: HTTP ${it.code}")
            }
            val responseBody = it.body?.string() ?: ""
            return json.decodeFromString<DopplerDeviceState>(responseBody)
        }
    }

    suspend fun setMainDisplayText(deviceId: String, textReq: DisplayTextRequest): Boolean = requestMutex.withLock {
        val payload = json.encodeToString(textReq)
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/set_main_display_text")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun setMiniDisplayNumber(deviceId: String, numReq: MiniDisplayNumberRequest): Boolean = requestMutex.withLock {
        val payload = json.encodeToString(numReq)
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/set_mini_display_number")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun triggerLightBarEffect(deviceId: String, effect: LightBarEffect): Boolean = requestMutex.withLock {
        val payload = json.encodeToString(effect)
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/activate_light_bar_${effect.mode}")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun stopLightBarEffect(deviceId: String): Boolean = requestMutex.withLock {
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/stop_light_bar")
            .post("{}".toRequestBody(jsonMediaType))
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun addAlarm(deviceId: String, alarm: DopplerAlarm): Boolean = requestMutex.withLock {
        val payload = json.encodeToString(alarm)
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/add_alarm")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun updateAlarm(deviceId: String, alarm: DopplerAlarm): Boolean = requestMutex.withLock {
        val payload = json.encodeToString(alarm)
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/update_alarm")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun deleteAlarm(deviceId: String, alarmId: Int): Boolean = requestMutex.withLock {
        val payload = """{"id": $alarmId}"""
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/services/delete_alarm")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }

    suspend fun pressPhysicalButton(deviceId: String, button: String): Boolean = requestMutex.withLock {
        val payload = """{"button": "$button"}"""
        val body = payload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/button-press")
            .post(body)
            .build()

        executeWithRetry(request).use { it.isSuccessful }
    }
}
