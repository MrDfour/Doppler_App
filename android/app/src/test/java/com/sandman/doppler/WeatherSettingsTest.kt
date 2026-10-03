package com.sandman.doppler

import com.sandman.doppler.model.DopplerDeviceInfo
import com.sandman.doppler.model.DopplerDeviceState
import com.sandman.doppler.model.DopplerTimeMode
import com.sandman.doppler.model.DopplerTimezone
import com.sandman.doppler.model.DopplerUtcTime
import com.sandman.doppler.model.DopplerWeather
import com.sandman.doppler.model.DopplerWeatherWakeupTime
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Weather settings as the clock stores them.
 *
 * The behaviour with teeth here is the **whole-object PUT**. `setWeather` serialises the
 * entire `DopplerWeather`, so there is no such thing as a partial write on the wire: a
 * naive `DopplerWeather(location = "...")` would carry the *model defaults* for
 * `wsonoff` and `wsmode`, silently switching the weather off and dropping the display
 * back to Fahrenheit-high every time someone changed town.
 *
 * The other behaviour with teeth is the read side. A dropped or throttled read must not
 * fall back to model defaults either - `wsonoff = false`, `location = ""` - because a
 * single flaky poll would then present the weather as switched off at an empty location.
 * That is the exact failure mode the rest of this codebase already guards against with
 * `DopplerDeviceState.lacks`, so weather obeys the same rule.
 *
 * These are all verified against the real wire shapes: `{"wsonoff":...,"location":...,
 * "wsmode":...}` read from an Enter Sandman, and `{"weatherwakeuptime":"10:00"}`.
 */
class WeatherSettingsTest {

    /**
     * Records what was PUT, and lets the confirm read-back be dictated.
     *
     * `executeConfirmRequest` is overridden because the inherited version performs a real
     * HTTPS request to 192.168.1.100. Overriding it also lets a test simulate firmware
     * clamping a value, which is the only way to prove the optimistic value is not what
     * ends up on screen.
     */
    private class RecordingWeatherApi : MockPollApi() {
        var storedWeather = DopplerWeather(
            wsonoff = true,
            location = "27.1258,-104.9118",
            wsmode = 10
        )
        var storedWakeup = "10:00"
        var storedTimezone = "America/Chihuahua"

        /** The payload of the last accepted `PUT software/weather`. */
        var writtenWeather: DopplerWeather? = null

        /** Raw JSON the confirm read-back returns, overriding what was stored. */
        var confirmOverride: String? = null

        var failWrites = false
        var failWeatherRead = false
        var failWakeupRead = false
        var failTimezoneRead = false
        var confirmCalls = 0

        /**
         * Fails the three reads `refresh()` treats as poll errors.
         *
         * `refresh()` bails once **four** endpoints have errored. Failing only one proves
         * nothing about how a weather failure is weighted, because a single extra error
         * never reaches the threshold. These three plus a flaky weather read do, which is
         * what makes the weighting observable.
         */
        var failCoreReads = false

        override suspend fun getDeviceInfo(): DopplerDeviceInfo {
            if (failCoreReads) throw Exception("device info unavailable")
            return DopplerDeviceInfo(
                mfgrName = "Palo Alto Innovation",
                modelNum = "SandmanDopplerTest",
                firmware = "v1.0.0"
            )
        }

        override suspend fun getTimeMode(): DopplerTimeMode {
            if (failCoreReads) throw Exception("time mode unavailable")
            return DopplerTimeMode(timeMode = 12)
        }

        override suspend fun getUtcTime(): DopplerUtcTime {
            if (failCoreReads) throw Exception("utc time unavailable")
            return DopplerUtcTime(hour = 22, min = 2)
        }

        override suspend fun getWeather(): DopplerWeather {
            if (failWeatherRead) throw Exception("weather read timed out")
            return storedWeather
        }

        /**
         * Reads from [storedWakeup] and honours [failWakeupRead].
         *
         * Both matter: without the stored read the fallback path is never exercised, and
         * without the failure flag the assertion that the previous value survives is
         * satisfied by two identical successful reads and proves nothing.
         */
        override suspend fun getWeatherWakeupTime(): DopplerWeatherWakeupTime {
            if (failWakeupRead) throw Exception("weather wakeup read timed out")
            return DopplerWeatherWakeupTime(storedWakeup)
        }

        /**
         * Must read from [storedTimezone], not inherit MockPollApi's hardcoded value.
         *
         * Otherwise refresh() publishes a timezone that disagrees with what this mock
         * records, and the rollback assertion below would be checking a value the test
         * never actually seeded.
         */
        override suspend fun getTimezone() =
            if (failTimezoneRead) throw Exception("timezone read timed out")
            else DopplerTimezone(storedTimezone)

        override suspend fun setWeather(weather: DopplerWeather): DopplerWeather {
            if (failWrites) throw Exception("HTTP 500 from clock")
            writtenWeather = weather
            storedWeather = weather
            return weather
        }

