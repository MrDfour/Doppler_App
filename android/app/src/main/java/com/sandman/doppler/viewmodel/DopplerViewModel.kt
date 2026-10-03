package com.sandman.doppler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sandman.doppler.model.*
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DiagnosticsLog(
    val timestamp: String,
    val type: String,
    val summary: String,
    val details: String
)

class DopplerViewModel(
    private val repository: DopplerRepository
) : ViewModel() {

    val deviceState: StateFlow<DopplerDeviceState?> = repository.deviceState
    val isRefreshing: StateFlow<Boolean> = repository.isRefreshing
    val lastError: StateFlow<String?> = repository.lastError

    private val _logs = MutableStateFlow<List<DiagnosticsLog>>(emptyList())
    val logs: StateFlow<List<DiagnosticsLog>> = _logs

    private val _overrideProbeResults = MutableStateFlow<List<OverrideProbeResult>?>(null)
    val overrideProbeResults: StateFlow<List<OverrideProbeResult>?> = _overrideProbeResults

    private val _isProbingOverrides = MutableStateFlow(false)
    val isProbingOverrides: StateFlow<Boolean> = _isProbingOverrides

    init {
        repository.startPolling(intervalMs = 8000L)
    }

    override fun onCleared() {
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
                val nextStatus = if (alarm.status == 1) 0 else 1
                val updated = alarm.copy(status = nextStatus)
                repository.addOrUpdateAlarm(updated)
                addLog("COMMAND", "Toggled Alarm #${alarm.id}", "Enabled: ${nextStatus == 1}")
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
