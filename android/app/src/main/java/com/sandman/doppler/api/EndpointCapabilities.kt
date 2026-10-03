package com.sandman.doppler.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks which read endpoints the connected clock will actually answer.
 *
 * ### Why this exists
 *
 * Probing a real clock on 2026-10-03 (DSN `Doppler-10caaebb`, Enter Sandman, firmware
 * `Escapement`, software `0.1214 Bucky`) found that **six of the twenty-two polled endpoints
 * never return**: `hardware/wifi-status`, `software/use-colon`, `software/colon-blink`,
 * `software/use-leading-zero`, `software/use-fade-time` and `software/display-seconds` all
 * stall until HTTP 408 at roughly 15 seconds.
 *
 * Requests are serialized through [RequestGate], so those six stalls were **about 90 seconds
 * of dead time in every poll cycle** - more than everything else combined, and larger than
 * any latency problem the client-side timeout work had addressed. The stall is a server-side
 * fault that cannot be fixed from the app, so the only correct client response is to stop
 * paying for it.
 *
 * ### Why this is not a silent skip
 *
 * Skipping a read and leaving the caller with the model's default value is exactly the
 * silent-default failure described in `STANDALONE_IMPLEMENTATION_PLAN.md` §2.3 - the UI would
 * show an invented `0` Wi-Fi RSSI or a `35` lux reading that looks real. Callers therefore get
 * [DopplerException.UnavailableException] instead of a value, and
 * `DopplerDeviceState.unavailableEndpoints` publishes the set so the UI can say "unavailable"
 * rather than "zero".
 *
 * ### Two failure modes this is deliberately built to avoid
 *
 * **A network outage must not disable features.** During an outage every read times out at
 * once, so naively "two timeouts means dead" would mark all twenty-two endpoints unavailable
 * and leave the app permanently featureless once the network came back. Give-ups are
 * therefore *buffered* and only committed by [endPollCycle] when the caller confirms the rest
 * of the cycle worked - a give-up is only trustworthy if it happened while the clock was
 * otherwise answering.
 *
 * **Recovery must not be starved.** With a single global re-probe counter, the first skipped
 * path consumed the window and the rest queued behind it, so a set of newly-disabled
 * endpoints would recover one per window. Each path keeps its own counter instead. Seeded
 * paths are never re-probed automatically (see [shouldSkip]); use [recheckAll] for that.
 */