        override suspend fun setWeatherWakeupTime(time: String): DopplerWeatherWakeupTime {
            if (failWrites) throw Exception("HTTP 500 from clock")
            storedWakeup = time
            return DopplerWeatherWakeupTime(time)
        }

        override suspend fun setTimezone(tz: String): DopplerTimezone {
            if (failWrites) throw Exception("HTTP 500 from clock")
            storedTimezone = tz
            return DopplerTimezone(tz)
        }

        override suspend fun executeConfirmRequest(path: String): String {
            confirmCalls++
            confirmOverride?.let { return it }
            return when (path) {
                "software/weather" ->
                    """{"wsonoff":${storedWeather.wsonoff},"location":"${storedWeather.location}","wsmode":${storedWeather.wsmode}}"""
                "software/weather-wakeup-time" -> """{"weatherwakeuptime":"$storedWakeup"}"""
                "doptime/timezone" -> """{"timezone":"$storedTimezone"}"""
                else -> "{}"
            }
        }
    }

    private lateinit var api: RecordingWeatherApi
    private lateinit var repository: DopplerRepository

    @Before
    fun setUp() {
        api = RecordingWeatherApi()
        repository = DopplerRepository(api)
    }

    private suspend fun loadedState(): DopplerDeviceState {
        repository.refresh()
        return repository.deviceState.value!!
    }

    // ---------- the read side ----------

    @Test
    fun `a poll publishes the weather settings the clock reports`() = runTest {
        val state = loadedState()

        assertTrue("wsonoff should be published", state.weatherEnabled)
        assertEquals("27.1258,-104.9118", state.weatherLocation)
        assertEquals(10, state.weatherMode)
        assertEquals("10:00", state.weatherWakeupTime)
        assertEquals("America/Chihuahua", state.clockTimezone)
    }

    @Test
    fun `a failed weather read keeps the last known values instead of showing defaults`() = runTest {
        // Seed with values that differ from every model default, so a fallback to
        // defaults cannot pass by coincidence.
        api.storedWeather = DopplerWeather(
            wsonoff = true,
            location = "19.4326,-99.1332",
            wsmode = 10
        )
        api.storedWakeup = "07:45"
        val before = loadedState()

        // Now the relay starts timing out, as it was measured doing.
        api.failWeatherRead = true
        api.failWakeupRead = true
        api.failTimezoneRead = true
        repository.refresh()
        val after = repository.deviceState.value!!

        assertEquals(before.weatherLocation, after.weatherLocation)
        assertEquals(before.weatherEnabled, after.weatherEnabled)
        assertEquals(before.weatherMode, after.weatherMode)
        assertEquals(before.weatherWakeupTime, after.weatherWakeupTime)
        assertEquals(before.clockTimezone, after.clockTimezone)
        // And specifically: the location is not the "" default.
        assertNotEquals("", after.weatherLocation)
        assertEquals("19.4326,-99.1332", after.weatherLocation)
        // The timezone must not silently revert to the model's own default either.
        assertNotEquals("America/Los_Angeles", after.clockTimezone)
        assertEquals("America/Chihuahua", after.clockTimezone)
    }

    @Test
    fun `a failed weather read does not mark an online clock as offline`() = runTest {
        loadedState()

        // refresh() gives up and reports the clock offline once four endpoints have
        // errored. Three core reads plus a flaky weather read is exactly one error
        // short of that, so if the weather read were counted the clock would be
        // declared offline while it is still answering.
        api.failCoreReads = true
        api.failWeatherRead = true
        api.failWakeupRead = true
        repository.refresh()

        val state = repository.deviceState.value!!
        assertTrue("the clock is still answering and must not be shown as offline", state.online)
        assertEquals(null, repository.lastError.value)
        // And the weather values survived the near-miss.
        assertEquals("27.1258,-104.9118", state.weatherLocation)
    }

    // ---------- the whole-object PUT ----------

    @Test
    fun `changing the location preserves the on-off switch and the reading`() = runTest {
        // wsmode 10 = current temperature in Celsius, wsonoff true. Both differ from the
        // model defaults (wsmode 1, wsonoff false), so sending defaults would be visible.
        loadedState()

        repository.updateWeatherLocation("19.4326,-99.1332")

        val sent = api.writtenWeather!!
        assertEquals("19.4326,-99.1332", sent.location)
        assertTrue("changing town must not switch the weather off", sent.wsonoff)
        assertEquals("changing town must not reset the reading", 10, sent.wsmode)
    }

    @Test
    fun `switching the weather on preserves the location and the reading`() = runTest {
        api.storedWeather = DopplerWeather(wsonoff = false, location = "19.4326,-99.1332", wsmode = 6)
        loadedState()

        repository.updateWeatherEnabled(true)

        val sent = api.writtenWeather!!
        assertTrue(sent.wsonoff)
        assertEquals("19.4326,-99.1332", sent.location)
        assertEquals(6, sent.wsmode)
    }

