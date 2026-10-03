package com.sandman.doppler.model

/**
 * The upstream weather service the clock fetches from for a given `wsmode`.
 *
 * This is not documented anywhere Sandman published. It was recovered from
 * `doppyler` 0.0.20 (the Python client pinned by Palo Alto Innovation's own
 * `pa-innovation/ha-doppler` Home Assistant integration), whose `WeatherMode`
 * enum annotates each value with the backend it talks to.
 *
 * The distinction is not cosmetic: the two providers have different geographic
 * coverage, so it decides whether a non-US location can work at all.
 */
enum class WeatherProvider(
    /** Shown in the UI as a section heading. */
    val label: String,
    /**
     * False when the provider cannot resolve locations outside the United States.
     *
     * The US National Weather Service has no data for other countries, so a clock
     * configured with one of its modes cannot display Mexican weather regardless of
     * what `location` is set to.
     */
    val coversUnitedStatesOnly: Boolean
) {
    WEATHER_API_COM("Anywhere in the world", coversUnitedStatesOnly = false),
    US_NWS("United States only", coversUnitedStatesOnly = true)
}

/** The unit or quantity a [WeatherMode] renders on the display. */
enum class WeatherUnit { CELSIUS, FAHRENHEIT, PERCENT, AIR_QUALITY, NONE }

/**
 * Decoded `wsmode` from `software/weather`.
 *
 * `wsmode` is three independent choices packed into one integer - **provider**,
 * **statistic** and **unit** - so the number is not interpretable on its own. Mode
 * 2 and mode 14 both mean "today's high in Celsius", but 14 cannot resolve a
 * Mexican location.
 *
 * Only [wireValue]s 0-17 have been observed or documented. [fromWire] returns
 * null for anything else rather than throwing, so firmware that grows new modes
 * degrades to a raw-number label instead of crashing the screen.
 *
 * Enum names follow the upstream `WeatherMode` member for member, including its
 * misspelling in `NWS_HOURLY_CELCIUS`, so this table can be diffed directly against
 * the source it was recovered from.
 */
