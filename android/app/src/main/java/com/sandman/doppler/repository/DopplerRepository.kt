package com.sandman.doppler.repository

import com.sandman.doppler.api.DopplerCloudApi
import com.sandman.doppler.api.DopplerException
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
    val localApi: DopplerLocalApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _deviceState = MutableStateFlow<DopplerDeviceState?>(null)
    val deviceState: StateFlow<DopplerDeviceState?> = _deviceState.asStateFlow()

    /**
     * How this repository reaches the clock: the Copilot cloud control plane, or the
     * clock's own LAN daemon. Diagnostics reports this because the two paths have
     * different failure modes and different latency, and guessing wrong sends you
     * debugging the wrong network.
     */
    val isCloudControlPlane: Boolean get() = localApi is DopplerCloudApi

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(foregroundIntervalMs: Long = 5000L, errorBackoffMs: Long = 30000L) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                var hadError = false
                try {
                    refresh()
                    if (_lastError.value != null) {
                        hadError = true
                    }
                } catch (e: Exception) {
                    hadError = true
                }
                val delayTime = if (hadError) errorBackoffMs else foregroundIntervalMs
                delay(delayTime)
            }
        }
    }

    fun startPolling(intervalMs: Long) {
        startPolling(foregroundIntervalMs = intervalMs, errorBackoffMs = intervalMs * 3)
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isRefreshing.value = false
    }

    /**
     * Executes a sequential aggregate poll of the hardware endpoints.
     * Keeps requests serial to protect the Doppler's single-threaded oatpp daemon.
     */
    suspend fun refresh() {
        try {
            _isRefreshing.value = true

            var errorsEncountered = 0
            var firstException: Exception? = null

            // Query basic info
            val info = try { localApi.getDeviceInfo() } catch (e: Exception) { errorsEncountered++; firstException = e; DopplerDeviceInfo() }
            val wifi = try { localApi.getWifiStatus() } catch (e: Exception) { errorsEncountered++; if (firstException == null) firstException = e; DopplerWifiStatus() }
            val time = try { localApi.getUtcTime() } catch (e: Exception) { errorsEncountered++; if (firstException == null) firstException = e; DopplerUtcTime() }
            val timeMode = try { localApi.getTimeMode() } catch (e: Exception) { errorsEncountered++; if (firstException == null) firstException = e; DopplerTimeMode() }
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
            val dayButtonColor = try { localApi.getHighButtonColor() } catch (e: Exception) { dayColor }
            val nightButtonColor = try { localApi.getLowButtonColor() } catch (e: Exception) { nightColor }
            val dayBrightness = try { localApi.getHighDisplayBrightness() } catch (e: Exception) { DopplerBrightness(85) }
            val nightBrightness = try { localApi.getLowDisplayBrightness() } catch (e: Exception) { DopplerBrightness(25) }
            val dayButtonBrightness = try { localApi.getHighButtonBrightness() } catch (e: Exception) { DopplerBrightness(80) }
            val nightButtonBrightness = try { localApi.getLowButtonBrightness() } catch (e: Exception) { DopplerBrightness(20) }

            // Query sync flags & thresholds
            val syncBtnDispBright = try { localApi.getSyncButtonDisplayBrightness() } catch (e: Exception) { DopplerSync(true) }
            val syncColor = try { localApi.getSyncHighLowColor() } catch (e: Exception) { DopplerSync(false) }
            val syncBtnDispColor = try { localApi.getSyncButtonDisplayColor() } catch (e: Exception) { DopplerSync(true) }
            val dayToNight = try { localApi.getHighToLowTransition() } catch (e: Exception) { DopplerHighToLowTransition(35) }
            val nightToDay = try { localApi.getLowToHighTransition() } catch (e: Exception) { DopplerLowToHighTransition(45) }

            // Query alarms
            val alarms = try { localApi.getAlarms() } catch (e: Exception) { emptyList() }
            val sounds = try { localApi.getAlarmSounds() } catch (e: Exception) { emptyList() }

            if (errorsEncountered >= 4 && firstException != null) {
                throw firstException
            }

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
                dayToNightThreshold = dayToNight.highToLowTransition,
                nightToDayThreshold = nightToDay.lowToHighTransition,
                dayDisplayColor = dayColor,
                nightDisplayColor = nightColor,
                dayButtonColor = dayButtonColor,
                nightButtonColor = nightButtonColor,
                dayDisplayBrightness = dayBrightness.brightness,
                nightDisplayBrightness = nightBrightness.brightness,
                dayButtonBrightness = dayButtonBrightness.brightness,
                nightButtonBrightness = nightButtonBrightness.brightness,
                syncButtonDisplayBrightness = syncBtnDispBright.sync,
                syncHighLowColor = syncColor.sync,
                syncButtonDisplayColor = syncBtnDispColor.sync,
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
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(dayDisplayColor = color) }
        try {
            localApi.setHighDisplayColor(color)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateNightDisplayColor(color: DopplerColor) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(nightDisplayColor = color) }
        try {
            localApi.setLowDisplayColor(color)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateMasterVolume(volume: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(masterVolume = volume) }
        try {
            localApi.setVolume(volume)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateDayDisplayBrightness(brightness: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(dayDisplayBrightness = brightness) }
        try {
            localApi.setHighDisplayBrightness(brightness)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateNightDisplayBrightness(brightness: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(nightDisplayBrightness = brightness) }
        try {
            localApi.setLowDisplayBrightness(brightness)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateTimeMode(is24Hour: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(time24Hour = is24Hour) }
        try {
            localApi.setTimeMode(if (is24Hour) 24 else 12)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateColonBlink(blink: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(colonBlink = blink) }
        try {
            localApi.setColonBlink(blink)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateUseColon(on: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(colonVisible = on) }
        try {
            localApi.setUseColon(on)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateLeadingZero(use: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(leadingZero24Hour = use) }
        try {
            localApi.setUseLeadingZero(use)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateFadeTime(fade: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(fadeTimeMode = fade) }
        try {
            localApi.setFadeTime(fade)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateDisplaySeconds(sec: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(displaySecondsOnMini = sec) }
        try {
            localApi.setDisplaySeconds(sec)
        } catch (e: Exception) {
            _deviceState.value = previousState
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

    /**
     * Probes whether this clock's firmware implements the two custom-display overrides.
     *
     * Dispatches one real PUT per override and classifies the HTTP status, so a dead
     * override can be attributed to the firmware instead of guessed at from the UI.
     * NOTE: this is not side-effect free - the clock really does scroll the probe text
     * and show the probe digits when it supports them, which is what makes the probe
     * conclusive. Requests still go through [localApi], so they stay serialized behind
     * its mutex. One probe failing never prevents the other from running.
     */
    suspend fun probeOverrideSupport(): List<OverrideProbeResult> = listOf(
        probeOverride("Scrolling Text Override", "hardware/display-text") {
            localApi.displayText(PROBE_TEXT, PROBE_DURATION_SECONDS, PROBE_SPEED, DopplerColor.CYAN)
        },
        probeOverride("Mini Display Override", "hardware/small-display-digits") {
            localApi.displaySmallDigits(PROBE_DIGITS, PROBE_DURATION_SECONDS, DopplerColor.AMBER)
        }
    )

    private suspend fun probeOverride(
        label: String,
        path: String,
        call: suspend () -> String
    ): OverrideProbeResult = try {
        call()
        // A successful call means the transport saw a 2xx; the exact code is not surfaced
        // on the success path, so report the range rather than inventing a specific code.
        OverrideProbeResult(
            label = label,
            path = path,
            httpCode = null,
            verdict = OverrideVerdict.SUPPORTED,
            detail = "Accepted (HTTP 2xx)"
        )
    } catch (e: Exception) {
        val code = (e as? DopplerException.ProtocolException)?.httpCode
        OverrideProbeResult(
            label = label,
            path = path,
            httpCode = code,
            verdict = verdictForCode(code),
            detail = e.message ?: e.javaClass.simpleName
        )
    }

    /**
     * Maps a status code to a verdict. The 404/405/501 vs 400/422 split is the point of
     * the probe: the first means this firmware does not route the override at all, the
     * second means it does route it and refused our payload - opposite fixes.
     */
    private fun verdictForCode(code: Int?): OverrideVerdict = when (code) {
        null -> OverrideVerdict.UNKNOWN
        in 200..299 -> OverrideVerdict.SUPPORTED
        // Taxonomy per SANDMAN_DOPPLER_KNOWLEDGE.md: a 400/422 means the clock DID
        // route the request and refused our payload. Naming that NOT_SUPPORTED would
        // wrongly send us hunting for a firmware gap that does not exist, so it is
        // deliberately REJECTED and must not be reported as a firmware gap.
        400, 422 -> OverrideVerdict.REJECTED
        // 404/405/501 mean no such route exists - the firmware does not implement it.
        404, 405, 501 -> OverrideVerdict.NOT_SUPPORTED
        else -> OverrideVerdict.UNKNOWN
    }

    suspend fun addOrUpdateAlarm(alarm: DopplerAlarm) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { current ->
            if (current == null) null
            else {
                val updatedAlarms = current.alarms.filterNot { it.id == alarm.id } + alarm
                current.copy(alarms = updatedAlarms)
            }
        }
        try {
            localApi.createOrUpdateAlarm(alarm)
            refresh()
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun deleteAlarm(alarmId: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { current ->
            if (current == null) null
            else current.copy(alarms = current.alarms.filterNot { it.id == alarmId })
        }
        try {
            localApi.deleteAlarm(alarmId)
            refresh()
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun playAlarmSound(sound: String) {
        localApi.playAlarmSound(sound)
    }

    suspend fun updateDayButtonColor(color: DopplerColor) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(dayButtonColor = color) }
        try {
            localApi.setHighButtonColor(color)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateNightButtonColor(color: DopplerColor) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(nightButtonColor = color) }
        try {
            localApi.setLowButtonColor(color)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateDayButtonBrightness(brightness: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(dayButtonBrightness = brightness) }
        try {
            localApi.setHighButtonBrightness(brightness)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateNightButtonBrightness(brightness: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(nightButtonBrightness = brightness) }
        try {
            localApi.setLowButtonBrightness(brightness)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateSyncButtonDisplayBrightness(sync: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(syncButtonDisplayBrightness = sync) }
        try {
            localApi.setSyncButtonDisplayBrightness(sync)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateSyncHighLowColor(sync: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(syncHighLowColor = sync) }
        try {
            localApi.setSyncHighLowColor(sync)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateSyncButtonDisplayColor(sync: Boolean) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(syncButtonDisplayColor = sync) }
        try {
            localApi.setSyncButtonDisplayColor(sync)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateDayToNightThreshold(threshold: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(dayToNightThreshold = threshold) }
        try {
            localApi.setHighToLowTransition(threshold)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    suspend fun updateNightToDayThreshold(threshold: Int) {
        val previousState = _deviceState.value
        applyOptimisticUpdate { it?.copy(nightToDayThreshold = threshold) }
        try {
            localApi.setLowToHighTransition(threshold)
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    private inline fun applyOptimisticUpdate(transform: (DopplerDeviceState?) -> DopplerDeviceState?) {
        _deviceState.value = transform(_deviceState.value)
    }

    private companion object {
        // Short and unmistakable on the display, so a SUPPORTED verdict is visible
        // to the naked eye and not just in the log.
        const val PROBE_TEXT = "PROBE"
        const val PROBE_DIGITS = 0
        const val PROBE_DURATION_SECONDS = 5
        const val PROBE_SPEED = 50
    }
}
