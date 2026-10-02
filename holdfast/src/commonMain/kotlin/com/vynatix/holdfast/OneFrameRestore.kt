@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

// Restoring several stores as one frame (issue #20 plan PR 15, decision D21):
// the write half of the multi-store cut ConsistentRead.kt reads. A cut taken
// by captureConsistent(stores) — encoded and decoded or not (an encoded one
// as its encodable projection) — goes back with
// restoreInOneFrame(snapshots), all of it or none of it, which is what issue
// #21's `tree.restore` builds on. Internal: #21 owns the public API.
//
// Why it refuses to nest. An `atomic` frame nested in an enclosing entry on
// its thread is not one frame (Atomic.kt, "Nesting"): a store the enclosing
// action or frame holds joins as a SAVEPOINT, which applies only when the
// enclosing transaction commits (and never, if that rolls back), while a store
// it does not hold gets a fresh ROOT, which commits when the nested frame
// exits — an enclosing rollback cannot undo it. A restore nested that way
// could leave some stores restored and others not, and a consistent cut could
// see it half-applied (USABILITY-ANALYSIS.md, the two frame-nesting findings
// of theme 1: "Nested frames are an unpoliced escape" and "The nesting docs
// are false for introduced stores"). Until mixed nesting is fixed, only an
// outermost frame keeps the promise, so restoreInOneFrame runs only as one.

/**
 * Restore each store of [snapshots] from its snapshot under [policy], all in
 * ONE outermost `atomic` frame over every store: all-or-nothing — a snapshot
 * one store rejects (a [RestoreRejectedException] under [policy], a
 * [SnapshotMigrationException], a target's failing initializer) rolls every
 * store back, and nothing changes — and applied inside one write bracket, so
 * a consistent cut ([captureConsistent]) sees every store restored or none.
 * Pair it with [captureConsistent]: a cut captured there restores here as
 * the cut it was. An encoded and decoded one restores its encodable
 * projection (see [StoreSnapshot.encode]): a [StateTag.Secret] state (written
 * as `null`), a [StateTag.Remote] state encoded without `includeRemote`, and
 * a state with no codec each keep the value they hold.
 *
 * Each store's restore is planned first, in [snapshots]' order, before the
 * frame opens and outside every lock — a restore's plan runs user code: the
 * schema check and `migrate`, never-read targets' initializers, the codecs
 * decoding a decoded snapshot (see [restore]). Then the frame stages each
 * plan's raw values into that store's root — no `Transformer.set` runs, so an
 * encrypted state's ciphertext is restored as it was captured — or throws the
 * first planned failure, which rolls the frame back. Each store's middleware
 * sees one transaction, its frame root (the frame's id, not `Restore`), and
 * its observers and bridges fire once its root commits, in the frame's fanout
 * order. Derived states over the restored stores settle once, after the frame:
 * the plans and the frame are one entry, so a derived state that follows a
 * restored store as a whole (StoreEdges.kt) and sees the plan create keyed
 * entries recomputes once, after the frame — committed or rolled back.
 *
 * It restores no store sterilely, and never evicts a keyed entry; one the
 * snapshot holds that is not live is created by the plan, and stays live if
 * the frame then rolls back, as with [restore]. The value is each store's
 * [RestoreReport], in [snapshots]' order.
 *
 * @throws IllegalArgumentException if [snapshots] is empty.
 * @throws IllegalStateException if a store is disposed when it is called (one
 *   disposed after its plan fails the frame: [TransactionResult.Error]); from
 *   inside a state initializer, a schema migration or a derived state's
 *   compute; and from inside any entry on this thread — an `action`, `atomic`
 *   frame, `suspendAction` or `suspendAtomic` body, a commit's fanout (an
 *   observer, a bridge publish) or a derived state's settle — where its frame
 *   would nest, so it would not be all-or-nothing (see the top of this file).
 *   Call it at top level. One gap: it is not a suspending function, so it
 *   sees a suspending entry only through the settle scope that entry installs
 *   on the thread it runs on; on iOS and wasmJs, a `suspendAction` or
 *   `suspendAtomic` body that hopped threads with a nested
 *   `withContext(Dispatchers.X)` has none installed there (as
 *   `:holdfast-coroutines`' SettleAmbientContext.kt documents for
 *   `withSettleScope`), so a call from inside that block is not refused.
 */
internal fun restoreInOneFrame(
    snapshots: Map<out Store<*>, StoreSnapshot>,
    policy: RestorePolicy = RestorePolicy.IgnoreUnknown,
): TransactionResult<Map<Store<*>, RestoreReport>> {
    require(snapshots.isNotEmpty()) { "restoreInOneFrame needs at least one store to restore" }
    val stores = snapshots.keys.toList()
    refuseEnclosingEntry(stores)
    stores.forEach { it.checkNotDisposed() }
    // The plans and the frame are one entry (opened here: none is open, as
    // refused above): a keyed entry a plan creates queues the recompute of a
    // derived state following its store as a whole into this scope, the
    // frame joins it, and it settles once, after the frame.
    return settling {
        val plans = snapshots.map { (store, snapshot) -> PlannedRestore(store, snapshot.content, policy) }
        atomic(*stores.toTypedArray()) {
            for (plan in plans) {
                // Disposed since its plan: a frame does not check, and this one
                // would stage into a store that has let go of its states.
                plan.store.checkNotDisposed()
                plan.stage(checkNotNull(plan.store.activeTransaction) { "the frame opened a root for every store" })
            }
            plans.associateTo(LinkedHashMap()) { it.store to it.report() }
        }
    }
}

/**
 * Throw unless this thread is outside every entry and every no-write region,
 * so the frame [restoreInOneFrame] opens over [stores] is an outermost one
 * (see the top of this file).
 */
private fun refuseEnclosingEntry(stores: List<Store<*>>) {
    val names = stores.joinToString { it.displayName }
    NoWriteRegion.refuse { "restore $names in one frame" }
    // An open settle scope is an entry on this thread: every action, frame,
    // suspending entry (carried across dispatch), top-level attempt and
    // harness transaction opens or joins one, and it stays open through the
    // entry's commit fanout and settle. The other two catch a transaction held
    // here without one.
    val nested =
        SettleScopes.current() != null ||
            FrameMarkers.current() != null ||
            stores.any { it.internalOwnsActiveTransaction() || it.appliedTransactionNestedHere() != null }
    check(!nested) {
        "Cannot restore $names in one frame from inside an action, an atomic(...) frame, a suspendAction or " +
            "suspendAtomic, a commit's observers or bridges, or a derived state's recompute: its frame would nest " +
            "in that entry, and a nested frame is not all-or-nothing — a store the entry holds joins it as a " +
            "savepoint that applies only with the entry's own commit, and any other store commits when the " +
            "nested frame exits, whatever the entry does next. So some stores could end restored and others " +
            "not. Restore from top level, outside any entry."
    }
}
