@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.NoWriteRegion
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.settling

// The one flat frame a subtree operation runs in (issue #21 decision U10):
// `Root.restore` and `Root.reset(node)` prepare every leaf outside the
// locks, then stage each leaf into its root of ONE outermost `atomic` over
// the subtree's live leaves — all-or-nothing, applied in one write bracket,
// the write half of `Root.snapshot`'s cut. It refuses to nest inside any
// entry on this thread, exactly as `restoreInOneFrame` does: a nested frame
// is not one frame (stores the enclosing entry holds join as savepoints and
// apply with it; the rest commit at the nested exit), so only an outermost
// frame keeps the promise until mixed nesting is fixed.

/**
 * Run [stage] for every live leaf of [targets] inside one outermost `atomic`
 * frame over their stores, after [prepare] ran for each outside every lock
 * (user code: initializers, codecs, `migrate`), inside the same settle scope
 * as the frame so what it creates settles once. A store disposed after its
 * prepare is skipped and listed; with no live target at all the frame is
 * not opened and [finish] receives a synthetic, already committed
 * transaction of id [id] (no store or middleware ever sees it).
 *
 * @throws IllegalStateException if the root is disposed, or from inside an
 *   action, an `atomic` frame, a `suspendAction`/`suspendAtomic` body, a
 *   commit's fanout or a derived state's recompute ([attempt] names the
 *   operation in the message).
 */
@Suppress("SpreadOperator") // `atomic` takes a vararg; the subtree's stores are only known at runtime.
internal fun <P, R> treeFrame(
    root: Root,
    id: String,
    attempt: String,
    targets: List<Pair<LeafNode, Store<*>>>,
    prepare: (Store<*>) -> P,
    stage: (Store<*>, Transaction, P) -> Unit,
    finish: (worked: List<TreeFrameTarget<P>>, skipped: List<LeafNode>) -> R,
): TransactionResult<R> {
    root.checkNotDisposed()
    val live = targets.filter { (_, store) -> !store.isDisposed }
    refuseEnclosingEntry(attempt, live.map { it.second })
    val skipped = ArrayList<LeafNode>(targets.filter { (_, store) -> store.isDisposed }.map { it.first })
    if (live.isEmpty()) {
        return TransactionResult.Success(syntheticCommittedTransaction(id), finish(emptyList(), skipped))
    }
    return settling {
        val prepared = live.map { (leaf, store) -> TreeFrameTarget(leaf, store, prepare(store)) }
        atomic(*live.map { it.second }.toTypedArray()) {
            val worked = ArrayList<TreeFrameTarget<P>>(prepared.size)
            for (target in prepared) {
                // A frame does not check; a leaf disposed after lock
                // acquisition holds an empty root that commits with the rest.
                if (target.store.isDisposed) {
                    skipped += target.leaf
                    continue
                }
                val txn = checkNotNull(target.store.activeTransaction) { "the frame opened a root for every store" }
                stage(target.store, txn, target.prepared)
                worked += target
            }
            finish(worked, skipped)
        }
    }
}

/**
 * A transaction that ran nothing and has already committed — what an
 * operation over an empty subtree carries in its `TransactionResult`.
 * `commit()` on a fresh root with no writes applies nothing, tells nobody
 * and ends `Committed` with an end time.
 */
internal fun syntheticCommittedTransaction(id: String): Transaction {
    val txn = Transaction(id = id, parent = null, ownerThreadId = currentThreadId(), frameId = id)
    txn.commit()
    return txn
}

/** A transaction that ran nothing and has been rolled back, to carry a refusal decided before any frame opened. */
internal fun syntheticRolledBackTransaction(id: String): Transaction {
    val txn = Transaction(id = id, parent = null, ownerThreadId = currentThreadId(), frameId = id)
    txn.rollback()
    return txn
}

private fun refuseEnclosingEntry(
    attempt: String,
    stores: List<Store<*>>,
) {
    NoWriteRegion.refuse { attempt }
    // An open settle scope is an entry on this thread: every action, frame,
    // suspending entry (carried across dispatch), top-level attempt and
    // harness transaction opens or joins one, and it stays open through the
    // entry's commit fanout and settle. The other checks catch a transaction
    // held here without one.
    val nested =
        SettleScopes.current() != null ||
            FrameMarkers.current() != null ||
            stores.any { it.internalOwnsActiveTransaction() || it.appliedTransactionNestedHere() != null }
    check(!nested) {
        "Cannot $attempt from inside an action, an atomic(...) frame, a suspendAction or suspendAtomic body, a " +
            "commit's observers or bridges, or a derived state's recompute: the operation runs as one outermost " +
            "frame over the subtree's stores. Call it from outside every entry."
    }
}
