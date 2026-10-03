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
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

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
                val startedAt = System.currentTimeMillis()
                try {
                    refresh()
                    if (_lastError.value != null) {
                        hadError = true
                    }
                } catch (e: Exception) {
                    hadError = true
                }

                // A poll cycle is the sum of 22 round-trips. Over the cloud relay that can
                // exceed the interval, in which case a fixed delay leaves the poller running
                // back-to-back with no idle gap - permanently occupying the request gate and
                // inviting relay throttling. Yield at least as long as the cycle actually
                // took, so the link always gets a breather between sweeps.
                val elapsed = System.currentTimeMillis() - startedAt
                val base = if (hadError) errorBackoffMs else foregroundIntervalMs
                delay(maxOf(base, elapsed))
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
     *
     * These reads run at [com.sandman.doppler.api.RequestGate.Priority.BACKGROUND], so
     * a user action issued mid-sweep is served after at most the single poll request
     * already in flight rather than after all 22.
     */
    suspend fun refresh() {
        try {
            _isRefreshing.value = true

            var errorsEncountered = 0
            var firstException: Exception? = null

            // Query basic info
            val info = try { localApi.getDeviceInfo() } catch (e: Exception) { errorsEncountered++; firstException = e; DopplerDeviceInfo() }
            // `hardware/wifi-status` never answers on real hardware (HTTP 408 at ~15s), so it is
            // caught separately and is NOT counted as an error. Two reasons, both load-bearing:
            // it is a device limitation rather than a fault, and counting it would contribute
            // one of the four failures that make this method throw and mark the whole device
            // offline - over a badge this unit never had.
            val wifi = try { localApi.getWifiStatus() }
                catch (e: DopplerException.UnavailableException) { DopplerWifiStatus() }
                catch (e: Exception) { errorsEncountered++; if (firstException == null) firstException = e; DopplerWifiStatus() }
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
            val clockAlarms = try { localApi.getAlarms() } catch (e: Exception) { emptyList() }
            // Ground truth for which alarm ids actually exist on the clock. Must be taken
            // from the raw clock list, never from the merged/optimistic one: a pending
            // write holds an id the clock has not acknowledged, and treating that as
            // "exists" would route a create to PUT and silently edit nothing.
            clockAlarmIds.clear()
            clockAlarmIds.addAll(clockAlarms.map { it.id })
            // The clock's own list is authoritative, except for alarms we have written and
            // it has not echoed back yet. See mergePendingAlarms.
            val alarms = mergePendingAlarms(clockAlarms)
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
                availableSounds = sounds,
                // Published so the UI can say "this device does not support this" instead of
                // rendering the model default that the failed read fell back to.
                unavailableEndpoints = localApi.capabilities.unavailablePaths
            )
            // Close the cycle by reporting whether the clock was actually answering. When it
            // was not, per-endpoint timeouts observed during the cycle are discarded rather
            // than treated as "this endpoint is dead" - otherwise one network outage would
            // permanently disable every endpoint on the device. `wifi` is deliberately not
            // counted in errorsEncountered above (it is unavailable on real hardware), so a
            // healthy cycle still reports healthy here.
            localApi.capabilities.endPollCycle(healthy = errorsEncountered == 0)
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
            // Read the clock back at interactive priority so the displayed value comes
            // from the hardware now rather than up to a poll interval later. The
            // optimistic value is already correct unless the firmware clamped the value,
            // which is exactly what this surfaces.
            confirmHardware("hardware/volume") { raw ->
                _deviceState.value = _deviceState.value?.copy(
                    masterVolume = json.decodeFromString<DopplerVolume>(raw).volume
                )
            }
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
     * Re-probes every endpoint currently believed unavailable, then refreshes.
     *
     * Measured-broken paths are deliberately never re-probed on a timer - six probes at ~15s
     * each would hand most of the ~90s per cycle that skipping them saves straight back. So
     * recovery is driven from here instead: the user asks, the app pays the cost once, and
     * anything still broken returns to the skipped set after
     * [EndpointCapabilities.GIVE_UPS_BEFORE_SKIP] more give-ups.
     *
     * This is also the escape hatch if a firmware update fixes one of these endpoints -
     * without it the app would keep hiding a control the clock can now actually perform.
     */
    suspend fun recheckUnavailableEndpoints() {
        localApi.capabilities.recheckAll()
        refresh()
    }

    /**
     * Probes whether this clock's firmware implements the two custom-display overrides.
     *
     * Dispatches one real PUT per override and classifies the HTTP status, so a dead
     * override can be attributed to the firmware instead of guessed at from the UI.
     *
     * NOTE: this is not side-effect free - the clock really does scroll the probe text
     * and show the probe digits if it honours them. One probe failing never prevents the
     * other from running.
     *
     * IMPORTANT: see [OverrideVerdict.ACCEPTED_UNVERIFIED]. These two overrides are
     * one-shot display commands with no read-back endpoint, so a 2xx proves only that
     * the route exists - it cannot prove the clock rendered anything.
     */
    suspend fun probeOverrideSupport(): List<OverrideProbeResult> = listOf(
        probeOverride("Scrolling Text Override", "hardware/display-text") {
            localApi.displayText(PROBE_TEXT, PROBE_DURATION_SECONDS, PROBE_SPEED, DopplerColor.CYAN)
        },
        probeOverride("Mini Display Override", "hardware/small-display-digits") {
            localApi.displaySmallDigits(PROBE_DIGITS, PROBE_DURATION_SECONDS, DopplerColor.AMBER)
        }
    )

    /**
     * Runs one override probe and classifies the outcome.
     *
     * A 2xx is reported as [OverrideVerdict.ACCEPTED_UNVERIFIED] rather than SUPPORTED:
     * `display-text` and `small-display-digits` are fire-and-forget display overrides
     * with no GET counterpart, so there is nothing to read back. The cloud relay also
     * returns 2xx for requests the clock never acts on. Calling that "supported" would
     * claim a confirmation we cannot make.
     */
    private suspend fun probeOverride(
        label: String,
        path: String,
        call: suspend () -> String
    ): OverrideProbeResult = try {
        call()
        OverrideProbeResult(
            label = label,
            path = path,
            httpCode = null,
            verdict = OverrideVerdict.ACCEPTED_UNVERIFIED,
            detail = "PUT accepted (HTTP 2xx). No read-back endpoint exists for this " +
                "override, so this confirms the route exists but NOT that the clock " +
                "rendered it - check the clock face."
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
        // Decide create-vs-update from the ids the clock has confirmed, not from the id we
        // picked. POST /alarms is create-only on real hardware, so posting an edit creates
        // a second alarm instead of changing the first.
        val existsOnClock = alarm.id in clockAlarmIds
        applyOptimisticUpdate { current ->
            if (current == null) null
            else {
                val updatedAlarms = current.alarms.filterNot { it.id == alarm.id } + alarm
                current.copy(alarms = updatedAlarms)
            }
        }
        try {
            if (existsOnClock) {
                localApi.updateAlarm(alarm)
                // Hold on to this copy until the clock's own list confirms it. See
                // mergePendingAlarms for why the read-back cannot be trusted immediately.
                pendingAlarmWrites[alarm.id] = PendingAlarm(alarm, System.currentTimeMillis())
            } else {
                // A create gets an id of the clock's choosing, not ours. The optimistic entry
                // we just showed carries our invented id, which will never match anything the
                // clock reports - so the alarm the user is looking at would vanish and
                // reappear under a different id one poll later.
                createAlarmAndAdopt(alarm)
            }
            refresh()
        } catch (e: Exception) {
            pendingAlarmWrites.remove(alarm.id)
            _deviceState.value = previousState
            throw e
        }
    }

    /**
     * Create an alarm and shield it under the id the clock actually assigned.
     *
     * `POST /alarms` ignores the id in the body and assigns its own sequential one - proven
     * on hardware: POSTing `id: 200` came back as `id: 4`. It also returns the *entire* alarm
     * list. Since a create adds exactly one id, the set difference between the list before
     * and the list in the response identifies the new alarm unambiguously.
     *
     * Deliberately not matched by content: the clock this was found on holds two alarms that
     * are byte-identical apart from id (both 13:15, same colour, volume and sound), so any
     * content match can adopt the wrong one and silently move a user's alarm onto a
     * different alarm's identity.
     */
    private suspend fun createAlarmAndAdopt(alarm: DopplerAlarm) {
        val idsBefore = clockAlarmIds.toSet()
        val response = localApi.createAlarm(alarm)

        val idsAfter = try {
            json.decodeFromString<DopplerAlarmsResponse>(response).alarms.map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val assigned = (idsAfter - idsBefore).singleOrNull()

        if (assigned != null) {
            pendingAlarmWrites[assigned] =
                PendingAlarm(alarm.copy(id = assigned), System.currentTimeMillis())
            return
        }

        // The response was not a usable list, or the write has not landed yet. Poll, then
        // take whichever single id is new. Falling back to our own invented id would show
        // an alarm under an id the clock never issued, which is the original bug.
        refresh()
        val afterPoll = clockAlarmIds - idsBefore
        val assignedByPoll = afterPoll.singleOrNull()
        if (assignedByPoll != null) {
            pendingAlarmWrites[assignedByPoll] =
                PendingAlarm(alarm.copy(id = assignedByPoll), System.currentTimeMillis())
        } else {
            // Clock has not reported it. Keep the write pending under our id; the grace
            // timer in mergePendingAlarms retires it rather than dropping it silently.
            pendingAlarmWrites[alarm.id] = PendingAlarm(alarm, System.currentTimeMillis())
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
            // A delete is not pending-confirmation: the clock dropping it from its list is
            // the confirmation, so stop shielding it immediately.
            pendingAlarmWrites.remove(alarmId)
            refresh()
        } catch (e: Exception) {
            _deviceState.value = previousState
            throw e
        }
    }

    private data class PendingAlarm(val alarm: DopplerAlarm, val writtenAt: Long)

    private val pendingAlarmWrites = ConcurrentHashMap<Int, PendingAlarm>()

    /**
     * Alarm ids the clock has actually confirmed, rebuilt from the raw list on every poll.
     *
     * This is what decides create-vs-update. The id the app picks client-side
     * (`AlarmsScreen.nextId`) is *not* an identity the clock honours: `POST /alarms`
     * assigns its own sequential id and ignores ours. Routing on that invented id is what
     * made edits create duplicate alarms.
     */
    private val clockAlarmIds = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Reconciles the clock's alarm list with alarms we have written but it has not echoed.
     *
     * Observed on device: setting an alarm made the clock fire it correctly at the
     * configured hour, but the alarm did not appear in the app until after it had already
     * triggered. The clock's `GET /alarms` does not include a freshly written alarm right
     * away, so the `refresh()` that runs immediately after every write - and again on every
     * 8s poll - replaced the optimistic list with the clock's stale one and made the alarm
     * vanish. It reappeared only once the clock's own list caught up, which happened to be
     * after the alarm fired.
     *
     * So the clock's list wins wherever the two agree, but an alarm we wrote is kept until
     * the clock confirms it or [ALARM_CONFIRMATION_GRACE_MS] passes. The timeout matters:
     * without it a genuinely rejected write would be shown forever.
     */
    private fun mergePendingAlarms(fromClock: List<DopplerAlarm>): List<DopplerAlarm> {
        if (pendingAlarmWrites.isEmpty()) return fromClock

        val now = System.currentTimeMillis()
        val merged = fromClock.associateBy { it.id }.toMutableMap()
        val expired = mutableListOf<Int>()

        for ((id, pending) in pendingAlarmWrites) {
            if (merged.containsKey(id)) {
                // The clock has it: it is authoritative from here, so stop shielding.
                expired += id
                continue
            }
            if (now - pending.writtenAt > ALARM_CONFIRMATION_GRACE_MS) {
                expired += id
                continue
            }
            merged[id] = pending.alarm
        }
        expired.forEach { pendingAlarmWrites.remove(it) }
        return merged.values.sortedBy { it.id }
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

    /**
     * Reads [path] back from the clock right after a write and applies the true value.
     *
     * The clock may clamp or reject part of what we sent (brightness limits, colour
     * rounding), so echoing our own optimistic value can leave the UI showing something
     * the hardware never accepted. Confirming immediately keeps the UI honest.
     *
     * Deliberately best-effort: a failed confirmation must not roll back a write that
     * already succeeded, and must not throw at the user. The next poll is the backstop.
     */
    private suspend fun confirmHardware(path: String, apply: (String) -> Unit) {
        try {
            apply(localApi.executeConfirmRequest(path))
        } catch (e: Exception) {
            // Keep the optimistic value; the poller will reconcile.
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private companion object {
        // Short and unmistakable on the display, so a SUPPORTED verdict is visible
        // to the naked eye and not just in the log.
        const val PROBE_TEXT = "PROBE"
        const val PROBE_DIGITS = 0
        const val PROBE_DURATION_SECONDS = 5
        const val PROBE_SPEED = 50

        /**
         * How long a locally-written alarm is shielded from the clock's alarm list.
         *
         * Long enough to outlast the observed gap between writing an alarm and the clock
         * reporting it, short enough that an alarm the clock genuinely rejected does not
         * linger in the UI indefinitely.
         */
        const val ALARM_CONFIRMATION_GRACE_MS = 60_000L
    }
}
