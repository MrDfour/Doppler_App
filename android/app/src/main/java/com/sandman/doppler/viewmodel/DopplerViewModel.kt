package com.sandman.doppler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sandman.doppler.api.LocationResolver
import com.sandman.doppler.api.RequestTelemetry
import com.sandman.doppler.model.*
import com.sandman.doppler.repository.DopplerRepository
import com.sandman.doppler.storage.WeatherPlaceStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DiagnosticsLog(
    val timestamp: String,
    val type: String,
    val summary: String,
    val details: String
)

class DopplerViewModel(
    private val repository: DopplerRepository,
    /**
     * Turns typed place names and postal codes into coordinates.
     *
     * Injectable so tests can point it at a stub instead of the real geocoders.
     */
    private val locationResolver: LocationResolver = LocationResolver(),
    /**
     * Optional so tests can run without an Android Context.
     *
     * Holds the place name behind the coordinates on the clock, which the clock itself
     * does not keep.
     */
    private val placeStore: WeatherPlaceStore? = null
) : ViewModel() {

    val deviceState: StateFlow<DopplerDeviceState?> = repository.deviceState
    val isRefreshing: StateFlow<Boolean> = repository.isRefreshing
    val lastError: StateFlow<String?> = repository.lastError

    /** True when commands are going through the Copilot cloud rather than the LAN daemon. */
    val isCloudControlPlane: Boolean get() = repository.isCloudControlPlane

    /** Human-readable transport description for the diagnostics screen. */
    val connectionModeLabel: String
        get() = if (isCloudControlPlane) "CLOUD CONTROL PLANE" else "DIRECT LOCAL WI-FI"

    private val _requestTelemetry = MutableStateFlow(RequestTelemetry.snapshot())
    val requestTelemetry: StateFlow<RequestTelemetry.Snapshot> = _requestTelemetry

    /**
     * Samples [RequestTelemetry] into an observable flow.
     *
     * Diagnostics needs to show live timings, and the recorder is a plain object with no
     * flow of its own. Polled rather than pushed because it is only ever read on a screen
     * the user opened deliberately, where a 1s tick is free.
     */
    fun startTelemetrySampling(periodMs: Long = 1000L) {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                _requestTelemetry.value = RequestTelemetry.snapshot()
                delay(periodMs)
            }
        }
    }

    private var telemetryJob: Job? = null

    private val _logs = MutableStateFlow<List<DiagnosticsLog>>(emptyList())
    val logs: StateFlow<List<DiagnosticsLog>> = _logs

    private val _overrideProbeResults = MutableStateFlow<List<OverrideProbeResult>?>(null)
    val overrideProbeResults: StateFlow<List<OverrideProbeResult>?> = _overrideProbeResults

    private val _isProbingOverrides = MutableStateFlow(false)
    val isProbingOverrides: StateFlow<Boolean> = _isProbingOverrides

    private val _isRecheckingEndpoints = MutableStateFlow(false)
    val isRecheckingEndpoints: StateFlow<Boolean> = _isRecheckingEndpoints

    init {
        repository.startPolling(intervalMs = 8000L)
    }

    override fun onCleared() {
        telemetryJob?.cancel()
        super.onCleared()
        repository.stopPolling()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            addLog("INFO", "Manual refresh requested", "Target: default device")
        }
    }

    fun setDayColor(color: DopplerColor) {
        viewModelScope.launch {
            try {
                repository.updateDayDisplayColor(color)
                addLog("COMMAND", "Set Day Display Color", "RGB: (${color.r}, ${color.g}, ${color.b})")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set day color", e.message ?: "")
            }
        }
    }

    fun setNightColor(color: DopplerColor) {
        viewModelScope.launch {
            try {
                repository.updateNightDisplayColor(color)
                addLog("COMMAND", "Set Night Display Color", "RGB: (${color.r}, ${color.g}, ${color.b})")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set night color", e.message ?: "")
            }
        }
    }

    fun setVolume(volume: Int) {
        viewModelScope.launch {
            try {
                repository.updateMasterVolume(volume)
                addLog("COMMAND", "Set Master Volume", "Level: $volume%")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set volume", e.message ?: "")
            }
        }
    }

    fun setDayBrightness(brightness: Int) {
        viewModelScope.launch {
            try {
                repository.updateDayDisplayBrightness(brightness)
                addLog("COMMAND", "Set Day Brightness", "Level: $brightness%")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set day brightness", e.message ?: "")
            }
        }
    }

    fun setNightBrightness(brightness: Int) {
        viewModelScope.launch {
            try {
                repository.updateNightDisplayBrightness(brightness)
                addLog("COMMAND", "Set Night Brightness", "Level: $brightness%")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set night brightness", e.message ?: "")
            }
        }
    }

    fun setTime24Hour(is24Hour: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateTimeMode(is24Hour)
                addLog("COMMAND", "Set 24H Format", "Enabled: $is24Hour")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set 24h mode", e.message ?: "")
            }
        }
    }

    fun setColonBlink(blink: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateColonBlink(blink)
                addLog("COMMAND", "Set Colon Blink", "Blink: $blink")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set colon blink", e.message ?: "")
            }
        }
    }

    fun setUseColon(on: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateUseColon(on)
                addLog("COMMAND", "Set Use Colon", "On: $on")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set colon visibility", e.message ?: "")
            }
        }
    }

    fun setLeadingZero(use: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateLeadingZero(use)
                addLog("COMMAND", "Set Leading Zero", "Use: $use")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set leading zero", e.message ?: "")
            }
        }
    }

    fun setFadeTime(fade: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateFadeTime(fade)
                addLog("COMMAND", "Set Fade Time", "Fade: $fade")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set fade time", e.message ?: "")
            }
        }
    }

    fun setDisplaySeconds(sec: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateDisplaySeconds(sec)
                addLog("COMMAND", "Set Display Seconds", "Show: $sec")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set display seconds", e.message ?: "")
            }
        }
    }

    fun triggerLightBar(effect: DopplerDisplayDots) {
        viewModelScope.launch {
            try {
                repository.triggerLightBarEffect(effect)
                addLog("COMMAND", "Triggered Lightbar Effect", "Duration: ${effect.duration}s, Speed: ${effect.speed}")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to trigger lightbar effect", e.message ?: "")
            }
        }
    }

    fun setDisplayText(text: String, duration: Int = 10, speed: Int = 50, color: DopplerColor = DopplerColor.CYAN) {
        viewModelScope.launch {
            try {
                repository.displayText(text, duration, speed, color)
                addLog("COMMAND", "Display Text Sent", "Text: '$text'")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to display text", e.message ?: "")
            }
        }
    }

    fun setSmallDisplayDigits(number: Int, duration: Int = 15, color: DopplerColor = DopplerColor.AMBER) {
        viewModelScope.launch {
            try {
                repository.displaySmallDigits(number, duration, color)
                addLog("COMMAND", "Display Small Digits Sent", "Number: $number")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to display small digits", e.message ?: "")
            }
        }
    }

    fun toggleAlarm(alarm: DopplerAlarm) {
        viewModelScope.launch {
            try {
                // Write the values the hardware actually uses: 10 for active, 0 for off. This
                // previously wrote 1, which the clock had never been observed to send, so
                // toggling a real alarm (status 10) wrote 1 and left the device in a state
                // the app itself could not then read back correctly.
                val nextStatus =
                    if (alarm.isEnabled) DopplerAlarm.STATUS_DISABLED else DopplerAlarm.STATUS_ACTIVE
                val updated = alarm.copy(status = nextStatus)
                repository.addOrUpdateAlarm(updated)
                addLog(
                    "COMMAND",
                    "Toggled Alarm #${alarm.id}",
                    "Enabled: ${nextStatus != DopplerAlarm.STATUS_DISABLED}"
                )
            } catch (e: Exception) {
                addLog("ERROR", "Failed to toggle alarm", e.message ?: "")
            }
        }
    }

    fun deleteAlarm(alarmId: Int) {
        viewModelScope.launch {
            try {
                repository.deleteAlarm(alarmId)
                addLog("COMMAND", "Deleted Alarm #$alarmId", "")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to delete alarm", e.message ?: "")
            }
        }
    }

    fun playAlarmSound(sound: String) {
        viewModelScope.launch {
            try {
                repository.playAlarmSound(sound)
                addLog("COMMAND", "Preview Alarm Sound", "Sound: $sound")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to preview sound", e.message ?: "")
            }
        }
    }

    fun setDayButtonColor(color: DopplerColor) {
        viewModelScope.launch {
            try {
                repository.updateDayButtonColor(color)
                addLog("COMMAND", "Set Day Button Color", "RGB: (${color.r}, ${color.g}, ${color.b})")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set day button color", e.message ?: "")
            }
        }
    }

    fun setNightButtonColor(color: DopplerColor) {
        viewModelScope.launch {
            try {
                repository.updateNightButtonColor(color)
                addLog("COMMAND", "Set Night Button Color", "RGB: (${color.r}, ${color.g}, ${color.b})")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set night button color", e.message ?: "")
            }
        }
    }

    fun setDayButtonBrightness(brightness: Int) {
        viewModelScope.launch {
            try {
                repository.updateDayButtonBrightness(brightness)
                addLog("COMMAND", "Set Day Button Brightness", "Level: $brightness%")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set day button brightness", e.message ?: "")
            }
        }
    }

    fun setNightButtonBrightness(brightness: Int) {
        viewModelScope.launch {
            try {
                repository.updateNightButtonBrightness(brightness)
                addLog("COMMAND", "Set Night Button Brightness", "Level: $brightness%")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set night button brightness", e.message ?: "")
            }
        }
    }

    fun setSyncButtonDisplayBrightness(sync: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateSyncButtonDisplayBrightness(sync)
                addLog("COMMAND", "Set Sync Button & Display Brightness", "Enabled: $sync")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set sync brightness", e.message ?: "")
            }
        }
    }

    fun setSyncHighLowColor(sync: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateSyncHighLowColor(sync)
                addLog("COMMAND", "Set Sync Day & Night Color", "Enabled: $sync")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set sync high/low color", e.message ?: "")
            }
        }
    }

    fun setSyncButtonDisplayColor(sync: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateSyncButtonDisplayColor(sync)
                addLog("COMMAND", "Set Sync Button & Display Color", "Enabled: $sync")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set sync button/display color", e.message ?: "")
            }
        }
    }

    fun setDayToNightThreshold(threshold: Int) {
        viewModelScope.launch {
            try {
                repository.updateDayToNightThreshold(threshold)
                addLog("COMMAND", "Set Day->Night Transition Threshold", "Threshold: $threshold")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set day->night threshold", e.message ?: "")
            }
        }
    }

    fun setNightToDayThreshold(threshold: Int) {
        viewModelScope.launch {
            try {
                repository.updateNightToDayThreshold(threshold)
                addLog("COMMAND", "Set Night->Day Transition Threshold", "Threshold: $threshold")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set night->day threshold", e.message ?: "")
            }
        }
    }

    fun saveAlarm(alarm: DopplerAlarm) {
        viewModelScope.launch {
            try {
                repository.addOrUpdateAlarm(alarm)
                addLog("COMMAND", "Saved Alarm #${alarm.id}", "Time: ${alarm.timeFormatted}, Status: ${alarm.status}")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to save alarm", e.message ?: "")
            }
        }
    }

    fun pressButton(button: String) {
        addLog("EVENT", "Pressed button: $button", "Triggered from mobile dashboard")
    }

    // ---------------------------------------------------------------------
    // Weather
    // ---------------------------------------------------------------------

    private val _placeResults = MutableStateFlow<List<PlaceCandidate>>(emptyList())
    val placeResults: StateFlow<List<PlaceCandidate>> = _placeResults

    private val _isSearchingPlaces = MutableStateFlow(false)
    val isSearchingPlaces: StateFlow<Boolean> = _isSearchingPlaces

    private val _placeSearchMessage = MutableStateFlow<String?>(null)
    val placeSearchMessage: StateFlow<String?> = _placeSearchMessage

    private val _savedPlaceLabel = MutableStateFlow(placeStore?.savedLabel)

    /**
     * Name of the place the clock's coordinates belong to, or null when unknown.
     *
     * Null is a normal state, not a failure: the clock only stores the coordinate string,
     * so until a place is picked through this app the label is genuinely not known.
     */
    val savedPlaceLabel: StateFlow<String?> = _savedPlaceLabel

    init {
        // Re-checked against the clock on every poll. If the location was changed from the
        // official app or another phone, the remembered name no longer describes what the
        // clock is using, so it is dropped rather than shown.
        viewModelScope.launch {
            repository.deviceState.collect { state ->
                _savedPlaceLabel.value = placeStore?.labelFor(state?.weatherLocation)
            }
        }
    }

    /**
     * Resolves free-text input to candidate places.
     *
     * The clock is never sent what the user typed. Free text goes to a third-party
     * geocoder, and only the coordinates of a place the user then *picks* reach the
     * clock. That is deliberate: postal codes mean different things in different
     * countries, and `33980` is both a Mexican postal code and a valid US ZIP.
     *
     * Deliberately not debounced per keystroke - Nominatim asks for at most one request
     * a second, so a search that fires as fast as someone types would be refused.
     * The screen sends one search per explicit Search action instead.
     */
    fun searchPlaces(query: String) {
        if (_isSearchingPlaces.value) return
        viewModelScope.launch {
            _isSearchingPlaces.value = true
            _placeSearchMessage.value = null
            try {
                val results = locationResolver.search(query)
                _placeResults.value = results
                if (results.isEmpty()) {
                    _placeSearchMessage.value =
                        "No place found for \"$query\". Try a town name, such as \"Chihuahua\"."
                }
            } catch (e: LocationLookupException) {
                _placeResults.value = emptyList()
                _placeSearchMessage.value = e.message ?: "Place search failed"
            } catch (e: Exception) {
                _placeResults.value = emptyList()
                _placeSearchMessage.value = "Place search failed: ${e.message ?: "unknown error"}"
            } finally {
                _isSearchingPlaces.value = false
            }
        }
    }

    fun clearPlaceResults() {
        _placeResults.value = emptyList()
        _placeSearchMessage.value = null
    }

    /**
     * Points the clock at [place] and, when the geocoder knew one, fixes the timezone too.
     *
     * Setting the timezone here is safe because it comes from the resolved place rather
     * than from the user. The clock's own timezone can be silently wrong - this unit
     * shipped set to `Canada/Saskatchewan` - and both that and the correct zone are
     * UTC-06:00, so nothing visible revealed the fault.
     */
    fun applyPlace(place: PlaceCandidate) {
        viewModelScope.launch {
            try {
                repository.updateWeatherLocation(place.coordinateString)
                addLog(
                    "COMMAND", "Set weather location",
                    "${place.displayLabel} -> ${place.coordinateString}"
                )
                // Only remembered once the clock has accepted it, so a failed write
                // cannot leave a name on screen for a location the clock never took.
                placeStore?.remember(place)
                val tz = place.timezone
                if (!tz.isNullOrBlank()) {
                    repository.updateClockTimezone(tz)
                    addLog("COMMAND", "Set clock timezone", tz)
                }
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set weather location", e.message ?: "")
            }
        }
    }

    fun setWeatherEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateWeatherEnabled(enabled)
                addLog("COMMAND", "Set weather display", if (enabled) "On" else "Off")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to change weather display", e.message ?: "")
            }
        }
    }

    fun setWeatherMode(mode: Int) {
        viewModelScope.launch {
            try {
                repository.updateWeatherMode(mode)
                val label = WeatherMode.labelFor(mode)
                addLog("COMMAND", "Set weather reading", "$label (wsmode $mode)")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to change weather reading", e.message ?: "")
            }
        }
    }

    /**
     * Sets the time the clock announces its forecast.
     *
     * Not a refresh interval: the clock's current weather and icon update as soon as new
     * data reaches it. This is when it *says* the forecast, alarm-style.
     */
    fun setWeatherWakeupTime(time: String) {
        viewModelScope.launch {
            try {
                repository.updateWeatherWakeupTime(time)
                addLog("COMMAND", "Set forecast announcement time", time)
            } catch (e: Exception) {
                addLog("ERROR", "Failed to change forecast time", e.message ?: "")
            }
        }
    }

    fun setClockTimezone(timezone: String) {
        viewModelScope.launch {
            try {
                repository.updateClockTimezone(timezone)
                addLog("COMMAND", "Set clock timezone", timezone)
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set clock timezone", e.message ?: "")
            }
        }
    }

    /**
     * Fires both custom-display overrides once and reports the HTTP status of each.
     *
     * This is the definitive test for "does my firmware implement these?": a NOT_SUPPORTED
     * verdict means the clock has no route for the override, while REJECTED means it does
     * route it and refused our payload - a bug on our side rather than the hardware's.
     * The clock will visibly scroll "PROBE" and show 0 for ~5s if the overrides work.
     */
    fun probeOverrideSupport() {
        if (_isProbingOverrides.value) return
        viewModelScope.launch {
            _isProbingOverrides.value = true
            _overrideProbeResults.value = null
            try {
                val results = repository.probeOverrideSupport()
                _overrideProbeResults.value = results
                addLog(
                    "PROBE",
                    "Override support probe complete",
                    results.joinToString("\n") { "${it.label}: ${it.describe()}" }
                )
            } catch (e: Exception) {
                addLog("ERROR", "Override support probe failed", e.message ?: "")
            } finally {
                _isProbingOverrides.value = false
            }
        }
    }

    /**
     * Re-probes the endpoints this clock was measured as not answering.
     *
     * Explicit rather than automatic because the whole point of skipping them is to avoid
     * paying their deadline, so the cost is only worth it when the user asks. Results land in
     * the log so a firmware fix is visible as evidence rather than a silent behaviour change.
     */
    fun recheckUnavailableEndpoints() {
        if (_isRecheckingEndpoints.value) return
        val before = repository.deviceState.value?.unavailableEndpoints.orEmpty()
        if (before.isEmpty()) return
        viewModelScope.launch {
            _isRecheckingEndpoints.value = true
            addLog("PROBE", "Re-checking unavailable endpoints", before.sorted().joinToString("\n"))
            try {
                repository.recheckUnavailableEndpoints()
                val after = repository.deviceState.value?.unavailableEndpoints.orEmpty()
                addLog(
                    "PROBE",
                    "Re-check complete",
                    if (after.size < before.size) {
                        "Recovered: " + (before - after).sorted().joinToString(", ")
                    } else {
                        "Still unanswered: " + after.sorted().joinToString(", ")
                    }
                )
            } catch (e: Exception) {
                addLog("ERROR", "Re-check failed", e.message ?: "")
            } finally {
                _isRecheckingEndpoints.value = false
            }
        }
    }

    private fun OverrideProbeResult.describe(): String {
        val status = httpCode?.let { "HTTP $it" } ?: "no HTTP response"
        return "$verdict ($status) - $detail"
    }

    private fun addLog(type: String, summary: String, details: String) {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val newLog = DiagnosticsLog(now, type, summary, details)
        _logs.value = listOf(newLog) + _logs.value.take(49)
    }
}
