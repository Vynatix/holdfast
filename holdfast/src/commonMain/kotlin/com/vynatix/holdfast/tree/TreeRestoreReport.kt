@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestoreIssue
import com.vynatix.holdfast.RestoreReport

/**
 * What `Root.restore(tree)` did, per leaf: [perNode] holds each restored
 * leaf's `RestoreReport` (what was restored, kept and skipped in that
 * store), [skipped] the leaves the tree held a capture for but no live
 * store — a keyed store since disposed, a branch store disposed before or
 * during the restore — [rebound] the keyed leaves whose capture reached a
 * store created after the capture under the same key (a decoded pending
 * key's store, created before the restore, is not rebound), and
 * [unresolvedPaths] the tree paths a decoded text named that this root does
 * not declare (a diagnostic naming nodes, the one string the tree hands
 * out). Under `RestorePolicy.Strict` a skipped leaf or an unresolved path
 * fails the restore before any leaf is touched.
 */
@ExperimentalStoreApi
class TreeRestoreReport internal constructor(
    val perNode: Map<StoreNode, RestoreReport>,
    val skipped: List<StoreNode>,
    val rebound: List<StoreNode>,
    val unresolvedPaths: List<List<String>>,
) {
    /** Every leaf report's issues, in tree order. */
    val issues: List<RestoreIssue> get() = perNode.values.flatMap { it.issues }

    override fun toString(): String =
        "TreeRestoreReport(restored=${perNode.keys.map { it.name }}, skipped=${skipped.map { it.name }}, " +
            "rebound=${rebound.map { it.name }}, unresolvedPaths=$unresolvedPaths)"
}
