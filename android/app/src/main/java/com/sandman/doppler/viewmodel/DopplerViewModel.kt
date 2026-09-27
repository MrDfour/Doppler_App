package com.sandman.doppler.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sandman.doppler.model.*
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.updateDayDisplayColor(devId, color)
                addLog("COMMAND", "Set Day Display Color", "RGB: (${color.r}, ${color.g}, ${color.b})")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set day color", e.message ?: "")
            }
        }
    }

    fun setNightColor(color: DopplerColor) {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.updateNightDisplayColor(devId, color)
                addLog("COMMAND", "Set Night Display Color", "RGB: (${color.r}, ${color.g}, ${color.b})")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set night color", e.message ?: "")
            }
        }
    }

    fun setVolume(volume: Int) {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.updateMasterVolume(devId, volume)
                addLog("COMMAND", "Set Master Volume", "Level: $volume%")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to set volume", e.message ?: "")
            }
        }
    }

    fun triggerLightBar(effect: LightBarEffect) {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.triggerLightBarEffect(devId, effect)
                addLog("COMMAND", "Triggered Lightbar Effect", "Mode: ${effect.mode}, Duration: ${effect.duration}s")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to trigger lightbar effect", e.message ?: "")
            }
        }
    }

    fun stopLightBar() {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.stopLightBarEffect(devId)
                addLog("COMMAND", "Stopped Lightbar Effect", "")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to stop lightbar", e.message ?: "")
            }
        }
    }

    fun toggleAlarm(alarm: DopplerAlarm) {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                val nextStatus = if (alarm.status == "set") "unarmed" else "set"
                val updated = alarm.copy(status = nextStatus)
                repository.updateAlarm(devId, updated)
                addLog("COMMAND", "Toggled Alarm #${alarm.id}", "New status: $nextStatus")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to toggle alarm", e.message ?: "")
            }
        }
    }

    fun deleteAlarm(alarmId: Int) {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.deleteAlarm(devId, alarmId)
                addLog("COMMAND", "Deleted Alarm #$alarmId", "")
            } catch (e: Exception) {
                addLog("ERROR", "Failed to delete alarm", e.message ?: "")
            }
        }
    }

    fun pressButton(button: String) {
        viewModelScope.launch {
            try {
                val devId = deviceState.value?.id ?: "doppler-radar-01"
                repository.pressButton(devId, button)
                addLog("EVENT", "Pressed button: $button", "Dispatched to clock")
            } catch (e: Exception) {
                addLog("ERROR", "Button press failed", e.message ?: "")
            }
        }
    }

    private fun addLog(type: String, summary: String, details: String) {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val newLog = DiagnosticsLog(now, type, summary, details)
        _logs.value = listOf(newLog) + _logs.value.take(49)
    }
}
