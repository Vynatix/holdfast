@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.LeafNode
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.StoreTree
import com.vynatix.holdfast.tree.internalLeaves
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Hydrate every store of the subtree at [node] — the receiver's whole
 * subtree by default, the receiver's own store included: each live store,
 * in `lockOrderKey` order (the receiver first whenever it was constructed
 * before its children, which a lazily materialized child always is), has
 * its hydrator ([Store.hydratorOrNull]) [hydrate][Hydrator.hydrate]d on
 * [scope] (else its own `Store.scope`), so every seed has committed and
 * every refresh is launched before the next store's turn; then, with
 * [awaitSettled], each is [awaited][Hydrator.awaitSettled] in the same
 * order, so the refreshes run concurrently and the report holds where each
 * settled. A store without a hydrator is reported
 * [HydrateAllReport.Outcome.NoHydrator]; one disposed meanwhile — before its
 * turn, as its hydrator runs, or while its hydration is awaited —
 * [HydrateAllReport.Outcome.Disposed], under the node it sat at when
 * listed; a hydrator that throws from `hydrate()` (a throwing `base { }`, a
 * rejecting middleware) is reported [Hydration.Failed] with what it threw,
 * and does not stop the rest — nothing here throws for a store's failure or
 * its dispose. Idempotent: a hydrated store's hydrator does nothing.
 * Cancellation propagates at once, while the refreshes already launched
 * keep running on their scopes. Lists the subtree once, as
 * [StoreTree.stores] does, so declared children not read yet are
 * materialized first — after the inside-an-entry refusal, never before it.
 * A store whose `hydratorOrNull()` throws is reported
 * [HydrateAllReport.Outcome.Disposed] when it is disposed, else
 * [Hydration.Failed] with what it threw.
 *
 * Experimental (issue #21, over issue #20's R8).
 *
 * @throws IllegalStateException if the tree's store is disposed, or from
 *   inside an action, `atomic` frame, `suspendAction` or `suspendAtomic`
 *   body of any store — before any store is touched — as
 *   [Hydrator.hydrate] refuses.
 * @throws IllegalArgumentException if [node] is not in the receiver's
 *   subtree.
 */
@ExperimentalStoreApi
suspend fun StoreTree.hydrateAll(
    node: StoreNode = this.node,
    scope: CoroutineScope? = null,
    awaitSettled: Boolean = true,
): HydrateAllReport {
    val owner = checkNotNull(this.node.store) { "hydrateAll(): the tree's store is disposed" }
    require(node === this.node || node.isUnder(this.node)) { "node '${node.name}' is not under this tree's store" }
    // Refused before any store is touched — before the listing materializes
    // a single child: inside an entry the first store's gate would wait
    // forever for the transaction that waits for it.
    refuseInsideEntry(owner, "hydrateAll()", ::insideEntryMessage)
    // One materialization and one listing, each store paired with its node
    // now, not after the run: a store disposed meanwhile leaves the tree.
    val leaves =
        internalLeaves(node)
            .map { (leaf, store) -> LiveLeaf(leaf, store) }
            .sortedBy { it.store.lockOrderKey }
    val outcomes = arrayOfNulls<HydrateAllReport.Outcome>(leaves.size)
    val awaited = arrayOfNulls<Hydrator<*>>(leaves.size)
    for ((index, leaf) in leaves.withIndex()) {
        val store = leaf.store
        outcomes[index] =
            stepOutcome(store, { store.hydratorOrNull() }) { hydrator ->
                if (hydrator == null) {
                    HydrateAllReport.Outcome.NoHydrator
                } else {
                    stepOutcome(store, { hydrator.hydrate(scope ?: store.scope) }) {
                        awaited[index] = hydrator
                        null // Settled, and reported, below.
                    }
                }
            }
    }
    for ((index, leaf) in leaves.withIndex()) {
        val hydrator = awaited[index] ?: continue
        val store = leaf.store
        outcomes[index] =
            stepOutcome(store, { if (awaitSettled) hydrator.awaitSettled() else hydrator.current }) { settled ->
                // `current` keeps answering after a dispose; the store is asked.
                if (store.isDisposed) HydrateAllReport.Outcome.Disposed else HydrateAllReport.Outcome.Ran(settled)
            }
    }
    val entries =
        leaves.mapIndexed { index, leaf ->
            val outcome = checkNotNull(outcomes[index]) { "no outcome for leaf '${leaf.node.name}'" }
            HydrateAllReport.Entry(leaf.node, leaf.store, outcome)
        }
    return HydrateAllReport(entries)
}

/** A store as listed: the store and the node it sat at then. */
private class LiveLeaf(
    val node: LeafNode,
    val store: Store<*>,
)

/**
 * One step of a leaf's hydration — [step] on [store]'s hydrator — as an
 * outcome: [ran] over what it answered, else [disposedOrFailed] with what
 * it threw. A cancellation propagates.
 */
private suspend inline fun <T> stepOutcome(
    store: Store<*>,
    step: () -> T,
    ran: (T) -> HydrateAllReport.Outcome?,
): HydrateAllReport.Outcome? {
    val answer =
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") thrown: Throwable, // A leaf's failure is reported, never rethrown.
        ) {
            return disposedOrFailed(store, thrown)
        }
    return ran(answer)
}

/**
 * What a hydrator step of [store] that threw [thrown] means:
 * [HydrateAllReport.Outcome.Disposed] for a store disposed meanwhile — every
 * hydrator entrypoint throws on a disposed store, before or while it waits,
 * and the store is asked, never the message — else [Hydration.Failed] with
 * what it threw.
 */
private fun disposedOrFailed(
    store: Store<*>,
    thrown: Throwable,
): HydrateAllReport.Outcome =
    if (store.isDisposed) HydrateAllReport.Outcome.Disposed else HydrateAllReport.Outcome.Ran(Hydration.Failed(thrown))
