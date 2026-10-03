package com.sandman.doppler

import com.sandman.doppler.model.DopplerAlarm
import com.sandman.doppler.model.DopplerAlarmsResponse
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm write protocol, as the real hardware actually behaves.
 *
 * Every assertion here is anchored to something observed on a live clock (DSN
 * Doppler-10caaebb, Enter Sandman, firmware Escapement), not to the app's assumptions:
 *
 *  - `POST /alarms` is **create-only**. POSTing `{"id":200, ...}` came back as `id: 4`.
 *    POSTing `{"id":4, ...}` to change it produced a *new* `id: 5` and left 4 untouched.
 *  - `PUT /alarms/{id}` is the update. `PUT /alarms` with no id is 404.
 *  - `POST /alarms` responds with the **entire** alarm list, new alarm first, unsorted.
 *  - Active alarms report `status: 10`. The app modelled only `1`/`0`.
 *
 * [FaithfulClockApi] reproduces the create-only quirk, because a mock that treats POST as
 * an upsert cannot catch an app that wrongly depends on it.
 */
class AlarmWriteProtocolTest {

    /** Models the clock exactly as observed: POST always creates with a fresh id. */
    private class FaithfulClockApi(
        initial: List<DopplerAlarm> = emptyList()
    ) : MockPollApi() {
        val clockAlarms = initial.toMutableList()
        val posts = mutableListOf<DopplerAlarm>()
        val puts = mutableListOf<Int>()
        var nextAssignedId = (clockAlarms.maxOfOrNull { it.id } ?: 0) + 1

        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun getAlarms(): List<DopplerAlarm> = clockAlarms.toList()

        /** Create-only, and ignores the id in the body. This is the whole point. */
        override suspend fun createAlarm(alarm: DopplerAlarm): String {
            posts += alarm
            val assigned = nextAssignedId++
            clockAlarms.add(0, alarm.copy(id = assigned))
            return json.encodeToString(DopplerAlarmsResponse(clockAlarms.toList()))
        }

        override suspend fun updateAlarm(alarm: DopplerAlarm): String {
            puts += alarm.id
            val idx = clockAlarms.indexOfFirst { it.id == alarm.id }
            if (idx >= 0) clockAlarms[idx] = alarm
            return ""
        }

        override suspend fun deleteAlarm(alarmId: Int): String {
            clockAlarms.removeAll { it.id == alarmId }
            return ""
        }
    }

    private fun alarm(id: Int, hour: Int = 7) = DopplerAlarm(id = id, name = "A$id", time_hr = hour)

    /**
     * The core regression. Editing an existing alarm used to POST, which the clock treats as
     * a create - so every edit left a duplicate behind. This is why the probed clock held two
     * byte-identical alarms, ids 2 and 3, both at 13:15.
     */
    @Test
    fun `editing an existing alarm updates it instead of creating a duplicate`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 2, hour = 9))

        assertTrue("an edit must never POST", api.posts.isEmpty())
        assertEquals("an edit must PUT exactly the edited alarm", listOf(2), api.puts)
        assertEquals("no duplicate may be created", 1, api.clockAlarms.size)
        assertEquals(9, api.clockAlarms.single().time_hr)
    }

    @Test
    fun `a brand new alarm is created rather than updated`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 1, hour = 6))

        assertTrue("a new alarm must POST", api.posts.isNotEmpty())
        assertTrue("a new alarm must not PUT a nonexistent id", api.puts.isEmpty())
    }

    /**
     * The clock assigns its own id, so the app must adopt it. Otherwise the entry the user
     * just created is shown under an id the device never issued, and one poll later it is
     * replaced by a differently-numbered alarm - the alarm appears to vanish and reappear.
     */
    @Test
    fun `the id the clock assigns is adopted rather than the invented one`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 1, hour = 6))

        val assigned = api.posts.single().let { api.clockAlarms.first { it.time_hr == 6 }.id }
        assertEquals("the clock issues ids sequentially, ignoring ours", 3, assigned)

        // Nothing may be left behind under the id we invented.
        assertFalse(
            "the invented id must not survive in app state",
            repository.deviceState.value!!.alarms.any { it.id == 1 }
        )
        assertTrue(
            "the clock's id must be what the app shows",
            repository.deviceState.value!!.alarms.any { it.id == assigned }
        )
    }

    /**
     * The clock this was found on holds two alarms identical apart from id. Any adoption
     * strategy that guesses by matching content can therefore adopt the wrong one, quietly
     * moving a user's alarm onto a different alarm's identity.
     */
    @Test
    fun `adoption does not confuse two alarms that are identical apart from id`(): Unit = runTest {
        val existing = alarm(id = 2, hour = 13)
        val api = FaithfulClockApi(listOf(existing, existing.copy(id = 3)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 1, hour = 5))

        val state = repository.deviceState.value!!.alarms
        assertEquals("nothing may be lost or duplicated", 3, state.size)
        assertEquals(
            "the new alarm must be present exactly once",
            1,
            state.count { it.time_hr == 5 }
        )
    }

    /** Real hardware reports 10 for active. Modelled as 1, every alarm looked switched off. */
    @Test
    fun `status 10 from real hardware reads as enabled`() {
        assertTrue(DopplerAlarm(id = 2, status = 10).isEnabled)
        assertTrue(DopplerAlarm(id = 2, status = 1).isEnabled)
        assertFalse(DopplerAlarm(id = 2, status = 0).isEnabled)
    }

    @Test
    fun `an alarm parsed from a real payload is enabled`() {
        // Verbatim from GET /alarms on the live clock.
        val raw = """{"id":2,"name":"","time_hr":13,"time_min":15,"repeat":"",""" +
            """"color":{"red":0,"green":220,"blue":255},"volume":100,"status":10,""" +
            """"src":1,"sound":"Harp.mp3","next_trigger":-1}"""
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString<DopplerAlarm>(raw)

        assertEquals(10, parsed.status)
        assertTrue("a real alarm must not render as disabled", parsed.isEnabled)
        assertEquals("13:15", parsed.timeFormatted)
        assertFalse(parsed.isSystemAlarm)
    }
}