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

    fun pressButton(button: String) {
        addLog("EVENT", "Pressed button: $button", "Triggered from mobile dashboard")
    }

    private fun addLog(type: String, summary: String, details: String) {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val newLog = DiagnosticsLog(now, type, summary, details)
        _logs.value = listOf(newLog) + _logs.value.take(49)
    }
}
