@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Run [body] as a cross-store atomic frame: every enrolled store's transaction
 * commits or rolls back together. Inside the body, `a.action { … }` joins the
 * frame as a savepoint of store `a`'s root transaction, and bare
 * `mutate`/`update` calls stage directly into the owning store's root.
 *
 * **Enrollment is enforced.** A write to a store that is NOT in the [stores]
 * list throws [UnenrolledStoreException] — such a write would commit
 * independently and would not roll back with the frame, silently breaking the
 * all-or-nothing promise. Pass `policy = FramePolicy.AllowUnenrolled` to
 * deliberately run an independent side-transaction inside the frame.
 * Enforcement covers the frame BODY only: middleware hooks, commit fanout, and
 * observers run outside the enforcement window (an observer that writes to a
 * foreign store during fanout is post-commit and legal, exactly as before).
 *
 * **Inner errors escalate.** An inner `action { }` on a participant store that
 * returns [TransactionResult.Error] aborts the whole frame; the frame returns
 * `Error` carrying the inner exception. Pass
 * `policy = FramePolicy.TolerateInnerErrors` to keep a failed sub-action from
 * aborting the frame — you then own checking each inner result.
 *
 * **Frame-contract violations rethrow.** [FrameContractException]s
 * ([UnenrolledStoreException], [FrameLockOrderException],
 * [FrameInteropException]) are programming errors: the frame rolls back and
 * RETHROWS them rather than folding them into an ignorable `Error` result.
 *
 * Locking: [stores] are de-duplicated and sorted by [Store.lockOrderKey]
 * before acquisition, giving a deadlock-safe global order across any store
 * combination. Each store's blocking `transactionLock` is held for the whole
 * frame — keep bodies small and free of I/O, same rule as `action { }`.
 *
 * Commit fanout order (the cross-store consistency contract):
 *  1. per-store middleware `onTransactionStarted`, in lock order (before body);
 *  2. the body;
 *  3. per-store middleware `onTransactionCompleted`, in lock order — ALL
 *     stores' `completed` hooks fire before ANY store commits, so a validation
 *     middleware throwing on the last store still rolls every store back
 *     (for frames, `completed` does not mean durably-committed);
 *  4. per-store commit, in lock order — store A's observer/bridge/event fanout
 *     completes before store B's commit applies;
 *  5. [FrameObserver.onFrameCommitted].
 * On abort, roots roll back in REVERSE lock order with per-store middleware
 * `onTransactionError` first. Rollback never touches state and never re-runs
 * `Transformer.set`. If a COMMIT itself throws partway (step 4), stores that
 * already committed stay committed — same in-memory-2PC limitation as before.
 *
 * Every participant root shares one [Transaction.frameId], so middleware and
 * the testing harness can correlate the per-store transactions of one frame.
 *
 * Nesting: an `atomic` nested inside an `action` or another `atomic` on the
 * same thread opens SAVEPOINTS of the enclosing transactions for shared
 * stores — inner commit merges into the enclosing scope; enclosing rollback
 * discards everything. A nested frame may only introduce stores whose
 * `lockOrderKey` sorts above every key the enclosing frame holds; violating
 * that throws [FrameLockOrderException] at entry (before any lock is taken).
 *
 * Limitations:
 *  - Body is non-suspending and must be single-threaded — writes from spawned
 *    threads are not recognized as in-frame (and are not policed either).
 *  - This is in-memory 2PC in one process: bridge/persistence publishes remain
 *    per-store post-commit fanout; there is no crash-consistency across
 *    external stores.
 *
 * Example:
 * ```
 * val r = atomic(accountA, accountB) {
 *     accountA.action { balance update { it - amount } }
 *     accountB.action { balance update { it + amount } }
 * }
 * when (r) {
 *     is TransactionResult.Success -> log("transfer ok")
 *     is TransactionResult.Error   -> log("transfer rolled back: ${r.exception}")
 * }
 * ```
 */
@OptIn(ExperimentalUuidApi::class)
fun <R> atomic(
    vararg stores: Store<*>,
    policy: FramePolicy = FramePolicy.Strict,
    body: () -> R,
): TransactionResult<R> {
    require(stores.isNotEmpty()) { "atomic requires at least one store" }
    // De-duplicate by identity and sort by global lock order key.
    val sorted = stores.toSet().sortedBy { it.lockOrderKey }
    val enclosing = FrameMarkers.current()
    // O(1)-per-store nested-frame safety: interop flavor + lock-order checks,
    // BEFORE any lock is acquired.
    verifyFrameNesting(enclosing, sorted, suspending = false)
    val ownerThreadId = currentThreadId()
    val id = "atomic-${Uuid.random()}"
    val marker =
        FrameMarker(
            frameId = id,
            participants = sorted.toSet(),
            policy = policy,
            suspending = false,
            parent = enclosing,
        )
    val result = acquireAndRun(sorted, 0, mutableListOf(), id, ownerThreadId, marker, body)
    // A nested frame is an inner unit of the enclosing frame's body: its Error
    // escalates just like an inner action's, unless the ENCLOSING policy
    // tolerates inner errors. (Contract exceptions never reach here — they
    // rethrow out of executeBody directly.)
    if (result is TransactionResult.Error && enclosing != null && !enclosing.policy.tolerateInnerErrors) {
        throw result.exception
    }
    return result
}

