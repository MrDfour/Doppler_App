package com.sandman.doppler

import com.sandman.doppler.model.ClockTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing and stepping of the forecast announcement time.
 *
 * The clock reads `{"weatherwakeuptime":"10:00"}` from `software/weather-wakeup-time`
 * and PUTs the same shape, so this is a round trip with the hardware rather than an
 * internal format.
 *
 * The failure this guards: a text field bound straight to that string will accept "24:00",
 * "9:5", "ten" or an empty box, and a lenient parser turns any of those into 00:00 -
 * quietly moving a parent's forecast announcement to the middle of the night. Returning
 * null and refusing to save is the only safe answer.
 */
class ClockTimeTest {

    @Test
    fun `the value read from the clock round trips`() {
        val parsed = ClockTime.parseOrNull("10:00")
        assertEquals(ClockTime(10, 0), parsed)
        assertEquals("10:00", parsed!!.format())
    }

    @Test
    fun `a single digit hour is accepted and padded on the way out`() {
        assertEquals(ClockTime(9, 5), ClockTime.parseOrNull("9:5"))
        assertEquals("09:05", ClockTime.parseOrNull("9:05")!!.format())
        assertEquals("09:05", ClockTime.parseOrNull("9:5")!!.format())
    }

    @Test
    fun `surrounding whitespace and padding are tolerated`() {
        assertEquals(ClockTime(7, 30), ClockTime.parseOrNull("  07:30  "))
        assertEquals(ClockTime(7, 30), ClockTime.parseOrNull("7 : 30"))
    }

    @Test
    fun `the boundaries are accepted`() {
        assertEquals(ClockTime(0, 0), ClockTime.parseOrNull("00:00"))
        assertEquals(ClockTime(23, 59), ClockTime.parseOrNull("23:59"))
        assertEquals(ClockTime(0, 59), ClockTime.parseOrNull("0:59"))
        assertEquals(ClockTime(23, 0), ClockTime.parseOrNull("23:0"))
    }

    @Test
    fun `an out of range time is rejected rather than wrapped`() {
        assertNull(ClockTime.parseOrNull("24:00"))
        assertNull(ClockTime.parseOrNull("12:60"))
        assertNull(ClockTime.parseOrNull("-1:00"))
        assertNull(ClockTime.parseOrNull("99:99"))
    }

    @Test
    fun `nonsense is rejected rather than coerced to midnight`() {
        assertNull(ClockTime.parseOrNull(""))
        assertNull(ClockTime.parseOrNull("   "))
        assertNull(ClockTime.parseOrNull("ten"))
        assertNull(ClockTime.parseOrNull("10"))
        assertNull(ClockTime.parseOrNull("10:00:00"))
        assertNull(ClockTime.parseOrNull("::"))
        assertNull(ClockTime.parseOrNull("10:am"))
        assertNull(ClockTime.parseOrNull("-"))
    }

    @Test
    fun `a leading plus sign is not silently accepted`() {
        // OkHttp and Integer parsing both tolerate "+10", so toIntOrNull does too.
        // "+10:00" is not a shape the clock ever sends and should not be honoured.
        assertNull(ClockTime.parseOrNull("+10:00"))
        assertNull(ClockTime.parseOrNull("10:+5"))
    }

    @Test
    fun `stepping forward crosses into the next hour and wraps at midnight`() {
        assertEquals(ClockTime(10, 30), ClockTime(10, 0).plusMinutes(30))
        assertEquals(ClockTime(11, 0), ClockTime(10, 45).plusMinutes(15))
        assertEquals(ClockTime(0, 1), ClockTime(23, 59).plusMinutes(2))
        assertEquals(ClockTime(0, 0), ClockTime(23, 59).plusMinutes(1))
    }

    @Test
    fun `stepping backward crosses into the previous hour and wraps at midnight`() {
        assertEquals(ClockTime(9, 30), ClockTime(10, 0).plusMinutes(-30))
        assertEquals(ClockTime(23, 59), ClockTime(0, 0).plusMinutes(-1))
        assertEquals(ClockTime(0, 0), ClockTime(0, 0).plusMinutes(0))
    }

    @Test
    fun `stepping by a large amount still lands in range`() {
        val stepped = ClockTime(10, 0).plusMinutes(60 * 24 * 3 + 37)
        assertEquals(ClockTime(10, 37), stepped)
        assertTrue(stepped.format().length == 5)
    }

    @Test
    fun `formatting always produces the five character wire form`() {
        for (hour in 0..23) {
            for (minute in 0..59) {
                val formatted = ClockTime(hour, minute).format()
                assertEquals("expected HH:mm for $hour:$minute", 5, formatted.length)
                assertTrue(
                    "expected HH:mm for $hour:$minute, got $formatted",
                    ClockTime(hour, minute).isWireFormatted
                )
                assertEquals(
                    "format must be parseable back",
                    ClockTime(hour, minute),
                    ClockTime.parseOrNull(formatted)
                )
            }
        }
    }

    @Test
    fun `every hour and minute parses back to itself`() {
        assertEquals(ClockTime(13, 45), ClockTime.parseOrNull(ClockTime(13, 45).format()))
        assertEquals(ClockTime(0, 0), ClockTime.parseOrNull(ClockTime(0, 0).format()))
        assertFalse(ClockTime(0, 0).format() != "00:00")
    }
}