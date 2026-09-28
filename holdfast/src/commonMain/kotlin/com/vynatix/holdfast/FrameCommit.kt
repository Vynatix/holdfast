@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.cancellation.CancellationException

// Committing transactions, and frames of them (issue #20, R9; plan decision D17).
//
// A top-level commit runs two passes. The APPLY pass assigns every pending
// write to its state inside one write bracket (ConsistentRead.kt), running no
// user code but a `distinct` state's `equals`. The FANOUT pass then notifies
// observers, publishes to bridges and drains events — the per-store contract
// observers → bridge publish → events, the sealed states' observers
// (SealedStates.kt) before any other's. A single transaction runs the two back
// to back (Transaction.commitDispatching). A frame (`atomic`/`suspendAtomic`)
// applies EVERY participant first, inside one bracket spanning all their
// top-level transactions' states, and only then fans each participant out, in
// lock order: a consistent cut across the participants sees all of the frame
// or none of it, and an observer of one participant finds every other
// participant already applied — so it cannot write into one any more, like
// into its own store. That holds for the participants that open a fresh
// top-level root: every one of an outermost frame, or of a nested frame that
// shares no store with the action or frame it is nested in. A participant a
// frame shares with an enclosing action or frame is a savepoint: its writes
// merge into the enclosing transaction and apply when that one commits.
//
// Between the two passes a root is applied but still active, and still the
// store's active transaction (and its middleware's context): a frame leaves
// every root in that window while the earlier ones fan out. A `commit()` (or
// `rollback()`) by hand on such a root — from an observer, say — is a no-op:
// its fanout belongs to the frame (or to the action's own commit, whose root
// sits in the same window while it fans out). `applyAlone` (and
// `Transaction.rollback`) decides that under the root's pending lock, where
// the apply pass marks it applied; and the other way round, a frame's pass
// leaves a root alone that a commit from another thread applied first under
// that lock (`applyBracketed`) — its fanout belongs to that commit. Likewise
// a savepoint whose parent has applied or ended meanwhile refuses to merge
// (D16) instead of leaving its writes in a buffer nobody applies again.

/**
 * What a transaction's apply pass left for its fanout: the writes that
 * changed a state ([committed], in pending-write order — deduped `distinct`
 * states are left out), the events to drain ([events]), the store they
 * belong to, for failure messages ([storeLabel]), and the keyed entries the
 * pass evicted ([evicted]), whose observers, bridges and inbound
 * subscriptions the fanout shuts down before anything else (KeyedCommit.kt).
 */
internal class AppliedWrites(
    val committed: List<Pair<MutableState<*>, Any>>,
    val events: List<Pair<MutableSharedFlow<*>, Any>>,
    val storeLabel: String?,
    val evicted: List<MutableState<*>> = emptyList(),
)

/** Which pass of a commit was running when it threw. */
internal enum class CommitPhase(
    val label: String,
) {
    APPLY("state-apply"),
    FANOUT("observer/bridge fanout"),
    EVENTS("event drain"),
}

/**
 * The outcome of [applyFrameCommit]: the top-level transactions that applied
 * ([applied], in the order given), each of which the caller must now fan out
 * with [fanOutApplied], and the failure that stopped the pass early
 * ([failure]), if any.
 */
@StoreInternalApi
class FrameApply internal constructor(
    val applied: List<Transaction>,
    val failure: Throwable?,
)

