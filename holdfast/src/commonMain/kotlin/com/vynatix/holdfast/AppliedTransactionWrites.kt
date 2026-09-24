package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentFanoutLocal
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.setFanoutLocal
import kotlinx.coroutines.flow.MutableSharedFlow

// Writes into a transaction that has already applied (issue #20, D16).
//
// A top-level commit applies its pending writes, then fans out to observers,
// bridges and event collectors while the transaction is still installed as the
// store's active one. Anything staged into it from that point on — an observer
// writing back into the store it observes, emitting, or opening a nested
// `action`/`atomic` — would never be applied. Every staging path refuses it
// instead: `mutate`/`update` and `emit` throw, a nested `action` or `atomic`
// returns `TransactionResult.Error`, each with the message built here.
//
// "Nested" means: on the transaction's owner thread (blocking commits), or
// inside its commit fanout — the synchronous `commitDispatching` window
// (`Transaction.fanoutThreadId`), or anywhere in a suspending commit
// (`FanoutMarkers`, installed by `:holdfast-coroutines`). Another thread's
// `action`/`atomic` is not nested: it waits for the store and commits.

/** The store's class name for failure messages; `"Store"` on targets without simple names. */
internal val Store<*>.displayName: String
    get() = this::class.simpleName ?: "Store"

/**
 * Teaching text for a refused [attempt] ("write CounterStore.count", "emit an
 * event on CounterStore", …) into [txn], which is closed to writes
 * ([Transaction.closedToWrites]). Names the store when [storeName] is known,
 * says why the write cannot land, and lists the fixes — none of which is
 * `postCommit`, an internal hook. A transaction closed because it (or an
 * ancestor) was rolled back ([Transaction.rolledBackIn]) gets
 * [rolledBackTransactionMessage] instead.
 */
internal fun appliedTransactionMessage(
    attempt: String,
    storeName: String?,
    txn: Transaction,
): String {
    val owner = storeName?.let { "$it's transaction" } ?: "the transaction"
    txn.rolledBackIn?.let { return rolledBackTransactionMessage(attempt, owner, txn, it) }
    val status = txn.status.takeIf { it != TransactionStatus.Active }?.let { " (status: $it)" } ?: ""
    return "Cannot $attempt: $owner '${txn.id}' has already applied its writes$status — its commit is " +
        "fanning out to observers, bridges and event collectors, or has finished — so anything staged into " +
        "it now would never commit. This usually means an observer (effect/observe) writes back into the " +
        "store it observes while that store's commit is notifying it. Fix: make the write part of the " +
        "action itself, before it commits; derive the value instead (computed { } or derived(...)); or run " +
        "it as a separate action once this one has finished — as `store action { … }` on another thread, " +
        "which waits for the store, or launched on a dispatcher that does not run it inline, checking the " +
        "result: store.scope.launch(Dispatchers.Default) { store action { … }.getOrThrow() }. On " +
        "Dispatchers.Unconfined, or Dispatchers.Main.immediate while already on the main thread, the " +
        "launched body runs inside this commit and is refused the same way."
}

/**
 * [appliedTransactionMessage] for a transaction closed because it, or an
 * ancestor, was rolled back: e.g. an `atomic` participant written from a
 * middleware's `onTransactionError` or a `FrameObserver` while the frame
 * unwinds, or a write after a manual `rollback()`.
 */
private fun rolledBackTransactionMessage(
    attempt: String,
    owner: String,
    txn: Transaction,
    rolledBack: Transaction,
): String {
    val whose = if (rolledBack === txn) "" else " of its enclosing transaction '${rolledBack.id}'"
    return "Cannot $attempt: $owner '${txn.id}' has already been rolled back (status: ${rolledBack.status}" +
        "$whose), so anything staged into it now would be discarded. This usually means code that runs " +
        "while a transaction or an atomic(...) frame unwinds (a middleware's onTransactionError, a " +
        "FrameObserver), or code after a manual rollback(), writes into it. Fix: run the write as a " +
        "separate action once this one has finished."
}

/**
 * Teaching text for a bare `mutate`/`update` ([attempt]) refused because a
 * `suspendAction` or `suspendAtomic` holds [storeName]'s store and its
 * transaction [txn] has already applied, while the caller is not recognised as
 * part of that commit — typically another thread. `suspendingOwner` makes a
 * bare write from any thread stage into the suspending transaction (its body
 * may resume on any thread), so this is not the observer case the default
 * message describes.
 */
