package com.sandman.doppler.api

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory record of recent request timings, surfaced on the Diagnostics screen.
 *
 * This exists because the cloud-path latency bug was diagnosed twice from theory and
 * was wrong both times. The first guess ("the mutex serializes the poll") was only
 * half right; the real causes were a 30s read timeout and a token-refresh storm, both
 * invisible from the code alone. Wall-clock numbers from the device settle this in one
 * run instead of three guesses.
 *
 * Deliberately not persisted and deliberately tiny: a ring buffer of the last
 * [CAPACITY] requests. It answers "how slow is the relay right now, and is the time
 * going into the network or into the queue", which is the only question that mattered.
 */
object RequestTelemetry {

    enum class Outcome { OK, HTTP_ERROR, TIMEOUT_OR_IO_ERROR }

    data class Sample(
        val path: String,
        val priority: RequestGate.Priority,
        val durationMs: Long,
        val outcome: Outcome,
        val httpCode: Int?
    )

    /** Aggregate view for the Diagnostics screen. */
    data class Snapshot(
        val sampleCount: Int,
        val lastDurationMs: Long?,
        val medianDurationMs: Long?,
        val maxDurationMs: Long?,
        val timeoutCount: Int,
        val httpErrorCount: Int,
        val tokenRefreshAttempts: Int,
        val slowestPath: String?
    )

    private const val CAPACITY = 64

    private val samples = ArrayDeque<Sample>(CAPACITY)
    private val lock = Any()

    private val timeouts = AtomicInteger(0)
    private val httpErrors = AtomicInteger(0)
    private val tokenRefreshes = AtomicInteger(0)

    fun record(
        path: String,
        priority: RequestGate.Priority,
        durationMs: Long,
        outcome: Outcome,
        httpCode: Int? = null
    ) {
        when (outcome) {
            Outcome.TIMEOUT_OR_IO_ERROR -> timeouts.incrementAndGet()
            Outcome.HTTP_ERROR -> httpErrors.incrementAndGet()
            Outcome.OK -> Unit
        }
        synchronized(lock) {
            if (samples.size >= CAPACITY) samples.removeFirst()
            samples.addLast(Sample(path, priority, durationMs, outcome, httpCode))
        }
    }

    fun tokenRefreshAttempted() {
        tokenRefreshes.incrementAndGet()
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        if (samples.isEmpty()) {
            return Snapshot(0, null, null, null, 0, 0, 0, null)
        }
        val durations = samples.map { it.durationMs }.sorted()
        val median = if (durations.size % 2 == 1) {
            durations[durations.size / 2]
        } else {
            (durations[durations.size / 2 - 1] + durations[durations.size / 2]) / 2
        }
        val slowest = samples.maxByOrNull { it.durationMs }
        Snapshot(
            sampleCount = samples.size,
            lastDurationMs = samples.last().durationMs,
            medianDurationMs = median,
            maxDurationMs = durations.last(),
            timeoutCount = timeouts.get(),
            httpErrorCount = httpErrors.get(),
            tokenRefreshAttempts = tokenRefreshes.get(),
            slowestPath = slowest?.path
        )
    }

    /** Test seam: telemetry is process-global, so suites must not inherit each other's numbers. */
    fun reset() {
        synchronized(lock) { samples.clear() }
        timeouts.set(0)
        httpErrors.set(0)
        tokenRefreshes.set(0)
    }
}