/**
 * The apply pass of a frame's commit: apply every pending write of the
 * top-level transactions among [transactions] inside one write bracket
 * spanning all their states, holding all their pending locks — so a
 * consistent cut ([readConsistent]) sees every one of them applied or none,
 * and a write racing the pass from another thread either lands before it or
 * is refused — and only then commit every savepoint among them into its
 * parent. Pass the participants in lock order; a transaction that is not
 * active is skipped, and so is a root that a commit from another thread
 * applied meanwhile — it is not in [FrameApply.applied], since its fanout
 * belongs to that commit ([applyBracketed]). A savepoint's writes apply with
 * its parent, when the enclosing action or frame commits, not here.
 *
 * Each top-level transaction is then applied — [Transaction.applied], closed
 * to writes — but still active: the caller fans each out with
 * [fanOutApplied], in the same order, which turns it Committed. A savepoint
 * is committed here and needs no fanout.
 *
 * Nothing here throws. A top-level transaction whose apply throws (a
 * `distinct` state's `equals`), or a savepoint whose merge throws (its
 * parent has applied or ended meanwhile, see [mergeIntoParent]), is marked
 * Failed and ends the pass: [FrameApply.failure] carries the failure (wrapped
 * in a [TransactionException] naming the transaction, store and phase), and
 * the top-level transactions applied before it are in [FrameApply.applied].
 * The ones after it are left active, for the caller to roll back — and so is
 * every savepoint when an apply fails: none has merged yet, so none of its
 * writes survives in the enclosing transaction of a frame that reports an
 * error. When a savepoint's MERGE fails (its parent was finished by hand),
 * the savepoints merged before it stay merged into their enclosing
 * transactions, which may still commit them; it is Failed, its buffers
 * cleared; and the ones after it are left active for the caller to roll
 * back. The states the pass had assigned stay assigned; the write bracket is
 * closed either way.
 */
@StoreInternalApi
fun applyFrameCommit(transactions: List<Transaction>): FrameApply {
    val active = transactions.filter { it.status == TransactionStatus.Active }
    val (savepoints, roots) = active.partition { it.parent != null }
    val applied = ArrayList<Transaction>(roots.size)
    // Roots first: a savepoint merged before a root's apply failed could not
    // be rolled back (rolling back a Committed transaction does nothing), so
    // its writes would survive in the enclosing transaction of a frame that
    // reports an error. The merges run after the roots' pending locks are
    // released: a savepoint's parent is never a root of this frame, and the
    // only lock nesting is savepoint, then parent.
    val failure =
        roots.withPendingLocks(0) { applyBracketed(roots, applied) }
            ?: savepoints.firstNotNullOfOrNull { it.mergeIntoParent() }
    return FrameApply(applied, failure)
}

/**
 * The apply pass of one transaction ([Transaction.commitDispatching]): a
 * savepoint merges into its parent, a top-level transaction applies inside a
 * write bracket of its own. [applyFrameCommit] of this transaction alone,
 * without its bookkeeping — every commit runs it. Returns whether THIS call
 * applied a top-level transaction, which the caller must now fan out with
 * [fanOutApplied]; `false` when there was nothing to do — the transaction is
 * not active, it is a savepoint (merged here; a savepoint needs no fanout),
 * or its writes have already applied — and throws the apply or merge failure
 * (a [TransactionException], after marking the transaction Failed).
 *
 * "Already applied" is decided under [Transaction.pendingLock], where
 * [applyWrites] sets it: a frame ([applyFrameCommit]) leaves each root
 * applied but still active until its turn to fan out, and a `commit()` by
 * hand on such a root meanwhile — from an observer of an earlier participant,
 * through `Store.activeTransaction` or a middleware context — must leave it
 * alone. It used to re-run the pass over the emptied buffers and replace the
 * fanout's input ([Transaction.applyResult]) with nothing, so when the frame
 * reached the root there was nothing left to fan out: its observers, bridges
 * and events never fired while its values stood. The same check makes a hand
 * commit from an observer of the transaction's own fanout a no-op (the
 * status transition it forced used to fail the running fanout), and covers a
 * commit from another thread that read the root as unapplied before the
 * frame's pass took the lock. [Transaction.closedToWrites] rather than
 * [Transaction.applied] alone, so a rollback that raced the status check is
 * left alone too.
 */
internal fun Transaction.applyAlone(): Boolean {
    if (status != TransactionStatus.Active) return false
    val appliedHere = ArrayList<Transaction>(1)
    val failure =
        if (parent != null) {
            mergeIntoParent()
        } else {
            pendingLock.withLock {
                if (closedToWrites) null else applyBracketed(listOf(this), appliedHere)
            }
        }
    failure?.let { throw it }
    return appliedHere.isNotEmpty()
}

