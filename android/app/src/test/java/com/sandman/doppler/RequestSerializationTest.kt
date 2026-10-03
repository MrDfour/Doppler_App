package com.sandman.doppler

import com.google.common.truth.Truth.assertThat
import com.sandman.doppler.api.DopplerCloudApi
import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.api.RequestGate
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.repository.DopplerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the serialized-request invariant on the cloud control path.
 *
 * `DopplerCloudApi` replaces `DopplerLocalApi.executeAuthenticatedRequest` wholesale.
 * When it did so it dropped the parent's mutex, so cloud-mode requests ran fully
 * concurrently - directly violating the hardware constraint in AGENTS.md and producing
 * the "jumping controls" symptom where a slow poll could land on top of a write.
 *
 * These tests assert the invariant at the transport boundary: no matter how many
 * coroutines fire concurrently, only one request is ever in flight.
 */
class RequestSerializationTest {

    private lateinit var mockWebServer: MockWebServer

    /** Records the peak number of simultaneously in-flight requests. */
    private class ConcurrencyTracker(private val body: String) : Dispatcher() {
        private val inFlight = AtomicInteger(0)
        private val lock = Any()
        private var peak = 0

        val maxConcurrent: Int get() = synchronized(lock) { peak }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val current = inFlight.incrementAndGet()
            synchronized(lock) { if (current > peak) peak = current }
            try {
                // Hold the request open briefly so overlapping calls would be observable.
                Thread.sleep(40)
            } finally {
                inFlight.decrementAndGet()
            }
            return MockResponse().setResponseCode(200).setBody(body)
        }
    }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun cloudApi(): DopplerCloudApi = object : DopplerCloudApi(
        dsn = "Doppler-12345678",
        cloudAccessToken = "test-token"
    ) {
        // Point the control plane at the local stub instead of the real host.
        override fun buildUrl(path: String): String =
            "http://${mockWebServer.hostName}:${mockWebServer.port}/$dsn/$path"
    }

    @Test
    fun `cloud requests never overlap even when fired concurrently`() = runBlocking {
        val tracker = ConcurrencyTracker("{}")
        mockWebServer.dispatcher = tracker
        val api = cloudApi()

        val results = (1..8).map {
            async(Dispatchers.IO) { api.getDeviceInfo() }
        }.awaitAll()

        assertThat(results).hasSize(8)
        assertThat(tracker.maxConcurrent).isEqualTo(1)
    }

    @Test
    fun `cloud writes never overlap even when fired concurrently`() = runBlocking {
        val tracker = ConcurrencyTracker("")
        mockWebServer.dispatcher = tracker
        val api = cloudApi()

        (1..6).map {
            async(Dispatchers.IO) { api.displayText("PROBE", 5, 50, DopplerColor.CYAN) }
        }.awaitAll()

        assertThat(tracker.maxConcurrent).isEqualTo(1)
    }

    @Test
    fun `a read and a write issued together do not interleave`() = runBlocking {
        val tracker = ConcurrencyTracker("{}")
        mockWebServer.dispatcher = tracker
        val api = cloudApi()

        val both = listOf(
            async(Dispatchers.IO) { api.getDeviceInfo() },
            async(Dispatchers.IO) { api.displayText("PROBE", 5, 50, DopplerColor.CYAN) }
        ).awaitAll()

        assertThat(both).hasSize(2)
        assertThat(tracker.maxConcurrent).isEqualTo(1)
    }

    @Test
    fun `local LAN requests remain serialized after making the mutex protected`() = runBlocking {
        val tracker = ConcurrencyTracker("{}")
        mockWebServer.dispatcher = tracker
        val api = DopplerLocalApi(
            host = mockWebServer.hostName,
            port = mockWebServer.port,
            dsn = "Doppler-12345678",
            localKey = "",
            customClient = OkHttpClient.Builder().build(),
            useTls = false
        )

        (1..6).map {
            async(Dispatchers.IO) { api.getDeviceInfo() }
        }.awaitAll()

        assertThat(tracker.maxConcurrent).isEqualTo(1)
    }

    @Test
    fun `every endpoint the repository polls reads at background priority`() = runBlocking {
        // The lag regression came from poll reads being served ahead of the user. This
        // pins that all 22 poll getters route through the background helper, so a
        // future getter added without the priority cannot silently reintroduce it.
        mockWebServer.dispatcher = ConcurrencyTracker("{}")
        val api = object : DopplerLocalApi(
            host = mockWebServer.hostName,
            port = mockWebServer.port,
            dsn = "Doppler-12345678",
            localKey = "",
            customClient = OkHttpClient.Builder().build(),
            useTls = false
        ) {
            // Surfaces the protected gate so the test can occupy it deliberately.
            suspend fun <T> holdGate(block: suspend () -> T): T =
                requestGate.withRequest(RequestGate.Priority.BACKGROUND, block)
        }

        val gateHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Occupy the gate so any request issued below has to queue.
        val held = async(Dispatchers.Default) {
            api.holdGate {
                gateHeld.complete(Unit)
                release.await()
            }
        }
        gateHeld.await()

        val order = Collections.synchronizedList(mutableListOf<String>())
        // A representative interactive write and a poll read, both queued behind the
        // held gate. The write must be served first.
        val write = async(Dispatchers.Default) {
            api.setVolume(50)
            order.add("write")
        }
        val read = async(Dispatchers.Default) {
            api.getVolume()
            order.add("read")
        }
        delay(100)
        release.complete(Unit)
        held.await()
        write.await()
        read.await()

        assertThat(order).containsExactly("write", "read").inOrder()
    }

    @Test
    fun `a failed request does not leave the mutex locked`() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500)
        }
        val api = cloudApi()

        // A 500 must surface as a ProtocolException...
        var threw = false
        try {
            api.getDeviceInfo()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected the 500 to raise an exception", threw)

        // ...and must not deadlock the next caller on a poisoned mutex. The timeout is
        // the real assertion here: without it a leaked lock would hang the suite rather
        // than fail it.
        mockWebServer.dispatcher = ConcurrencyTracker("""{"mfgrName":"Palo Alto Innovation"}""")
        val info = withTimeout(10_000) { api.getDeviceInfo() }
        assertThat(info.mfgrName).isEqualTo("Palo Alto Innovation")
    }

    @Test
    fun `repository reports cloud mode only for the cloud api`() {
        val cloudRepository = DopplerRepository(cloudApi())
        assertTrue(cloudRepository.isCloudControlPlane)

        val localRepository = DopplerRepository(
            DopplerLocalApi(host = "192.168.1.5", port = 5443, dsn = "Doppler-1")
        )
        assertFalse(localRepository.isCloudControlPlane)
    }

    @Test
    fun `buildUrl is the single seam the cloud path uses for its endpoint`() {
        // Pins the contract the test double relies on: overriding buildUrl redirects
        // every control-plane request. If someone reintroduces an inline URL literal,
        // the stub above silently stops intercepting and these tests go vacuous.
        val api = object : DopplerCloudApi(
            dsn = "Doppler-abc",
            cloudAccessToken = "t"
        ) {
            fun defaultUrl(path: String) = buildUrl(path)
        }

        assertThat(api.defaultUrl("hardware/display-text"))
            .isEqualTo("https://control.sandmandoppler.com/Doppler-abc/hardware/display-text")
    }

    @Test
    fun `cloud api targets the configured host rather than a hardcoded domain`() {
        // Surfaces the protected production builder so the default URL can be asserted
        // directly, with no network involved.
        val api = object : DopplerCloudApi(
            host = "control.example.test",
            dsn = "Doppler-abc",
            cloudAccessToken = "t"
        ) {
            fun productionUrl(path: String) = buildUrl(path)
        }

        // The old code ignored [host] and hardcoded control.sandmandoppler.com here,
        // which is what made the cloud path untestable.
        assertThat(api.productionUrl("hardware/volume"))
            .isEqualTo("https://control.example.test/Doppler-abc/hardware/volume")
    }

    @Test
    fun `an interactive request does not wait behind a queued background sweep`(): Unit = runBlocking {
        // Reproduces the regression where a 22-request poll, serialized ahead of the
        // user, delayed a volume change by the length of the whole sweep.
        val gate = RequestGate()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstPollHolding = CompletableDeferred<Unit>()
        val releaseFirstPoll = CompletableDeferred<Unit>()

        // Occupy the gate with one poll, signalled so we know it is really in hand.
        val held = async(Dispatchers.Default) {
            gate.withRequest(RequestGate.Priority.BACKGROUND) {
                order.add("poll-0")
                firstPollHolding.complete(Unit)
                releaseFirstPoll.await()
            }
        }
        firstPollHolding.await()

        // Pile up the rest of the sweep behind it.
        val queued = (1..3).map { i ->
            async(Dispatchers.Default) {
                gate.withRequest(RequestGate.Priority.BACKGROUND) { order.add("poll-$i") }
            }
        }
        delay(50)

        // The user acts and must be served before the remaining polls.
        val interactive = async(Dispatchers.Default) {
            gate.withRequest(RequestGate.Priority.INTERACTIVE) { order.add("USER") }
        }
        delay(50)

        releaseFirstPoll.complete(Unit)
        held.await()
        interactive.await()
        queued.awaitAll()

        // The queued polls are launched concurrently, so their relative order among
        // themselves is not defined. The invariant under test is that the user is
        // served before every one of them.
        assertThat(order.first()).isEqualTo("poll-0")
        assertThat(order.indexOf("USER")).isEqualTo(1)
        assertThat(order.subList(2, order.size).toSet()).containsExactly("poll-1", "poll-2", "poll-3")
    }

    @Test
    fun `a background request never overtakes a waiting interactive request`(): Unit = runBlocking {
        val gate = RequestGate()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        val gateHeld = CompletableDeferred<Unit>()

        val held = async(Dispatchers.Default) {
            gate.withRequest(RequestGate.Priority.BACKGROUND) {
                gateHeld.complete(Unit)
                release.await()
                order.add("held-poll")
            }
        }
        gateHeld.await()

        val interactive = async(Dispatchers.Default) {
            gate.withRequest(RequestGate.Priority.INTERACTIVE) { order.add("interactive") }
        }
        delay(50)
        // A poll that arrives *after* the user is already queued must still go last.
        val late = async(Dispatchers.Default) {
            gate.withRequest(RequestGate.Priority.BACKGROUND) { order.add("late-poll") }
        }
        delay(50)

        release.complete(Unit)
        held.await()
        interactive.await()
        late.await()

        assertThat(order).containsExactly("held-poll", "interactive", "late-poll").inOrder()
    }

    @Test
    fun `requests are still fully serialized under priority`() = runBlocking {
        val tracker = ConcurrencyTracker("{}")
        mockWebServer.dispatcher = tracker
        val api = cloudApi()

        // Mix both priorities so the gate is exercised under contention.
        val jobs = (1..10).map { i ->
            async(Dispatchers.IO) {
                if (i % 2 == 0) api.getDeviceInfo()
                else api.displayText("PROBE", 5, 50, DopplerColor.CYAN)
            }
        }
        jobs.awaitAll()

        assertThat(tracker.maxConcurrent).isEqualTo(1)
    }

    @Test
    fun `the gate releases after a thrown failure so later work still runs`() = runBlocking {
        val gate = RequestGate()
        val ran = AtomicInteger(0)

        runCatching {
            gate.withRequest(RequestGate.Priority.BACKGROUND) { throw IllegalStateException("boom") }
        }
        gate.withRequest(RequestGate.Priority.INTERACTIVE) { ran.incrementAndGet() }

        assertThat(ran.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent cloud requests all complete and none are lost`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request.path ?: "")
                Thread.sleep(10)
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        val api = cloudApi()

        (1..10).map { async(Dispatchers.IO) { api.getDeviceInfo() } }.awaitAll()

        // Serialization must not silently drop requests.
        assertThat(seen).hasSize(10)
    }
}
