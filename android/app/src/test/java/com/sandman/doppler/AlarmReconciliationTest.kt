package com.sandman.doppler

import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.model.DopplerAlarm
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the alarm disappearing from the app while the clock still fired it.
 *
 * Observed on device: adding an alarm made the clock sound at the configured hour, but the
 * alarm only appeared in the app after it had already triggered. Cause: the clock's
 * `GET /alarms` does not include a freshly written alarm immediately, and the `refresh()`
 * that runs after every write replaced the optimistic list with the clock's stale one.
 */
class AlarmReconciliationTest {

    /**
     * Extends the full 22-endpoint mock so `refresh()` does not trip its
     * "4 or more errors" bail-out, and overrides only the alarm behaviour under test.
     */
    private open class FakeApi(
        /** What the clock's own alarm list currently contains. */
        var clockAlarms: List<DopplerAlarm> = emptyList(),
        private val echoWritesImmediately: Boolean = false
    ) : MockDopplerLocalApi() {
        var writeCount = 0

        override suspend fun getAlarms(): List<DopplerAlarm> = clockAlarms

        override suspend fun createOrUpdateAlarm(alarm: DopplerAlarm): String {
            writeCount++
            if (echoWritesImmediately) {
                clockAlarms = clockAlarms.filterNot { it.id == alarm.id } + alarm
            }
            return ""
        }

        override suspend fun deleteAlarm(alarmId: Int): String {
            clockAlarms = clockAlarms.filterNot { it.id == alarmId }
            return ""
        }
    }

    /** The 22-endpoint poll mock from [DopplerRepositoryTest], reused so refresh() succeeds. */
    private open class MockDopplerLocalApi : com.sandman.doppler.api.DopplerLocalApi(
        host = "192.168.1.100",
        port = 5443,
        dsn = "Doppler-12345678"
    ) {
        override suspend fun getDeviceInfo() =
            com.sandman.doppler.model.DopplerDeviceInfo(
                mfgrName = "Palo Alto Innovation",
                modelNum = "SandmanDopplerTest",
                firmware = "v1.0.0"
            )

        override suspend fun getWifiStatus() =
            com.sandman.doppler.model.DopplerWifiStatus(uptime = 3600000L, ssid = "TestNetwork", str = 75)

        override suspend fun getUtcTime() =
            com.sandman.doppler.model.DopplerUtcTime(hour = 14, min = 30)
        override suspend fun getTimeMode() = com.sandman.doppler.model.DopplerTimeMode(timeMode = 24)
        override suspend fun getUseColon() = com.sandman.doppler.model.DopplerUseColon(true)
        override suspend fun getColonBlink() = com.sandman.doppler.model.DopplerColonBlink(true)
        override suspend fun getVolume() = com.sandman.doppler.model.DopplerVolume(75)
        override suspend fun getSoundPreset() = com.sandman.doppler.model.DopplerSoundPreset("Flat")
        override suspend fun getAscendingVolume() = com.sandman.doppler.model.DopplerAscending(true)
        override suspend fun getLightSensor() = com.sandman.doppler.model.DopplerLightSensor(100)
        override suspend fun getDayMode() = com.sandman.doppler.model.DopplerDayMode(true)
        override suspend fun getHighDisplayColor() = com.sandman.doppler.model.DopplerColor.CYAN
        override suspend fun getLowDisplayColor() = com.sandman.doppler.model.DopplerColor.DEEP_RED
        override suspend fun getHighDisplayBrightness() = com.sandman.doppler.model.DopplerBrightness(85)
        override suspend fun getLowDisplayBrightness() = com.sandman.doppler.model.DopplerBrightness(25)
        override suspend fun getHighButtonColor() = com.sandman.doppler.model.DopplerColor.CYAN
        override suspend fun getLowButtonColor() = com.sandman.doppler.model.DopplerColor.DEEP_RED
        override suspend fun getHighButtonBrightness() = com.sandman.doppler.model.DopplerBrightness(80)
        override suspend fun getLowButtonBrightness() = com.sandman.doppler.model.DopplerBrightness(20)
        override suspend fun getSyncButtonDisplayBrightness() = com.sandman.doppler.model.DopplerSync(true)
        override suspend fun getSyncHighLowColor() = com.sandman.doppler.model.DopplerSync(false)
        override suspend fun getSyncButtonDisplayColor() = com.sandman.doppler.model.DopplerSync(true)
        override suspend fun getHighToLowTransition() =
            com.sandman.doppler.model.DopplerHighToLowTransition(35)
        override suspend fun getLowToHighTransition() =
            com.sandman.doppler.model.DopplerLowToHighTransition(45)

        override suspend fun getAlarms(): List<DopplerAlarm> = emptyList()

        override suspend fun getAlarmSounds(): List<String> = emptyList()
    }

