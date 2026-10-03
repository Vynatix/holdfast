@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ConstructionLog
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachment

// A tree child lambda or a keyed factory runs holding no lock (TreeMaterialize.kt,
// KeyedConstruction.kt), so racing first reads or creates may each run it, and
// only the first to attach wins. The stores a run built that end up attached
// nowhere — a loser's, or those of a run whose attach failed after it returned
// — would leak: nothing reaches them. And the stores a WINNING run built are
// its declaration's to dispose with the parent (`ChildEntry.owned`, step 9 of
// `StoreDetach`). `RunBuilt` tells them apart from stores that existed before
// the run (returned shared stores, which are never the tree's to dispose)
// through the run's `ConstructionLog`: the stores constructed on the running
// thread while it ran. A store a racing thread constructed meanwhile is not
// the run's.

/** The stores one run of user code (that may build stores) constructed on its own thread. */
internal class RunBuilt private constructor(
    private val log: ConstructionLog,
) {
    /** Whether [store] was constructed on the running thread during the run. */
    fun builtDuringRun(store: Store<*>): Boolean = store.lockOrderKey in log

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
        /** Run [body], logging the stores it constructs on this thread; answer its result and the log. */
        fun <R> during(body: () -> R): Pair<R, RunBuilt> {
            val (result, log) = ConstructionLog.record(body)
            return result to RunBuilt(log)
        }
    }
}
