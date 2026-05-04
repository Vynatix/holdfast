@file:OptIn(com.vynatix.holdfast.HoldfastInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.Holdfast
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex

/**
 * AsyncSerializer impl backed by a coroutine [Mutex]. Blocking `action` callers
 * spin via `tryLock` + platform yield; suspending [suspendAction] / [suspendAtomic]
 * callers use the natural `Mutex.lock(owner)` suspending wait. Shared between
 * the two suspending entry points so a suspendAtomic and a suspendAction on
 * the same vault block each other.
 */
internal class MutexSerializer : Holdfast.AsyncSerializer {
    val mutex = Mutex()

    override fun blockingAcquire() {
        while (!mutex.tryLock(SPIN_OWNER)) {
            com.vynatix.holdfast.platform.threadYield()
        }
    }

    override fun blockingRelease() {
        runCatching { mutex.unlock(SPIN_OWNER) }
    }

    private companion object {
        private val SPIN_OWNER = Any()
    }
}

/**
 * Lazy installation of the [Holdfast.AsyncSerializer] hook on each vault. The
 * hook is installed on first use and persists for the vault's lifetime — the
 * coroutine [Mutex] inside it serializes blocking action, [suspendAction], and
 * [suspendAtomic] for any vault that participates in any one of them.
 */
private val installLock = object : SynchronizedObject() {}

internal fun ensureSerializer(vault: Holdfast<*>): MutexSerializer {
    val installed = vault.asyncSerializer as? MutexSerializer
    if (installed != null) return installed
    return synchronized(installLock) {
        val again = vault.asyncSerializer as? MutexSerializer
        if (again != null) return@synchronized again
        val fresh = MutexSerializer()
        vault.asyncSerializer = fresh
        fresh
    }
}

/**
 * Commit a transaction with the suspending-action bridge / event interpose.
 *
 * Identical to [Transaction.commit] for nested (savepoint) transactions —
 * pending writes merge into the parent's buffer. For a top-level transaction,
 * each pending write is applied via [MutableState.applyCommittedRaw] (replace
 * value + observer fanout, NO bridge publish), and the bridge publish is
 * dispatched separately:
 *
 *  - If the bound bridge is a [SuspendingBridge], call its [SuspendingBridge.publishAwaited]
 *    directly — the surrounding `withContext(NonCancellable)` ensures the write
 *    completes even if the calling scope cancels.
 *  - Otherwise (sync [com.vynatix.holdfast.Bridge] or no bridge), call
 *    [com.vynatix.holdfast.Bridge.publish] fire-and-forget, matching the sync
 *    action contract.
 *
 * Events are drained AFTER bridge publishes via suspending `emit` so
 * `BufferOverflow.SUSPEND` back-pressure is honored.
 *
 * Pending writes are pre-snapshotted before observer fanout so the suspend
 * call out of [Transaction.commitDispatching] does not run inside its
 * `pendingLock.withLock` block — `publishAwaited` is genuinely suspending and
 * could deadlock or cause re-entrant lock issues otherwise. The downside is
 * one extra map allocation; the upside is correctness.
 *
 * Shared between [suspendAction] and [suspendAtomic] so the commit-phase
 * ordering contract is single-sourced.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun suspendingCommit(txn: Transaction) {
    val publishQueue = mutableListOf<Pair<MutableState<Any>, Any>>()
    val eventsQueue = mutableListOf<Pair<MutableSharedFlow<*>, Any>>()
    txn.commitDispatching(
        applyTopLevel = { state, value ->
            val ms = state as MutableState<Any>
            // Step 1+2: replace + observers (no bridge publish yet).
            // applyCommittedRaw returns false when the state is `distinct = true`
            // and the new value equals the current — observer fanout was skipped
            // by dedup. Mirror sync `applyCommitted`'s contract: skip the bridge
            // publish in that case too.
            if (ms.applyCommittedRaw(value)) publishQueue += ms to value
        },
        drainEvents = { snapshot ->
            eventsQueue.addAll(snapshot)
        },
    )
    // Step 3a: bridge publish phase. SuspendingBridge gets awaited;
    // every other Bridge falls back to fire-and-forget Bridge.publish.
    for ((ms, value) in publishQueue) {
        val br = ms.bridge ?: continue
        if (br is SuspendingBridge<*>) {
            (br as SuspendingBridge<Any>).publishAwaited(value)
        } else {
            br.publish(value)
        }
    }
    // Step 3b: events drain via suspending emit, honoring SUSPEND back-pressure.
    for ((channel, event) in eventsQueue) {
        (channel as MutableSharedFlow<Any>).emit(event)
    }
}