    private fun alarm(id: Int, hour: Int = 7) = DopplerAlarm(id = id, name = "A$id", time_hr = hour)

    @Test
    fun `an alarm stays visible while the clock has not echoed it back`(): Unit = runTest {
        val api = FakeApi(echoWritesImmediately = false)
        val repository = DopplerRepository(api)

        repository.addOrUpdateAlarm(alarm(id = 3, hour = 6))

        // The clock accepted the write and will fire it, but its list is still empty.
        assertEquals(1, api.writeCount)
        assertTrue(api.clockAlarms.isEmpty())

        // Every poll from here on must not delete the alarm the user just created.
        repeat(5) { repository.refresh() }

        val shown = repository.deviceState.value!!.alarms
        assertEquals("the locally written alarm must survive a stale read-back", 1, shown.size)
        assertEquals(3, shown[0].id)
        assertEquals(6, shown[0].time_hr)
    }

    @Test
    fun `the clock's version wins once it confirms the alarm`(): Unit = runTest {
        val api = FakeApi(echoWritesImmediately = false)
        val repository = DopplerRepository(api)

        repository.addOrUpdateAlarm(alarm(id = 3, hour = 6))

        // The clock catches up, but with its own authoritative copy of the alarm.
        api.clockAlarms = listOf(alarm(id = 3, hour = 5))
        repository.refresh()

        val shown = repository.deviceState.value!!.alarms
        assertEquals(1, shown.size)
        assertEquals("clock value must win over the shielded local copy", 5, shown[0].time_hr)
    }

    @Test
    fun `a confirmed alarm is no longer shielded, so a later clock removal sticks`(): Unit = runTest {
        val api = FakeApi(echoWritesImmediately = true)
        val repository = DopplerRepository(api)

        repository.addOrUpdateAlarm(alarm(id = 3))
        assertEquals(1, repository.deviceState.value!!.alarms.size)

        // Alarm deleted elsewhere (e.g. on the clock itself). Because it was confirmed,
        // the app must accept the clock's empty list rather than re-asserting it.
        api.clockAlarms = emptyList()
        repository.refresh()

        assertTrue(repository.deviceState.value!!.alarms.isEmpty())
    }

    @Test
    fun `deleting an alarm is not shielded and disappears immediately`(): Unit = runTest {
        val api = FakeApi(echoWritesImmediately = true)
        val repository = DopplerRepository(api)

        repository.addOrUpdateAlarm(alarm(id = 3))
        repository.deleteAlarm(3)

        assertTrue(repository.deviceState.value!!.alarms.isEmpty())
    }

    @Test
    fun `other alarms from the clock are preserved alongside a shielded one`(): Unit = runTest {
        val api = FakeApi(clockAlarms = listOf(alarm(id = 1), alarm(id = 2)))
        val repository = DopplerRepository(api)

        repository.addOrUpdateAlarm(alarm(id = 3))

        val ids = repository.deviceState.value!!.alarms.map { it.id }.sorted()
        assertEquals(listOf(1, 2, 3), ids)
    }

    @Test
    fun `a failed write does not leave the alarm shielded`(): Unit = runTest {
        val api = object : FakeApi(echoWritesImmediately = false) {
            override suspend fun createOrUpdateAlarm(alarm: DopplerAlarm): String =
                throw IllegalStateException("clock refused the write")
        }
        val repository = DopplerRepository(api)

        runCatching { repository.addOrUpdateAlarm(alarm(id = 3)) }

        // The optimistic update must have been rolled back and nothing shielded, so a
        // later poll cannot resurrect an alarm the clock rejected outright.
        assertTrue(repository.deviceState.value?.alarms.isNullOrEmpty())
        repository.refresh()
        assertTrue(repository.deviceState.value!!.alarms.isEmpty())
    }
}
