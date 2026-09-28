@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
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
 * hydrator is reported [HydrateAllReport.Outcome.NoHydrator], one disposed
 * meanwhile [HydrateAllReport.Outcome.Disposed]; a hydrator that throws
 * from `hydrate()` (a throwing `base { }`, a rejecting middleware) is
 * reported [Hydration.Failed] with what it threw, and does not stop the
 * rest — nothing here throws for a leaf's failure. Idempotent: a hydrated
 * leaf's hydrator does nothing. Cancellation propagates at once, while the
 * refreshes already launched keep running on their scopes.
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
    val leaves = children(node).sortedBy { it.lockOrderKey }
    // Refused before any leaf is touched: inside an entry the first leaf's
    // gate would wait forever for the transaction that waits for it.
    leaves.firstOrNull()?.let { refuseInsideEntry(it, "hydrateAll()", ::insideEntryMessage) }
    val driven = ArrayList<Pair<Store<*>, Hydrator<*>?>>(leaves.size)
    val outcomes = HashMap<Store<*>, HydrateAllReport.Outcome>()
    for (store in leaves) {
        val hydrator = runCatching { store.hydratorOrNull() }.getOrNull()
        when {
            store.isDisposed -> outcomes[store] = HydrateAllReport.Outcome.Disposed
            hydrator == null -> outcomes[store] = HydrateAllReport.Outcome.NoHydrator
            else -> {
                val failure = hydrateOrFailure(hydrator, scope ?: store.scope)
                if (failure != null) outcomes[store] = HydrateAllReport.Outcome.Ran(Hydration.Failed(failure))
                driven += store to hydrator.takeIf { failure == null }
            }
        }
    }
    for ((store, hydrator) in driven) {
        if (hydrator == null) continue
        val settled = if (awaitSettled) hydrator.awaitSettled() else hydrator.current
        outcomes[store] = HydrateAllReport.Outcome.Ran(settled)
    }
    val entries =
        leaves.map { store ->
            val leaf = nodeOf(store) ?: node
            HydrateAllReport.Entry(leaf, store, outcomes.getValue(store))
        }
    return HydrateAllReport(entries)
}

/** Run [hydrator]'s `hydrate` on [scope]: `null` when it ran, else what it threw (a cancellation propagates). */
private suspend fun hydrateOrFailure(
    hydrator: Hydrator<*>,
    scope: CoroutineScope,
): Throwable? =
    try {
        hydrator.hydrate(scope)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught") thrown: Throwable, // A leaf's failure is reported, never rethrown.
    ) {
        thrown
    }