/**
 * Apply [roots] one after the other inside one write bracket over all their
 * states, adding each that applied to [applied], and stop at the first that
 * fails: its failure, or `null`. The caller holds every root's pending lock.
 *
 * A root that is already [Transaction.applied] is left alone, and not added
 * to [applied]: a `commit()` by hand from another thread took its pending
 * lock before this pass did and applied it (the caller's status check ran
 * before the locks were taken), so its buffers are empty and its fanout
 * belongs to that commit. Re-running the pass over them would replace the
 * fanout's input ([Transaction.applyResult]) with nothing, and both commits
 * would then try to turn the root Committed.
 */
private fun applyBracketed(
    roots: List<Transaction>,
    applied: MutableList<Transaction>?,
): Throwable? {
    // A stable copy: opening and closing must cover exactly the same states,
    // the keyed entries each root evicts included (see bracketedStates).
    val states = if (roots.size == 1) roots[0].bracketedStates() else roots.flatMap { it.bracketedStates() }
    // Before any bracket opens: a capture that listed keyed entries learns
    // that a commit applied since (KeyedRegistry.commitsApplied).
    val stores =
        if (roots.size == 1) {
            listOfNotNull(states.firstOrNull()?.owningStore)
        } else {
            states.mapTo(LinkedHashSet()) { it.owningStore }
        }
    for (store in stores) store.registry.keyed.commitApplying()
    openWriteBracket(states)
    try {
        for (root in roots) {
            if (root.applied) continue
            val failure = root.applyWrites()
            if (failure != null) return failure
            applied?.add(root)
        }
        return null
    } finally {
        // Closed even if a `distinct` state's `equals` throws, so no reader
        // waits forever on a bracket this commit left open.
        closeWriteBracket(states)
    }
}

/** Run [block] holding the pending locks of this list's transactions from [from] on, in list order. */
private fun <R> List<Transaction>.withPendingLocks(
    from: Int,
    block: () -> R,
): R = if (from == size) block() else this[from].pendingLock.withLock { withPendingLocks(from + 1, block) }

/**
 * Assign this top-level transaction's pending writes, collecting the ones
 * that changed a state, and leave them with the staged events for
 * [fanOutApplied]; then mark it [Transaction.applied] and close its buffers.
 * The caller holds its pending lock and has opened the write bracket on every
 * state it writes. Returns `null`, or the failure after marking it Failed.
 *
 * Assignment only: fanout happens afterwards, once every state of the
 * transaction (of the whole frame) holds its committed value, so an observer
 * reading a sibling state sees the committed value directly. The events are
 * drained after the pending lock is released: `tryEmit` may invoke ready
 * collectors synchronously, and no internal lock is ever held across user code.
 *
 * A `distinct` state's `equals` does run here, under the pending lock, and
 * may first-read — so materialize — a state of the store, waiting on its
 * initializer latch: the one place a pending lock is held while waiting on a
 * latch, which is why nothing takes a pending lock under a latch
 * ([Transaction.pendingLock]; [MutableState.value] peeks at no pending write
 * inside an initializer).
 *
 * Once every write is assigned, the keyed entries this transaction evicts
 * are retired and dropped from their families ([retireEvictions]) — inside
 * the same write bracket, so a consistent cut sees the evictions exactly
 * when it sees the writes. Their shutdown is left to the fanout.
 */
private fun Transaction.applyWrites(): Throwable? {
    val storeLabel = pendingWrites.keys.firstOrNull()?.describeOwner()
    val committed = mutableListOf<Pair<MutableState<*>, Any>>()
    val failure =
        runCatching {
            pendingWrites.forEach { (state, value) ->
                @Suppress("UNCHECKED_CAST")
                if ((state as MutableState<Any>).applyCommittedValue(value)) committed += state to value
            }
        }.exceptionOrNull()
    if (failure != null) {
        recordEndTime()
        return failCommit(CommitPhase.APPLY, failure, storeLabel, committed)
    }
    applyResult = AppliedWrites(committed, pendingEvents.toList(), storeLabel, retireEvictions())
    applied = true
    buffersClosed = true
    pendingWrites.clear()
    pendingEvents.clear()
    return null
}

