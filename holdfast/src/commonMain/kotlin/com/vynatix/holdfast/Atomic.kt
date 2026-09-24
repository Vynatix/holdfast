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
 * store outside the frame during fanout is post-commit and legal, exactly as
 * before; one that writes to a participant is refused, see below).
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
 *  4. apply: EVERY store's writes are assigned inside one write bracket
 *     spanning all the participants' states, before any store fans out — a
 *     consistent read across the participants sees the whole frame or none of
 *     it, from any thread. That holds when every participant opens a fresh
 *     top-level root: an outermost frame, or a nested one that shares no
 *     store with the action or frame it is nested in. A participant shared
 *     with an enclosing action or frame is a savepoint (see Nesting): its
 *     writes apply when the enclosing transaction commits, so until then a
 *     consistent read can see the frame's other stores new and that one old.
 *     Enroll every store in the outermost frame to keep the guarantee;
 *  5. per-store fanout, in lock order — each store's observers, then its
 *     bridge publishes, then its events. Every participant has applied by
 *     then, so an observer of store A reads store B's committed value, and
 *     cannot write into B (or open an action or frame on it) any more than
 *     into A: that is refused as a write into an applied transaction;
 *  6. [FrameObserver.onFrameCommitted];
 *  7. once the outermost action or frame on this thread has exited and
 *     released everything it took, derived states settle: each
 *     [derivedState]/[merged] a participant's commit changed a source of
 *     recomputes once, from a committed cut, and the post-commit work
 *     (`derived` recomputes) of every store whose root this frame opened runs.
 * On abort, roots roll back in REVERSE lock order with per-store middleware
 * `onTransactionError` first. Rollback never touches state and never re-runs
 * `Transformer.set`. If the apply itself throws partway (step 4; only a
 * `distinct` state's `equals` can), stores applied before it still fan out and
 * stay committed, and the rest roll back — as does every participant joined
 * as a savepoint, whose writes would otherwise have merged into the enclosing
 * transaction; a store whose fanout fails (step 5; only a throwing
 * [Store.uncaughtObserverHandler] can) does not keep the later ones from
 * fanning out — same in-memory-2PC limitation as before. Either way the frame
 * returns `Error` and [FrameObserver.onFrameRolledBack] fires instead of
 * `onFrameCommitted`, although the applied participants' values stand; their
 * middleware gets no `onTransactionError`.
 *
 * Every participant root shares one [Transaction.frameId], so middleware and
 * the testing harness can correlate the per-store transactions of one frame.
 *
 * Nesting: an `atomic` nested inside an `action` or another `atomic` on the
 * same thread opens SAVEPOINTS of the enclosing transactions for shared
 * stores — inner commit merges into the enclosing scope, and an enclosing
 * rollback discards those shared stores' writes. The stores the nested frame
 * introduces get fresh roots, which commit when the nested frame exits: an
 * enclosing rollback cannot undo them. A nested frame may only introduce
 * stores whose `lockOrderKey` sorts above every key the enclosing frame holds;
 * violating that throws [FrameLockOrderException] at entry (before any lock is
 * taken).
 * A frame that would nest into a participant's transaction that has already
 * applied — `atomic(store) { … }` from an observer of `store` while `store`'s
 * commit is notifying it — behaves like a nested `action` there: it returns
 * [TransactionResult.Error] carrying an [IllegalStateException], before any
 * lock is taken or any body or middleware runs, because its savepoint could
 * never commit. From inside a state initializer or a schema migration
 * ([SchemaVersioned.migrate]), `atomic` throws [IllegalStateException]
 * before taking anything: both may read states but not write (see
 * [Store.state]).
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
    NoWriteRegion.refuse { "open an atomic(...) frame on ${sorted.joinToString { it.displayName }}" }
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
    // Stores whose root this frame opened top-level. The frame is their holder,
    // so it owes their post-commit queues a drain, but only once it has fully
    // unwound: a drain at a participant's own unwind step would run derived
    // recomputes (and their observers) while the EARLIER participants' locks
    // and committed roots were still installed on this thread, so an observer
    // writing to one of those stores hit a finished transaction.
    val drainOnExit = mutableListOf<Store<*>>()
    val result =
        refuseFrameUnderAppliedTransaction(sorted, id) ?: settling {
            try {
                acquireAndRun(sorted, 0, mutableListOf(), drainOnExit, id, ownerThreadId, marker, body)
            } finally {
                // After BOTH the transaction locks and the serializers are
                // released, and deferred to the settle of the outermost entry
                // on this thread (this frame's own, unless it is nested in
                // another action or frame): a recompute opens a fresh
                // top-level action, which would otherwise find its store still
                // held. This drain also serves recomputes other threads handed
                // to this frame while it held the store (Store.tryTopLevelAction).
                drainOnExit.forEach { it.internalDrainPostCommitTasksWhenSettled() }
            }
        }
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
 * The refusal of a frame that would nest into a participant's transaction
 * that has already applied its writes (see `Store.appliedTransactionNestedHere`),
 * or `null` when no participant would. Checked before any serializer or lock
 * is taken: under a `suspendAction`'s or `suspendAtomic`'s commit, the
 * serializer acquire would wait for the very coroutine running this call.
 */
private fun refuseFrameUnderAppliedTransaction(
    sorted: List<Store<*>>,
    id: String,
): TransactionResult.Error? {
    for (store in sorted) {
        val applied = store.appliedTransactionNestedHere() ?: continue
        return refusedUnderAppliedTransaction(store, applied, id, "open an atomic(...) frame", frameId = id)
    }
    return null
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
 * other stores get fresh top-level roots. A store that had no active
 * transaction is added to [drainOnExit], whose drains [atomic] defers to the
 * settle of this thread's outermost entry, once the whole frame has unwound.
 */
@Suppress("LongParameterList")
private fun <R> acquireAndRun(
    sorted: List<Store<*>>,
    index: Int,
    roots: MutableList<FrameRoot>,
    drainOnExit: MutableList<Store<*>>,
    id: String,
    ownerThreadId: Long,
    marker: FrameMarker,
    body: () -> R,
): TransactionResult<R> {
    if (index == sorted.size) {
        return executeBody(roots, marker, body)
    }
    val v = sorted[index]
    // Serialize this participant against in-flight suspending work, exactly as
    // `action` does. Without it a frame took only `transactionLock`, installed a
    // fresh root over a suspendAction's active transaction, and — because
    // `suspendingOwner` relaxes `mutate`'s owner check — that suspending body
    // then staged its writes into the frame's transaction.
    //
    // Acquired in the same globally-sorted `lockOrderKey` order as the
    // transaction locks, so the extra lock cannot introduce a cycle; skipped
    // when this thread is already inside the store's serialized region (an
    // enclosing action, or an outer frame that holds this store).
    val serializer = if (v.internalOwnsActiveTransaction()) null else v.asyncSerializer
    serializer?.blockingAcquire()
    try {
        return v.runUnderLock {
            val priorActive = v.activeTransaction
            // A fresh top-level root makes this frame the store's holder. A store
            // that already had a transaction leaves the drain to its holder.
            if (priorActive == null) drainOnExit += v
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
                acquireAndRun(sorted, index + 1, roots, drainOnExit, id, ownerThreadId, marker, body)
            } finally {
                v.internalSetActiveTransaction(priorActive)
            }
        }
    } finally {
        serializer?.blockingRelease()
    }
}

/**
 * Run the frame body between the per-store middleware phases, then commit all
 * roots — apply every one, then fan each out in lock order — (success) or
 * roll all back in reverse lock order (failure). The frame marker is
 * installed around the BODY only — enrollment enforcement never polices
 * middleware hooks or commit fanout.
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
        // Phase 3: apply EVERY store inside one write bracket, then fan each
        // out in lock order: A's observers already see B applied.
        commitFrame(roots.map(FrameRoot::txn))
        observers.forEach { runCatching { it.onFrameCommitted(marker.frameId) } }
        TransactionResult.Success(resultTxn, value)
    } catch (e: Throwable) {
        // Reverse lock-order unwind of every root still open: per-store error
        // hooks, then rollback. Each step is isolated so one store's failure
        // can't strand another's pending writes. A root that already applied
        // (it committed, or failed in its own fanout) gets no error hook — its
        // values stand, as after a single action's commit failure — and
        // neither does one whose own apply failed; only the roots the commit
        // never reached (and every root, when the body or a hook threw) do.
        for (i in roots.indices.reversed()) {
            val entry = roots[i]
            if (entry.txn.status == TransactionStatus.Active) {
                runCatching { entry.session.fireError(e) }
                runCatching { entry.txn.rollback() }
            }
        }
        observers.forEach { runCatching { it.onFrameRolledBack(marker.frameId, e) } }
        // Contract violations are programming errors — fail loud instead of
        // folding into an ignorable Error result.
        if (e is FrameContractException) throw e
        TransactionResult.Error(e, resultTxn)
    }
}
