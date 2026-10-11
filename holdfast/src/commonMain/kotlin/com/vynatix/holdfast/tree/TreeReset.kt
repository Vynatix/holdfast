@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.materializeDeclaredStates
import com.vynatix.holdfast.stageResetOfDeclaredStates

// `tree.reset(node)`: every live store of the subtree — the receiver's own
// included when [node] is the receiver — is reset in its own
// transaction of ONE frame (the `stageResetOfDeclaredStates` shape issue #20
// PR 5 left for #21) — never-read states materialized before the frame,
// initializers re-run in fresh-store order, output staged raw and only where
// it differs, so observers fire once per changed state and never for the rest.

internal const val TREE_RESET_ID = "tree-reset"

internal fun resetTree(
    ownerNode: LeafNode,
    node: StoreNode,
): TransactionResult<TreeResetReport> {
    val owner = checkNotNull(ownerNode.store) { "${ownerNode.name}'s store disposed" }
    owner.checkNotDisposed()
    require(node === ownerNode || node.isUnder(ownerNode)) { "node '${node.name}' is not under '${ownerNode.name}'" }
    return treeFrame(
        owner = owner,
        id = TREE_RESET_ID,
        attempt = "reset the subtree at '${node.name}' under '${ownerNode.name}'",
        targets = liveLeavesUnder(ownerNode, node),
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
