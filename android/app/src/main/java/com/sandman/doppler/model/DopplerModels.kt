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

        /**
         * Strict hex parse, or `null` when [hex] is not a colour.
         *
         * [fromHex] must not be used on user input, for two reasons this avoids:
         *
         * - it coerces anything unparseable to `0`, so a typo becomes black instead of being
         *   rejected - the user picks a colour, sees no error, and the clock goes dark;
         * - it misreads the short form. `#FFF` parses as `0x000FFF`, a dark teal, rather than
         *   white, and nothing anywhere reports the discrepancy.
         *
         * Accepts `#RRGGBB`, `RRGGBB`, and the three-digit shorthand `#RGB`.
         */
        fun fromHexOrNull(hex: String): DopplerColor? {
            val clean = hex.trim().removePrefix("#")
            if (clean.length != 6 && clean.length != 3) return null
            if (!clean.all { it.isHexDigit() }) return null
            val expanded = if (clean.length == 3) clean.map { "$it$it" }.joinToString("") else clean
            val num = expanded.toLongOrNull(16)?.toInt() ?: return null
            return DopplerColor(
                r = (num shr 16) and 0xFF,
                g = (num shr 8) and 0xFF,
                b = num and 0xFF
            )
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
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
    // Live payload: {"preset":"PRESET4"}. The wire field is `preset`, NOT `soundPreset`.
    // The docs claim the values are "Flat"/"Rock"/"Pop"/...; the device actually reports
    // PRESETn. Verified on hardware. PUT is 500 on this endpoint - read-only.
    @SerialName("preset") val soundPreset: String = "PRESET1"
)

@Serializable
data class DopplerSoundPresetMode(
    // Live payload: {"presetmode":1} - a number, not the documented "auto"/"manual" string.
    @SerialName("presetmode") val soundPresetMode: Int = 1
)

@Serializable
data class DopplerAscending(
    val ascending: Boolean = true
)

@Serializable
data class DopplerLightSensor(
    // Live payload: {"sensor":1414}. Wire field is `sensor`, not `lightSensor`. Because
    // unknown keys are ignored on decode, the old name silently produced 0 forever.
    @SerialName("sensor") val lightSensor: Int = 0
)

@Serializable
data class DopplerDayMode(
    // Live payload: {"isDayMode":true}. Wire field is `isDayMode`. PUT is 500 - read-only,
    // which is consistent with day mode being driven by the ambient light sensor.
    @SerialName("isDayMode") val dayMode: Boolean = true
)

@Serializable
data class DopplerHighToLowTransition(
    // Live payload: {"transition":230}. Wire field is the bare `transition`, not
    // `highToLowTransition`. PUT with the old name returns HTTP 500 and changes nothing,
    // so this slider was inert; with the real name it returns 200 and applies.
    @SerialName("transition") val highToLowTransition: Int = 230
)

@Serializable
data class DopplerLowToHighTransition(
    // Also `{"transition":270}` - both transition endpoints use the bare `transition` key,
    // distinguished only by which endpoint you call.
    @SerialName("transition") val lowToHighTransition: Int = 270
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
    val status: Int = STATUS_ACTIVE, // see STATUS_* below
    val sound: String = "Gentle.mp3",
    val src: Int = 1 // 1 = user, 0 = system
) {
    /**
     * Live hardware reports `status: 10` on every active alarm - verified against a real
     * clock, where the system alarm and both user alarms all came back as 10. This app
     * previously assumed `1 = enabled`, which made `isEnabled` false for every alarm the
     * device actually had, so they all rendered dimmed with the toggle showing off.
     *
     * `0` is stored verbatim by the clock when sent (verified), so 0 remains "disabled".
     * `1` is still accepted because this app wrote it before the real value was known, and
     * rejecting it would make previously-written alarms look disabled. Any other value is
     * treated as active: an alarm is only shown as off when the clock positively says 0,
     * because wrongly dimming a real alarm is worse than wrongly lighting up a stale one.
     */
    val isEnabled: Boolean get() = status != STATUS_DISABLED
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

    companion object {
        /** What live hardware reports for an active alarm. Verified on a real unit. */
        const val STATUS_ACTIVE = 10

        /** Stored verbatim by the clock when sent; the only value that means "off". */
        const val STATUS_DISABLED = 0

        /** Legacy value this app wrote before the real status was known. Still honoured. */
        const val STATUS_LEGACY_ENABLED = 1
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
    /** Raw `wsmode` from the clock. Decode with `WeatherMode.fromWire`. */
    val weatherMode: Int = 2,
    val weatherWakeupTime: String = "06:00",
    /**
     * IANA timezone the clock believes it is in, e.g. `America/Chihuahua`.
     *
     * Worth surfacing because it can silently be wrong: this unit shipped set to
     * `Canada/Saskatchewan`. Both that and the correct zone are UTC-06:00, so the
     * displayed time was correct and the fault was invisible.
     */
    val clockTimezone: String? = null,
    val currentTemperatureF: Int = 72,

    /**
     * Read endpoints this clock does not answer, e.g. `hardware/wifi-status`.
     *
     * These are skipped rather than retried, because six of them stall for ~15s each and
     * requests are serialized - together that was ~90s of dead time per poll cycle.
     *
     * The field exists so a skipped read is not silently presented as a reading. Every
     * property backed by a path in this set still holds its model **default**, which is
     * indistinguishable from a real value; UI must check membership here and say
     * "unavailable" instead. See `EndpointCapabilities` and `STANDALONE_IMPLEMENTATION_PLAN.md`
     * §2.3 for the silent-default trap this avoids.
     */
    val unavailableEndpoints: Set<String> = emptySet()
) {
    /**
     * Whether this clock does not answer the endpoint backing a control.
     *
     * UI must ask this before rendering a value or enabling a switch for the matching
     * property. A property backed by an unavailable endpoint holds its model default, which
     * looks exactly like a real reading - a "0" signal strength, a `true` colon setting - and
     * cannot be told apart from one by inspection. See [unavailableEndpoints].
     *
     * @param path endpoint path relative to the DSN, e.g. `"software/colon-blink"`.
     */
    fun lacks(path: String): Boolean = path in unavailableEndpoints
}
