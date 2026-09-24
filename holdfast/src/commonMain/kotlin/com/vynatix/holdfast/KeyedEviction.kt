@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId

// Staging keyed-entry evictions (issue #20, R7; plan D16, D18).
//
// `evict`/`evictAll` stage like `mutate`: into the store's transaction open on
// this thread (and, while a `suspendAction`/`suspendAtomic` holds the store,
// from any thread), else as a one-shot action. A transaction's evictions are a
// buffer next to its pending writes (`Transaction.evictions`), disjoint from
// them — the last operation on an entry wins: evicting drops the entry's
// pending write, and a write to the entry, or a `get` of its key on the
// transaction's thread, cancels the eviction. Under a suspending holder only a
// write cancels: its body's thread cannot be told from another coroutine's on
// the thread it started on (or any thread on wasmJs), so a `get` there never
// touches its evictions. Nor does a `get` from an `atomic` frame body that
// does not enroll the store (unless the frame's policy allows unenrolled
// writes): the transaction it would cancel in is an enclosing action's, which
// commits whatever the frame does, so the cancel would escape the frame's
// rollback — the frame refuses a write there, and a read leaves the eviction
// staged instead. The transaction's own thread reads its staged
// evictions (`contains`, `getOrNull` and `entries` leave the entry out), as
// it reads its own writes; every other thread sees committed membership only.
// The commit applies them (KeyedCommit.kt).
//
// D16: a write into a transaction that has already applied fails loudly — but
// an eviction from that transaction's own commit fanout (an observer of the
// store) is deferred through the store's post-commit queue instead: the
// entries live at the call are evicted, once the commit has released the
// store, by a transaction of their own (id `Evict`) that the never-blocking
// `Store.tryTopLevelAction` runs — handed off to the store's holder when the
// store is busy, which drains it after it releases. It is the only write that
// defers.

/**
 * Stage the eviction of the entries [targets] lists for this family ([attempt]
 * names the call for refusals), or, with no transaction of the store to stage
 * into on this thread, do it in a one-shot action (listing [targets] there).
 * From the store's own commit fanout, the eviction of the entries [targets]
 * lists now is deferred until the commit is done (see the top of this file).
 *
 * @throws IllegalStateException if the store is disposed, inside a no-write
 *   region, or for a foreign thread's eviction into a suspending commit.
 * @throws UnenrolledStoreException inside a frame that does not enroll the
 *   store.
 */
internal fun KeyedFamily<*, *>.stageEvictions(
    attempt: String,
    targets: () -> List<MutableState<*>>,
) {
    store.checkNotDisposed()
    NoWriteRegion.refuse { attempt }
    val txn = store.activeTransaction
    if (txn != null && store.stagesInto(txn)) {
        stageEvictionsInto(txn, attempt, targets)
    } else {
        // As mutate does: a one-shot action, whose middleware and observers
        // see a committed eviction. Listing the targets inside it is still
        // listing them at the call: the action runs synchronously.
        store.action { stageEvictions(attempt, targets) }
    }
}

/** The staging half of [stageEvictions]: police, then stage into [txn] — or defer, or refuse. */
private fun KeyedFamily<*, *>.stageEvictionsInto(
    txn: Transaction,
    attempt: String,
    targets: () -> List<MutableState<*>>,
) {
    // The frame rule stageWrite applies: an unenrolled store's transaction
    // here is an enclosing action's, which commits whatever the frame does.
    val frame = FrameMarkers.current()
    if (frame != null && !frame.isEnrolled(store) && !frame.policy.allowUnenrolled) {
        throw UnenrolledStoreException(store.unenrolledMessage(frame, "evict"))
    }
    // Listed once: a deferred eviction evicts exactly the entries live now.
    val states = targets()
    when {
        txn.stageEviction(states) -> Unit
        // D16: from this store's own commit fanout, the eviction waits for the
        // commit to finish instead of failing like a write.
        txn.rootApplied && store.appliedTransactionNestedHere() === txn -> {
            if (states.isNotEmpty()) store.postCommit(DeferredEviction(store, states))
        }
        else -> throw IllegalStateException(store.evictionRefusal(attempt, txn))
    }
}

/**
 * Stage the eviction of [states] under [Transaction.pendingLock], dropping
 * their pending writes; `false`, staging nothing, when the transaction is
 * closed to writes.
 */
private fun Transaction.stageEviction(states: List<MutableState<*>>): Boolean =
    pendingLock.withLock {
        if (closedToWrites) return@withLock false
        for (state in states) {
            evictions[state] = true
            pendingWrites.remove(state)
        }
        true
    }