/**
 * Commit this savepoint: merge its pending writes and events into its
 * parent's, under both pending locks (child before parent, the only nesting
 * order), since a foreign write may be staging there. Last write wins on a
 * state both hold, and the parent fires this savepoint's events after its own,
 * preserving stage order across the whole tree. Its keyed-entry evictions
 * merge the same way, last operation winning ([mergeEvictionsInto]). Returns
 * `null`, or the failure after marking it Failed.
 *
 * A parent that is [Transaction.closedToWrites] — it, or an ancestor, has
 * applied its writes or ended, typically by a `commit()`/`rollback()` by
 * hand on the enclosing transaction from this savepoint's body or middleware
 * — refuses the merge (D16, like every other write into its buffers), with
 * an [IllegalStateException] in the [appliedTransactionMessage] family, so
 * the nested action returns `Error` instead of Success with its writes in a
 * buffer nobody applies again. Checked under the parent's pending lock, where
 * its apply pass, merge or rollback closes the buffers, so the merge either
 * lands before that or is refused, never dropped in between. Either way this
 * savepoint's buffers are closed and cleared — merged, or refused: a Failed
 * savepoint keeps nothing staged, like a rolled-back one. (A savepoint that
 * rolled ITSELF back never gets here: [applyAlone] finds it not active.)
 */
private fun Transaction.mergeIntoParent(): Throwable? {
    var storeLabel: String? = null
    val failure =
        runCatching {
            val parentTxn = checkNotNull(parent)
            pendingLock.withLock {
                storeLabel = pendingWrites.keys.firstOrNull()?.describeOwner()
                try {
                    parentTxn.pendingLock.withLock {
                        check(!parentTxn.closedToWrites) {
                            appliedTransactionMessage(
                                "merge nested transaction '$id' into its enclosing transaction",
                                storeLabel,
                                parentTxn,
                            )
                        }
                        parentTxn.pendingWrites.putAll(pendingWrites)
                        parentTxn.pendingEvents.addAll(pendingEvents)
                        mergeEvictionsInto(parentTxn)
                    }
                } finally {
                    // Consumed for good either way, merged or refused; still
                    // under this savepoint's pending lock, so the closing is
                    // one step against every staging path.
                    buffersClosed = true
                    pendingWrites.clear()
                    pendingEvents.clear()
                    evictions.clear()
                }
            }
            updateStatus(TransactionStatus.Committed)
        }.exceptionOrNull()
    recordEndTime()
    return failure?.let { failCommit(CommitPhase.APPLY, it, storeLabel, emptyList()) }
}

/**
 * The fanout pass of this applied top-level transaction: first the derived
 * states following its store as a whole are told (StoreEdges.kt; that only
 * queues their recomputes); then the keyed entries it evicted are shut down —
 * their observers dropped, bridges detached and `observeFrom` subscriptions
 * disposed, silently — and their families' membership listeners told
 * ([shutDownEvicted]); then [fanout] with
 * the writes that changed a SEALED state (SealedStates.kt); then what a
 * shutdown threw is reported ([reportShutdownFailures]); then [fanout] with
 * every other write that changed a state; then the staged events — through
 * [drainEvents] when given, else `tryEmit` — then Committed. Each [fanout]
 * call notifies observers, then publishes to bridges (a sealed state has no
 * bridge), each in pending-write order. A no-op for a transaction with no
 * apply result: a savepoint, one not applied, or one already fanned out.
 *
 * Sealed states go first because their owner keeps its bookkeeping in their
 * observers (a hydrator mirrors its committed phase there): run before
 * anything a rethrowing `uncaughtObserverHandler` can end the fanout with —
 * a user observer, a bridge publish, a shutdown's `dispose` — they see every
 * commit that applied.
 *
 * Runs outside every internal lock, with [Transaction.fanoutThreadId] naming
 * this thread. The transaction refuses writes throughout (see
 * [Transaction.commitDispatching]). A throwing [fanout] or [drainEvents]
 * marks it Failed and throws a [TransactionException] naming the phase (a
 * [CancellationException] is rethrown as it is); its values stay applied.
 */
