@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.threadYield
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex

/**
 * AsyncSerializer impl backed by a coroutine [Mutex]. Blocking `action` /
 * `atomic` callers spin via `tryLock` + platform yield; suspending
 * [suspendAction] / [suspendAtomic] callers use the natural `Mutex.lock(owner)`
 * suspending wait. Shared between the two suspending entry points so a
 * suspendAtomic and a suspendAction on the same store block each other.
 *
 * The blocking side is THREAD-REENTRANT: the thread that holds the mutex via
 * [blockingAcquire] may re-acquire it (nested `action { action { } }`, an
 * `action` inside an `atomic` body, or an `atomic` nested inside an `action`)
 * without deadlocking — kotlinx's [Mutex] is not owner-reentrant, and a raw
 * re-`tryLock` with the shared spin owner would throw its
 * IllegalStateException. Reentrancy is tracked with a volatile holder-thread
 * id plus a holder-confined depth counter; on wasmJs every caller reports
 * thread id 0, which is correct there because wasmJs is single-threaded.
 */
internal class MutexSerializer : Store.AsyncSerializer {
    val mutex = Mutex()

    /**
     * Thread currently holding [mutex] via the BLOCKING side, or [NO_HOLDER].
     * Volatile so a non-holder thread reliably sees "someone else holds it"
     * and takes the spin path. Suspending holders never set this — a blocking
     * caller racing a suspending holder spins until the mutex frees.
     */
    @kotlin.concurrent.Volatile
    private var holderThreadId: Long = NO_HOLDER

    /** Reentry depth. Only ever touched by the holder thread. */
    private var holdDepth: Int = 0

    override fun blockingAcquire() {
        val me = currentThreadId()
        if (holderThreadId == me) {
            holdDepth++
            return
        }
        while (!mutex.tryLock(SPIN_OWNER)) {
            threadYield()
        }
        holderThreadId = me
        holdDepth = 1
    }

    override fun blockingRelease() {
        if (holderThreadId != currentThreadId()) return
        holdDepth--
        if (holdDepth > 0) return
        holderThreadId = NO_HOLDER
        runCatching { mutex.unlock(SPIN_OWNER) }
    }

    private companion object {
        private val SPIN_OWNER = Any()
        private const val NO_HOLDER = Long.MIN_VALUE
    }
}

/**
 * Lazy installation of the [Store.AsyncSerializer] hook on each store. The
 * hook is installed on first use and persists for the store's lifetime — the
 * coroutine [Mutex] inside it serializes blocking action, [suspendAction], and
 * [suspendAtomic] for any store that participates in any one of them.
 */
private val installLock = object : SynchronizedObject() {}

internal fun ensureSerializer(store: Store<*>): MutexSerializer {
    val installed = store.asyncSerializer as? MutexSerializer
    if (installed != null) return installed
    return synchronized(installLock) {
        val again = store.asyncSerializer as? MutexSerializer
        if (again != null) return@synchronized again
        val fresh = MutexSerializer()
        store.asyncSerializer = fresh
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
