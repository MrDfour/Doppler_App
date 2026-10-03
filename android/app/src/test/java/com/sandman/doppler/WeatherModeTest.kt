package com.sandman.doppler

import com.sandman.doppler.model.WeatherMode
import com.sandman.doppler.model.WeatherProvider
import com.sandman.doppler.model.WeatherUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding of `software/weather`'s `wsmode`.
 *
 * `wsmode` is three choices in one integer - provider, statistic, unit - and nothing
 * Sandman published explains it. The mapping was recovered from `doppyler` 0.0.20, the
 * Python client that Palo Alto Innovation's own Home Assistant integration depends on.
 *
 * The part with teeth: modes 1-12 fetch from weatherapi.com, which resolves worldwide,
 * and modes 13-17 fetch from the US National Weather Service, which does not. An Enter
 * Sandman set to mode 2 in Chihuahua displayed correct local weather once `location`
 * was given coordinates; the same coordinates under mode 14 could not have worked.
 * Getting this table wrong silently sends users outside the US to a dead provider.
 */
class WeatherModeTest {

    @Test
    fun `every documented wire value decodes`() {
        for (value in 0..17) {
            assertNotNull("wsmode $value should decode", WeatherMode.fromWire(value))
            assertEquals(
                "wsmode $value must map back to itself",
                value,
                WeatherMode.fromWire(value)!!.wireValue
            )
        }
        assertEquals("all 18 documented modes are modelled", 18, WeatherMode.entries.size)
    }

    @Test
    fun `an unrecognised value decodes to null rather than a wrong mode`() {
        // Firmware can add modes. Guessing would show "High today (C)" for something else.
        assertNull(WeatherMode.fromWire(18))
        assertNull(WeatherMode.fromWire(-1))
        assertNull(WeatherMode.fromWire(999))
    }

    @Test
    fun `an unrecognised value still gets a label the user can read`() {
        assertTrue(WeatherMode.labelFor(42).contains("42"))
        assertTrue(WeatherMode.labelFor(42).contains("not recognised"))
        // The label must still name the number the clock holds, or the picker row is a lie.
        assertEquals("High today (C)", WeatherMode.labelFor(2))
    }

    @Test
    fun `modes one through twelve are worldwide and thirteen through seventeen are US only`() {
        for (value in 1..12) {
            assertEquals(
                "wsmode $value should be weatherapi.com",
                WeatherProvider.WEATHER_API_COM,
                WeatherMode.fromWire(value)!!.provider
            )
        }
        for (value in 13..17) {
            assertEquals(
                "wsmode $value should be the US NWS",
                WeatherProvider.US_NWS,
                WeatherMode.fromWire(value)!!.provider
            )
            assertTrue("wsmode $value cannot resolve a non-US location",
                WeatherMode.fromWire(value)!!.requiresUnitedStates)
        }
        for (value in 1..12) {
            assertFalse("wsmode $value must not be marked US only",
                WeatherMode.fromWire(value)!!.requiresUnitedStates)
        }
    }

    @Test
    fun `the same statistic and unit can come from two different providers`() {
        // Modes 2 and 14 both mean "today's high in Celsius". Only the provider differs,
        // and that difference is exactly what decides whether Mexico works.
        val weatherapi = WeatherMode.fromWire(2)!!
        val nws = WeatherMode.fromWire(14)!!

        assertEquals(nws.statistic, weatherapi.statistic)
        assertEquals(nws.unit, weatherapi.unit)
        assertEquals(WeatherUnit.CELSIUS, weatherapi.unit)
        assertTrue(weatherapi.provider != nws.provider)
    }

    @Test
    fun `units are assigned per mode`() {
        assertEquals(WeatherUnit.FAHRENHEIT, WeatherMode.fromWire(1)!!.unit)
        assertEquals(WeatherUnit.CELSIUS, WeatherMode.fromWire(2)!!.unit)
        assertEquals(WeatherUnit.PERCENT, WeatherMode.fromWire(3)!!.unit)
        assertEquals(WeatherUnit.AIR_QUALITY, WeatherMode.fromWire(4)!!.unit)
        assertEquals(WeatherUnit.FAHRENHEIT, WeatherMode.fromWire(5)!!.unit)
        assertEquals(WeatherUnit.CELSIUS, WeatherMode.fromWire(6)!!.unit)
        assertEquals(WeatherUnit.PERCENT, WeatherMode.fromWire(7)!!.unit)
        assertEquals(WeatherUnit.PERCENT, WeatherMode.fromWire(8)!!.unit)
        assertEquals(WeatherUnit.FAHRENHEIT, WeatherMode.fromWire(9)!!.unit)
        assertEquals(WeatherUnit.CELSIUS, WeatherMode.fromWire(10)!!.unit)
        assertEquals(WeatherUnit.PERCENT, WeatherMode.fromWire(11)!!.unit)
        assertEquals(WeatherUnit.AIR_QUALITY, WeatherMode.fromWire(12)!!.unit)
    }

    @Test
    fun `the picker offers every mode except off`() {
        assertEquals(17, WeatherMode.selectable.size)
        assertFalse(WeatherMode.selectable.contains(WeatherMode.OFF))
        assertTrue(WeatherMode.selectable.contains(WeatherMode.DAILY_HIGH_CELSIUS))
        assertTrue(WeatherMode.selectable.contains(WeatherMode.NWS_HOURLY_HUMIDITY))
    }

    @Test
    fun `every selectable mode has a distinct label`() {
        // Duplicate labels would make the picker ambiguous - two rows reading the same,
        // sending different wsmode values.
        val labels = WeatherMode.selectable.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        val descriptions = WeatherMode.selectable.map { it.description }
        assertEquals(descriptions.size, descriptions.toSet().size)
    }

    @Test
    fun `no selectable mode has a blank label or description`() {
        for (mode in WeatherMode.selectable) {
            assertTrue("wsmode ${mode.wireValue} needs a label", mode.label.isNotBlank())
            assertTrue("wsmode ${mode.wireValue} needs help text", mode.description.isNotBlank())
        }
    }

    @Test
    fun `the default works outside the United States and uses Celsius`() {
        // A fresh install in Mexico with a Fahrenheit default would be wrong twice over.
        assertEquals(WeatherUnit.CELSIUS, WeatherMode.PREFERRED_DEFAULT.unit)
        assertFalse(WeatherMode.PREFERRED_DEFAULT.requiresUnitedStates)
    }

    @Test
    fun `providers are labelled for a non-technical reader`() {
        assertFalse(WeatherProvider.WEATHER_API_COM.label.isBlank())
        assertFalse(WeatherProvider.US_NWS.label.isBlank())
        assertTrue(WeatherProvider.WEATHER_API_COM.label != WeatherProvider.US_NWS.label)
    }
}