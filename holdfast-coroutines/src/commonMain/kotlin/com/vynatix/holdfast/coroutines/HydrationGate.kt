@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.internalRefuseInitializerWrite
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

// The hydration gate (issue #20, R8; plan decision D19).
//
// Every hydration decision — seed a detached store, retry a failed refresh,
// adopt what a refresh fetched, record its failure — reads the phase and
// commits the next one while the gate holds the store, so no two decisions on
// one store interleave: two concurrent hydrate() calls seed once and fetch
// once, and fifty calls on a failed hydration retry once. The gate holds the
// store's serializer (the coroutine Mutex that `suspendAction`, `suspendAtomic`
// and blocking actions on the store share), so a decision also never
// interleaves with any other transaction on the store.
//
// It takes the serializer POLITELY: `tryLock`, and on failure a short backoff
// — a yield, then delays doubling from 1 ms to 32 ms — and never `lock()`: it
// never queues on the Mutex, so it never becomes the Mutex's owner while
// parked in its queue (the hand-off window derived recomputes see as a busy
// store with no transaction), and it yields to every other holder. It holds
// the serializer only across non-suspending work — the decision and its
// transactions ([internalTopLevelAction]) — then releases it and drains the
// store's post-commit queue, as every top-level holder does
// (`Store.tryTopLevelAction`), all inside one settle scope, so the derived
// states a decision changes settle once it has released the store. The
// backoff waits in the coroutine's own time, never the store's clock: a
// fixed test clock cannot wedge it.

/** The first backoff after a yield, in milliseconds; each retry doubles it up to [MAX_BACKOFF_MS]. */
private const val FIRST_BACKOFF_MS = 1L

/** The longest the gate waits between two attempts to take the store, in milliseconds. */
private const val MAX_BACKOFF_MS = 32L

/** Serializes one store's hydration decisions: see the top of this file. */
internal class HydrationGate(
    private val store: Store<*>,
) {
    /**
     * Run [decide] while this gate holds the store: take the store's
     * serializer politely, run [decide] (non-suspending: it reads the phase
     * and commits transactions through [internalTopLevelAction]), then
     * release the serializer and drain the store's post-commit queue — inside
     * one settle scope, which settles after that.
     *
     * @throws IllegalStateException when the store is disposed while the gate
     *   waits for it, and whatever [decide] throws.
     */
    suspend fun <R> hold(decide: () -> R): R {
        val serializer = ensureSerializer(store)
        return settlingSuspended {
            val token = Any()
            acquire(serializer.mutex, token)
            try {
                decide()
            } finally {
                serializer.mutex.unlock(token)
                // After the release, on every exit: a derived recompute that
                // found the store busy while the gate held it was handed to
                // this queue (the hand-off invariant, Store.tryTopLevelAction).
                store.internalDrainPostCommitTasks()
            }
        }
    }

    /** Take [mutex] with [token], trying and backing off, never queueing (see the top of this file). */
    private suspend fun acquire(
        mutex: Mutex,
        token: Any,
    ) {
        var backoff = 0L
        while (!mutex.tryLock(token)) {
            check(!store.isDisposed) { "store disposed" }
            if (backoff == 0L) yield() else delay(backoff)
            backoff = (backoff * 2).coerceIn(FIRST_BACKOFF_MS, MAX_BACKOFF_MS)
        }
    }
}

/**
 * Refuse [call] (`"hydrate()"`), a hydration entrypoint that runs transactions
 * of its own on [store] through the gate, from where it would deadlock or
 * escape a transaction: inside a state initializer, a schema migration or a
 * derived state's compute; and inside an action, an `atomic` frame, a
 * `suspendAction` or a `suspendAtomic` of ANY store — body or commit — on
 * this thread or carried by this coroutine (its settle scope is open there).
 * On [store] the gate would wait forever for the transaction that waits for
 * it; on another store the seed would commit on its own, outside the
 * enclosing transaction and its rollback.
 *
 * Not detected: a coroutine that is not a child of the body — one launched on
 * another scope — which the body then waits for. Its gate waits, politely,
 * for the body forever.
 */
internal suspend fun refuseInsideEntry(
    store: Store<*>,
    call: String,
) {
    store.internalRefuseInitializerWrite("run $call")
    val carried = coroutineContext[SettleAmbientContext]?.scope?.isOpen == true
    if (carried || SettleScopes.current() != null || FrameMarkers.current() != null) {
        throw IllegalStateException(insideEntryMessage(store, call))
    }
}

private fun insideEntryMessage(
    store: Store<*>,
    call: String,
): String {
    val name = store::class.simpleName ?: "Store"
    return "Cannot run $call on $name's hydrator here: it runs transactions of its own on $name, so it may not " +
        "run inside an action, an atomic frame, a suspendAction or a suspendAtomic — body or commit (an observer, " +
        "a bridge publish, an event collector) — of any store. Inside one of $name's it would wait forever for " +
        "the transaction that waits for it, and inside another store's its seed would commit on its own, outside " +
        "the enclosing transaction and its rollback. Fix: call it once that transaction has returned — for " +
        "example store.scope.launch { hydrator.hydrate() } — and to detach inside an action, use " +
        "hydrator.stageInvalidate()."
}
