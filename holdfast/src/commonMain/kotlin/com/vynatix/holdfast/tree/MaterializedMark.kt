@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.settling
import kotlinx.atomicfu.atomic

/**
 * Materialize every declared child of [ownerNode]'s store that has not run
 * yet, then, recursively, every live child's own. A child that disposed
 * meanwhile is skipped with its subtree; the first throwing lambda of a
 * live store propagates (its entry stays retryable and the entries after it
 * are left unmaterialized). A store whose tree state is gone has nothing to
 * materialize.
 */
internal fun materializeSubtree(ownerNode: LeafNode) {
    val attachment = ownerNode.attachment ?: return
    val mark = MaterializedMark(attachment.structuralGeneration.value, childDeclarationEpoch.value)
    val quiet = attachment.structuralWriters.value == 0
    if (quiet && attachment.materializedAt.value == mark) return
    materializeSubtreeFully(attachment.registry)
    if (quiet) attachment.materializedAt.value = mark
}

private fun materializeSubtreeFully(registry: ChildRegistry) {
    settling {
        for (entry in registry.declaredEntries()) {
            if (entry.kind != ChildEntry.Kind.Keyed && entry.produced == null) materializeChild(entry)
        }
        for ((leaf, store) in registry.liveChildStores()) {
            if (store.isDisposed) continue
            try {
                materializeSubtree(leaf)
            } catch (e: IllegalStateException) {
                // A child that disposed meanwhile is skipped as a listing skips it,
                // so an ancestor's `children` never throws "disposed" for it.
                if (leaf.store?.isDisposed == false) throw e
            }
        }
    }
}

/** A complete `materializeSubtree` pass's starting point; see `TreeLeafAttachment.materializedAt`. */
internal data class MaterializedMark(
    val generation: Long,
    val declarationEpoch: Long,
)

/** Bumped by every child declaration, so a recorded [MaterializedMark] never hides a new one. */
internal val childDeclarationEpoch = atomic(0L)
