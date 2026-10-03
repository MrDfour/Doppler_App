package com.sandman.doppler

import com.sandman.doppler.api.DopplerCloudApi
import com.sandman.doppler.api.DopplerException
import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.api.EndpointCapabilities
import com.sandman.doppler.model.DopplerColonBlink
import com.sandman.doppler.model.DopplerDeviceState
import com.sandman.doppler.model.DopplerSoundPreset
import com.sandman.doppler.model.DopplerDeviceInfo
import com.sandman.doppler.model.DopplerTimeMode
import com.sandman.doppler.model.DopplerUtcTime
import com.sandman.doppler.model.DopplerWifiStatus
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * How the app behaves around the endpoints that never answer.
 *
 * Six of the twenty-two polled endpoints stall until HTTP 408 at ~15s on real hardware
 * (probed 2026-10-03, DSN `Doppler-10caaebb`). Serialized requests turned that into ~90s of
 * dead time per poll cycle. [EndpointCapabilitiesTest] covers the tracking logic; this file
 * covers the two things that actually reach the user:
 *
 * - the requests are genuinely **not sent**, so the time is really saved;
 * - the app **says so** instead of showing the model default as though it were a reading.
 */
class UnavailableEndpointHandlingTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DopplerLocalApi
    private val testDsn = "Doppler-12345678"
    private val testLocalKey = "test-local-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DopplerLocalApi(
            host = server.hostName,
            port = server.port,
            dsn = testDsn,
            localKey = testLocalKey,
            customClient = OkHttpClient.Builder().build(),
            useTls = false
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun nonce() = MockResponse().setResponseCode(200).setBody("""{"nonce":"n1"}""")

    /**
     * Serves [code] for every endpoint except `nonce`.
     *
     * A dispatcher rather than an enqueued queue, because OkHttp treats 408 as retryable and
     * re-issues the request - which drains a finite queue and turns a protocol assertion into a
     * connection timeout. This way the clock can keep answering 408 for as long as the client
     * insists, which is also what the real relay does.
     */
    private fun failEverythingWith(code: Int) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path?.endsWith("/nonce") == true) nonce()
                else MockResponse().setResponseCode(code).setBody("""{"error":"unavailable"}""")
        }
    }

    // ---------- the saving is real: nothing goes on the wire ----------

    @Test
    fun `a measured-broken endpoint is never requested`() = runBlocking {
        // No responses are enqueued. If the skip did not work, the request would be made and
        // this would fail on a 404 rather than on the exception type.
        try {
            api.getWifiStatus()
            fail("expected UnavailableException, but the read was attempted")
        } catch (e: DopplerException.UnavailableException) {
            assertEquals("hardware/wifi-status", e.path)
        }
        assertEquals("nothing may be sent to the clock", 0, server.requestCount)
    }

    @Test
    fun `a skipped read surfaces as unavailable rather than as a default value`() = runBlocking {
        // The silent-default trap: had this returned DopplerWifiStatus(), the UI would show a
        // fabricated 0 dBm / 0 uptime that looks like a real reading.
        val thrown = try {
            api.getWifiStatus()
            null
        } catch (e: DopplerException.UnavailableException) {
            e
        }
        assertTrue("must not silently fall back to a default", thrown != null)
    }

    // ---------- writable but unreadable: the asymmetry that matters ----------

    @Test
    fun `use-colon can still be written although its read is skipped`() = runBlocking {
        // Measured 2026-10-03: `PUT software/use-colon {"on":true}` returned 200 in ~400ms while
        // `GET` on the same path never returned. Skipping reads must not disable the write, or
        // the colon toggle the user can see in Settings would stop working.
        server.enqueue(nonce())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"on":true}"""))

        val result = api.setUseColon(true)
        assertTrue(result.on)

        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("/$testDsn/software/use-colon", put.path)
        assertEquals("PUT", put.method)
    }

    // ---------- what counts as evidence a path is dead ----------

    @Test
    fun `a 408 disables a path after two healthy cycles`() = runBlocking {
        // 408 is the clock saying a handler produced no response, which is exactly the
        // measured failure of the six endpoints.
        failEverythingWith(408)
        repeat(2) {
            try {
                api.getSoundPreset()
            } catch (e: DopplerException.ProtocolException) {
                assertEquals(408, e.httpCode)
            }
            api.capabilities.endPollCycle(healthy = true)
        }
        assertTrue("408 twice on a healthy link means dead", api.capabilities.shouldSkip("hardware/sound-preset"))

        // And it is no longer requested.
        val before = server.requestCount
        try {
            api.getSoundPreset()
            fail("expected the path to be skipped")
        } catch (e: DopplerException.UnavailableException) {
            assertEquals("hardware/sound-preset", e.path)
        }
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `a rejected request does not disable the path`() = runBlocking {
        // HTTP 500 means the route answered and refused. That is a property of the request, not
        // evidence the endpoint is gone, so the path must stay enabled - otherwise a single
        // bad payload would permanently disable a working control.
        failEverythingWith(500)
        repeat(5) {
            try {
                api.getSoundPreset()
            } catch (e: DopplerException.ProtocolException) {
                assertEquals(500, e.httpCode)
            }
            api.capabilities.endPollCycle(healthy = true)
        }
        assertFalse(
            "a refusal is not a dead endpoint",
            api.capabilities.shouldSkip("hardware/sound-preset")
        )
    }

    @Test
    fun `a give-up during an unhealthy cycle is not committed`() = runBlocking {
        // The outage case, at the transport level: repeated 408s while the caller reports the
        // link is down must not disable anything, or one dropout would end every feature.
        failEverythingWith(408)
        repeat(6) {
            try {
                api.getSoundPreset()
            } catch (e: DopplerException.ProtocolException) {
                // expected
            }
            api.capabilities.endPollCycle(healthy = false)
        }
        assertFalse(api.capabilities.shouldSkip("hardware/sound-preset"))
        assertFalse(api.capabilities.shouldSkip("hardware/volume"))
    }

    // ---------- the cloud transport behaves identically ----------

    @Test
    fun `the cloud transport skips the same endpoints`() = runBlocking {
        // DopplerCloudApi only overrides executeAuthenticatedRequest, so the skip in
        // executePollRequest has to apply to it too - otherwise cloud mode, which is the
        // transport this user is actually on, would keep paying the full 90s.
        // Point the control path at the stub; production hardcodes the real domain.
        // No explicit return type on the anonymous subclass, so the overridden buildUrl -
        // which is protected on DopplerCloudApi - can be widened here.
        val cloud = object : DopplerCloudApi(
            host = server.hostName,
            port = server.port,
            dsn = testDsn,
            cloudAccessToken = "token"
        ) {
            override fun buildUrl(path: String): String = "http://${server.hostName}:${server.port}/$testDsn/$path"
        }

        assertEquals(6, cloud.capabilities.unavailablePaths.size)
        try {
            cloud.getWifiStatus()
            fail("expected UnavailableException from the cloud transport")
        } catch (e: DopplerException.UnavailableException) {
            assertEquals("hardware/wifi-status", e.path)
        }
        assertEquals("cloud mode must not send it either", 0, server.requestCount)
    }

    // ---------- what the user is shown ----------

    @Test
    fun `an unanswered endpoint is not counted towards the offline threshold`() = runBlocking {
        // refresh() marks the whole device offline once four reads error. On real hardware
        // wifi-status is *permanently* unanswered, so counting it means a cycle that also has
        // three genuine failures - an ordinary flaky cloud moment - takes the clock offline for
        // a reason the user cannot act on. Three real errors must stay below the threshold
        // whether or not the dead endpoint is counted.
        val repository = DopplerRepository(MockPollApiWithThreeErrorsAndOneUnavailable())
        repository.refresh()

        val state = requireNotNull(repository.deviceState.value)
        assertTrue("three genuine errors must not take the clock offline", state.online)
    }

    @Test
    fun `an unanswered endpoint is published so the UI can say unavailable`() = runBlocking {
        // Uses a path that is NOT in the measured seed, so this cannot pass merely because
        // the seed happens to contain the wifi endpoint.
        val api = MockPollApiWithLearnedUnavailable()
        val repository = DopplerRepository(api)
        repository.refresh()

        val state = requireNotNull(repository.deviceState.value)
        assertTrue(
            "a learned unavailable path must reach the UI",
            "software/colon-blink-x" in state.unavailableEndpoints
        )
        assertEquals("state must mirror the capability set exactly", api.capabilities.unavailablePaths, state.unavailableEndpoints)
    }

    @Test
    fun `an unanswered endpoint does not blank the rest of the cycle`() = runBlocking {
        // Guards the opposite failure: treating an unavailable endpoint as fatal for the whole
        // refresh. The remaining twenty-one reads still have real values, and discarding them
        // because one control is unsupported would leave the dashboard on model defaults.
        //
        // Asserted against hour 14, which is what the mock returns and NOT the model default
        // of 12 - most MockPollApi values coincide with their model default, which is exactly
        // the silent-default trap, and an assertion on those would pass either way.
        val repository = DopplerRepository(MockPollApiWithLearnedUnavailable())
        repository.refresh()

        val state = requireNotNull(repository.deviceState.value)
        assertEquals("a healthy endpoint must still contribute its real value", 14, state.currentUtcHour)
        assertEquals(30, state.currentUtcMin)
    }

    // ---------- the contract the UI depends on ----------

    @Test
    fun `lacks reports every endpoint the UI must distrust`() {
        // DopplerDeviceState.lacks is what stands between a model default and the screen, so
        // it has to answer true for all six measured paths.
        val state = DopplerDeviceState(
            unavailableEndpoints = EndpointCapabilities.MEASURED_UNAVAILABLE
        )
        EndpointCapabilities.MEASURED_UNAVAILABLE.forEach { path ->
            assertTrue("UI would show a default for $path", state.lacks(path))
        }
        assertEquals(6, EndpointCapabilities.MEASURED_UNAVAILABLE.count { state.lacks(it) })
    }

    @Test
    fun `working endpoints are not reported as missing`() {
        // Exact match, so a working control is never disabled by a near-miss path.
        val state = DopplerDeviceState(
            unavailableEndpoints = EndpointCapabilities.MEASURED_UNAVAILABLE
        )
        assertFalse(state.lacks("hardware/volume"))
        assertFalse(state.lacks("alarms"))
        assertFalse(state.lacks("hardware/light-sensor"))
        assertFalse(state.lacks(""))
    }

    @Test
    fun `a state with no missing endpoints enables everything`() {
        // The common case: a healthy clock must not have a single control disabled, which is
        // the failure mode if `lacks` defaulted to true.
        val state = DopplerDeviceState()
        assertFalse(state.lacks("software/colon-blink"))
        assertFalse(state.lacks("hardware/wifi-status"))
        assertTrue(state.unavailableEndpoints.isEmpty())
    }

    /**
     * Unavailable on wifi-status, and genuinely failing on three others.
     *
     * The mix matters: it is what separates "the device is unreachable" from "one control
     * this unit lacks", which are very different things to show the user.
     */
    private class MockPollApiWithThreeErrorsAndOneUnavailable : MockPollApi() {
        override suspend fun getWifiStatus(): DopplerWifiStatus =
            throw DopplerException.UnavailableException("hardware/wifi-status")
        override suspend fun getDeviceInfo(): DopplerDeviceInfo = throw java.io.IOException("cloud blip")
        override suspend fun getUtcTime(): DopplerUtcTime = throw java.io.IOException("cloud blip")
        override suspend fun getTimeMode(): DopplerTimeMode = throw java.io.IOException("cloud blip")
    }

    /**
     * Fails an endpoint the measured seed says nothing about.
     *
     * The capability set is primed through the real API rather than by stubbing the
     * collection, so the repository's publication path is genuinely exercised.
     */
    private class MockPollApiWithLearnedUnavailable : MockPollApi() {
        init {
            val path = LEARNED_PATH
            repeat(2) {
                capabilities.recordGiveUp(path)
                capabilities.endPollCycle(healthy = true)
            }
        }

        override suspend fun getColonBlink(): DopplerColonBlink =
            throw DopplerException.UnavailableException(LEARNED_PATH)
    }

    // ---------- the only way back from a skip ----------

    @Test
    fun `recheck re-probes so a firmware fix is not hidden forever`() = runBlocking {
        // The sole escape hatch from a skip. Measured-broken paths are never re-probed on a
        // timer, so without this a firmware fix would leave the app hiding a control the clock
        // can now actually perform - permanently, and invisibly.
        val api = MockPollApiWithRecoverableEndpoint()
        val repository = DopplerRepository(api)
        repository.refresh()
        assertTrue("precondition: learned as unavailable", LEARNED_PATH in api.capabilities.unavailablePaths)

        // The firmware update lands and the endpoint starts answering again.
        api.recovered = true
        val probesBefore = api.probes

        repository.recheckUnavailableEndpoints()

        assertTrue("recheck must actually put the endpoint back on the wire", api.probes > probesBefore)
        assertFalse("a working endpoint must not stay skipped", api.capabilities.shouldSkip(LEARNED_PATH))
        assertTrue(api.capabilities.unavailablePaths.isEmpty())
    }

    @Test
    fun `recheck returns a still-broken endpoint to the skipped set`() = runBlocking {
        // Recheck is not a permanent pardon. Still-dead endpoints must go back to being
        // skipped, or the app would resume paying ~15s per cycle for them forever.
        val api = MockPollApiWithRecoverableEndpoint()
        val repository = DopplerRepository(api)
        repository.refresh()
        repository.refresh()
        assertTrue("precondition: learned as unavailable", LEARNED_PATH in api.capabilities.unavailablePaths)
        val probesBefore = api.probes

        repository.recheckUnavailableEndpoints()

        assertTrue("the probe was attempted", api.probes > probesBefore)
        assertTrue(
            "still dead, so it belongs back in the skipped set",
            api.capabilities.shouldSkip(LEARNED_PATH)
        )
    }

    @Test
    fun `recheck leaves working endpoints alone`() = runBlocking {
        // Volume answered throughout and must stay enabled. Recheck is scoped to paths that
        // were believed unavailable, not a blanket re-enable of everything.
        val api = MockPollApiWithRecoverableEndpoint()
        val repository = DopplerRepository(api)
        repository.refresh()
        repository.refresh()

        repository.recheckUnavailableEndpoints()
        assertFalse(api.capabilities.shouldSkip("hardware/volume"))
    }

    @Test
    fun `recheck is a no-op when nothing is unavailable`() = runBlocking {
        // Guards an early return that would otherwise skip the refresh and leave the screen
        // stale with nothing to indicate it happened.
        val api = MockPollApi()
        val repository = DopplerRepository(api)
        repository.refresh()

        repository.recheckUnavailableEndpoints()
        assertTrue(api.capabilities.unavailablePaths.isEmpty())
    }

    /**
     * Healthy on every endpoint except one that can be made to start working.
     *
     * The capability set is primed through the real API rather than by stubbing the
     * collection, so the repository's publication path is genuinely exercised.
     *
     * [probes] counts attempts, which is the only way to tell "recheck asked the clock again"
     * from "recheck quietly did nothing" - both leave the endpoint looking unavailable.
     */
    private class MockPollApiWithRecoverableEndpoint : MockPollApi() {
        var probes = 0
            private set

        /** Flipped to simulate the firmware update that makes the endpoint work. */
        var recovered = false

        init {
            repeat(2) {
                capabilities.recordGiveUp(LEARNED_PATH)
                capabilities.endPollCycle(healthy = true)
            }
        }

        override suspend fun getColonBlink(): DopplerColonBlink {
            probes++
            // Mirrors what executePollRequest does around a real request, because overriding
            // the getter bypasses it. Without the skip check and the give-up record, "the
            // endpoint stayed dead" and "recheck never asked" look identical.
            if (capabilities.shouldSkip(LEARNED_PATH)) {
                throw DopplerException.UnavailableException(LEARNED_PATH)
            }
            if (recovered) {
                capabilities.recordSuccess(LEARNED_PATH)
                return DopplerColonBlink(true)
            }
            capabilities.recordGiveUp(LEARNED_PATH)
            throw DopplerException.ProtocolException("no response", httpCode = 408)
        }
    }

    private companion object {
        /** Learned at runtime, so deliberately outside the measured seed. */
        const val LEARNED_PATH = "software/colon-blink-x"
    }
}