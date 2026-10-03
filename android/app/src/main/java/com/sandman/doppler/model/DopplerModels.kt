package com.sandman.doppler.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DopplerColor(
    val r: Int = 0,
    val g: Int = 220,
    val b: Int = 255
) {
    init {
        require(r in 0..255) { "Red component must be 0..255 (got $r)" }
        require(g in 0..255) { "Green component must be 0..255 (got $g)" }
        require(b in 0..255) { "Blue component must be 0..255 (got $b)" }
    }

    fun toList(): List<Int> = listOf(r, g, b)
    fun toColorObject(): DopplerColorObject = DopplerColorObject(red = r, green = g, blue = b)
    fun toHex(): String = String.format("#%02X%02X%02X", r, g, b)

    companion object {
        val CYAN = DopplerColor(0, 220, 255)
        val AMBER = DopplerColor(255, 150, 0)
        val DEEP_RED = DopplerColor(255, 40, 40)
        val EMERALD = DopplerColor(16, 220, 120)
        val PURPLE = DopplerColor(180, 60, 255)

        fun fromList(list: List<Int>): DopplerColor {
            val red = list.getOrNull(0)?.coerceIn(0, 255) ?: 0
            val green = list.getOrNull(1)?.coerceIn(0, 255) ?: 0
            val blue = list.getOrNull(2)?.coerceIn(0, 255) ?: 0
            return DopplerColor(red, green, blue)
        }

        fun fromHex(hex: String): DopplerColor {
            val clean = hex.removePrefix("#")
            val num = clean.toLongOrNull(16)?.toInt() ?: 0
            return DopplerColor(
                r = (num shr 16) and 0xFF,
                g = (num shr 8) and 0xFF,
                b = num and 0xFF
            )
        }
    }
}

