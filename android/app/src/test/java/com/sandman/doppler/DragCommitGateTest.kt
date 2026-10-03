package com.sandman.doppler

import com.google.common.truth.Truth.assertThat
import com.sandman.doppler.ui.DragCommitGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [DragCommitGate], the throttle that keeps slider drags from flooding
 * the Doppler's single-threaded oatpp daemon.
 *
 * Tests:
 * - A drag burst collapses into a single command carrying the final value
 * - Continuous dragging is throttled to at most one command per settle window
 * - Commands are serialized, never concurrent
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DragCommitGateTest {

    @Test
    fun `drag burst collapses into a single command with the final value`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val committed = mutableListOf<Int>()
        try {
            val gate = DragCommitGate(scope) { committed += it }

            (0..50).forEach { gate.submit(it) }

            advanceTimeBy(DragCommitGate.DEFAULT_SETTLE_DELAY_MS + 50L)
            assertThat(committed).containsExactly(50)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `continuous drag is throttled and still lands the final value`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val committed = mutableListOf<Int>()
        var newestSubmitted = -1
        val staleWrites = mutableListOf<Int>()
        val settle = 250L
        try {
            val gate = DragCommitGate(scope, settleDelayMs = settle) { value ->
                committed += value
                if (value != newestSubmitted) staleWrites += value
            }

            // One drag event every 10ms for a full second of dragging.
            repeat(100) { index ->
                advanceTimeBy(10L)
                newestSubmitted = index
                gate.submit(index)
            }
            advanceTimeBy(settle + 50L)

            // Throttled: 100 drag events must not become 100 hardware writes.
            assertThat(committed.size).isAtMost(5)
            // Every write carries the newest value, never a value the user has moved past.
            assertThat(staleWrites).isEmpty()
            assertThat(committed).isInStrictOrder()
            assertThat(committed.last()).isEqualTo(99)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `commands are never issued concurrently`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var inFlight = 0
        var maxInFlight = 0
        try {
            val gate = DragCommitGate(scope, settleDelayMs = 50L) {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay(200)
                inFlight--
            }

            repeat(6) { index ->
                advanceTimeBy(400L)
                gate.submit(index)
            }
            advanceTimeBy(1_000L)

            assertThat(maxInFlight).isEqualTo(1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `gate stops pending commits when its scope is cancelled`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val committed = mutableListOf<Int>()
        val gate = DragCommitGate(scope) { committed += it }

        gate.submit(42)
        scope.cancel()
        advanceTimeBy(DragCommitGate.DEFAULT_SETTLE_DELAY_MS + 50L)

        assertThat(committed).isEmpty()
    }
}