class EndpointCapabilities(
    /** Endpoints already known to be dead before any request is made. */
    private val seededUnavailable: Set<String> = MEASURED_UNAVAILABLE
) {
    companion object {
        /**
         * Consecutive give-ups on a healthy cycle before a path is skipped.
         *
         * Two, not one: a single stall can be a genuine network blip, and permanently
         * disabling a working feature over one dropped packet would be worse than the latency
         * it saves.
         */
        const val GIVE_UPS_BEFORE_SKIP = 2

        /**
         * Poll cycles between automatic re-probes of a **runtime-learned** path.
         *
         * At the live 8000ms poll interval this is roughly four minutes, so a transient
         * failure is recovered from promptly. Only learned paths use it; seeded paths wait for
         * an explicit [recheckAll] so that confirmed-broken endpoints cost nothing in the
         * background.
         */
        const val REPROBE_EVERY_POLLS = 30

        /**
         * Endpoints measured as never returning on real hardware.
         *
         * Verified 2026-10-03 against DSN `Doppler-10caaebb`, Enter Sandman / `Escapement`,
         * through the cloud relay, with retries.
         *
         * Note the asymmetry: `software/use-colon` is **writable** - `PUT {"on":true}` returns
         * 200 in ~400ms - it is only the `GET` that never answers. Its setter deliberately does
         * not go through `DopplerLocalApi.executePollRequest`, so writing still works.
         * `software/display-seconds` is broken both ways (408 on read, 500 on write).
         *
         * This set is the one piece of hard-coded evidence in the codebase. Remove an entry
         * when Sandman confirms the endpoint works again.
         */
        val MEASURED_UNAVAILABLE: Set<String> = setOf(
            "hardware/wifi-status",
            "software/use-colon",
            "software/colon-blink",
            "software/use-leading-zero",
            "software/use-fade-time",
            "software/display-seconds",
        )

        /** Cloud and clock both stall at this status when a handler does not respond. */
        const val HTTP_REQUEST_TIMEOUT = 408
    }

    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    private val confirmedGiveUps = ConcurrentHashMap<String, Int>()

    /**
     * Give-ups seen during the cycle in flight, not yet committed.
     *
     * Committed only by [endPollCycle] when the cycle was otherwise healthy.
     */
    private val pendingGiveUps = ConcurrentHashMap<String, Int>()

    /** Per-path poll counters, so no path can starve the others out of a re-probe. */
    private val pollsSinceProbe = ConcurrentHashMap<String, Int>()
    private val completedPolls = AtomicInteger(0)

    init {
        unavailable.addAll(seededUnavailable)
    }

    /** Paths currently believed dead. Safe to read from any thread. */
    val unavailablePaths: Set<String>
        get() = unavailable.toSet()

    /** Paths skipped purely on measured hardware evidence, as opposed to learned at runtime. */
    val seededPaths: Set<String>
        get() = seededUnavailable intersect unavailable.toSet()

    /**
     * Whether [path] should be skipped without being attempted.
     *
     * False when a re-probe is due, so a recovered endpoint is discovered rather than
     * permanently abandoned.
     */
    fun shouldSkip(path: String): Boolean {
        if (path !in unavailable) return false

        // Measured-broken endpoints are not re-probed on a timer. Six of them cost ~90s per
        // attempt, so probing them every few minutes would hand most of the saving straight
        // back. Recovery is explicit, via recheckAll().
        if (path in seededUnavailable) return true

        val since = (pollsSinceProbe[path] ?: 0) + 1
        if (since < REPROBE_EVERY_POLLS) {
            pollsSinceProbe[path] = since
            return true
        }
        pollsSinceProbe[path] = 0
        return false
    }

    /** Records a success, clearing both the skip flag and any partial failure count. */
    fun recordSuccess(path: String) {
        confirmedGiveUps.remove(path)
        pendingGiveUps.remove(path)
        unavailable.remove(path)
    }

    /**
     * Records a give-up. Committed only if the cycle turns out to have been healthy.
     *
     * Only call this for failures that indicate the endpoint itself is not answering - see
     * `DopplerLocalApi.isEndpointGiveUp` - never for generic transport errors, or an outage
     * would disable every endpoint on the device.
     */
    fun recordGiveUp(path: String) {
        pendingGiveUps.merge(path, 1, Int::plus)
    }

    /**
     * Closes the poll cycle, committing give-ups only when [healthy].
     *
     * @param healthy false when the cycle failed broadly enough to indicate the link or the
     *   clock was down. Give-ups from such a cycle are discarded rather than committed,
     *   because a stalled endpoint is indistinguishable from a stalled clock.
     */
    fun endPollCycle(healthy: Boolean) {
        completedPolls.incrementAndGet()
        val giveUps = pendingGiveUps.keys.toList()
        pendingGiveUps.clear()
        if (!healthy) return
        giveUps.forEach { path ->
            val count = (confirmedGiveUps[path] ?: 0) + 1
            confirmedGiveUps[path] = count
            if (count >= GIVE_UPS_BEFORE_SKIP) {
                unavailable.add(path)
                pollsSinceProbe.remove(path)
            }
        }
    }

    /**
     * Forgets the skip for every path so the next poll probes all of them.
     *
     * For an explicit user-driven re-check (Diagnostics "re-check unavailable endpoints").
     * Keeps the counts rather than clearing them, so an endpoint that is still dead returns
     * to the skipped set after [GIVE_UPS_BEFORE_SKIP] more give-ups instead of costing a
     * second full window of 15s stalls.
     */
    fun recheckAll() {
        unavailable.clear()
        pollsSinceProbe.clear()
        pendingGiveUps.clear()
    }

    /**
     * Discards everything learned from live traffic and restores the measured seed.
     *
     * Called when the target clock changes, so a broken LAN unit cannot suppress endpoints
     * that work on a healthy one.
     */
    fun resetLearned() {
        confirmedGiveUps.clear()
        pendingGiveUps.clear()
        pollsSinceProbe.clear()
        completedPolls.set(0)
        unavailable.clear()
        unavailable.addAll(seededUnavailable)
    }
}