internal fun suspendingCommitWriteMessage(
    attempt: String,
    storeName: String,
    txn: Transaction,
): String =
    "Cannot $attempt: a suspendAction or suspendAtomic holds $storeName, and its transaction '${txn.id}' " +
        "has already applied its writes and is still committing. While a suspending transaction holds a " +
        "store, a bare mutate/update from any thread stages into that transaction instead of opening an " +
        "action of its own — before it applies, the write silently joins it; after, it could never commit. " +
        "Fix: from another thread, write through `store action { … }`, which waits until the store is free " +
        "and then commits. From inside that commit (a bridge publish or an event collector), write in the " +
        "action itself or derive the value instead."

/**
 * Accessors for the thread-local commit-fanout marker: the transactions whose
 * suspending commit the current thread is running — its observers, bridge
 * publishes, event emits and frame observers. They are roots, or savepoints
 * for a nested `suspendAtomic`'s participants held by an enclosing frame.
 * `:holdfast-coroutines`
 * installs it around the commit phase of `suspendAction` and `suspendAtomic`
 * and keeps it coherent across coroutine dispatch, so a blocking
 * `action`/`atomic` on one of those stores from inside that commit is
 * recognised as nested (see `Store.appliedTransactionNestedHere`) even after
 * the commit has hopped threads — it would otherwise wait for the serializer
 * this very commit holds. Application code has no reason to touch it.
 */
@StoreInternalApi
object FanoutMarkers {
    /** The transactions whose suspending commit this thread is running, or `null`. */
    @Suppress("UNCHECKED_CAST")
    fun current(): Set<Transaction>? = currentFanoutLocal() as Set<Transaction>?

    /**
     * Install [roots] (or clear the slot when `null`) and return the previous
     * value so callers can restore it — install/restore must always pair.
     */
    fun install(roots: Set<Transaction>?): Set<Transaction>? {
        val prior = current()
        setFanoutLocal(roots)
        return prior
    }
}

/**
 * Whether this thread is inside the commit of this transaction or of one of
 * its ancestors: the synchronous [Transaction.commitDispatching] fanout window
 * on this thread, or — under `suspendAction`/`suspendAtomic` — anywhere in
 * that suspending commit ([FanoutMarkers]). An ancestor counts because a
 * nested `suspendAtomic` marks the savepoints it opened for stores its
 * enclosing frame holds: code inside its commit that reaches one of those
 * stores meets the savepoint (committed, or still pending) installed there.
 */
@OptIn(StoreInternalApi::class)
internal fun Transaction.fanningOutHere(): Boolean {
    val here = currentThreadId()
    val marked = FanoutMarkers.current()
    var t: Transaction? = this
    while (t != null) {
        if (t.fanoutThreadId == here || marked?.contains(t) == true) return true
        t = t.parent
    }
    return false
}

/**
 * The result of a nested `action` or `atomic` frame that [store] refused to
 * open under [applied], which is closed to writes (typically: its root has
 * applied): a savepoint of [applied], rolled back before anything
 * ran, carrying the teaching [IllegalStateException]. Neither the body nor any
 * middleware hook runs — like a frame-contract rejection, the refused
 * transaction never started.
 */
internal fun refusedUnderAppliedTransaction(
    store: Store<*>,
    applied: Transaction,
    id: String,
    attempt: String,
    frameId: String? = null,
): TransactionResult.Error {
    val refused = Transaction(id = id, parent = applied, ownerThreadId = currentThreadId(), frameId = frameId)
    refused.rollback()
    val name = store.displayName
    val error = IllegalStateException(appliedTransactionMessage("$attempt on $name", name, applied))
    return TransactionResult.Error(error, refused)
}

/**
 * `emit` for [store]: stage [event] on [channel] in this transaction, refusing
 * one that is closed to writes with a message naming [store]
 * ([Transaction.stagePendingEvent] refuses it too, but cannot name the store).
 * The callers refuse an emit from inside a state initializer before they look
 * for a transaction.
 */
@OptIn(StoreInternalApi::class)
internal fun Transaction.stageEmittedEvent(
    store: Store<*>,
    channel: MutableSharedFlow<*>,
    event: Any,
) {
    check(!closedToWrites) {
        appliedTransactionMessage("emit an event on ${store.displayName}", store.displayName, this)
    }
    stagePendingEvent(channel, event)
}
