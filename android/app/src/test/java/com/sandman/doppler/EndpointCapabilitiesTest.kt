package com.sandman.doppler

import com.sandman.doppler.api.EndpointCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of [EndpointCapabilities].
 *
 * Six of the twenty-two polled endpoints never answer on real hardware (probed 2026-10-03,
 * DSN `Doppler-10caaebb`, Enter Sandman / `Escapement`). Each costs ~15s, and requests are
 * serialized, so skipping them removes ~90s from every poll cycle.
 *
 * Two properties matter more than the skipping itself and are what most of these tests are
 * about:
 *
 * 1. **A network outage must not disable features.** Naive "two timeouts means dead" logic
 *    would mark all twenty-two endpoints dead during a dropout and leave the app permanently
 *    featureless once the network returned.
 * 2. **The saving must not quietly give itself back.** Six re-probes at 15s each, spread
 *    across routine polling, would hand most of the 90s back.
 */
class EndpointCapabilitiesTest {

    /** A path with no measured verdict, so it starts enabled and can only be disabled by evidence. */
    private val fresh = "hardware/never-probed"
    private val wifi = "hardware/wifi-status"

    // ---------- the measured evidence itself ----------

    @Test
    fun `measured set is exactly the six endpoints that never answered`() {
        // Pins the evidence. If Sandman fixes an endpoint this test should be the thing that
        // fails, prompting the entry's removal from MEASURED_UNAVAILABLE.
        assertEquals(
            setOf(
                "hardware/wifi-status",
                "software/use-colon",
                "software/colon-blink",
                "software/use-leading-zero",
                "software/use-fade-time",
                "software/display-seconds"
            ),
            EndpointCapabilities.MEASURED_UNAVAILABLE
        )
        assertEquals(6, EndpointCapabilities.MEASURED_UNAVAILABLE.size)
    }

    @Test
    fun `measured endpoints are skipped without being attempted`() {
        val caps = EndpointCapabilities()
        assertTrue(caps.shouldSkip(wifi))
        assertEquals(EndpointCapabilities.MEASURED_UNAVAILABLE, caps.unavailablePaths)
        assertEquals(6, caps.unavailablePaths.size)
    }

    @Test
    fun `an endpoint with no evidence against it is attempted`() {
        val caps = EndpointCapabilities()
        assertFalse(caps.shouldSkip(fresh))
        assertFalse(caps.shouldSkip("hardware/volume"))
    }

    // ---------- an outage must not disable anything ----------

    @Test
    fun `give-ups during an unhealthy cycle are discarded`() {
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        // Five cycles, every one of them a link that was down. Without the healthy gate this
        // would disable the endpoint on cycle two and leave it disabled forever.
        repeat(5) {
            caps.recordGiveUp(fresh)
            caps.endPollCycle(healthy = false)
        }
        assertFalse("outage must not disable a feature", caps.shouldSkip(fresh))
        assertTrue(caps.unavailablePaths.isEmpty())
    }

    @Test
    fun `a healthy cycle after an outage commits normally`() {
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        caps.recordGiveUp(fresh)
        caps.endPollCycle(healthy = false)
        assertFalse(caps.shouldSkip(fresh))
        // Next cycle the clock is answering, so this give-up is trustworthy.
        caps.recordGiveUp(fresh)
        caps.endPollCycle(healthy = true)
        assertFalse("one committed give-up is below the threshold", caps.shouldSkip(fresh))
    }

    // ---------- learning from healthy cycles ----------

    @Test
    fun `two committed give-ups on healthy cycles disable the endpoint`() {
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        repeat(2) {
            caps.recordGiveUp(fresh)
            caps.endPollCycle(healthy = true)
        }
        assertTrue(caps.shouldSkip(fresh))
        assertEquals(setOf(fresh), caps.unavailablePaths)
    }

    @Test
    fun `a single healthy-cycle give-up does not disable the endpoint`() {
        // One stalled read on a working link is usually a blip. Disabling a working feature
        // over it would be a worse bug than the latency it saves.
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        caps.recordGiveUp(fresh)
        caps.endPollCycle(healthy = true)
        assertFalse(caps.shouldSkip(fresh))
    }

    @Test
    fun `endpoints disabled by one endpoint stay independent`() {
        // Give-up accounting is per path; one bad endpoint must not disable its neighbours.
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        val other = "hardware/also-never-probed"
        repeat(2) {
            caps.recordGiveUp(fresh)
            caps.endPollCycle(healthy = true)
        }
        assertTrue(caps.shouldSkip(fresh))
        assertFalse(caps.shouldSkip(other))
        assertFalse(caps.shouldSkip("hardware/volume"))
    }

