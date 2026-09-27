package com.sandman.doppler

import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DopplerProtocolTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var localApi: DopplerLocalApi
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        localApi = DopplerLocalApi(
            host = mockWebServer.hostName,
            port = mockWebServer.port
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testColorHexConversion() {
        val color = DopplerColor.fromHex("#00DCFF")
        assertEquals(0, color.r)
        assertEquals(220, color.g)
        assertEquals(255, color.b)
        assertEquals("#00DCFF", color.toHex())
    }

    @Test
    fun testAlarmModelSerialization() {
        val alarm = DopplerAlarm(
            id = 1,
            name = "Morning Wake",
            time = "06:30",
            repeat = listOf("Mo", "Tu", "We"),
            color = DopplerColor.AMBER,
            volume = 80,
            status = "set",
            sound = "Gentle.mp3"
        )
        val serialized = json.encodeToString(alarm)
        val deserialized = json.decodeFromString<DopplerAlarm>(serialized)

        assertEquals(1, deserialized.id)
        assertEquals("Morning Wake", deserialized.name)
        assertEquals("06:30", deserialized.time)
        assertTrue(deserialized.isEnabled)
        assertFalse(deserialized.isRinging)
    }

    @Test
    fun testGetDeviceStatusFromMockServer() = runBlocking {
        val mockJson = """
        {
            "id": "doppler-radar-01",
            "dsn": "Doppler-deadbeef",
            "name": "Radar (Master Bedroom)",
            "ipAddress": "192.168.1.142",
            "online": true,
            "masterVolume": 70,
            "dayDisplayBrightness": 85,
            "weatherLocation": "94301, USA"
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val status = localApi.getDeviceStatus("doppler-radar-01")
        assertEquals("doppler-radar-01", status.id)
        assertEquals("Doppler-deadbeef", status.dsn)
        assertEquals(70, status.masterVolume)
        assertEquals(85, status.dayDisplayBrightness)
    }

    @Test
    fun testTriggerLightBarEffectRequest() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success": true}"""))

        val effect = LightBarEffect(
            mode = "comet",
            color = DopplerColor.CYAN,
            speed = 40,
            duration = 15,
            direction = "right"
        )

        val success = localApi.triggerLightBarEffect("doppler-radar-01", effect)
        assertTrue(success)

        val recorded = mockWebServer.takeRequest()
        assertEquals("/api/devices/doppler-radar-01/services/activate_light_bar_comet", recorded.path)
        assertEquals("POST", recorded.method)
    }
}
