@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.CaptureStats
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.captureConsistent

// `Root.snapshot(node, scope)` (issue #21 decision U10): copy the subtree's
// membership under the registry lock, release, then take ONE consistent cut
// over every live leaf through `captureConsistent` — never `atomic(*leaves)`,
// which holds serializers and transaction locks, fails nested lock order,
// deadlocks from observers and spins from inside a `suspendAction` body.

/**
 * Capture the subtree at [node] in [scope]. Materializes every never-read
 * declared state the scope captures (their initializers run, outside every
 * tree lock), excludes keyed entries still under construction, skips a
 * leaf disposed concurrently (the capture is retried without it), and under
 * `SnapshotScope.UserAuthored` prunes leaves and branches with nothing
 * captured — the requested [node] itself is always returned. With
 * [previous], a capture in the same scope, a leaf whose cut stamp has not
 * moved reuses that capture's `StoreSnapshot` by reference (`CutStamp`:
 * `Root.value` recaptures only the leaves that changed); [stats] counts
 * what the cut did.
 *
 * @throws IllegalStateException if the root is disposed, or as `snapshot()`
 *   does (a throwing or cyclic initializer, a schema version below 1).
 * @throws IllegalArgumentException if [node] belongs to another root.
 */
internal fun captureTree(
    root: Root,
    node: StoreNode,
    scope: SnapshotScope,
    previous: TreeSnapshot? = null,
    stats: CaptureStats? = null,
): TreeSnapshot {
    root.checkNotDisposed()
    root.requireOwn(node)
    val reusable = previous?.takeIf { it.scope === scope }
    while (true) {
        val shape = root.registry.shapeOf(node)
        val stores = shape.stores().filter { !it.isDisposed }
        val known = reusable?.let { last -> stores.map { last.index.byStoreKey[it.lockOrderKey]?.leaf } }
        val captures = captureOrRetry(stores, scope, known, stats) ?: continue
        val byStoreKey = HashMap<Long, StoreSnapshot>(stores.size)
        for ((i, store) in stores.withIndex()) byStoreKey[store.lockOrderKey] = captures[i]
        val index = TreeIndex()
        return checkNotNull(buildTree(shape, scope, byStoreKey, index, top = true))
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

private fun buildTree(
    shape: TreeShape,
    scope: SnapshotScope,
    byStoreKey: Map<Long, StoreSnapshot>,
    index: TreeIndex,
    top: Boolean,
): TreeSnapshot? {
    val prune = scope === SnapshotScope.UserAuthored
    val node = shape.node
    if (node is LeafNode) {
        val capture = leafCapture(node, shape.leaves.singleOrNull()?.second, scope, byStoreKey, index, prune)
        // The requested node is always returned: a leaf whose store is gone,
        // or that captured nothing in this scope, comes back empty.
        return capture ?: if (top) emptyLeaf(node, scope, index) else null
    }
    val children = ArrayList<TreeSnapshot>()
    for ((leaf, store) in shape.leaves) {
        leafCapture(leaf, store, scope, byStoreKey, index, prune)?.let(children::add)
    }
    for (child in shape.children) {
        buildTree(child, scope, byStoreKey, index, top = false)?.let(children::add)
    }
    val pruned = prune && !top && children.isEmpty()
    return if (pruned) null else TreeSnapshot(node, children, scope, leaf = null, storeKey = 0L, index = index)
}

private fun leafCapture(
    leaf: LeafNode,
    store: Store<*>?,
    scope: SnapshotScope,
    byStoreKey: Map<Long, StoreSnapshot>,
    index: TreeIndex,
    prune: Boolean,
): TreeSnapshot? {
    val capture = store?.let { byStoreKey[it.lockOrderKey] }
    val pruned = capture == null || (prune && capture.stateNames.isEmpty())
    if (pruned) return null
    return TreeSnapshot(leaf, emptyList(), scope, capture, checkNotNull(store).lockOrderKey, index)
}

/** The capture of a requested leaf that holds no `StoreSnapshot`; its states read `Absent`. */
private fun emptyLeaf(
    node: LeafNode,
    scope: SnapshotScope,
    index: TreeIndex,
): TreeSnapshot = TreeSnapshot(node, emptyList(), scope, leaf = null, storeKey = node.storeKey, index = index)
