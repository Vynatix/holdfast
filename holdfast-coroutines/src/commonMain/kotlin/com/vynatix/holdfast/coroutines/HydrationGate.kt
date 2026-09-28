@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.internalRefuseInitializerWrite
import kotlinx.coroutines.sync.Mutex
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
// interleaves with any other transaction on the store. Before deciding, the
// gate also waits out the one holder the serializer does not keep out: a
// blocking action, `atomic` or `reset` that read the serializer before its
// first install, which holds the transaction lock alone
// ([awaitLockOnlyHolders]). Each gate transaction still reads the phase
// again in its body, under the transaction lock, and decides nothing when a
// detach landed meanwhile — a backstop, since nothing else can commit on the
// store while the gate holds it. (A `:holdfast-testing` open transaction
// holds the store's active-transaction slot without either lock: a gate
// transaction that meets it fails — internalTopLevelAction refuses to open
// under an active transaction — so `hydrate()` throws, and a refresh's
// settle strands and reports.)
//
// It takes the serializer POLITELY: `tryLock`, and on failure a short backoff
// ([backOffUntil]), never `lock()`: it never queues on the Mutex, so it never
// becomes the Mutex's owner while parked in its queue (the hand-off window
// derived recomputes see as a busy store with no transaction), and it yields
// to every other holder. It holds the serializer only across non-suspending
// work — the decision and its transactions ([internalTopLevelAction]) —
// besides that wait, then releases it and drains the store's post-commit
// queue, as every top-level holder does (`Store.tryTopLevelAction`), all
// inside one settle scope, so the derived states a decision changes settle
// once it has released the store.

/** Serializes one store's hydration decisions: see the top of this file. */
internal class HydrationGate(
    private val store: Store<*>,
) {
    /**
     * Run [decide] while this gate holds the store: take the store's
     * serializer politely, wait out a blocking holder that read it as not
     * installed yet ([awaitLockOnlyHolders]), run [decide] (non-suspending: it
     * reads the phase and commits transactions through
     * [internalTopLevelAction]), then
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
                store.awaitLockOnlyHolders()
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
        backOffUntil {
            val taken = mutex.tryLock(token)
            check(taken || !store.isDisposed) { "store disposed" }
            taken
        }
    }
}

/**
 * Refuse [call] — a hydration entrypoint that takes [store] under the gate,
 * or waits for a refresh that must — from where it would deadlock or escape
 * a transaction, with [message]'s teaching text: inside a state
 * initializer, a schema migration or a derived state's compute; and inside
 * an action, an `atomic` frame, a `suspendAction` or a `suspendAtomic` of ANY
 * store — body or commit — on this thread or carried by this coroutine (its
 * settle scope is open there). `hydrate()` runs transactions of its own: on
 * [store] the gate would wait forever for the transaction that waits for it,
 * and on another store the seed would commit on its own, outside the
 * enclosing transaction and its rollback. `awaitSettled()` waits for a
 * refresh's settle, which takes [store] under the gate: inside one of
 * [store]'s entries it would wait forever for the transaction that waits for
 * it.
 *
 * Not detected: a coroutine that runs neither on the body's thread nor as a
 * child of a suspending body — one launched on another scope, or one a
 * blocking `action`/`atomic` body runs on another thread
 * (`runBlocking(Dispatchers.Default) { … }`, a `launch(Dispatchers.X)` inside
 * the body's `runBlocking`), since a blocking entry marks only its own
 * thread — which the body then waits for. On [store] its gate waits for the
 * body forever, politely: behind a serializer the body holds, or, holding
 * the serializer, for the transaction lock of a body that took the store
 * before its serializer was installed.
 */
internal suspend fun refuseInsideEntry(
    store: Store<*>,
    call: String,
    message: (store: Store<*>, call: String) -> String,
) {
    store.internalRefuseInitializerWrite("run $call")
    val carried = coroutineContext[SettleAmbientContext]?.scope?.isOpen == true
    if (carried || SettleScopes.current() != null || FrameMarkers.current() != null) {
        throw IllegalStateException(message(store, call))
    }
}

/** Why `hydrate()` may not run inside an entry: [refuseInsideEntry]. */
internal fun insideEntryMessage(
    store: Store<*>,
    call: String,
): String {
    val name = store::class.simpleName ?: "Store"
    return "Cannot run $call on $name's hydrator here: it runs transactions of its own on $name, so it may not " +
        "run inside an action, an atomic frame, a suspendAction or a suspendAtomic — body or commit (an observer, " +
        "a bridge publish, an event collector) — of any store. Inside one of $name's it would wait forever for " +
        "the transaction that waits for it, and inside another store's its seed would commit on its own, outside " +
        "the enclosing transaction and its rollback. Fix: call it once that transaction has returned — from the " +
        "coroutine that ran it, after it returns, or launched on a dispatcher that does not run it inline, e.g. " +
        "store.scope.launch(Dispatchers.Default) { hydrator.hydrate() } (on Dispatchers.Unconfined, or " +
        "Dispatchers.Main.immediate on the main thread, launch runs it at once, still inside the transaction). " +
        "To detach inside an action, use hydrator.stageInvalidate()."
}

/** Why `awaitSettled()` may not run inside an entry: [refuseInsideEntry]. */
internal fun awaitInsideEntryMessage(
    store: Store<*>,
    call: String,
): String {
    val name = store::class.simpleName ?: "Store"
    return "Cannot run $call on $name's hydrator here: a refresh in flight settles only by taking $name under " +
        "the hydration gate, so it may not run inside an action, an atomic frame, a suspendAction or a " +
        "suspendAtomic — body or commit (an observer, a bridge publish, an event collector) — of any store. " +
        "Inside one of $name's it would wait forever for the transaction that waits for it, and inside another " +
        "store's it would hold that store for as long as the refresh takes — forever, if what the adoption fires " +
        "needs that store. Fix: await it before opening the transaction, e.g. hydration.awaitSettled(), then " +
        "store.suspendAction { … }."
}
