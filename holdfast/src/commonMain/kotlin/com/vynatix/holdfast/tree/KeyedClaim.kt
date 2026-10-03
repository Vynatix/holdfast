@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.CycleStep
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.InitializerGraph
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.platform.currentThreadId

// The race half of keyed creation (KeyedConstruction.kt): run the factory
// holding nothing, claim the key with its store, attach the winner — or
// hand the loser the winner's store and dispose the loser's own.

/** One key's factory run, as a cycle message names it; equal per branch and key. */
private class KeyedStep(
    val branch: KeyedBranch<*, *>,
    val key: Any,
) : CycleStep {
    override fun describeForCycle(): String = "keyed factory '${branch.owner.displayName}.${branch.name}'"

    override fun equals(other: Any?): Boolean = other is KeyedStep && other.branch === branch && other.key == key

    override fun hashCode(): Int = branch.hashCode() * HASH_PRIME + key.hashCode()
}

private const val HASH_PRIME = 31

/**
 * Run the factory, then claim [key] with its store and attach it — or, when
 * another run claimed the key first, return that run's store once attached
 * and dispose this run's. A winner whose attach failed leaves no entry:
 * this run claims again with its own store.
 */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.raceToAttach(
    key: K,
    leafName: String,
): S {
    val (produced, built) = runFactory(key)
    while (true) {
        val (entry, claimed) = claimOrDispose(key, leafName, produced, built)
        if (claimed) return attachClaimed(entry, key, produced, built)
        val winner = awaitAttached(entry) ?: continue
        built.disposeOrphans(listOf(produced), keep = listOf(winner))
        return winner
    }
}

/**
 * Run the factory for [key] holding nothing, marked on this thread, and
 * verify its store; on a refusal, dispose what the run built and nobody
 * else holds.
 */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.runFactory(key: K): Pair<S, RunBuilt> {
    val built = RunBuilt.mark()
    val produced = InitializerGraph.Process.runMarked(KeyedStep(this, key)) { factory(key) }
    var verified = false
    try {
        check(storeClass.isInstance(produced)) {
            "${owner.displayName}: $name is declared as keyed<…, ${storeClass.simpleName}> but the factory " +
                "returned ${produced::class.simpleName}"
        }
        check(!produced.isDisposed) {
            "${owner.displayName}: the factory for $name.create($key) returned a disposed store"
        }
        verified = true
    } finally {
        if (!verified) built.disposeOrphans(listOf(produced))
    }
    return produced to built
}

/** [ChildRegistry.claimKey] for [produced]; on a closed registry, dispose the run's store first. */
internal fun KeyedBranch<*, *>.claimOrDispose(
    key: Any,
    leafName: String,
    produced: Store<*>,
    built: RunBuilt,
): Pair<LeafEntry, Boolean> {
    var answered = false
    try {
        val leaf = produced.treeAttachment().node
        return registry.claimKey(this, key, leafName, leaf, currentThreadId()).also { answered = true }
    } finally {
        if (!answered) built.disposeOrphans(listOf(produced))
    }
}

/**
 * Attach [produced] under the [entry] this thread just claimed (so it holds
 * the entry's construction lock), inside the caller's settle scope, so a
 * recompute the attach queues (an ancestor's tree value following the new
 * store) runs once the create returns, never inside the factory. Releases
 * the construction lock once the store attached or the claim was abandoned
 * — and then disposes the run's store, which nothing holds.
 */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.attachClaimed(
    entry: LeafEntry,
    key: K,
    produced: S,
    built: RunBuilt,
): S {
    var promoted = false
    try {
        // Phases 3 and 4: link, then register through the promotion.
        val target = AttachTarget(produced.treeAttachment(), entry.leafName, NameOrigin.Key, key)
        val attach = ChildAttach(owner, registry, name, this, listOf(target), entry)
        attach.link()
        attach.publish { registry.promoteLocked(entry, produced) }
        promoted = true
        // Phases 5 and 6 cannot fail the create.
        attach.syncRings()
        attach.announce()
        return produced
    } finally {
        if (!promoted) registry.abandon(entry)
        entry.constructingThreadId = null
        entry.constructionLock.release()
        if (!promoted) built.disposeOrphans(listOf(produced))
    }
}
