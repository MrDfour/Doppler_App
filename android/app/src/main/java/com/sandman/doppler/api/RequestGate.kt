package com.sandman.doppler.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Priority-aware serialization gate for requests to the clock.
 *
 * The Doppler's oatpp daemon is single-threaded, so only one request may be in flight
 * at a time - a plain [Mutex] is enough to guarantee that. But a plain mutex is
 * first-come-first-served, and the repository's poll cycle is *25 sequential GETs*
 * ([DopplerRepository.refresh]). Under a fair mutex a user action issued mid-sweep has
 * to queue behind every remaining poll request, which over a high-latency link like
 * `control.sandmandoppler.com` means seconds of delay before a volume change is even
 * sent. Serializing without prioritizing trades visible jitter for visible lag.
 *
 * This gate keeps the hard invariant (one request at a time) while letting interactive
 * work jump ahead of queued background polls. A user write therefore waits for at most
 * the single poll request already in flight, not for the whole sweep.
 *
 * One caveat worth knowing: serializing requests is not free. Every request, user or
 * poll, is now strictly one-at-a-time, so total poll duration is the sum of 25
 * round-trips. Over a WAN link that can exceed the poll interval, which means the poll
 * is effectively always running. Priority ordering keeps that from delaying the user,
 * but if the link is slow enough the poll itself should be trimmed or made adaptive.
 */
class RequestGate {

    enum class Priority {
        /** User-initiated: slider commits, toggles, alarm edits, diagnostics probes. */
        INTERACTIVE,

        /** The periodic state poll. Yields to anything interactive. */
        BACKGROUND
    }

    private val mutex = Mutex()
    private var busy = false
    private val interactiveWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val backgroundWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    /**
     * Runs [block] with exclusive access to the clock.
     *
     * Callers are served in priority order, FIFO within a priority. [block] runs while
     * the gate is held, so callers must keep it to a single request and must not call
     * back into this gate (that would deadlock).
     */
    suspend fun <T> withRequest(priority: Priority, block: suspend () -> T): T {
        val ticket = CompletableDeferred<Unit>()
        var granted = false

        mutex.withLock {
            // Take the gate immediately only if it is free and nobody of higher
            // priority is already waiting - otherwise we would jump the queue.
            if (!busy && (priority == Priority.INTERACTIVE || interactiveWaiters.isEmpty())) {
                busy = true
                granted = true
            } else if (priority == Priority.INTERACTIVE) {
                interactiveWaiters.addLast(ticket)
            } else {
                backgroundWaiters.addLast(ticket)
            }
        }

        if (!granted) ticket.await()

        return try {
            block()
        } finally {
            mutex.withLock {
                busy = false
                val next = when {
                    interactiveWaiters.isNotEmpty() -> interactiveWaiters.removeFirst()
                    backgroundWaiters.isNotEmpty() -> backgroundWaiters.removeFirst()
                    else -> null
                }
                if (next != null) {
                    busy = true
                    next.complete(Unit)
                }
            }
        }
    }
}
