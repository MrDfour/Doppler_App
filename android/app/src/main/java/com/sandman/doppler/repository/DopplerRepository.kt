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

    suspend fun refresh(deviceId: String = "doppler-radar-01") {
        try {
            _isRefreshing.value = true
            val state = localApi.getDeviceStatus(deviceId)
            _deviceState.value = state
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Failed to connect to Doppler"
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun updateDayDisplayColor(deviceId: String, color: DopplerColor) {
        val patch = """{"dayDisplayColor": {"r": ${color.r}, "g": ${color.g}, "b": ${color.b}}}"""
        applyOptimisticUpdate { it?.copy(dayDisplayColor = color) }
        try {
            val updated = localApi.updateSettings(deviceId, patch)
            _deviceState.value = updated
        } catch (e: Exception) {
            refresh(deviceId)
            throw e
        }
    }

    suspend fun updateNightDisplayColor(deviceId: String, color: DopplerColor) {
        val patch = """{"nightDisplayColor": {"r": ${color.r}, "g": ${color.g}, "b": ${color.b}}}"""
        applyOptimisticUpdate { it?.copy(nightDisplayColor = color) }
        try {
            val updated = localApi.updateSettings(deviceId, patch)
            _deviceState.value = updated
        } catch (e: Exception) {
            refresh(deviceId)
            throw e
        }
    }

    suspend fun updateMasterVolume(deviceId: String, volume: Int) {
        val patch = """{"masterVolume": $volume}"""
        applyOptimisticUpdate { it?.copy(masterVolume = volume) }
        try {
            val updated = localApi.updateSettings(deviceId, patch)
            _deviceState.value = updated
        } catch (e: Exception) {
            refresh(deviceId)
            throw e
        }
    }

    suspend fun triggerLightBarEffect(deviceId: String, effect: LightBarEffect) {
        localApi.triggerLightBarEffect(deviceId, effect)
        refresh(deviceId)
    }

    suspend fun stopLightBarEffect(deviceId: String) {
        localApi.stopLightBarEffect(deviceId)
        refresh(deviceId)
    }

    suspend fun addAlarm(deviceId: String, alarm: DopplerAlarm) {
        localApi.addAlarm(deviceId, alarm)
        refresh(deviceId)
    }

    suspend fun updateAlarm(deviceId: String, alarm: DopplerAlarm) {
        localApi.updateAlarm(deviceId, alarm)
        refresh(deviceId)
    }

    suspend fun deleteAlarm(deviceId: String, alarmId: Int) {
        localApi.deleteAlarm(deviceId, alarmId)
        refresh(deviceId)
    }

    suspend fun pressButton(deviceId: String, button: String) {
        localApi.pressPhysicalButton(deviceId, button)
        refresh(deviceId)
    }

    private inline fun applyOptimisticUpdate(transform: (DopplerDeviceState?) -> DopplerDeviceState?) {
        _deviceState.value = transform(_deviceState.value)
    }
}
