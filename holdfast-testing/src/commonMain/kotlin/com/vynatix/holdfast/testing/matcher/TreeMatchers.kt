@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.testing.TreeHandle
import com.vynatix.holdfast.tree.StoreNode

/**
 * Assert that every live leaf under [node] committed a root of the SAME
 * frame — one `atomic`/`suspendAtomic`, `Root.restore` or `Root.reset`
 * enrolled them all and committed — judged from the tree's own timeline
 * (`TreeEvent`s, so a keyed store's frames count too). Returns the shared
 * frame id (the most recent one, when several frames spanned the whole
 * subtree). A frame vetoed on its last participant committed nowhere, so
 * it never counts.
 *
 * @throws AssertionError listing each leaf's committed frame ids when no
 *   frame is shared by all; [IllegalStateException] with fewer than two live leaves.
 */
@ExperimentalStoreApi
fun TreeHandle.shouldCommitTogether(node: StoreNode): String {
    val perLeaf = committedFrameIdsPerLeaf(node)
    check(perLeaf.size >= 2) {
        "shouldCommitTogether needs at least two live leaves under '${node.name}'; found ${perLeaf.size}"
    }
    val shared = perLeaf.map { (_, ids) -> ids.toSet() }.reduce { acc, ids -> acc intersect ids }
    if (shared.isEmpty()) {
        val detail =
            perLeaf.joinToString("\n") { (leaf, ids) ->
                "  ${leaf.name}: ${if (ids.isEmpty()) "(no committed frames)" else ids.joinToString()}"
            }
        throw AssertionError(
            "Expected every leaf under '${node.name}' of root '${root.name}' to commit inside one frame, " +
                "but no frame id is shared by every leaf's events:\n$detail",
        )
    }
    return perLeaf.first().second.last { it in shared }
}

/**
 * Negation of [shouldCommitTogether]: no single frame committed across every
 * live leaf under [node].
 */
@ExperimentalStoreApi
fun TreeHandle.shouldNotCommitTogether(node: StoreNode) {
    val perLeaf = committedFrameIdsPerLeaf(node)
    check(perLeaf.size >= 2) {
        "shouldNotCommitTogether needs at least two live leaves under '${node.name}'; found ${perLeaf.size}"
    }
    val shared = perLeaf.map { (_, ids) -> ids.toSet() }.reduce { acc, ids -> acc intersect ids }
    if (shared.isNotEmpty()) {
        throw AssertionError(
            "Expected no shared committed frame across the leaves under '${node.name}', " +
                "but found: ${shared.joinToString()}",
        )
    }
}