@Serializable
data class DopplerColorObject(
    val red: Int = 0,
    val green: Int = 220,
    val blue: Int = 255
) {
    fun toDopplerColor(): DopplerColor = DopplerColor(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
}

@Serializable
data class DopplerColorPayload(
    val color: List<Int>
)

@Serializable
data class DopplerDeviceInfo(
    val mfgrName: String? = null,
    val modelNum: String? = null,
    val serialNum: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val software: String? = null
)

@Serializable
data class DopplerWifiStatus(
    val uptime: Long = 0L,
    val ssid: String = "",
    val str: Int = 0
)

@Serializable
data class DopplerUtcTime(
    val hour: Int = 0,
    val min: Int = 0
)

@Serializable
data class DopplerTimeMode(
    val timeMode: Int = 12 // 12 or 24
)

@Serializable
data class DopplerTimezone(
    val timezone: String = "America/Los_Angeles"
)

@Serializable
data class DopplerTimeOffset(
    val offset: Int = 0
)

@Serializable
data class DopplerUseColon(
    val on: Boolean = true
)

@Serializable
data class DopplerColonBlink(
    val blink: Boolean = true
)

@Serializable
data class DopplerUseLeadingZero(
    val useLeadingZero: Boolean = false
)

@Serializable
data class DopplerFadeTime(
    val fadeTime: Boolean = true
)

@Serializable
data class DopplerDisplaySeconds(
    val displaySeconds: Boolean = false
)

@Serializable
data class DopplerVolume(
    val volume: Int = 75
)

@Serializable
data class DopplerSoundPreset(
    val soundPreset: String = "Flat"
)

@Serializable
data class DopplerSoundPresetMode(
    val soundPresetMode: String = "auto"
)

@Serializable
data class DopplerAscending(
    val ascending: Boolean = true
)

@Serializable
data class DopplerLightSensor(
    val lightSensor: Int = 0
)

@Serializable
data class DopplerDayMode(
    val dayMode: Boolean = true
)

@Serializable
data class DopplerHighToLowTransition(
    val highToLowTransition: Int = 35
)

@Serializable
data class DopplerLowToHighTransition(
    val lowToHighTransition: Int = 45
)

@Serializable
data class DopplerBrightness(
    val brightness: Int = 80
)

@Serializable
data class DopplerSync(
    val sync: Boolean = true
)

@Serializable
data class DopplerAlarm(
    val id: Int,
    val name: String = "",
    val time_hr: Int = 7,
    val time_min: Int = 0,
    val repeat: String = "", // e.g. "MoTuWeThFr"
    val color: DopplerColorObject? = null,
    val volume: Int = 80,
    val status: Int = 1, // 1 = enabled, 0 = disabled
    val sound: String = "Gentle.mp3",
    val src: Int = 1 // 1 = user, 0 = system
) {
    val isEnabled: Boolean get() = status == 1
    val isSystemAlarm: Boolean get() = id == 0
    val timeFormatted: String get() = String.format("%02d:%02d", time_hr, time_min)

    val repeatDaysList: List<String>
        get() {
            if (repeat.isBlank()) return emptyList()
            val days = mutableListOf<String>()
            var idx = 0
            while (idx + 2 <= repeat.length) {
                days.add(repeat.substring(idx, idx + 2))
                idx += 2
            }
            return days
        }
}

@Serializable
data class DopplerAlarmsResponse(
    val alarms: List<DopplerAlarm> = emptyList()
)

@Serializable
data class DopplerAlarmSoundsResponse(
    val sounds: List<String> = emptyList()
)

@Serializable
data class DopplerPlaySoundRequest(
    val sound: String
)

@Serializable
data class DopplerDisplayText(
    val text: String,
    val duration: Int = 10,
    val speed: Int = 50,
    val color: List<Int> = listOf(0, 220, 255)
)

@Serializable
data class DopplerSmallDigits(
    val num: Int,
    val duration: Int = 15,
    val color: List<Int> = listOf(255, 150, 0)
)

@Serializable
data class DopplerDisplayDots(
    val colors: List<List<Int>>? = null,
    val duration: Int = 15,
    val speed: Int = 50,
    val attributes: Map<String, String>? = null
)

/**
 * Outcome of a single custom-display override support probe.
 *
 * The two overrides (scrolling text, mini digits) are documented but optional: a given
 * firmware build may simply not route them. The status code alone cannot distinguish
 * "this firmware does not implement the override" from "we sent the wrong payload",
 * so the probe classifies the code into a [OverrideVerdict] instead.
 */
data class OverrideProbeResult(
    val label: String,
    val path: String,
    /** Verbatim HTTP status, or null when the request never got an HTTP response. */
    val httpCode: Int?,
    val verdict: OverrideVerdict,
    val detail: String
)

enum class OverrideVerdict {
    /**
     * Clock answered 2xx. The route exists, but these overrides are one-shot display
     * commands with no read-back endpoint, so this does NOT prove the clock rendered
     * anything. Reported separately from SUPPORTED so a 2xx is never mistaken for proof.
     */
    ACCEPTED_UNVERIFIED,

    /**
     * The clock demonstrably acted on the command. Reserved for overrides that expose a
     * GET so the stored value can be read back; the two display overrides cannot reach
     * this verdict.
     */
    SUPPORTED,

    /** Route answered 400/422 - the override exists but refused our payload. */
    REJECTED,

    /** Route answered 404/405/501 - this firmware does not implement the override. */
    NOT_SUPPORTED,

    /** Transport failure or an unrecognised status - inconclusive, needs a retry. */
    UNKNOWN
}

@Serializable
data class DopplerWeather(
    val wsonoff: Boolean = false,
    val location: String = "",
    val wsmode: Int = 1
)

@Serializable
data class DopplerWeatherWakeupTime(
    val weatherwakeuptime: String = "06:00"
)

@Serializable
data class DopplerAlexaTone(
    val tone: Boolean = true
)

/**
 * Unified Immutable UI State consumed by Jetpack Compose screens.
 * Aggregates all live Doppler clock hardware readings and user preferences.
 */
@Serializable
data class DopplerDeviceState(
    val dsn: String = "Doppler-00000000",
    val name: String = "Sandman Doppler",
    /** Blank until a real host is known. Never fabricate a plausible IP here. */
    val ipAddress: String = "",
    val port: Int = 5443,
    val online: Boolean = false,
    val lastSyncTimestampMs: Long = 0L,

    // Hardware metadata
    val manufacturer: String = "Palo Alto Innovation",
    val modelNumber: String = "SandmanDopplerProduction",
    val firmwareVersion: String = "Unknown",
    val softwareVersion: String = "Unknown",
    val uptimeSeconds: Long = 0L,
    val wifiSsid: String = "",
    val wifiRssi: Int = 0,

    // Time & Formatting
    val currentUtcHour: Int = 12,
    val currentUtcMin: Int = 0,
    val time24Hour: Boolean = false,
    val leadingZero24Hour: Boolean = false,
    val colonBlink: Boolean = true,
    val colonVisible: Boolean = true,
    val displaySecondsOnMini: Boolean = false,
    val fadeTimeMode: Boolean = true,
    val timezone: String = "America/Los_Angeles",
    val timeOffsetMinutes: Int = 0,

    // Audio & Equalizer
    val masterVolume: Int = 75,
    val soundPreset: String = "Flat",
    val soundPresetMode: String = "auto",
    val ascendingAlarms: Boolean = true,

    // Ambient Sensor & Mode
    val ambientLightSensorLux: Int = 100,
    val isNightMode: Boolean = false,
    val dayToNightThreshold: Int = 35,
    val nightToDayThreshold: Int = 45,

    // Brightness & Colors
    val dayDisplayBrightness: Int = 85,
    val nightDisplayBrightness: Int = 25,
    val dayButtonBrightness: Int = 80,
    val nightButtonBrightness: Int = 20,
    val dayDisplayColor: DopplerColor = DopplerColor.CYAN,
    val nightDisplayColor: DopplerColor = DopplerColor.DEEP_RED,
    val dayButtonColor: DopplerColor = DopplerColor.CYAN,
    val nightButtonColor: DopplerColor = DopplerColor.DEEP_RED,

    // Sync Flags
    val syncButtonDisplayBrightness: Boolean = true,
    val syncHighLowColor: Boolean = false,
    val syncButtonDisplayColor: Boolean = true,

    // Alarms
    val alarms: List<DopplerAlarm> = emptyList(),
    val availableSounds: List<String> = emptyList(),

    // Weather
    val weatherEnabled: Boolean = false,
    val weatherLocation: String = "",
    val weatherWakeupTime: String = "06:00",
    val currentTemperatureF: Int = 72
)
