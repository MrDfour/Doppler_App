package com.sandman.doppler

import com.sandman.doppler.api.*
import com.sandman.doppler.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class DopplerProtocolTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var localApi: DopplerLocalApi
    private val testDsn = "Doppler-12345678"
    private val testLocalKey = "Wbe1pTYzkOHPsRdYGWtiJAd1FzgShOmBoRgzcbioOZs0Ynuc7i0WtS40BD9rVhSEMJY"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Standard OkHttpClient for MockWebServer tests
        val httpClient = OkHttpClient.Builder().build()
        localApi = DopplerLocalApi(
            host = mockWebServer.hostName,
            port = mockWebServer.port,
            dsn = testDsn,
            localKey = testLocalKey,
            customClient = httpClient
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testColorHexAndListConversion() {
        val color = DopplerColor.fromHex("#00DCFF")
        assertEquals(0, color.r)
        assertEquals(220, color.g)
        assertEquals(255, color.b)
        assertEquals("#00DCFF", color.toHex())
        assertEquals(listOf(0, 220, 255), color.toList())

        val reconstructed = DopplerColor.fromList(listOf(16, 220, 120))
        assertEquals(16, reconstructed.r)
        assertEquals(220, reconstructed.g)
        assertEquals(120, reconstructed.b)
    }

    @Test
    fun testLocalTokenGenerationAlgorithm() {
        val nonce = "j0BraaWp+TH5RJbNIhFM6lPsxmQ="
        val tokenManager = LocalTokenManager(OkHttpClient())
        val token = tokenManager.calculateLocalToken(nonce, testLocalKey)

        assertTrue("Token must start with nonce + '|'", token.startsWith("$nonce|"))

        // Recompute expected hash independently
        val expectedSha = nonce + testLocalKey
        val digest = MessageDigest.getInstance("SHA-256").digest(expectedSha.toByteArray(Charsets.UTF_8))
        val expectedBase64 = Base64.getEncoder().encodeToString(digest)
        val expectedToken = "$nonce|$expectedBase64"

        assertEquals(expectedToken, token)
    }

    @Test
    fun testLanTrustManagerHostClassification() {
        assertTrue(LanTrustManager.isLocalOrDopplerHost("localhost"))
        assertTrue(LanTrustManager.isLocalOrDopplerHost("127.0.0.1"))
        assertTrue(LanTrustManager.isLocalOrDopplerHost("10.0.2.2"))
        assertTrue(LanTrustManager.isLocalOrDopplerHost("192.168.1.142"))
        assertTrue(LanTrustManager.isLocalOrDopplerHost("10.0.0.50"))
        assertTrue(LanTrustManager.isLocalOrDopplerHost("172.20.10.2"))
        assertTrue(LanTrustManager.isLocalOrDopplerHost("Doppler-1234.local"))

        assertFalse(LanTrustManager.isLocalOrDopplerHost("8.8.8.8"))
        assertFalse(LanTrustManager.isLocalOrDopplerHost("1.1.1.1"))
    }

    @Test
    fun testAlarmModelSerialization() {
        val alarm = DopplerAlarm(
            id = 1,
            name = "Morning Wake",
            time_hr = 6,
            time_min = 30,
            repeat = "MoTuWe",
            color = DopplerColor.AMBER.toColorObject(),
            volume = 80,
            status = 1,
            sound = "Gentle.mp3",
            src = 1
        )
        val serialized = json.encodeToString(alarm)
        val deserialized = json.decodeFromString<DopplerAlarm>(serialized)

        assertEquals(1, deserialized.id)
        assertEquals("Morning Wake", deserialized.name)
        assertEquals(6, deserialized.time_hr)
        assertEquals(30, deserialized.time_min)
        assertEquals("06:30", deserialized.timeFormatted)
        assertEquals(listOf("Mo", "Tu", "We"), deserialized.repeatDaysList)
        assertTrue(deserialized.isEnabled)
        assertFalse(deserialized.isSystemAlarm)
    }

    @Test
    fun testAuthenticatedVolumeRequestWithNonceHandshake() = runBlocking {
        val testNonce = "mock-nonce-abc-123"

        // Step 1: MockWebServer returns nonce for GET /<dsn>/nonce
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"nonce":"$testNonce"}""")
        )

        // Step 2: MockWebServer returns volume for GET /<dsn>/hardware/volume
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"volume":80}""")
        )

        val volume = localApi.getVolume()
        assertEquals(80, volume.volume)

        // Verify request sequence
        val nonceReq = mockWebServer.takeRequest()
        assertEquals("/$testDsn/nonce", nonceReq.path)
        assertEquals("GET", nonceReq.method)

        val volumeReq = mockWebServer.takeRequest()
        assertEquals("/$testDsn/hardware/volume", volumeReq.path)
        val authHeader = volumeReq.getHeader("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Bearer $testNonce|"))
    }

    @Test
    fun testNonceExpirationAndAutoRetryOnHttp410() = runBlocking {
        val initialNonce = "stale-nonce-001"
        val freshNonce = "fresh-nonce-002"

        // 1. Initial nonce fetch
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"nonce":"$initialNonce"}""")
        )

        // 2. Clock rejects request with 410 Gone (expired nonce)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setBody("""{"error":"GONE","message":"Nonce no longer valid"}""")
        )

        // 3. Api automatically fetches new nonce
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"nonce":"$freshNonce"}""")
        )

        // 4. Retried request succeeds
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"volume":85}""")
        )

        val volume = localApi.getVolume()
        assertEquals(85, volume.volume)

        assertEquals(4, mockWebServer.requestCount)
    }

    @Test
    fun testGetDeviceInfoAndWifiStatus() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"nonce":"n1"}"""))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                {
                    "mfgrName": "Palo Alto Innovation",
                    "modelNum": "SandmanDopplerProduction",
                    "serialNum": "Doppler-12345678",
                    "firmware": "Escapement",
                    "hardware": "Enter Sandman",
                    "software": "0.1544 Bucky"
                }
                """.trimIndent())
        )

        val info = localApi.getDeviceInfo()
        assertEquals("Palo Alto Innovation", info.mfgrName)
        assertEquals("Doppler-12345678", info.serialNum)
        assertEquals("Escapement", info.firmware)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"uptime": 13430808, "ssid": "Home_WiFi", "str": 92}""")
        )

        val wifi = localApi.getWifiStatus()
        assertEquals("Home_WiFi", wifi.ssid)
        assertEquals(92, wifi.str)
    }

    @Test
    fun testHighDisplayColorPutAndGet() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"nonce":"n1"}"""))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"color": [0, 220, 255]}""")
        )

        val updatedColor = localApi.setHighDisplayColor(DopplerColor.CYAN)
        assertEquals(0, updatedColor.r)
        assertEquals(220, updatedColor.g)
        assertEquals(255, updatedColor.b)

        val req = mockWebServer.takeRequest() // nonce
        val putReq = mockWebServer.takeRequest()
        assertEquals("/$testDsn/hardware/high-display-color", putReq.path)
        assertEquals("PUT", putReq.method)
        assertTrue(putReq.body.readUtf8().contains("[0,220,255]") || putReq.body.readUtf8().contains("[0, 220, 255]"))
    }
}