    @Test
    fun `switching the weather off preserves the location and the reading`() = runTest {
        api.storedWeather = DopplerWeather(wsonoff = true, location = "19.4326,-99.1332", wsmode = 6)
        loadedState()

        repository.updateWeatherEnabled(false)

        val sent = api.writtenWeather!!
        assertFalse(sent.wsonoff)
        assertEquals("19.4326,-99.1332", sent.location)
        assertEquals(6, sent.wsmode)
    }

    @Test
    fun `changing the reading preserves the location and the on-off switch`() = runTest {
        api.storedWeather = DopplerWeather(wsonoff = true, location = "19.4326,-99.1332", wsmode = 6)
        loadedState()

        repository.updateWeatherMode(4)

        val sent = api.writtenWeather!!
        assertEquals(4, sent.wsmode)
        assertEquals("19.4326,-99.1332", sent.location)
        assertTrue(sent.wsonoff)
    }

    @Test
    fun `the whole weather object is written, never a field in isolation`() = runTest {
        loadedState()
        val before = api.confirmCalls

        repository.updateWeatherMode(12)

        // A PUT of just {"wsmode":12} is not expressible on this endpoint.
        assertEquals(12, api.writtenWeather!!.wsmode)
        assertTrue(api.confirmCalls > before)
    }

    // ---------- rollback and confirmation ----------

    @Test
    fun `a rejected write rolls the optimistic location back`() = runTest {
        loadedState()
        val before = repository.deviceState.value!!

        api.failWrites = true
        try {
            repository.updateWeatherLocation("19.4326,-99.1332")
            fail("expected the write to surface as a failure")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("500"))
        }

        assertEquals(before.weatherLocation, repository.deviceState.value!!.weatherLocation)
        assertEquals("27.1258,-104.9118", repository.deviceState.value!!.weatherLocation)
    }

    @Test
    fun `a rejected read-mode change rolls back`() = runTest {
        loadedState()
        val before = repository.deviceState.value!!.weatherMode

        api.failWrites = true
        try {
            repository.updateWeatherMode(3)
            fail("expected the write to surface as a failure")
        } catch (e: Exception) {
            // expected
        }

        assertEquals(before, repository.deviceState.value!!.weatherMode)
        assertEquals(10, repository.deviceState.value!!.weatherMode)
    }

    @Test
    fun `the clock's confirmed value wins over the optimistic one`() = runTest {
        // The optimistic value is already what we sent, so this only proves anything if
        // the read-back disagrees. Simulates firmware refusing to take our value.
        loadedState()
        api.confirmOverride =
            """{"wsonoff":true,"location":"40.4168,-3.7038","wsmode":10}"""

        repository.updateWeatherLocation("19.4326,-99.1332")

        assertEquals(
            "the UI must show what the clock actually holds",
            "40.4168,-3.7038",
            repository.deviceState.value!!.weatherLocation
        )
    }

    @Test
    fun `a failed confirm does not undo an accepted write`() = runTest {
        loadedState()
        // A confirm read that cannot be parsed leaves the optimistic value in place.
        api.confirmOverride = "<html>gateway timeout</html>"

        repository.updateWeatherLocation("19.4326,-99.1332")

        assertEquals("19.4326,-99.1332", repository.deviceState.value!!.weatherLocation)
    }

    // ---------- forecast announcement time ----------

    @Test
    fun `the forecast announcement time round trips in the clock's HH mm form`() = runTest {
        loadedState()

        repository.updateWeatherWakeupTime("07:30")

        assertEquals("07:30", repository.deviceState.value!!.weatherWakeupTime)
        assertEquals("07:30", api.storedWakeup)
    }

    @Test
    fun `a rejected forecast time rolls back`() = runTest {
        val before = loadedState().weatherWakeupTime
        api.failWrites = true
        try {
            repository.updateWeatherWakeupTime("07:30")
            fail("expected a failure")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(before, repository.deviceState.value!!.weatherWakeupTime)
    }

    // ---------- timezone ----------

    @Test
    fun `the clock timezone can be corrected`() = runTest {
        api.storedTimezone = "Canada/Saskatchewan"
        loadedState()

        repository.updateClockTimezone("America/Chihuahua")

        assertEquals("America/Chihuahua", repository.deviceState.value!!.clockTimezone)
        assertEquals("America/Chihuahua", api.storedTimezone)
    }

    @Test
    fun `a rejected timezone write rolls back`() = runTest {
        api.storedTimezone = "Canada/Saskatchewan"
        loadedState()
        api.failWrites = true
        try {
            repository.updateClockTimezone("America/Chihuahua")
            fail("expected a failure")
        } catch (e: Exception) {
            // expected
        }
        assertEquals("Canada/Saskatchewan", repository.deviceState.value!!.clockTimezone)
    }
}