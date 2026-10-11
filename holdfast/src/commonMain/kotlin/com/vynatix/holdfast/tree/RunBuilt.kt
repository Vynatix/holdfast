@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.lastStoreLockOrderKey

// A tree child lambda or a keyed factory runs holding no lock (TreeMaterialize.kt,
// KeyedConstruction.kt), so racing first reads or creates may each run it, and
// only the first to attach wins. The stores a run built that end up attached
// nowhere — a loser's, or those of a run whose attach failed after it returned
// — would leak: nothing reaches them. `RunBuilt` tells them apart from stores
// that existed before the run (returned shared stores, which are never the
// tree's to dispose) by the process-monotonic `Store.lockOrderKey`.

/** The store counter read right before one run of user code that may build stores. */
internal class RunBuilt private constructor(
    private val before: Long,
) {
    /** Whether [store] was constructed after this mark: during the run, or by a racing thread meanwhile. */
    fun builtDuringRun(store: Store<*>): Boolean = store.lockOrderKey > before

    /**
     * Dispose each of [stores] that the run built and nothing else holds: not
     * one in [keep] (a winner's store), not one that existed before the run,
     * not one that hangs under a tree parent (whoever attached it owns it),
     * not one already disposed. Called holding no lock of the tree.
     */
    fun disposeOrphans(
        stores: Iterable<Store<*>>,
        keep: Collection<Store<*>> = emptyList(),
    ) {
        stores
            .filter { store -> !store.isDisposed && builtDuringRun(store) && keep.none { it === store } }
            .filter { store -> store.internalAttachment(treeMembershipKey)?.parentEdge?.value == null }
            .forEach { it.dispose() }
    }

    companion object {
        /** Mark the counter now, before the run. */
        fun mark(): RunBuilt = RunBuilt(lastStoreLockOrderKey())
    }
}
