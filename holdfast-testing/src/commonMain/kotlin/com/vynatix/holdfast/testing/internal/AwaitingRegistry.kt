package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.testing.HoldfastEvent
import com.vynatix.holdfast.testing.HoldfastTestScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel

/**
 * Backing list for live `awaiting { ... }` subscriber channels created via
 * [com.vynatix.holdfast.testing.concurrency.awaiting]. Held inside the
 * [HoldfastTestScope] so the lifetime mirrors the test body; [cancelAll] is
 * called from `tearDown` to close every still-live subscriber so a forgotten
 * `awaiting` (one whose surrounding coroutine never resumed) is unblocked
 * with a [kotlinx.coroutines.channels.ClosedReceiveChannelException] rather
 * than leaving a coroutine suspended past the test boundary.
 *
 * Thread-safe: an `awaiting` call may register from the test thread and a
 * sibling `parallel` worker may push events that fan out to the channel
 * concurrently with the registry's [add] / [remove] operations. The lock is
 * held only briefly around list mutation; iteration during teardown takes a
 * defensive snapshot first.
 *
 * Mirrors the [BarrierRegistry] / [OpenTransactionRegistry] shape — the seam
 * between `concurrency/` extension functions and the scope's `internal`
 * registry lives in this file's bottom-of-file bridge.
 */
internal class AwaitingRegistry {
    private val lock = SynchronizedObject()
    private val channels: MutableList<Channel<HoldfastEvent>> = mutableListOf()

    fun add(channel: Channel<HoldfastEvent>) {
        synchronized(lock) {
            channels.add(channel)
        }
    }

    fun remove(channel: Channel<HoldfastEvent>) {
        synchronized(lock) {
            channels.removeAll { it === channel }
        }
    }

    /**
     * Snapshot the current channel list, close every entry, and clear the
     * registry. Closing a channel makes any pending
     * [kotlinx.coroutines.channels.Channel.receive] in the corresponding
     * `awaiting` body resume with a
     * [kotlinx.coroutines.channels.ClosedReceiveChannelException] — the
     * `awaiting` body's `try/finally` then runs its own unsubscribe path.
     *
     * This runs BEFORE [com.vynatix.holdfast.testing.internal.Recorder.dispose]
     * in [HoldfastTestScope.tearDown] so the recorder still has a valid view of
     * its subscriber list at the moment we close — the unsubscribe call from
     * the awaiting body's finally block is then a clean no-op (or removes
     * the entry whose channel is already drained).
     */
    fun cancelAll() {
        val snapshot = synchronized(lock) {
            val copy = channels.toList()
            channels.clear()
            copy
        }
        for (channel in snapshot) {
            channel.close()
        }
    }
}

/**
 * Bridge from public-API extension functions in `concurrency/` back to the
 * scope's `internal` registry. Co-located here so the seam is obvious and so
 * the [AwaitingRegistry] type can stay `internal`. Mirrors the
 * [registerBarrier] / [openTransactionsRegistry] shape.
 */
internal fun HoldfastTestScope.awaitingsRegistry(): AwaitingRegistry = awaitingRegistry()
