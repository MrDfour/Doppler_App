package com.sandman.doppler.model

/**
 * A 24-hour wall-clock time of day, as used by the clock's forecast announcement setting.
 *
 * The wire format for `software/weather-wakeup-time` is a bare `"HH:mm"` string
 * (`{"weatherwakeuptime":"10:00"}` read from the clock). Parsing that into an int and
 * doing arithmetic in the UI is where off-by-one and "24:00" bugs live, so it is
 * modelled here and tested instead.
 */
data class ClockTime(val hour: Int, val minute: Int) {

    init {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
    }

    /** Zero-padded `HH:mm`, exactly the form the clock expects. */
    fun format(): String = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

    /**
     * Moves by [minutes], wrapping around midnight.
     *
     * Wrapping rather than clamping because this is a time of day on a repeating daily
     * alarm-style setting: 23:59 plus one minute is 00:00, not 23:59 stuck forever.
     */
    fun plusMinutes(minutes: Int): ClockTime {
        val total = (hour * 60 + minute + minutes) % (24 * 60)
        val normalised = ((total % (24 * 60)) + (24 * 60)) % (24 * 60)
        return ClockTime(normalised / 60, normalised % 60)
    }

    /** True when both components are exactly two digits, i.e. already in the wire form. */
    val isWireFormatted: Boolean get() = format().length == 5

    companion object {
        /**
         * Parses `HH:mm`, tolerating a single-digit hour and surrounding whitespace.
         *
         * Returns null rather than throwing or coercing: an unparseable time must not
         * become 00:00, which would silently move someone's forecast announcement to
         * the middle of the night.
         *
         * Each part must be digits only. `toIntOrNull` delegates to
         * `Integer.parseInt`, which happily accepts a leading sign - so without this
         * check `"+10:00"` parses as ten o'clock, and worse, `"-0:30"` would parse as
         * half past midnight from the day before. The clock never emits either shape.
         */
        fun parseOrNull(text: String): ClockTime? {
            val trimmed = text.trim()
            val parts = trimmed.split(':')
            if (parts.size != 2) return null
            // Trim each part first so "7 : 30" is accepted, then require digits only.
            val numbers = parts.map { it.trim() }
            if (numbers.any { part -> part.isEmpty() || !part.all { it in '0'..'9' } }) return null
            val hour = numbers[0].toIntOrNull() ?: return null
            val minute = numbers[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return ClockTime(hour, minute)
        }
    }
}