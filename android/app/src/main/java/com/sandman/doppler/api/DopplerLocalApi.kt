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
    /**
     * The clock (or the cloud control plane) answered, but rejected the request.
     *
     * [httpCode] carries the status verbatim so callers - notably the override
     * support probe - can tell "this route does not exist on this firmware"
     * (404/405/501) apart from "the route exists but refused the payload"
     * (400/422) without parsing the message. Null when no HTTP response arrived.
     */
    class ProtocolException(
        message: String,
        cause: Throwable? = null,
        val httpCode: Int? = null
    ) : DopplerException(message, cause)
    class DeviceNotFoundException(message: String) : DopplerException(message)
    class AuthenticationException(message: String) : DopplerException(message)
}

/**
 * Direct Local HTTPS REST Client for communicating with the Sandman Doppler clock hardware.
 *
 * Implements the authentic local protocol (oatpp HTTPS daemon on port 5443) with:
 * - Local SHA-256 Bearer Token derivation via `LocalTokenManager`.
 * - Mutual exclusion lock (`requestMutex`) to serialize writes and protect the single-threaded daemon.
 * - Automatic HTTP 410 (Gone) / 401 token refresh retry.
 * - Granular typed methods mapping all Doppler hardware endpoints.
 */
open class DopplerLocalApi(
    var host: String = "192.168.1.100",
    var port: Int = 5443,
    var dsn: String = "Doppler-00000000",
    var localKey: String = "",
    customClient: OkHttpClient? = null,
    // Default true: real Doppler clocks always serve HTTPS (oatpp). Tests may disable.
    private val useTls: Boolean = true
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = customClient ?: run {
        val baseBuilder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
        LanTrustManager.configureLanTls(baseBuilder).build()
    }

    private val tokenManager = LocalTokenManager(client, json)
    private val requestMutex = Mutex()

    fun updateCredentials(newHost: String, newPort: Int, newDsn: String, newLocalKey: String) {
        this.host = newHost
        this.port = newPort
        this.dsn = newDsn
        this.localKey = newLocalKey
        tokenManager.invalidateToken()
    }

    val baseUrl: String
        get() {
            val scheme = if (useTls) "https" else "http"
            return "$scheme://$host:$port"
        }

    /**
     * Executes an authenticated request against `baseUrl/$dsn/$path`.
     * Automatically handles token derivation and refreshes token on HTTP 410 Gone / 401.
     */
    open suspend fun executeAuthenticatedRequest(
        method: String,
        path: String,
        jsonBody: String? = null,
        retryOn410: Boolean = true
    ): String = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            val authHeader = if (localKey.isNotBlank()) {
                tokenManager.getBearerHeader(baseUrl, dsn, localKey)
            } else null

            val url = "$baseUrl/$dsn/$path"
            val requestBuilder = Request.Builder().url(url)

            authHeader?.let { requestBuilder.header("Authorization", it) }

            val body = jsonBody?.toRequestBody(jsonMediaType)
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post(body ?: "".toRequestBody(jsonMediaType))
                "PUT" -> requestBuilder.put(body ?: "".toRequestBody(jsonMediaType))
                "DELETE" -> requestBuilder.delete(body)
                else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
            }

            val response = try {
                client.newCall(requestBuilder.build()).execute()
            } catch (e: IOException) {
                throw DopplerException.LocalConnectionException(
                    "Failed to communicate with Doppler at $url. Check Wi-Fi connection.",
                    e
                )
            }

            response.use { resp ->
                if ((resp.code == 410 || resp.code == 401) && retryOn410 && localKey.isNotBlank()) {
                    // Nonce expired; invalidate and re-execute once
                    tokenManager.invalidateToken()
                    val freshAuthHeader = tokenManager.getBearerHeader(baseUrl, dsn, localKey, forceRefresh = true)
                    val retryRequest = requestBuilder
                        .header("Authorization", freshAuthHeader)
                        .build()

                    val retryResponse = client.newCall(retryRequest).execute()
                    retryResponse.use { retryResp ->
                        if (!retryResp.isSuccessful) {
                            throw DopplerException.ProtocolException(
                                "Request failed after nonce refresh: HTTP ${retryResp.code}",
                                httpCode = retryResp.code
                            )
                        }
                        return@withContext retryResp.body?.string() ?: ""
                    }
                }

                if (!resp.isSuccessful) {
                    throw DopplerException.ProtocolException(
                        "Doppler returned HTTP ${resp.code} for $path",
                        httpCode = resp.code
                    )
                }

                return@withContext resp.body?.string() ?: ""
            }
        }
    }

    // ==========================================
    // Basic & Hardware Status Endpoints
    // ==========================================

    open suspend fun getDeviceInfo(): DopplerDeviceInfo {
        val raw = executeAuthenticatedRequest("GET", "device")
        return json.decodeFromString<DopplerDeviceInfo>(raw)
    }

    open suspend fun getWifiStatus(): DopplerWifiStatus {
        val raw = executeAuthenticatedRequest("GET", "hardware/wifi-status")
        return json.decodeFromString<DopplerWifiStatus>(raw)
    }

    // ==========================================
    // Time & Display Mode Endpoints
    // ==========================================

    open suspend fun getUtcTime(): DopplerUtcTime {
        val raw = executeAuthenticatedRequest("GET", "doptime/utc-time")
        return json.decodeFromString<DopplerUtcTime>(raw)
    }

    open suspend fun getTimeMode(): DopplerTimeMode {
        val raw = executeAuthenticatedRequest("GET", "software/time-mode")
        return json.decodeFromString<DopplerTimeMode>(raw)
    }

    open suspend fun setTimeMode(mode: Int): DopplerTimeMode {
        val body = json.encodeToString(DopplerTimeMode(mode))
        val raw = executeAuthenticatedRequest("PUT", "software/time-mode", body)
        return json.decodeFromString<DopplerTimeMode>(raw)
    }

    open suspend fun getTimezone(): DopplerTimezone {
        val raw = executeAuthenticatedRequest("GET", "doptime/timezone")
        return json.decodeFromString<DopplerTimezone>(raw)
    }

    open suspend fun setTimezone(tz: String): DopplerTimezone {
        val body = json.encodeToString(DopplerTimezone(tz))
        val raw = executeAuthenticatedRequest("PUT", "doptime/timezone", body)
        return json.decodeFromString<DopplerTimezone>(raw)
    }

    open suspend fun getTimeOffset(): DopplerTimeOffset {
        val raw = executeAuthenticatedRequest("GET", "doptime/offset")
        return json.decodeFromString<DopplerTimeOffset>(raw)
    }

    open suspend fun setTimeOffset(offset: Int): DopplerTimeOffset {
        val body = json.encodeToString(DopplerTimeOffset(offset))
        val raw = executeAuthenticatedRequest("PUT", "doptime/offset", body)
        return json.decodeFromString<DopplerTimeOffset>(raw)
    }

    open suspend fun getUseColon(): DopplerUseColon {
        val raw = executeAuthenticatedRequest("GET", "software/use-colon")
        return json.decodeFromString<DopplerUseColon>(raw)
    }

    open suspend fun setUseColon(on: Boolean): DopplerUseColon {
        val body = json.encodeToString(DopplerUseColon(on))
        val raw = executeAuthenticatedRequest("PUT", "software/use-colon", body)
        return json.decodeFromString<DopplerUseColon>(raw)
    }

    open suspend fun getColonBlink(): DopplerColonBlink {
        val raw = executeAuthenticatedRequest("GET", "software/colon-blink")
        return json.decodeFromString<DopplerColonBlink>(raw)
    }

    open suspend fun setColonBlink(blink: Boolean): DopplerColonBlink {
        val body = json.encodeToString(DopplerColonBlink(blink))
        val raw = executeAuthenticatedRequest("PUT", "software/colon-blink", body)
        return json.decodeFromString<DopplerColonBlink>(raw)
    }

    open suspend fun getUseLeadingZero(): DopplerUseLeadingZero {
        val raw = executeAuthenticatedRequest("GET", "software/use-leading-zero")
        return json.decodeFromString<DopplerUseLeadingZero>(raw)
    }

    open suspend fun setUseLeadingZero(use: Boolean): DopplerUseLeadingZero {
        val body = json.encodeToString(DopplerUseLeadingZero(use))
        val raw = executeAuthenticatedRequest("PUT", "software/use-leading-zero", body)
        return json.decodeFromString<DopplerUseLeadingZero>(raw)
    }

    open suspend fun getFadeTime(): DopplerFadeTime {
        val raw = executeAuthenticatedRequest("GET", "software/use-fade-time")
        return json.decodeFromString<DopplerFadeTime>(raw)
    }

    open suspend fun setFadeTime(fade: Boolean): DopplerFadeTime {
        val body = json.encodeToString(DopplerFadeTime(fade))
        val raw = executeAuthenticatedRequest("PUT", "software/use-fade-time", body)
        return json.decodeFromString<DopplerFadeTime>(raw)
    }

    open suspend fun getDisplaySeconds(): DopplerDisplaySeconds {
        val raw = executeAuthenticatedRequest("GET", "software/display-seconds")
        return json.decodeFromString<DopplerDisplaySeconds>(raw)
    }

    open suspend fun setDisplaySeconds(sec: Boolean): DopplerDisplaySeconds {
        val body = json.encodeToString(DopplerDisplaySeconds(sec))
        val raw = executeAuthenticatedRequest("PUT", "software/display-seconds", body)
        return json.decodeFromString<DopplerDisplaySeconds>(raw)
    }

    // ==========================================
    // Audio & Equalizer Endpoints
    // ==========================================

    open suspend fun getVolume(): DopplerVolume {
        val raw = executeAuthenticatedRequest("GET", "hardware/volume")
        return json.decodeFromString<DopplerVolume>(raw)
    }

    open suspend fun setVolume(volume: Int): DopplerVolume {
        val body = json.encodeToString(DopplerVolume(volume.coerceIn(0, 100)))
        val raw = executeAuthenticatedRequest("PUT", "hardware/volume", body)
        return json.decodeFromString<DopplerVolume>(raw)
    }

    open suspend fun getSoundPreset(): DopplerSoundPreset {
        val raw = executeAuthenticatedRequest("GET", "hardware/sound-preset")
        return json.decodeFromString<DopplerSoundPreset>(raw)
    }

    open suspend fun setSoundPreset(preset: String): DopplerSoundPreset {
        val body = json.encodeToString(DopplerSoundPreset(preset))
        val raw = executeAuthenticatedRequest("PUT", "hardware/sound-preset", body)
        return json.decodeFromString<DopplerSoundPreset>(raw)
    }

    open suspend fun getAscendingVolume(): DopplerAscending {
        val raw = executeAuthenticatedRequest("GET", "alexa/ascending")
        return json.decodeFromString<DopplerAscending>(raw)
    }

    open suspend fun setAscendingVolume(asc: Boolean): DopplerAscending {
        val body = json.encodeToString(DopplerAscending(asc))
        val raw = executeAuthenticatedRequest("PUT", "alexa/ascending", body)
        return json.decodeFromString<DopplerAscending>(raw)
    }

    // ==========================================
    // Alarms Management Endpoints
    // ==========================================

    open suspend fun getAlarms(): List<DopplerAlarm> {
        val raw = executeAuthenticatedRequest("GET", "alarms")
        return try {
            json.decodeFromString<DopplerAlarmsResponse>(raw).alarms
        } catch (e: Exception) {
            json.decodeFromString<List<DopplerAlarm>>(raw)
        }
    }

    open suspend fun createOrUpdateAlarm(alarm: DopplerAlarm): String {
        val body = json.encodeToString(alarm)
        return executeAuthenticatedRequest("POST", "alarms", body)
    }

    open suspend fun deleteAlarm(alarmId: Int): String {
        return executeAuthenticatedRequest("DELETE", "alarms/$alarmId")
    }

    open suspend fun getAlarmSounds(): List<String> {
        val raw = executeAuthenticatedRequest("GET", "alarms/sounds")
        return try {
            json.decodeFromString<DopplerAlarmSoundsResponse>(raw).sounds
        } catch (e: Exception) {
            json.decodeFromString<List<String>>(raw)
        }
    }

    open suspend fun playAlarmSound(sound: String): String {
        val body = json.encodeToString(DopplerPlaySoundRequest(sound))
        return executeAuthenticatedRequest("POST", "alarms/sounds/play", body)
    }

    // ==========================================
    // Brightness, Colors & Auto-Dimming
    // ==========================================

    open suspend fun getLightSensor(): DopplerLightSensor {
        val raw = executeAuthenticatedRequest("GET", "hardware/light-sensor")
        return json.decodeFromString<DopplerLightSensor>(raw)
    }

    open suspend fun getDayMode(): DopplerDayMode {
        val raw = executeAuthenticatedRequest("GET", "hardware/day-mode")
        return json.decodeFromString<DopplerDayMode>(raw)
    }

    open suspend fun getHighToLowTransition(): DopplerHighToLowTransition {
        val raw = executeAuthenticatedRequest("GET", "hardware/high-to-low-transition")
        return json.decodeFromString<DopplerHighToLowTransition>(raw)
    }

    open suspend fun setHighToLowTransition(threshold: Int): DopplerHighToLowTransition {
        val body = json.encodeToString(DopplerHighToLowTransition(threshold))
        val raw = executeAuthenticatedRequest("PUT", "hardware/high-to-low-transition", body)
        return json.decodeFromString<DopplerHighToLowTransition>(raw)
    }

    open suspend fun getLowToHighTransition(): DopplerLowToHighTransition {
        val raw = executeAuthenticatedRequest("GET", "hardware/low-to-high-transition")
        return json.decodeFromString<DopplerLowToHighTransition>(raw)
    }

    open suspend fun setLowToHighTransition(threshold: Int): DopplerLowToHighTransition {
        val body = json.encodeToString(DopplerLowToHighTransition(threshold))
        val raw = executeAuthenticatedRequest("PUT", "hardware/low-to-high-transition", body)
        return json.decodeFromString<DopplerLowToHighTransition>(raw)
    }

    open suspend fun getHighDisplayBrightness(): DopplerBrightness {
        val raw = executeAuthenticatedRequest("GET", "hardware/high-display-brightness")
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun setHighDisplayBrightness(brightness: Int): DopplerBrightness {
        val body = json.encodeToString(DopplerBrightness(brightness.coerceIn(0, 100)))
        val raw = executeAuthenticatedRequest("PUT", "hardware/high-display-brightness", body)
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun getLowDisplayBrightness(): DopplerBrightness {
        val raw = executeAuthenticatedRequest("GET", "hardware/low-display-brightness")
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun setLowDisplayBrightness(brightness: Int): DopplerBrightness {
        val body = json.encodeToString(DopplerBrightness(brightness.coerceIn(0, 100)))
        val raw = executeAuthenticatedRequest("PUT", "hardware/low-display-brightness", body)
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun getHighButtonBrightness(): DopplerBrightness {
        val raw = executeAuthenticatedRequest("GET", "hardware/high-button-brightness")
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun setHighButtonBrightness(brightness: Int): DopplerBrightness {
        val body = json.encodeToString(DopplerBrightness(brightness.coerceIn(0, 100)))
        val raw = executeAuthenticatedRequest("PUT", "hardware/high-button-brightness", body)
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun getLowButtonBrightness(): DopplerBrightness {
        val raw = executeAuthenticatedRequest("GET", "hardware/low-button-brightness")
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun setLowButtonBrightness(brightness: Int): DopplerBrightness {
        val body = json.encodeToString(DopplerBrightness(brightness.coerceIn(0, 100)))
        val raw = executeAuthenticatedRequest("PUT", "hardware/low-button-brightness", body)
        return json.decodeFromString<DopplerBrightness>(raw)
    }

    open suspend fun getSyncButtonDisplayBrightness(): DopplerSync {
        val raw = executeAuthenticatedRequest("GET", "hardware/sync-button-display-brightness")
        return json.decodeFromString<DopplerSync>(raw)
    }

    open suspend fun setSyncButtonDisplayBrightness(sync: Boolean): DopplerSync {
        val body = json.encodeToString(DopplerSync(sync))
        val raw = executeAuthenticatedRequest("PUT", "hardware/sync-button-display-brightness", body)
        return json.decodeFromString<DopplerSync>(raw)
    }

    open suspend fun getSyncHighLowColor(): DopplerSync {
        val raw = executeAuthenticatedRequest("GET", "hardware/sync-high-low-color")
        return json.decodeFromString<DopplerSync>(raw)
    }

    open suspend fun setSyncHighLowColor(sync: Boolean): DopplerSync {
        val body = json.encodeToString(DopplerSync(sync))
        val raw = executeAuthenticatedRequest("PUT", "hardware/sync-high-low-color", body)
        return json.decodeFromString<DopplerSync>(raw)
    }

    open suspend fun getSyncButtonDisplayColor(): DopplerSync {
        val raw = executeAuthenticatedRequest("GET", "hardware/sync-button-display-color")
        return json.decodeFromString<DopplerSync>(raw)
    }

    open suspend fun setSyncButtonDisplayColor(sync: Boolean): DopplerSync {
        val body = json.encodeToString(DopplerSync(sync))
        val raw = executeAuthenticatedRequest("PUT", "hardware/sync-button-display-color", body)
        return json.decodeFromString<DopplerSync>(raw)
    }

    open suspend fun getHighDisplayColor(): DopplerColor {
        val raw = executeAuthenticatedRequest("GET", "hardware/high-display-color")
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun setHighDisplayColor(color: DopplerColor): DopplerColor {
        val body = json.encodeToString(DopplerColorPayload(color.toList()))
        val raw = executeAuthenticatedRequest("PUT", "hardware/high-display-color", body)
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun getLowDisplayColor(): DopplerColor {
        val raw = executeAuthenticatedRequest("GET", "hardware/low-display-color")
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun setLowDisplayColor(color: DopplerColor): DopplerColor {
        val body = json.encodeToString(DopplerColorPayload(color.toList()))
        val raw = executeAuthenticatedRequest("PUT", "hardware/low-display-color", body)
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun getHighButtonColor(): DopplerColor {
        val raw = executeAuthenticatedRequest("GET", "hardware/high-button-color")
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun setHighButtonColor(color: DopplerColor): DopplerColor {
        val body = json.encodeToString(DopplerColorPayload(color.toList()))
        val raw = executeAuthenticatedRequest("PUT", "hardware/high-button-color", body)
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun getLowButtonColor(): DopplerColor {
        val raw = executeAuthenticatedRequest("GET", "hardware/low-button-color")
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    open suspend fun setLowButtonColor(color: DopplerColor): DopplerColor {
        val body = json.encodeToString(DopplerColorPayload(color.toList()))
        val raw = executeAuthenticatedRequest("PUT", "hardware/low-button-color", body)
        return DopplerColor.fromList(json.decodeFromString<DopplerColorPayload>(raw).color)
    }

    // ==========================================
    // Custom Screen Overrides & 29-LED Lightbar
    // ==========================================

    open suspend fun displayText(text: String, duration: Int = 10, speed: Int = 50, color: DopplerColor = DopplerColor.CYAN): String {
        val payload = DopplerDisplayText(text, duration, speed, color.toList())
        return executeAuthenticatedRequest("PUT", "hardware/display-text", json.encodeToString(payload))
    }

    open suspend fun displaySmallDigits(number: Int, duration: Int = 15, color: DopplerColor = DopplerColor.AMBER): String {
        val payload = DopplerSmallDigits(number, duration, color.toList())
        return executeAuthenticatedRequest("PUT", "hardware/small-display-digits", json.encodeToString(payload))
    }

    open suspend fun displayDots(effect: DopplerDisplayDots): String {
        return executeAuthenticatedRequest("PUT", "hardware/display-dots", json.encodeToString(effect))
    }

    // ==========================================
    // Weather
    // ==========================================

    open suspend fun getWeather(): DopplerWeather {
        val raw = executeAuthenticatedRequest("GET", "software/weather")
        return json.decodeFromString<DopplerWeather>(raw)
    }

    open suspend fun setWeather(weather: DopplerWeather): DopplerWeather {
        val body = json.encodeToString(weather)
        val raw = executeAuthenticatedRequest("PUT", "software/weather", body)
        return json.decodeFromString<DopplerWeather>(raw)
    }

    open suspend fun getWeatherWakeupTime(): DopplerWeatherWakeupTime {
        val raw = executeAuthenticatedRequest("GET", "software/weather-wakeup-time")
        return json.decodeFromString<DopplerWeatherWakeupTime>(raw)
    }

    open suspend fun setWeatherWakeupTime(time: String): DopplerWeatherWakeupTime {
        val body = json.encodeToString(DopplerWeatherWakeupTime(time))
        val raw = executeAuthenticatedRequest("PUT", "software/weather-wakeup-time", body)
        return json.decodeFromString<DopplerWeatherWakeupTime>(raw)
    }
}