    // ---------- recovery ----------

    @Test
    fun `a learned path recovers after a successful probe`() {
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        repeat(2) {
            caps.recordGiveUp(fresh)
            caps.endPollCycle(healthy = true)
        }
        // Note: shouldSkip advances the probe counter, so it must not be called before the
        // loop below or the window is one tick shorter than it looks.
        // Its probe comes due and succeeds, so it is usable again.
        repeat(EndpointCapabilities.REPROBE_EVERY_POLLS - 1) {
            assertTrue("still inside the skip window", caps.shouldSkip(fresh))
            caps.endPollCycle(healthy = true)
        }
        assertFalse("probe is due and must be attempted", caps.shouldSkip(fresh))

        caps.recordSuccess(fresh)
        assertFalse(caps.shouldSkip(fresh))
        assertTrue(caps.unavailablePaths.isEmpty())
    }

    @Test
    fun `a learned path is still skipped one poll short of its probe`() {
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        repeat(2) {
            caps.recordGiveUp(fresh)
            caps.endPollCycle(healthy = true)
        }
        repeat(EndpointCapabilities.REPROBE_EVERY_POLLS - 1) {
            assertTrue("must not re-probe early", caps.shouldSkip(fresh))
            caps.endPollCycle(healthy = true)
        }
        assertFalse("probe is now due", caps.shouldSkip(fresh))
    }

    @Test
    fun `several learned paths are not starved of probes by each other`() {
        // A single shared probe counter let whichever path asked first consume the window and
        // left the rest queued behind it, so N broken endpoints recovered one per window.
        val caps = EndpointCapabilities(seededUnavailable = emptySet())
        val paths = (1..4).map { "hardware/learned-$it" }
        paths.forEach { path ->
            repeat(2) {
                caps.recordGiveUp(path)
                caps.endPollCycle(healthy = true)
            }
        }
        // The loop's first assertion also proves all four are currently skipped.
        repeat(EndpointCapabilities.REPROBE_EVERY_POLLS - 1) {
            assertTrue("every learned path is due in the same window", paths.all { caps.shouldSkip(it) })
        }
        // All four become probeable at once, not one per window.
        assertFalse(paths.any { caps.shouldSkip(it) })
    }

    @Test
    fun `measured-broken paths are never re-probed automatically`() {
        // The whole point is not paying ~15s per endpoint per cycle. Timed re-probes would give
        // most of the 90s back, so measured evidence is only revisited on explicit request.
        val caps = EndpointCapabilities()
        repeat(400) {
            assertTrue(caps.shouldSkip(wifi))
            caps.endPollCycle(healthy = true)
        }
        assertEquals(6, caps.unavailablePaths.size)
    }

    @Test
    fun `recheckAll re-probes measured paths and re-skips those still broken`() {
        val caps = EndpointCapabilities()
        assertTrue(caps.shouldSkip(wifi))

        caps.recheckAll()
        assertFalse("explicit re-check must probe", caps.shouldSkip(wifi))
        assertTrue(caps.unavailablePaths.isEmpty())

        // Still broken: the retained count means it is back to skipped after the threshold,
        // not after a second full window of 15s stalls.
        caps.recordGiveUp(wifi)
        caps.endPollCycle(healthy = true)
        assertFalse(caps.shouldSkip(wifi))
        caps.recordGiveUp(wifi)
        caps.endPollCycle(healthy = true)
        assertTrue(caps.shouldSkip(wifi))
    }

    @Test
    fun `a recovered measured path stays available`() {
        val caps = EndpointCapabilities()
        caps.recheckAll()
        caps.recordSuccess(wifi)
        caps.endPollCycle(healthy = true)
        assertFalse(caps.shouldSkip(wifi))
        assertTrue(caps.unavailablePaths.isEmpty())
    }

    // ---------- switching clocks ----------

    @Test
    fun `resetLearned restores the measured evidence`() {
        val caps = EndpointCapabilities()
        repeat(2) {
            caps.recordGiveUp(fresh)
            caps.endPollCycle(healthy = true)
        }
        caps.recheckAll()
        assertTrue(caps.unavailablePaths.isEmpty())

        // Pointing the app at a different clock must not inherit the previous one's verdicts.
        caps.resetLearned()
        assertEquals(EndpointCapabilities.MEASURED_UNAVAILABLE, caps.unavailablePaths)
        assertFalse(caps.shouldSkip(fresh))
    }
}