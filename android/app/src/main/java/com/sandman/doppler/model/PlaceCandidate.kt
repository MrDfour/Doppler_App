package com.sandman.doppler.model

import java.util.Locale
import kotlin.math.abs

/**
 * Formats a coordinate the way the clock accepts it in `software/weather`'s `location`.
 *
 * The clock stores the string verbatim - verified on an Enter Sandman, which accepted
 * `27.1258,-104.9118`, `33980` and `Jimenez, Chihuahua, Mexico` all byte-identically,
 * with no validation or normalisation. weatherapi.com documents `q=lat,lon` as a
 * supported location form, so latitude/longitude is what this app always sends.
 *
 * Four decimal places is roughly 11 metres, far finer than any weather grid.
 */
fun formatCoordinate(value: Double): String {
    // Collapse negative zero so a longitude of -0.00001 rounds to "0", not "-0".
    val normalised = if (value == 0.0) 0.0 else value
    var text = String.format(Locale.US, "%.4f", normalised)
    if (text.contains('.')) {
        text = text.trimEnd('0').trimEnd('.')
    }
    return if (text == "-0" || text.isEmpty()) "0" else text
}

/**
 * A place the user can pick, resolved from their plain-text input to coordinates.
 *
 * The clock never sees the user's text. It receives [coordinateString] only. That is
 * deliberate: postal codes are ambiguous across countries, and `33980` is both the
 * postal code for Jimenez, Chihuahua and a valid US ZIP for Port Charlotte,
 * Florida. Sending the raw string would have silently produced US weather.
 */
data class PlaceCandidate(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** State, province or region, when the provider knows one. */
    val admin: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    /** IANA timezone id, when the provider returns one. Used to fix the clock's timezone. */
    val timezone: String? = null,
    /** Postal code that produced this match, for postal searches. */
    val postalCode: String? = null
) {
    /**
     * The exact string written to the clock's `location`.
     *
     * Comma-separated, no space, no sign padding - matching the form verified on
     * real hardware.
     */
    val coordinateString: String
        get() = "${formatCoordinate(latitude)},${formatCoordinate(longitude)}"

    /** Region line: "Chihuahua, Mexico". Empty when the provider gave neither. */
    val regionLine: String
        get() = listOfNotNull(admin, country).filter { it.isNotBlank() }.joinToString(", ")

    /** Single line for a list row: "Jimenez - Chihuahua, Mexico". */
    val displayLabel: String
        get() = if (regionLine.isBlank()) name else "$name - $regionLine"
}