@StoreInternalApi
fun Transaction.fanOutApplied(
    fanout: (List<Pair<MutableState<*>, Any>>) -> Unit,
    drainEvents: ((List<Pair<MutableSharedFlow<*>, Any>>) -> Unit)?,
) {
    val writes = applyResult ?: return
    applyResult = null
    var phase = CommitPhase.FANOUT
    fanoutThreadId = currentThreadId()
    val failure =
        runCatching {
            // First, as it runs no user code: a derived state following this
            // store as a whole queues its recompute (StoreEdges.kt).
            writes.tellStoreEdges()
            val shutdownFailures = if (writes.evicted.isEmpty()) emptyList() else shutDownEvicted(writes.evicted)
            val (sealed, others) = writes.committed.sealedFirst()
            if (sealed.isNotEmpty()) fanout(sealed)
            reportShutdownFailures(shutdownFailures)
            if (others.isNotEmpty()) fanout(others)
            // Events drain AFTER observer fanout and AFTER bridge publishes: a
            // collector subscribed to both `state.asFlow()` and `store.events`
            // sees the state value before the event. Sync `commit()` cannot
            // suspend, so a full SUSPEND-policy buffer falls back to drop
            // here; suspending callers pass a drainEvents that honors it.
            phase = CommitPhase.EVENTS
            if (writes.events.isNotEmpty()) (drainEvents ?: ::emitEvents)(writes.events)
            updateStatus(TransactionStatus.Committed)
        }.exceptionOrNull()
    fanoutThreadId = NOT_FANNING_OUT
    recordEndTime()
    if (failure != null) throw failCommit(phase, failure, writes.storeLabel, writes.committed)
}

/**
 * Commit a blocking `atomic` frame's roots, given in lock order: apply them
 * all ([applyFrameCommit]), then fan each out in turn with the blocking
 * fanout. A root whose fanout fails still leaves the others to fan out —
 * every one of them has applied — and the first failure (an apply failure
 * first) is thrown once they have, carrying later ones as suppressed.
 */
internal fun commitFrame(transactions: List<Transaction>) {
    val apply = applyFrameCommit(transactions)
    var failure = apply.failure
    for (txn in apply.applied) {
        val fanoutFailure = runCatching { txn.fanOutApplied(::fanOutBlocking, drainEvents = null) }.exceptionOrNull()
        if (fanoutFailure != null) failure = failure?.apply { addSuppressed(fanoutFailure) } ?: fanoutFailure
    }
    if (failure != null) throw failure
}

/** The blocking commit's fanout: every changed state's observers, then every bridge publish. */
internal fun fanOutBlocking(committed: List<Pair<MutableState<*>, Any>>) {
    @Suppress("UNCHECKED_CAST")
    committed.forEach { (state, value) -> (state as MutableState<Any>).fanOutToObservers(value) }
    @Suppress("UNCHECKED_CAST")
    committed.forEach { (state, value) -> (state as MutableState<Any>).publishToBridge(value) }
}

/** The blocking event drain: `tryEmit` each staged event, in stage order. */
private fun emitEvents(events: List<Pair<MutableSharedFlow<*>, Any>>) {
    for ((channel, event) in events) {
        @Suppress("UNCHECKED_CAST")
        (channel as MutableSharedFlow<Any>).tryEmit(event)
    }
}

/**
 * Mark this transaction Failed after [cause] ended its commit in [phase], and
 * return what to throw: [cause] itself for a [CancellationException] —
 * cancellation is control flow, and structured concurrency must see its own
 * exception — else a [TransactionException] naming the transaction, the
 * store, the phase and how many states were already applied (a bare "Commit
 * failed" leaves the reader no way to tell which store, state or phase).
 * `Throwable`, not `Exception`: an `Error` escaping would otherwise leave the
 * transaction Active while the caller reports a rollback that never happened.
 */
private fun Transaction.failCommit(
    phase: CommitPhase,
    cause: Throwable,
    storeLabel: String?,
    committed: List<Pair<MutableState<*>, Any>>,
): Throwable {
    runCatching { updateStatus(TransactionStatus.Failed) }
    if (cause is CancellationException) return cause
    val where = storeLabel?.let { " on $it" } ?: ""
    val frame = frameId?.let { " of frame '$it'" } ?: ""
    val states = if (committed.isEmpty()) "" else " (${committed.size} state(s) already applied)"
    val message = "Commit of transaction '$id'$frame$where failed during the ${phase.label} phase$states"
    return TransactionException(message, cause)
}
