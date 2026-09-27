package com.sandman.doppler.model

import kotlinx.serialization.Serializable

@Serializable
data class DopplerColor(
    val r: Int = 0,
    val g: Int = 220,
    val b: Int = 255
) {
    init {
        require(r in 0..255) { "Red component must be 0..255" }
        require(g in 0..255) { "Green component must be 0..255" }
        require(b in 0..255) { "Blue component must be 0..255" }
    }

    fun toHex(): String = String.format("#%02X%02X%02X", r, g, b)

    companion object {
        val CYAN = DopplerColor(0, 220, 255)
        val AMBER = DopplerColor(255, 150, 0)
        val DEEP_RED = DopplerColor(255, 40, 40)
        val EMERALD = DopplerColor(16, 220, 120)
        val PURPLE = DopplerColor(180, 60, 255)

        fun fromHex(hex: String): DopplerColor {
            val clean = hex.removePrefix("#")
            val num = clean.toLong(16).toInt()
            return DopplerColor(
                r = (num shr 16) and 0xFF,
                g = (num shr 8) and 0xFF,
                b = num and 0xFF
            )
        }
    }
}

@Serializable
enum class AlarmStatus {
    SET,
    UNARMED,
    SNOOZED,
    ACTIVE
}

@Serializable
data class DopplerAlarm(
    val id: Int,
    val name: String,
    val time: String, // "HH:MM"
    val repeat: List<String> = emptyList(), // "Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"
    val color: DopplerColor = DopplerColor.AMBER,
    val volume: Int = 75,
    val status: String = "set", // "set", "unarmed", "snoozed", "active"
    val sound: String = "Sandman.mp3"
) {
    val isEnabled: Boolean get() = status == "set" || status == "active" || status == "snoozed"
    val isRinging: Boolean get() = status == "active"
}

@Serializable
enum class LightBarMode {
    OFF,
    SET,
    SET_EACH,
    BLINK,
    PULSE,
    COMET,
    SWEEP,
    RAINBOW
}

@Serializable
data class LightBarEffect(
    val mode: String = "pulse",
    val color: DopplerColor? = null,
    val colors: List<DopplerColor>? = null,
    val rainbow: Boolean = false,
    val speed: Int = 50,
    val duration: Int = 15,
    val sparkle: String? = null, // "low", "medium", "high"
    val gap: Int? = null,
    val size: Int? = null,
    val direction: String? = null // "left", "right", "bounce"
)

@Serializable
data class DisplayTextRequest(
    val text: String,
    val duration: Int = 10,
    val speed: Int = 50,
    val color: DopplerColor = DopplerColor.CYAN
)

@Serializable
data class MiniDisplayNumberRequest(
    val number: Int,
    val duration: Int = 15,
    val color: DopplerColor = DopplerColor.AMBER
)

@Serializable
data class DopplerDeviceState(
    val id: String = "doppler-radar-01",
    val dsn: String = "Doppler-deadbeef",
    val name: String = "Sandman Doppler",
    val ipAddress: String = "192.168.1.142",
    val firmwareVersion: String = "1.4.12",
    val softwareVersion: String = "2.1.0",
    val modelNumber: String = "PAI-DOPPLER-01",
    val manufacturer: String = "Palo Alto Innovation",
    val online: Boolean = true,
    val uptimeSeconds: Long = 86420,
    val wifiSsid: String = "Home_WiFi",
    val wifiRssi: Int = -58,
    val alexaLoggedIn: Boolean = true,

    val dayDisplayColor: DopplerColor = DopplerColor.CYAN,
    val dayDisplayBrightness: Int = 85,
    val nightDisplayColor: DopplerColor = DopplerColor.DEEP_RED,
    val nightDisplayBrightness: Int = 25,
    val dayButtonColor: DopplerColor = DopplerColor.CYAN,
    val dayButtonBrightness: Int = 80,
    val nightButtonColor: DopplerColor = DopplerColor.DEEP_RED,
    val nightButtonBrightness: Int = 20,
    val smartButtonColor: DopplerColor = DopplerColor.EMERALD,

    val ambientLightSensorLux: Int = 145,
    val isNightMode: Boolean = false,
    val dayToNightThreshold: Int = 35,
    val nightToDayThreshold: Int = 45,

    val time24Hour: Boolean = false,
    val leadingZero24Hour: Boolean = false,
    val colonBlink: Boolean = true,
    val colonVisible: Boolean = true,
    val displaySecondsOnMini: Boolean = false,
    val fadeTimeMode: Boolean = true,
    val timezone: String = "America/Los_Angeles",

    val masterVolume: Int = 65,
    val soundPreset: String = "Flat",
    val volumeDependentEq: Boolean = true,
    val ascendingAlarms: Boolean = true,
    val alexaTapToTalkTone: Boolean = true,
    val alexaWakeWordTone: Boolean = true,

    val weatherLocation: String = "94301, USA",
    val currentTemperatureF: Int = 72,

    val lightBarEffect: LightBarEffect? = null,
    val alarms: Map<Int, DopplerAlarm> = emptyMap()
)
