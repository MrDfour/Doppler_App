package com.sandman.doppler.repository

import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth repository for Sandman Doppler clock state.
 *
 * Coordinates data flow between UI screens and the local hardware REST API.
 * Adheres to the hardware constraint that requests must be serialized (semaphore limit = 1).
 */
class DopplerRepository(
    private val localApi: DopplerLocalApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _deviceState = MutableStateFlow<DopplerDeviceState?>(null)
    val deviceState: StateFlow<DopplerDeviceState?> = _deviceState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(intervalMs: Long = 10000L) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                refresh()
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Executes a sequential aggregate poll of the hardware endpoints.
     * Keeps requests serial to protect the Doppler's single-threaded oatpp daemon.
     */
    suspend fun refresh() {
        try {
            _isRefreshing.value = true

            // Query basic info
            val info = try { localApi.getDeviceInfo() } catch (e: Exception) { DopplerDeviceInfo() }
            val wifi = try { localApi.getWifiStatus() } catch (e: Exception) { DopplerWifiStatus() }
            val time = try { localApi.getUtcTime() } catch (e: Exception) { DopplerUtcTime() }
            val timeMode = try { localApi.getTimeMode() } catch (e: Exception) { DopplerTimeMode() }
            val colon = try { localApi.getUseColon() } catch (e: Exception) { DopplerUseColon() }
            val blink = try { localApi.getColonBlink() } catch (e: Exception) { DopplerColonBlink() }

            // Query audio
            val volume = try { localApi.getVolume() } catch (e: Exception) { DopplerVolume() }
            val preset = try { localApi.getSoundPreset() } catch (e: Exception) { DopplerSoundPreset() }
            val asc = try { localApi.getAscendingVolume() } catch (e: Exception) { DopplerAscending() }

            // Query lighting & colors
            val sensor = try { localApi.getLightSensor() } catch (e: Exception) { DopplerLightSensor() }
            val dayMode = try { localApi.getDayMode() } catch (e: Exception) { DopplerDayMode() }
            val dayColor = try { localApi.getHighDisplayColor() } catch (e: Exception) { DopplerColor.CYAN }
            val nightColor = try { localApi.getLowDisplayColor() } catch (e: Exception) { DopplerColor.DEEP_RED }
            val dayBrightness = try { localApi.getHighDisplayBrightness() } catch (e: Exception) { DopplerBrightness(85) }
            val nightBrightness = try { localApi.getLowDisplayBrightness() } catch (e: Exception) { DopplerBrightness(25) }

            // Query alarms
            val alarms = try { localApi.getAlarms() } catch (e: Exception) { emptyList() }
            val sounds = try { localApi.getAlarmSounds() } catch (e: Exception) { emptyList() }

            val currentState = _deviceState.value ?: DopplerDeviceState(dsn = localApi.dsn, ipAddress = localApi.host, port = localApi.port)

            _deviceState.value = currentState.copy(
                dsn = localApi.dsn,
                ipAddress = localApi.host,
                port = localApi.port,
                online = true,
                lastSyncTimestampMs = System.currentTimeMillis(),
                manufacturer = info.mfgrName ?: "Palo Alto Innovation",
                modelNumber = info.modelNum ?: "SandmanDopplerProduction",
                firmwareVersion = info.firmware ?: "Unknown",
                softwareVersion = info.software ?: "Unknown",
                uptimeSeconds = wifi.uptime / 1000,
                wifiSsid = wifi.ssid,
                wifiRssi = wifi.str,
                currentUtcHour = time.hour,
                currentUtcMin = time.min,
                time24Hour = timeMode.timeMode == 24,
                colonVisible = colon.on,
                colonBlink = blink.blink,
                masterVolume = volume.volume,
                soundPreset = preset.soundPreset,
                ascendingAlarms = asc.ascending,
                ambientLightSensorLux = sensor.lightSensor,
                isNightMode = !dayMode.dayMode,
                dayDisplayColor = dayColor,
                nightDisplayColor = nightColor,
                dayDisplayBrightness = dayBrightness.brightness,
                nightDisplayBrightness = nightBrightness.brightness,
                alarms = alarms,
                availableSounds = sounds
            )
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Failed to connect to Doppler"
            _deviceState.value = _deviceState.value?.copy(online = false)
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun updateDayDisplayColor(color: DopplerColor) {
        applyOptimisticUpdate { it?.copy(dayDisplayColor = color) }
        try {
            localApi.setHighDisplayColor(color)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun updateNightDisplayColor(color: DopplerColor) {
        applyOptimisticUpdate { it?.copy(nightDisplayColor = color) }
        try {
            localApi.setLowDisplayColor(color)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun updateMasterVolume(volume: Int) {
        applyOptimisticUpdate { it?.copy(masterVolume = volume) }
        try {
            localApi.setVolume(volume)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun updateDayDisplayBrightness(brightness: Int) {
        applyOptimisticUpdate { it?.copy(dayDisplayBrightness = brightness) }
        try {
            localApi.setHighDisplayBrightness(brightness)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun updateNightDisplayBrightness(brightness: Int) {
        applyOptimisticUpdate { it?.copy(nightDisplayBrightness = brightness) }
        try {
            localApi.setLowDisplayBrightness(brightness)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun updateTimeMode(is24Hour: Boolean) {
        applyOptimisticUpdate { it?.copy(time24Hour = is24Hour) }
        try {
            localApi.setTimeMode(if (is24Hour) 24 else 12)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun updateColonBlink(blink: Boolean) {
        applyOptimisticUpdate { it?.copy(colonBlink = blink) }
        try {
            localApi.setColonBlink(blink)
        } catch (e: Exception) {
            refresh()
            throw e
        }
    }

    suspend fun triggerLightBarEffect(effect: DopplerDisplayDots) {
        localApi.displayDots(effect)
    }

    suspend fun displayText(text: String, duration: Int = 10, speed: Int = 50, color: DopplerColor = DopplerColor.CYAN) {
        localApi.displayText(text, duration, speed, color)
    }

    suspend fun displaySmallDigits(number: Int, duration: Int = 15, color: DopplerColor = DopplerColor.AMBER) {
        localApi.displaySmallDigits(number, duration, color)
    }

    suspend fun addOrUpdateAlarm(alarm: DopplerAlarm) {
        localApi.createOrUpdateAlarm(alarm)
        refresh()
    }

    suspend fun deleteAlarm(alarmId: Int) {
        localApi.deleteAlarm(alarmId)
        refresh()
    }

    suspend fun playAlarmSound(sound: String) {
        localApi.playAlarmSound(sound)
    }

    private inline fun applyOptimisticUpdate(transform: (DopplerDeviceState?) -> DopplerDeviceState?) {
        _deviceState.value = transform(_deviceState.value)
    }
}
