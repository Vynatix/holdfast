@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.PlannedRestore
import com.vynatix.holdfast.RestoreIssue
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.RestoreRejectedException
import com.vynatix.holdfast.RestoreReport
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult

// `Root.restore(tree, policy, sterile)`: the captures of a `TreeSnapshot`
// go back into the stores that sit at its nodes NOW — a keyed leaf's into
// whichever store lives under its key today (rebound when it is not the
// captured instance) — each planned outside every lock (`RestorePlanner`:
// schema check and `migrate` per leaf, target initializers, codecs, the
// policy), then staged raw into its root of ONE frame: all-or-nothing.

internal const val TREE_RESTORE_ID = "tree-restore"

/** A leaf capture matched to the store that holds its place now. */
private class ResolvedLeaf(
    val leaf: LeafNode,
    val store: Store<*>,
    val capture: StoreSnapshot,
    val rebound: Boolean,
)

internal fun restoreTree(
    root: Root,
    tree: TreeSnapshot,
    policy: RestorePolicy,
    sterile: Boolean,
): TransactionResult<TreeRestoreReport> {
    root.checkNotDisposed()
    require(tree.node.root === root) {
        "the tree was captured from root '${tree.node.root.name}', not root '${root.name}'; " +
            "restore it into its own root"
    }
    val resolved = ArrayList<ResolvedLeaf>()
    val skipped = ArrayList<LeafNode>()
    resolveLeaves(root, tree, resolved, skipped)
    if (policy == RestorePolicy.Strict && (skipped.isNotEmpty() || tree.unresolvedPaths.isNotEmpty())) {
        val issues =
            skipped.map { RestoreIssue.UnknownState(it.pathUnderRoot()) } +
                tree.unresolvedPaths.map { RestoreIssue.UnknownState(it.joinToString("/")) }
        return TransactionResult.Error(
            RestoreRejectedException(root.name, policy, issues),
            syntheticRolledBackTransaction(TREE_RESTORE_ID),
        )
    }
    val byStoreKey = resolved.associateBy { it.store.lockOrderKey }
    val reboundLeaves = resolved.filter { it.rebound }.map { it.leaf }.toSet()
    return treeFrame(
        root = root,
        id = TREE_RESTORE_ID,
        attempt = "restore the tree of root '${root.name}'",
        targets = resolved.map { it.leaf to it.store },
        prepare = { store ->
            val match = byStoreKey.getValue(store.lockOrderKey)
            PlannedRestore(store, match.capture.content, policy, sterile)
        },
        stage = { _, txn, plan -> plan.stage(txn) },
        finish = { worked, frameSkipped ->
            val perNode = LinkedHashMap<StoreNode, RestoreReport>()
            for (target in worked) perNode[target.leaf] = target.prepared.report()
            TreeRestoreReport(
                perNode = perNode,
                skipped = skipped + frameSkipped,
                rebound = worked.map { it.leaf }.filter { it in reboundLeaves },
                unresolvedPaths = tree.unresolvedPaths,
            )
        },
    )
}

/** Match every leaf capture of [tree] to the store at its place now; a place with no live store is skipped. */
private fun resolveLeaves(
    root: Root,
    tree: TreeSnapshot,
    resolved: MutableList<ResolvedLeaf>,
    skipped: MutableList<LeafNode>,
) {
    val capture = tree.leaf
    val node = tree.node
    if (capture != null && node is LeafNode) {
        val match = resolveLeaf(root, node, capture, tree.storeKey)
        if (match == null) skipped += node else resolved += match
    }
    for (child in tree.children) resolveLeaves(root, child, resolved, skipped)
}

private fun resolveLeaf(
    root: Root,
    node: LeafNode,
    capture: StoreSnapshot,
    capturedStoreKey: Long,
): ResolvedLeaf? {
    val branch = node.parent as? KeyedBranch<*, *>
    return if (branch == null) {
        node.store?.takeIf { !it.isDisposed }?.let { ResolvedLeaf(node, it, capture, rebound = false) }
    } else {
        // A keyed place is found by key: the captured store may be gone and a
        // new one live under the same key (rebound), or — a decoded pending
        // key, which captured no store at all — created since the decode (not
        // rebound: that is the process-death idiom, create then restore).
        val key = checkNotNull(node.key)
        root.registry.liveStore(branch, key)?.let { live ->
            val leaf = root.registry.leafOf(live) ?: node
            val rebound = capturedStoreKey != 0L && live.lockOrderKey != capturedStoreKey
            ResolvedLeaf(leaf, live, capture, rebound)
        }
    }
}
