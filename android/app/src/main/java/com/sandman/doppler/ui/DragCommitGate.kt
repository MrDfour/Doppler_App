package com.sandman.doppler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces a burst of slider edits into a small number of hardware commands.
 *
 * The Doppler's oatpp daemon is single threaded, so dispatching one write per drag
 * frame would queue hundreds of requests behind the user's finger. Every value handed
 * to [submit] replaces the one still waiting, and [commit] runs [settleDelayMs] after the
 * *first* edit of a burst, not after the last. That distinction is the whole design: a drag
 * therefore reaches the hardware roughly every [settleDelayMs] while the finger is still
 * moving, so it feels live, instead of a single write on release. The final value of a drag
 * is never dropped - the drain takes the newest value submitted, so whatever the user let go
 * at is what the clock is left holding.
 *
 * A single consumer coroutine drains the queue, so commands are always serial.
 */
class DragCommitGate(
    scope: CoroutineScope,
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    private val commit: suspend (Int) -> Unit
) {
    private val pending = Channel<Int>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                // Block until the first edit of a burst, then let the burst settle so the
                // write carries the newest value rather than the one the user has moved past.
                val first = pending.receive()
                delay(settleDelayMs)
                var value = first
                while (true) {
                    value = pending.tryReceive().getOrNull() ?: break
                }
                commit(value)
            }
        }
    }

    /** Queues [value], replacing any edit that has not been sent to the clock yet. */
    fun submit(value: Int) {
        pending.trySend(value)
    }

    companion object {
        const val DEFAULT_SETTLE_DELAY_MS = 250L
    }
}

/**
 * Remembers a [DragCommitGate] bound to the composition, so the pending value survives
 * recomposition while the slider is being dragged.
 */
@Composable
fun rememberDragCommitGate(
    settleDelayMs: Long = DragCommitGate.DEFAULT_SETTLE_DELAY_MS,
    commit: (Int) -> Unit
): DragCommitGate {
    val scope = rememberCoroutineScope()
    val currentCommit by rememberUpdatedState(commit)
    return remember(scope, settleDelayMs) {
        DragCommitGate(scope, settleDelayMs) { currentCommit(it) }
    }
}