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
 * Every assertion here is anchored to something measured on a live clock (DSN
 * Doppler-10caaebb, Enter Sandman, firmware Escapement / 0.1214), by arming real alarms and
 * listening to them:
 *
 *  - `status: 1` is **armed**. The clock lit its "alarm armed" LED and rang.
 *  - `status: 10` is **unarmed**. No LED, no ring. Every alarm on the unit reported 10.
 *  - Observed lifecycle: `1` armed -> `3` activating -> `4` active (ringing) -> `6` snoozed.
 *  - `POST /alarms` is **create-only** and the clock assigns its own id.
 *  - `PUT /alarms/{id}` **does not work**: `404 "alarm not found"` for every alarm, including
 *    a byte-identical no-op write and including one that was ringing at the time.
 *  - `DELETE /alarms/{id}` **does** work. Same path shape as the failing PUT, so the id lookup
 *    is fine and only the update handler is broken.
 *
 * So replacing an alarm is a delete followed by a create, and the replacement lands under a
 * new id. That is what [FaithfulClockApi] models, and it refuses to emulate a working PUT -
 * a mock that silently succeeds at updates cannot catch an app that depends on one.
 */
class AlarmWriteProtocolTest {

    /**
     * Models the clock exactly as observed.
     *
     * [updateAlarm] deliberately returns the hardware's real answer. Any test that reaches it
     * is asserting against a route the clock does not implement.
     */
    private class FaithfulClockApi(
        initial: List<DopplerAlarm> = emptyList()
    ) : MockPollApi() {
        val clockAlarms = initial.toMutableList()
        val posts = mutableListOf<DopplerAlarm>()
        val deletes = mutableListOf<Int>()
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

        /** The clock rejects this with 404 "alarm not found". Recorded so tests can prove it. */
        override suspend fun updateAlarm(alarm: DopplerAlarm): String {
            puts += alarm.id
            throw IllegalStateException("PUT /alarms/${alarm.id} -> 404 alarm not found")
        }

        override suspend fun deleteAlarm(alarmId: Int): String {
            deletes += alarmId
            clockAlarms.removeAll { it.id == alarmId }
            return ""
        }
    }

    private fun alarm(id: Int, hour: Int = 7, status: Int = DopplerAlarm.STATUS_ARMED) =
        DopplerAlarm(id = id, name = "A$id", time_hr = hour, status = status)

    /**
     * An edit must delete then create, never PUT.
     *
     * The old expectation here was "an edit must PUT exactly the edited alarm", which is
     * precisely the thing the hardware does not support. The duplicate-alarm regression that
     * test guarded against is still guarded - by asserting the count instead.
     */
    @Test
    fun `editing an existing alarm replaces it without leaving a duplicate`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 2, hour = 9))

        assertEquals("an edit must delete the old alarm", listOf(2), api.deletes)
        assertEquals("an edit must never PUT", emptyList<Int>(), api.puts)
        assertEquals("no duplicate may be left behind", 1, api.clockAlarms.size)
        assertEquals(9, api.clockAlarms.single().time_hr)
    }

    @Test
    fun `a brand new alarm is created rather than replaced`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 1, hour = 6))

        assertTrue("a new alarm must POST", api.posts.isNotEmpty())
        assertTrue("a new alarm must not delete anything", api.deletes.isEmpty())
        assertEquals("a new alarm must not PUT", emptyList<Int>(), api.puts)
    }

    /**
     * The clock assigns its own id, so the app must adopt it - including after a replacement,
     * where the id necessarily changes.
     */
    @Test
    fun `the id the clock assigns is adopted rather than the invented one`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 1, hour = 6))

        val assigned = api.clockAlarms.first { it.time_hr == 6 }.id
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
     * A replacement gets a new id. The app must show the alarm under the id the clock issued,
     * never the old one, or the alarm appears to vanish and reappear under a different
     * number - the identity bug this adoption logic exists to prevent.
     */
    @Test
    fun `a replaced alarm is shown under its new id not the old one`(): Unit = runTest {
        val api = FaithfulClockApi(listOf(alarm(id = 2, hour = 7)))
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.addOrUpdateAlarm(alarm(id = 2, hour = 9))

        val state = repository.deviceState.value!!.alarms
        assertEquals("the old id must be gone from app state", 0, state.count { it.id == 2 })
        assertEquals("the alarm must appear exactly once", 1, state.count { it.time_hr == 9 })
        assertEquals("and under the id the clock issued", 3, state.single { it.time_hr == 9 }.id)
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

    // ---------------------------------------------------------------------------------
    // Status semantics, measured by arming alarms and listening to them.
    //
    // These previously asserted that 10 was the *enabled* value. That was wrong: an alarm
    // created with 10 never rang and never lit the armed LED, while one created with 1 did
    // both. The consequence in the app was severe - the toggle wrote 10 to switch an alarm
    // ON, so alarms switched on in the app never rang.
    // ---------------------------------------------------------------------------------

    @Test
    fun `status 1 is armed and 10 is unarmed`() {
        assertTrue("1 is armed", DopplerAlarm(id = 2, status = 1).isEnabled)
        assertFalse("10 is unarmed", DopplerAlarm(id = 2, status = 10).isEnabled)
        assertFalse("0 is unset", DopplerAlarm(id = 2, status = 0).isEnabled)
    }

    @Test
    fun `every live state in the ringing lifecycle counts as enabled`() {
        // 3 activating, 4 active, 5 snoozing, 6 snoozed - all states of an alarm the user
        // has switched on. Treating any of them as off would dim a real, working alarm.
        listOf(1, 2, 3, 4, 5, 6, 7, 8, 9).forEach { status ->
            assertTrue("status $status is a live state", DopplerAlarm(id = 2, status = status).isEnabled)
        }
    }

    @Test
    fun `an alarm parsed from a real payload reads as disabled`() {
        // Verbatim from GET /alarms on the live clock. The clock reported 10 for every alarm,
        // so the app was showing real, silent, disarmed alarms as enabled.
        val raw = """{"id":2,"name":"","time_hr":13,"time_min":15,"repeat":"",""" +
            """"color":{"red":0,"green":220,"blue":255},"volume":100,"status":10,""" +
            """"src":1,"sound":"Harp.mp3","next_trigger":-1}"""
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString<DopplerAlarm>(raw)

        assertEquals(10, parsed.status)
        assertFalse("a disarmed real alarm must not render as enabled", parsed.isEnabled)
        assertEquals("13:15", parsed.timeFormatted)
        assertFalse(parsed.isSystemAlarm)
    }

    @Test
    fun `a new alarm defaults to armed so it will actually ring`() {
        // The default is what an alarm the user just added is created with. It must be the
        // value that rings.
        assertEquals(DopplerAlarm.STATUS_ARMED, DopplerAlarm(id = 9).status)
        assertTrue(DopplerAlarm(id = 9).isEnabled)
    }
}