enum class WeatherMode(
    val wireValue: Int,
    val provider: WeatherProvider,
    val statistic: String,
    val unit: WeatherUnit,
    /** Short label for the picker row. */
    val label: String,
    /** One plain sentence for the help text, written for a non-technical reader. */
    val description: String
) {
    OFF(
        0, WeatherProvider.WEATHER_API_COM, "Off", WeatherUnit.NONE,
        "Off", "No weather is shown on the clock."
    ),
    DAILY_HIGH_FAHRENHEIT(
        1, WeatherProvider.WEATHER_API_COM, "Daily high", WeatherUnit.FAHRENHEIT,
        "High today (F)", "The highest temperature expected today, in Fahrenheit."
    ),
    DAILY_HIGH_CELSIUS(
        2, WeatherProvider.WEATHER_API_COM, "Daily high", WeatherUnit.CELSIUS,
        "High today (C)", "The highest temperature expected today, in Celsius."
    ),
    DAILY_AVG_HUMIDITY(
        3, WeatherProvider.WEATHER_API_COM, "Daily humidity", WeatherUnit.PERCENT,
        "Humidity today (%)", "The average humidity expected today, as a percentage."
    ),
    DAILY_AQI(
        4, WeatherProvider.WEATHER_API_COM, "Daily air quality", WeatherUnit.AIR_QUALITY,
        "Air quality today", "How clean the air is expected to be today."
    ),
    DAILY_LOW_FAHRENHEIT(
        5, WeatherProvider.WEATHER_API_COM, "Daily low", WeatherUnit.FAHRENHEIT,
        "Low today (F)", "The lowest temperature expected today, in Fahrenheit."
    ),
    DAILY_LOW_CELSIUS(
        6, WeatherProvider.WEATHER_API_COM, "Daily low", WeatherUnit.CELSIUS,
        "Low today (C)", "The lowest temperature expected today, in Celsius."
    ),
    DAILY_MIN_HUMIDITY(
        7, WeatherProvider.WEATHER_API_COM, "Daily humidity", WeatherUnit.PERCENT,
        "Lowest humidity (%)", "The lowest humidity expected today, as a percentage."
    ),
    DAILY_MAX_HUMIDITY(
        8, WeatherProvider.WEATHER_API_COM, "Daily humidity", WeatherUnit.PERCENT,
        "Highest humidity (%)", "The highest humidity expected today, as a percentage."
    ),
    HOURLY_TEMPERATURE_FAHRENHEIT(
        9, WeatherProvider.WEATHER_API_COM, "Current temperature", WeatherUnit.FAHRENHEIT,
        "Temp now (F)", "The temperature right now, in Fahrenheit."
    ),
    HOURLY_TEMPERATURE_CELSIUS(
        10, WeatherProvider.WEATHER_API_COM, "Current temperature", WeatherUnit.CELSIUS,
        "Temp now (C)", "The temperature right now, in Celsius."
    ),
    HOURLY_HUMIDITY(
        11, WeatherProvider.WEATHER_API_COM, "Current humidity", WeatherUnit.PERCENT,
        "Humidity now (%)", "The humidity right now, as a percentage."
    ),
    HOURLY_AQI(
        12, WeatherProvider.WEATHER_API_COM, "Current air quality", WeatherUnit.AIR_QUALITY,
        "Air quality now", "How clean the air is right now."
    ),
    NWS_DAILY_FORECAST_FAHRENHEIT(
        13, WeatherProvider.US_NWS, "Daily high", WeatherUnit.FAHRENHEIT,
        "High today (F) - US", "Today's high from the US weather service, in Fahrenheit. United States only."
    ),
    NWS_DAILY_FORECAST_CELSIUS(
        14, WeatherProvider.US_NWS, "Daily high", WeatherUnit.CELSIUS,
        "High today (C) - US", "Today's high from the US weather service, in Celsius. United States only."
    ),
    NWS_HOURLY_FAHRENHEIT(
        15, WeatherProvider.US_NWS, "Current temperature", WeatherUnit.FAHRENHEIT,
        "Temp now (F) - US", "The current temperature from the US weather service, in Fahrenheit. United States only."
    ),
    NWS_HOURLY_CELCIUS(
        16, WeatherProvider.US_NWS, "Current temperature", WeatherUnit.CELSIUS,
        "Temp now (C) - US", "The current temperature from the US weather service, in Celsius. United States only."
    ),
    NWS_HOURLY_HUMIDITY(
        17, WeatherProvider.US_NWS, "Current humidity", WeatherUnit.PERCENT,
        "Humidity now (%) - US", "The current humidity from the US weather service, as a percentage. United States only."
    );

    /** True when this mode cannot resolve a location outside the United States. */
    val requiresUnitedStates: Boolean get() = provider.coversUnitedStatesOnly

    companion object {
        /**
         * Decode a `wsmode`, or null when the value is not one we know.
         *
         * Null rather than a fallback so the UI can tell "firmware sent something new"
         * apart from "the clock is set to Celsius".
         */
        fun fromWire(value: Int): WeatherMode? = entries.firstOrNull { it.wireValue == value }

        /** Human label for any `wsmode`, including unrecognised ones. */
        fun labelFor(value: Int): String =
            fromWire(value)?.label ?: "Mode $value (not recognised)"

        /** Modes offered in the picker, in display order, with [OFF] excluded. */
        val selectable: List<WeatherMode> get() = entries.filter { it != OFF }

        /**
         * The mode this app prefers to select for a new user.
         *
         * Celsius daily high: it is what a metric audience expects, and unlike the
         * NWS modes it resolves outside the United States.
         */
        val PREFERRED_DEFAULT: WeatherMode = DAILY_HIGH_CELSIUS
    }
}

/** Thrown when a place lookup fails, so the UI can explain rather than show a blank list. */
class LocationLookupException(message: String, cause: Throwable? = null) : Exception(message, cause)