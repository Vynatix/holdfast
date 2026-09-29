@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.tree.StoreNode

/**
 * What `Root.hydrateAll(node)` did, leaf by leaf, in the order it drove
 * them: an [Entry] per leaf live in the subtree when it listed them, with
 * its [Entry.outcome] — the [Hydration] its hydrator settled to (or,
 * without waiting, is in), [Outcome.NoHydrator] for a leaf that declared
 * none, [Outcome.Disposed] for one disposed meanwhile. [failed] lists the
 * leaves whose hydration ended [Hydration.Failed]; [skipped] those without
 * a hydrator or gone; [isHealthy] says no leaf failed. Never a state value.
 */
@ExperimentalStoreApi
class HydrateAllReport internal constructor(
    val entries: List<Entry>,
) {
    /**
     * One leaf: its store, how its hydration went, and its [node] — the
     * `LeafNode` it sat at when `hydrateAll` listed it, kept for a leaf
     * [disposed][Outcome.Disposed] meanwhile too (its root no longer knows
     * that leaf: `Root.nodeOf` answers `null`).
     */
    @ExperimentalStoreApi
    class Entry internal constructor(
        val node: StoreNode,
        val store: Store<*>,
        val outcome: Outcome,
    ) {
        override fun toString(): String = "Entry(${node.name}: $outcome)"
    }

    @ExperimentalStoreApi
    sealed class Outcome {
        /**
         * The leaf's hydrator ran: [hydration] is the phase it settled to
         * (or, without waiting, the phase after its seed).
         */
        class Ran internal constructor(
            val hydration: Hydration,
        ) : Outcome() {
            override fun toString(): String = "Ran($hydration)"
        }

        /** The leaf declared no hydrator: nothing to drive. */
        data object NoHydrator : Outcome()

        /**
         * The leaf's store was disposed meanwhile: between the listing and
         * its turn, as its hydrator ran, or while its hydration was awaited
         * (or, without waiting, before its phase was read). Not a failure:
         * [isHealthy] ignores it.
         */
        data object Disposed : Outcome()
    }

    /** The leaves whose hydration ended [Hydration.Failed]. */
    val failed: List<Entry> get() = entries.filter { (it.outcome as? Outcome.Ran)?.hydration is Hydration.Failed }

    /** The leaves that were not hydrated: no hydrator, or disposed meanwhile. */
    val skipped: List<Entry> get() = entries.filter { it.outcome !is Outcome.Ran }

    /** Whether no leaf's hydration failed. */
    val isHealthy: Boolean get() = failed.isEmpty()

    override fun toString(): String = "HydrateAllReport(${entries.joinToString()})"
}
