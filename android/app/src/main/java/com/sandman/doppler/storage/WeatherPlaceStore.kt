package com.sandman.doppler.storage

import android.content.Context
import com.sandman.doppler.model.PlaceCandidate

/**
 * Remembers the place name behind the coordinates already written to the clock.
 *
 * `software/weather` stores only whatever string we sent, so after a location is chosen
 * the clock can report `27.1258,-104.9118` and nothing else. Showing that to a parent is
 * useless - they cannot tell whether it is their town or somewhere in Spain - and the
 * geocoders used here resolve names to coordinates, not the reverse. So the chosen
 * place's display label is kept alongside it.
 *
 * Plain SharedPreferences on purpose: this is a human-readable place name and a pair of
 * public coordinates, not a credential, so it does not belong in the encrypted store.
 */
class WeatherPlaceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("weather_place", Context.MODE_PRIVATE)

    /** The label last shown to the user, e.g. "Jimenez - Chihuahua, Mexico". */
    var savedLabel: String?
        get() = prefs.getString(KEY_LABEL, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LABEL) else putString(KEY_LABEL, value)
            }.apply()
        }

    /** The exact `location` string written to the clock, so a mismatch can be detected. */
    var savedCoordinates: String?
        get() = prefs.getString(KEY_COORDINATES, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_COORDINATES) else putString(KEY_COORDINATES, value)
            }.apply()
        }

    var savedTimezone: String?
        get() = prefs.getString(KEY_TIMEZONE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TIMEZONE) else putString(KEY_TIMEZONE, value)
            }.apply()
        }

    /** Records a chosen place so it can be shown by name later. */
    fun remember(place: PlaceCandidate) {
        savedLabel = place.displayLabel
        savedCoordinates = place.coordinateString
        savedTimezone = place.timezone
    }

    /**
     * The remembered label, but only when it still matches what the clock reports.
     *
     * Returns null on a mismatch rather than a stale name: if the clock's `location` was
     * changed from the official app or another phone, showing the old place would be a
     * confident lie about where the weather is coming from.
     */
    fun labelFor(clockLocation: String?): String? {
        val label = savedLabel ?: return null
        val coordinates = savedCoordinates ?: return null
        return if (clockLocation != null && clockLocation.isNotBlank() && !clockLocation.equals(coordinates, ignoreCase = true)) {
            null
        } else {
            label
        }
    }
}

private const val KEY_LABEL = "label"
private const val KEY_COORDINATES = "coordinates"
private const val KEY_TIMEZONE = "timezone"