/**
 * One participant slot: the store, the frame root (or savepoint) transaction
 * opened for it, and its pre-bound middleware session.
 */
private class FrameRoot(
    val store: Store<*>,
    val txn: Transaction,
    val session: FrameMiddlewareSession,
)

/**
 * Tail-recursive helper that acquires each store's transactionLock in order
 * via [Store.runUnderLock], then opens a root [Transaction] per store, then
 * runs [body], then commits/rollbacks all roots, then unwinds.
 *
 * A store whose thread already has an active transaction (an enclosing
 * `action` or `atomic` on this thread) gets a SAVEPOINT root — commit merges
 * into the enclosing scope, rollback discards only this frame's writes. All
 * other stores get fresh top-level roots.
 */
@Suppress("LongParameterList")
private fun <R> acquireAndRun(
    sorted: List<Store<*>>,
    index: Int,
    roots: MutableList<FrameRoot>,
    id: String,
    ownerThreadId: Long,
    marker: FrameMarker,
    body: () -> R,
): TransactionResult<R> {
    if (index == sorted.size) {
        return executeBody(roots, marker, body)
    }
    val v = sorted[index]
    return v.runUnderLock {
        val priorActive = v.activeTransaction
        val root =
            if (priorActive != null && priorActive.ownerThreadId == ownerThreadId) {
                // Nested inside an enclosing action/atomic on this thread for this
                // store: open a savepoint so this frame's commit merges into the
                // enclosing scope and this frame's rollback discards only its own
                // writes — never the enclosing transaction's.
                Transaction.createSavepointForExternal(id, ownerThreadId, priorActive, frameId = id)
            } else {
                Transaction.createForExternal(id, ownerThreadId, frameId = id)
            }
        v.internalSetActiveTransaction(root)
        roots.add(FrameRoot(v, root, v.internalFrameMiddlewareSession(root)))
        try {
            acquireAndRun(sorted, index + 1, roots, id, ownerThreadId, marker, body)
        } finally {
            v.internalSetActiveTransaction(priorActive)
            // Top-level exit for this store: drain deferred work (derived
            // recomputes) queued during the frame — same contract as the
            // blocking action's top-level drain. Runs after the active
            // transaction is restored so recomputes open fresh top-level
            // actions instead of nesting under a terminal root.
            if (priorActive == null) v.internalDrainPostCommitTasks()
        }
    }
}

/**
 * Run the frame body between the per-store middleware phases, then commit all
 * roots in lock order (success) or roll all back in reverse lock order
 * (failure). The frame marker is installed around the BODY only — enrollment
 * enforcement never polices middleware hooks or commit fanout.
 */
private fun <R> executeBody(
    roots: List<FrameRoot>,
    marker: FrameMarker,
    body: () -> R,
): TransactionResult<R> {
    val observers = FrameObservers.snapshot()
    val resultTxn = roots.last().txn
    observers.forEach { runCatching { it.onFrameStarted(marker.frameId, roots.map(FrameRoot::store)) } }
    return try {
        // Phase 1: middleware `started` per store, in lock order. A throw here
        // aborts the frame before the body runs.
        roots.forEach { it.session.fireStarted() }
        val prior = FrameMarkers.install(marker)
        val value: R =
            try {
                body()
            } finally {
                // Clear BEFORE completed/commit/observer fanout: enforcement
                // polices the body only.
                FrameMarkers.install(prior)
            }
        // Phase 2: ALL stores' `completed` hooks fire before ANY store commits,
        // so a validation middleware throwing on the last store still rolls
        // every store back.
        roots.forEach { it.session.fireCompleted() }
        // Phase 3: commit in lock order. Store A's observer fanout completes
        // before store B's commit applies.
        roots.forEach { it.txn.commit() }
        observers.forEach { runCatching { it.onFrameCommitted(marker.frameId) } }
        TransactionResult.Success(resultTxn, value)
    } catch (e: Throwable) {
        // Reverse lock-order unwind: per-store error hooks, then rollback.
        // Each step is isolated so one store's failure can't strand another's
        // pending writes.
        for (i in roots.indices.reversed()) {
            val entry = roots[i]
            runCatching { entry.session.fireError(e) }
            runCatching { entry.txn.rollback() }
        }
        observers.forEach { runCatching { it.onFrameRolledBack(marker.frameId, e) } }
        // Contract violations are programming errors — fail loud instead of
        // folding into an ignorable Error result.
        if (e is FrameContractException) throw e
        TransactionResult.Error(e, resultTxn)
    }
}
