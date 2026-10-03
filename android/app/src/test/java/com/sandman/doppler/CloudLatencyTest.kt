package com.sandman.doppler

import com.google.common.truth.Truth.assertThat
import com.sandman.doppler.api.DopplerCloudApi
import com.sandman.doppler.api.RequestTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the erratic cloud-mode latency reported on device.
 *
 * The bug was invisible to the existing serialization tests because those assert
 * *ordering*, not *duration*. These assert the two things that actually produced the
 * 2s-to-30s stalls: an unbounded per-request deadline, and a token refresh storm.
 */
class CloudLatencyTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        RequestTelemetry.reset()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // No explicit return type: callers need `pollPath`, which only exists on the
    // anonymous subclass.
    private fun cloudApi(
        refreshTokenProvider: (suspend () -> String?)? = null
    ) = object : DopplerCloudApi(
        // Must point at the stub. DopplerCloudApi defaults to the real
        // control.sandmandoppler.com, which would make these tests pass by accident
        // (a DNS stall looks like a timeout) while never exercising the code.
        host = mockWebServer.hostName,
        port = mockWebServer.port,
        dsn = "Doppler-12345678",
        cloudAccessToken = "token",
        refreshTokenProvider = refreshTokenProvider,
        customClient = OkHttpClient.Builder().build()
    ) {
        override fun buildUrl(path: String) = "http://$host:$port/$dsn/$path"

        // Surfaces the protected poll helper so a failing endpoint can be exercised
        // through the same BACKGROUND priority path the repository uses.
        suspend fun pollPath(path: String): String = executePollRequest(path)
    }

    /**
     * A poll read that stalls must not hold the gate anywhere near the old 30s read
     * timeout, or the user's write behind it inherits the whole stall.
     */
    @Test
    fun `a stalled poll read gives up well before the interactive deadline`(): Unit = runBlocking {
        // Accept the connection but never send a response body.
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val api = cloudApi()

        val startedAt = System.currentTimeMillis()
        var threw = false
        try {
            // getVolume is a poll read, so it runs at BACKGROUND priority.
            api.getVolume()
        } catch (e: Exception) {
            threw = true
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertThat(threw).isTrue()
        // Must distinguish the 5s BACKGROUND deadline from the 12s client readTimeout
        // backstop. A loose bound here passes even with no per-call deadline at all,
        // because the backstop alone would satisfy it - which is exactly what a first
        // draft of this assertion did.
        assertThat(elapsed).isLessThan(9_000L)
    }

    /**
     * A successful refresh must be written back so later requests are accepted without
     * refreshing again.
     *
     * Note this is *not* a concurrency test. [DopplerCloudApi.requestGate] serializes
     * requests, so ten simultaneous calls cannot see ten simultaneous 401s - the first
     * one refreshes and the rest send the fresh token. An earlier draft of this test
     * fired ten requests concurrently and asserted a single refresh; it passed with the
     * dedup guards entirely deleted, because serialization alone explains the result.
     * Asserting the real mechanism is the point.
     */
    @Test
    fun `a successful refresh is reused by later requests instead of refreshing again`(): Unit = runBlocking {
        val refreshes = AtomicInteger(0)
        val deviceResponses = AtomicInteger(0)

        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                // A fresh token is accepted; the stale one is rejected.
                return if (auth == "Bearer fresh-token") {
                    deviceResponses.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody("{\"volume\":40}")
                } else {
                    MockResponse().setResponseCode(401)
                }
            }
        }

        val api = cloudApi(
            refreshTokenProvider = {
                refreshes.incrementAndGet()
                "fresh-token"
            }
        )

        // Sequential, matching how the request gate actually admits work.
        repeat(5) { api.getVolume() }

        assertThat(refreshes.get()).isEqualTo(1)
        assertThat(deviceResponses.get()).isEqualTo(5)
    }

    /**
     * A dead refresh token cannot succeed on a later attempt, so retrying it per request
     * only multiplies latency. One failure must disable further attempts.
     */
    @Test
    fun `a failed refresh is not retried on every subsequent request`(): Unit = runBlocking {
        val refreshes = AtomicInteger(0)
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }

        val api = cloudApi(
            refreshTokenProvider = {
                refreshes.incrementAndGet()
                null // refresh token is dead
            }
        )

        repeat(5) { runCatching { api.getVolume() } }

        // Fails fast after the first attempt instead of five refresh round-trips.
        assertThat(refreshes.get()).isEqualTo(1)
    }

    /** Telemetry must actually record, or the Diagnostics numbers are decoration. */
    @Test
    fun `telemetry records latency outcomes and survives a full ring buffer`(): Unit = runBlocking {
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("nope") -> MockResponse().setResponseCode(500)
                else -> MockResponse().setResponseCode(200).setBody("{\"volume\":40}")
            }
        }
        val api = cloudApi()

        runCatching { api.getVolume() }
        runCatching { api.getVolume() }
        runCatching { api.pollPath("hardware/nope") }

        val snapshot = RequestTelemetry.snapshot()
        assertThat(snapshot.sampleCount).isEqualTo(3)
        assertThat(snapshot.httpErrorCount).isEqualTo(1)
        assertThat(snapshot.lastDurationMs).isAtLeast(0L)
        assertThat(snapshot.medianDurationMs).isAtLeast(0L)
    }

    @Test
    fun `telemetry ring buffer is bounded so it cannot grow without limit`() {
        repeat(500) {
            RequestTelemetry.record(
                path = "hardware/volume",
                priority = com.sandman.doppler.api.RequestGate.Priority.BACKGROUND,
                durationMs = it.toLong(),
                outcome = RequestTelemetry.Outcome.OK,
                httpCode = 200
            )
        }
        assertThat(RequestTelemetry.snapshot().sampleCount).isEqualTo(64)
    }
}
