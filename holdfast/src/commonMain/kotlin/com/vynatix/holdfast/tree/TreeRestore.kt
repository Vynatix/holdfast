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

// `tree.restore(tree, policy, sterile)`: the captures of a `TreeSnapshot`
// go back into the stores that sit at its nodes NOW — a keyed store's into
// whichever store lives under its key today (rebound when it is not the
// captured instance) — each planned outside every lock (`RestorePlanner`:
// schema check and `migrate` per store, target initializers, codecs, the
// policy), then staged raw into its root of ONE frame: all-or-nothing. A
// captured store that left the receiver's subtree since (disposed, or
// released by an ancestor's dispose) is skipped and reported by its path
// AS CAPTURED.

internal const val TREE_RESTORE_ID = "tree-restore"

/** A leaf capture matched to the store that holds its place now. */
private class ResolvedLeaf(
    val leaf: LeafNode,
    val store: Store<*>,
    val capture: StoreSnapshot,
    val rebound: Boolean,
)

internal fun restoreTree(
    ownerNode: LeafNode,
    tree: TreeSnapshot,
    policy: RestorePolicy,
    sterile: Boolean,
): TransactionResult<TreeRestoreReport> {
    val owner = checkNotNull(ownerNode.store) { "${ownerNode.name}'s store disposed" }
    owner.checkNotDisposed()
    require(tree.node === ownerNode || tree.node.isUnder(ownerNode)) {
        "the tree was captured under '${tree.index.ownerNode.name}' and its node '${tree.name}' is not under " +
            "this store ('${ownerNode.name}'); restore it through the store it was captured from"
    }
    val resolved = ArrayList<ResolvedLeaf>()
    val skipped = ArrayList<LeafNode>()
    resolveLeaves(ownerNode, tree, resolved, skipped)
    if (policy == RestorePolicy.Strict && (skipped.isNotEmpty() || tree.unresolvedPaths.isNotEmpty())) {
        val issues =
            skipped.map { RestoreIssue.UnknownState(tree.index.pathOf(it).joinToString("/")) } +
                tree.unresolvedPaths.map { RestoreIssue.UnknownState(it.joinToString("/")) }
        return TransactionResult.Error(
            RestoreRejectedException(ownerNode.name, policy, issues),
            syntheticRolledBackTransaction(TREE_RESTORE_ID),
        )
    }
    val byStoreKey = resolved.associateBy { it.store.lockOrderKey }
    val reboundLeaves = resolved.filter { it.rebound }.map { it.leaf }.toSet()
    return treeFrame(
        owner = owner,
        id = TREE_RESTORE_ID,
        attempt = "restore the tree under '${ownerNode.name}'",
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

/** Match every store capture of [tree] to the store at its place now; a place with no live store is skipped. */
private fun resolveLeaves(
    ownerNode: LeafNode,
    tree: TreeSnapshot,
    resolved: MutableList<ResolvedLeaf>,
    skipped: MutableList<LeafNode>,
) {
    val capture = tree.leaf
    val node = tree.node
    if (capture != null && node is LeafNode) {
        val match = resolveLeaf(ownerNode, tree, node, capture)
        if (match == null) skipped += node else resolved += match
    }
    for (child in tree.children) resolveLeaves(ownerNode, child, resolved, skipped)
}

private fun resolveLeaf(
    ownerNode: LeafNode,
    tree: TreeSnapshot,
    node: LeafNode,
    capture: StoreSnapshot,
): ResolvedLeaf? {
    // The captured structure says where the store was; the live links say
    // whether that place is still under the receiver.
    val branch = tree.index.parentOf[node] as? KeyedBranch<*, *>
    return if (branch == null) {
        node.store
            ?.takeIf { !it.isDisposed && (node === ownerNode || node.isUnder(ownerNode)) }
            ?.let { ResolvedLeaf(node, it, capture, rebound = false) }
    } else {
        // A keyed place is found by key: the captured store may be gone and a
        // new one live under the same key (rebound), or — a decoded pending
        // key, which captured no store at all — created since the decode (not
        // rebound: that is the process-death idiom, create then restore).
        // A keyed store released by its branch owner's dispose lost its key:
        // that branch lists nothing any more.
        val key = node.key ?: return null
        val leaf = branch.liveLeaf(key)?.takeIf { it === ownerNode || it.isUnder(ownerNode) }
        leaf?.store?.takeIf { !it.isDisposed }?.let { live ->
            val rebound = tree.storeKey != 0L && live.lockOrderKey != tree.storeKey
            ResolvedLeaf(leaf, live, capture, rebound)
        }
    }
}
