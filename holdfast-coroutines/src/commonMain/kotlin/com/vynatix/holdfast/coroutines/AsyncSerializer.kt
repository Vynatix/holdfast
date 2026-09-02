@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException

/**
 * AsyncSerializer impl backed by a coroutine [Mutex]. Blocking `action` callers
 * spin via `tryLock` + platform yield; suspending [suspendAction] / [suspendAtomic]
 * callers use the natural `Mutex.lock(owner)` suspending wait. Shared between
 * the two suspending entry points so a suspendAtomic and a suspendAction on
 * the same store block each other.
 *
 * The blocking bracket deliberately passes NO owner token. kotlinx's
 * `Mutex.tryLock(owner)` does not return `false` when the mutex is already held
 * by that same token — it throws `IllegalStateException("This mutex is already
 * locked by the specified owner")`. A single shared token for every blocking
 * caller therefore made the second of two concurrent blocking `action`s (or
 * `atomic` frames) on a store that had ever run a `suspendAction` throw that
 * raw error out of `Store.action`, before its `try`, instead of waiting its
 * turn. Without a token, `tryLock()` simply reports "held" and the caller keeps
 * spinning until the holder — coroutine or thread — releases.
 */
internal class MutexSerializer : Store.AsyncSerializer {
    val mutex = Mutex()

    override fun blockingAcquire() {
        while (!mutex.tryLock()) {
            com.vynatix.holdfast.platform
                .threadYield()
        }
    }

    override fun blockingRelease() {
        runCatching { mutex.unlock() }
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
 * every pending write is applied to state first, then observers fan out via
 * [MutableState.fanOutToObservers] (NO bridge publish), and the bridge publish
 * is dispatched separately:
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
 * The publish queue is collected during fanout and drained after
 * [Transaction.commitDispatching] returns, so the suspending `publishAwaited`
 * never runs inside the transaction's `pendingLock` — it could otherwise
 * deadlock or re-enter the lock.
 *
 * Publish failures are isolated per state and reported through
 * [com.vynatix.holdfast.Store.uncaughtObserverHandler], matching the sync path:
 * a bridge is external sync, so a failed write cannot undo values that are
 * already committed, nor stop the remaining states from publishing.
 *
 * Shared between [suspendAction] and [suspendAtomic] so the commit-phase
 * ordering contract is single-sourced.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun suspendingCommit(txn: Transaction) {
    val publishQueue = mutableListOf<Pair<MutableState<Any>, Any>>()
    val eventsQueue = mutableListOf<Pair<MutableSharedFlow<*>, Any>>()
    txn.commitDispatching(
        fanout = { committed ->
            // Step 2: observers for every state whose value actually changed.
            // Deduped `distinct` states never reach here, so they correctly skip
            // the bridge publish too.
            committed.forEach { (state, value) ->
                val ms = state as MutableState<Any>
                ms.fanOutToObservers(value)
                publishQueue += ms to value
            }
        },
        drainEvents = { snapshot ->
            eventsQueue.addAll(snapshot)
        },
    )
    // Step 3a: bridge publish phase. SuspendingBridge gets awaited;
    // every other Bridge falls back to fire-and-forget Bridge.publish.
    for ((ms, value) in publishQueue) {
        val br = ms.bridge ?: continue
        try {
            if (br is SuspendingBridge<*>) {
                (br as SuspendingBridge<Any>).publishAwaited(value)
            } else {
                br.publish(value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ms.owningStore.uncaughtObserverHandler?.invoke(e)
        }
    }
    // Step 3b: events drain via suspending emit, honoring SUSPEND back-pressure.
    for ((channel, event) in eventsQueue) {
        (channel as MutableSharedFlow<Any>).emit(event)
    }
}
