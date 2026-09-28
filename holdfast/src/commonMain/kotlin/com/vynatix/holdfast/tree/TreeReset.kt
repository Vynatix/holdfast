@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.materializeDeclaredStates
import com.vynatix.holdfast.stageResetOfDeclaredStates

// `Root.reset(node)`: every live leaf of the subtree is reset in its own
// transaction of ONE frame (the `stageResetOfDeclaredStates` shape issue #20
// PR 5 left for #21) — never-read states materialized before the frame,
// initializers re-run in fresh-store order, output staged raw and only where
// it differs, so observers fire once per changed state and never for the rest.

internal const val TREE_RESET_ID = "tree-reset"

internal fun resetTree(
    root: Root,
    node: StoreNode,
): TransactionResult<TreeResetReport> {
    root.checkNotDisposed()
    root.requireOwn(node)
    val targets = leavesUnder(root, node)
    return treeFrame(
        root = root,
        id = TREE_RESET_ID,
        attempt = "reset the subtree at '${node.name}' of root '${root.name}'",
        targets = targets,
        // Materialize outside every lock: a never-read state's initializer
        // must not run under the frame's locks. A failure is carried into the
        // frame, so the reset fails as one transaction there.
        prepare = { store -> runCatching { store.materializeDeclaredStates() }.exceptionOrNull() },
        stage = { store, txn, failure ->
            failure?.let { throw it }
            store.stageResetOfDeclaredStates(txn)
        },
        finish = { worked, skipped -> TreeResetReport(reset = worked.map { it.leaf }, skipped = skipped) },
    )
}

/** The live leaves of the subtree at [node] with their stores, in tree order, as of this call. */
internal fun leavesUnder(
    root: Root,
    node: StoreNode,
): List<Pair<LeafNode, Store<*>>> {
    val out = ArrayList<Pair<LeafNode, Store<*>>>()

    fun walk(shape: TreeShape) {
        out.addAll(shape.leaves)
        shape.children.forEach(::walk)
    }
    walk(root.registry.shapeOf(node))
    return out
}
