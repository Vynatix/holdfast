@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.LeafNode
import com.vynatix.holdfast.tree.Root
import com.vynatix.holdfast.tree.StoreNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Hydrate every leaf of the subtree at [node] — the whole tree by default:
 * each live leaf, in `lockOrderKey` order, has its hydrator
 * ([Store.hydratorOrNull]) [hydrate][Hydrator.hydrate]d on [scope] (else
 * its own `Store.scope`), so every seed has committed and every refresh is
 * launched before the next leaf's turn; then, with [awaitSettled], each is
 * [awaited][Hydrator.awaitSettled] in the same order, so the refreshes run
 * concurrently and the report holds where each settled. A leaf without a
 * hydrator is reported [HydrateAllReport.Outcome.NoHydrator]; one disposed
 * meanwhile — before its turn, as its hydrator runs, or while its hydration
 * is awaited — [HydrateAllReport.Outcome.Disposed], under the node it sat
 * at when listed; a hydrator that throws from `hydrate()` (a throwing
 * `base { }`, a rejecting middleware) is reported [Hydration.Failed] with
 * what it threw, and does not stop the rest — nothing here throws for a
 * leaf's failure or its dispose. Idempotent: a hydrated leaf's hydrator does
 * nothing. Cancellation propagates at once, while the refreshes already
 * launched keep running on their scopes.
 *
 * Experimental (issue #21 plan PR 21-8, over issue #20's R8).
 *
 * @throws IllegalStateException if the root is disposed, or from inside an
 *   action, `atomic` frame, `suspendAction` or `suspendAtomic` body of any
 *   store — before any leaf is touched — as [Hydrator.hydrate] refuses.
 * @throws IllegalArgumentException if [node] belongs to another root.
 */
@ExperimentalStoreApi
suspend fun Root.hydrateAll(
    node: StoreNode = this,
    scope: CoroutineScope? = null,
    awaitSettled: Boolean = true,
): HydrateAllReport {
    check(!isDisposed) { "root '$name' disposed" }
    require(node.root === this) { "node '${node.name}' belongs to root '${node.root.name}', not root '$name'" }
    val leaves = liveLeavesUnder(node)
    // Refused before any leaf is touched: inside an entry the first leaf's
    // gate would wait forever for the transaction that waits for it.
    leaves.firstOrNull()?.let { refuseInsideEntry(it.store, "hydrateAll()", ::insideEntryMessage) }
    val outcomes = arrayOfNulls<HydrateAllReport.Outcome>(leaves.size)
    val awaited = arrayOfNulls<Hydrator<*>>(leaves.size)
    for ((index, leaf) in leaves.withIndex()) {
        val store = leaf.store
        val hydrator = runCatching { store.hydratorOrNull() }.getOrNull()
        outcomes[index] =
            when {
                store.isDisposed -> HydrateAllReport.Outcome.Disposed
                hydrator == null -> HydrateAllReport.Outcome.NoHydrator
                else ->
                    stepOutcome(store, { hydrator.hydrate(scope ?: store.scope) }) {
                        awaited[index] = hydrator
                        null // Settled, and reported, below.
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

/** A leaf as listed: the store and the node it sat at then. */
private class LiveLeaf(
    val node: LeafNode,
    val store: Store<*>,
)

/**
 * The live leaves of the subtree at [node] — what `Root.children(node)`
 * lists — each paired with its node as of this listing, in `lockOrderKey`
 * order. Paired now, not after the run: a leaf disposed meanwhile leaves the
 * registry, and `Root.nodeOf` no longer finds its node.
 */
private fun Root.liveLeavesUnder(node: StoreNode): List<LiveLeaf> =
    nodes
        .mapNotNull { candidate ->
            val leaf = candidate as? LeafNode ?: return@mapNotNull null
            if (!leaf.isUnder(node)) return@mapNotNull null
            leaf.store?.let { LiveLeaf(leaf, it) }
        }.sortedBy { it.store.lockOrderKey }

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