/**
 * An eviction deferred out of a commit's fanout: the eviction of [states] —
 * those not retired by then (evicted meanwhile, or dropped with a disposed
 * store), so an entry created again for one of their keys is never touched —
 * in a transaction of its own (id [EVICT_ID]) once the commit has released
 * the store.
 *
 * Every attempt goes through [Store.tryTopLevelAction], which never blocks: a
 * post-commit drain runs it wherever a holder releases the store — a
 * `suspendAction`'s `finally` on a thread another coroutine waiting for the
 * store's mutex can only run on, or inside another store's fanout — and a
 * blocking acquire there could wait forever (or add a lock-order edge). A
 * busy store gets the task handed to its queue plus one retry, safe by the
 * hand-off invariant (its holder drains after it releases); a successful
 * attempt withdraws a handed-off copy first, so no later drain runs it
 * again. Its failure — a middleware rejecting it, say — is reported through
 * the store's `uncaughtObserverHandler`, as the post-commit side effect it
 * is; on a store disposed meanwhile it does nothing.
 */
private class DeferredEviction(
    private val store: Store<*>,
    private val states: List<MutableState<*>>,
) : () -> Unit {
    override fun invoke() {
        val first = attempt()
        if (first is TopLevelAttempt.Busy || first is TopLevelAttempt.BusyNoTxn) {
            store.handOffPostCommit(this)
            attempt()
        }
    }

    private fun attempt(): TopLevelAttempt {
        val outcome =
            runCatching {
                store.tryTopLevelAction(EVICT_ID, onAcquired = { store.withdrawPostCommit(this) }) {
                    // This attempt's own transaction: owned here, open, in no frame.
                    val txn = checkNotNull(store.activeTransaction)
                    check(txn.stageEviction(states.filterNot { it.retired }))
                }
            }
        val failure =
            outcome.fold(
                onSuccess = { ((it as? TopLevelAttempt.Ran)?.result as? TransactionResult.Error)?.exception },
                onFailure = { it },
            )
        if (failure != null && !store.isDisposed) store.internalReportUncaughtFailure(failure)
        return outcome.getOrDefault(TopLevelAttempt.Disposed)
    }
}

/** The id of a deferred eviction's transaction, as middleware sees it. */
private const val EVICT_ID = "Evict"

/**
 * Why an eviction into [txn], which is closed to writes, is refused: the
 * foreign-thread case under a suspending commit, else the write-into-an-applied
 * (or rolled back) transaction case.
 */
private fun Store<*>.evictionRefusal(
    attempt: String,
    txn: Transaction,
): String =
    if (suspendingOwner != null && txn.rolledBackIn == null && !txn.fanningOutHere()) {
        suspendingCommitWriteMessage(attempt, displayName, txn)
    } else {
        appliedTransactionMessage(attempt, displayName, txn)
    }

/**
 * This store's active transaction when this thread reads through it — its
 * owner thread, outside any no-write region, exactly where
 * [MutableState.value] reads pending writes — so its staged evictions hide
 * their entries; `null` otherwise (committed membership).
 */
internal fun Store<*>.evictionView(): Transaction? {
    val txn = activeTransaction ?: return null
    return txn.takeIf { it.ownerThreadId == currentThreadId() && NoWriteRegion.current() == null }
}

/**
 * Whether this transaction's savepoint chain evicts [state]: the innermost
 * transaction that decided anything about it decides (the last operation
 * wins). Each buffer is read under its pending lock, child before parent.
 */
internal fun Transaction.evictsInChain(state: MutableState<*>): Boolean {
    var txn: Transaction? = this
    while (txn != null) {
        val level = txn
        val decision = level.pendingLock.withLock { level.evictions[state] }
        if (decision != null) return decision
        txn = level.parent
    }
    return false
}

/**
 * Cancel an eviction of [state] that this transaction's chain stages — a
 * write to a keyed entry, the last operation on it, wins. The caller holds
 * this transaction's pending lock and has staged the write.
 */
internal fun Transaction.cancelStagedEviction(state: MutableState<*>) {
    if (state.declaration?.kind == StateKind.Keyed && evictsInChain(state)) evictions[state] = false
}

/**
 * `get` of [state]'s key on the thread of this store's transaction: cancel
 * the eviction of [state] that transaction stages, if any — the last
 * operation on an entry wins. Not while a `suspendAction`/`suspendAtomic`
 * holds the store: its transaction's owner thread is only where its body
 * started, so a `get` from any other coroutine on that thread (or anywhere
 * on wasmJs, where every thread id is 0) would cancel the body's eviction.
 * Nor from an `atomic` frame body that does not enroll this store (and whose
 * policy does not allow unenrolled writes): the transaction is then an
 * enclosing action's, which commits whatever the frame does, so the cancel
 * would escape the frame's rollback. A read does not throw there, as a write
 * does ([UnenrolledStoreException]): the eviction stays staged. A write
 * still cancels it wherever it may stage ([cancelStagedEviction]).
 */
internal fun Store<*>.cancelEvictionInView(state: MutableState<*>) {
    // Owner before slot, as ownsActiveTransaction reads them.
    val view = if (suspendingOwner == null) evictionView() else null
    if (view == null || !view.evictsInChain(state)) return
    // The frame rule stageWrite applies, without its throw: a get is a read.
    val frame = FrameMarkers.current()
    if (frame != null && !frame.isEnrolled(this) && !frame.policy.allowUnenrolled) return
    view.pendingLock.withLock { if (!view.closedToWrites) view.evictions[state] = false }
}
