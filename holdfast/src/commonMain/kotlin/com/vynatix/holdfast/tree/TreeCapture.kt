@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.CaptureStats
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.captureConsistent

// `tree.snapshot(node, scope)` (issue #21 decision U10): list the subtree's
// shape and the receiver's membership in ONE seqlock-validated walk (one
// registry lock at a time, `TreeWalk.kt`), then take ONE consistent cut over
// every live store through `captureConsistent` — never `atomic(*stores)`,
// which holds serializers and transaction locks, fails nested lock order,
// deadlocks from observers and spins from inside a `suspendAction` body.
// Membership is decided by that listing (`TreeIndex.memberKeys`): a store
// under the receiver outside the captured subtree, or one the cut then
// missed because it disposed meanwhile, reads `Absent`. The listed structure
// (parents and names, from the receiver down) is frozen into the capture.

/**
 * Capture the subtree at [node] — [ownerNode] or a node under it — in
 * [scope]. Materializes every never-read declared state the scope captures
 * (their initializers run, outside every tree lock) but no child
 * declaration (it runs inside host transactions and derived computes),
 * excludes keyed entries still under construction, skips a store disposed
 * concurrently (the capture is retried without it), and under
 * `SnapshotScope.UserAuthored` prunes store nodes and branches with nothing
 * captured — the requested [node] itself is always returned. With
 * [previous], a capture in the same scope, a store whose cut stamp has not
 * moved reuses that capture's `StoreSnapshot` by reference (`CutStamp`: the
 * tree value recaptures only the stores that changed); [stats] counts what
 * the cut did.
 *
 * @throws IllegalStateException if [ownerNode]'s store is disposed, or as
 *   `snapshot()` does (a throwing or cyclic initializer, a schema version
 *   below 1).
 * @throws IllegalArgumentException if [node] is not under [ownerNode].
 */
internal fun captureTree(
    ownerNode: LeafNode,
    node: StoreNode,
    scope: SnapshotScope,
    previous: TreeSnapshot? = null,
    stats: CaptureStats? = null,
): TreeSnapshot {
    val owner = checkNotNull(ownerNode.store) { "${ownerNode.name}'s store disposed" }
    owner.checkNotDisposed()
    require(node === ownerNode || node.isUnder(ownerNode)) { "node '${node.name}' is not under '${ownerNode.name}'" }
    val reusable = previous?.takeIf { it.scope === scope }
    while (true) {
        val listing = listingOf(ownerNode, node, stats = stats)
        val stores = listing.shape.stores().filter { !it.isDisposed }
        val known = reusable?.let { last -> stores.map { last.index.byStoreKey[it.lockOrderKey]?.leaf } }
        val captures = captureOrRetry(stores, scope, known, stats) ?: continue
        val byStoreKey = HashMap<Long, StoreSnapshot>(stores.size)
        for ((i, store) in stores.withIndex()) byStoreKey[store.lockOrderKey] = captures[i]
        val index = TreeIndex(listing.memberKeys, ownerNode)
        index.recordChain(listing.chain)
        return checkNotNull(TreeBuilder(scope, byStoreKey, index).build(listing.shape, top = true))
    }
}

/** One cut over [stores], or `null` when a store disposed meanwhile (the caller re-lists and tries again). */
private fun captureOrRetry(
    stores: List<Store<*>>,
    scope: SnapshotScope,
    previous: List<StoreSnapshot?>?,
    stats: CaptureStats?,
): List<StoreSnapshot>? {
    if (stores.isEmpty()) return emptyList()
    return try {
        captureConsistent(stores, scope, previous, stats)
    } catch (e: IllegalStateException) {
        if (stores.any { it.isDisposed }) null else throw e
    }
}

/** Builds a capture bottom-up over one listed shape: every node uniform, a store node with its children. */
private class TreeBuilder(
    private val scope: SnapshotScope,
    private val byStoreKey: Map<Long, StoreSnapshot>,
    private val index: TreeIndex,
) {
    private val prune = scope === SnapshotScope.UserAuthored

    fun build(
        shape: TreeShape,
        top: Boolean,
    ): TreeSnapshot? {
        val captured = shape.store?.let { byStoreKey[it.lockOrderKey] }
        // A listed store that disposed before the cut is left out with its
        // subtree, as a listing taken a moment later would have; the
        // requested node itself is always returned.
        val disposedSinceListing = shape.store != null && captured == null
        val capture = captured?.takeIf { !(prune && it.stateNames.isEmpty()) }
        val children = if (disposedSinceListing) emptyList() else shape.children.mapNotNull { build(it, top = false) }
        val pruned = prune && capture == null && children.isEmpty()
        if (!top && (disposedSinceListing || pruned)) return null
        // A store node that captured nothing keeps its store's key (a disposed
        // store's states read Absent); a branch has none.
        val storeKey = shape.store?.lockOrderKey ?: (shape.node as? LeafNode)?.storeKey ?: 0L
        index.origins[shape.node] = shape.node.nameOrigin
        return TreeSnapshot(shape.node, shape.name, children, scope, capture, storeKey, index)
    }
}
