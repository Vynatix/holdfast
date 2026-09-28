@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

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
 * captured — the requested [node] itself is always returned.
 *
 * @throws IllegalStateException if the root is disposed, or as `snapshot()`
 *   does (a throwing or cyclic initializer, a schema version below 1).
 * @throws IllegalArgumentException if [node] belongs to another root.
 */
internal fun captureTree(
    root: Root,
    node: StoreNode,
    scope: SnapshotScope,
): TreeSnapshot {
    root.checkNotDisposed()
    root.requireOwn(node)
    while (true) {
        val shape = root.registry.shapeOf(node)
        val stores = shape.stores().filter { !it.isDisposed }
        val captures = captureOrRetry(stores, scope) ?: continue
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
): List<StoreSnapshot>? {
    if (stores.isEmpty()) return emptyList()
    return try {
        captureConsistent(stores, scope)
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
    if (node is LeafNode) return leafCapture(node, shape.leaves.singleOrNull()?.second, scope, byStoreKey, index, prune)
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
