@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

/**
 * Attach phase 6 (`TreeAttach.kt`): tell [chain] — the store nodes from the
 * new parent's store up, nearest first — that a store joined their subtree,
 * outside every lock; a throwing listener is handed to [report] and the
 * others are still told.
 */
internal class AttachAnnouncer(
    private val chain: List<LeafNode>,
    private val report: (Throwable) -> Unit,
) {
    /**
     * Tell [chain] that [leaf] — and every live store already under it (a
     * graft) — joined, then end [leaf]'s ANNOUNCING phase: re-check, while
     * it is still ANNOUNCING, that no ancestor closed meanwhile (such an
     * ancestor may have walked before our Attached was visible to it: it is
     * told the detach), then move to ANNOUNCED — or, when a dispose deferred
     * [leaf]'s detach to this thread meanwhile, deliver it after every
     * listener heard Attached and reset the phase.
     */
    fun announce(leaf: LeafNode) {
        val below =
            runCatching {
                listingOf(leaf, leaf)
                    .shape
                    .leaves()
                    .drop(1)
                    .map { it.first }
            }.getOrDefault(emptyList())
        val announced = listOf(leaf) + below
        try {
            announceAttached(chain, listOf(leaf), report)
            for (descendant in below) {
                announceAttached(chain, listOf(descendant), report)
                revalidateDescendant(leaf, descendant)
            }
        } finally {
            detachFromClosedAncestors(announced)
            if (!leaf.attachPhase.compareAndSet(ATTACH_ANNOUNCING, ATTACH_ANNOUNCED)) {
                // DETACH_DEFERRED: the store disposed while it was announced.
                try {
                    announceDetached(chain, announced, report)
                } finally {
                    leaf.attachPhase.compareAndSet(DETACH_DEFERRED, ATTACH_UNANNOUNCED)
                }
            }
        }
    }

    /**
     * A grafted [descendant] holds no epoch of ours: if it disposed, or a
     * store between it and [top] closed, while it was being told, its own
     * detach may already have passed these listeners — tell them again
     * (`onDetached` is idempotent). A store's `close()` precedes its walk,
     * so either that walk follows our delivery or this read sees it closed.
     */
    private fun revalidateDescendant(
        top: LeafNode,
        descendant: LeafNode,
    ) {
        var current = descendant.parentStoreNode()
        var gone = descendant.store == null
        while (!gone && current != null && current !== top) {
            gone = current.attachment?.registry?.disposed != false
            current = current.parentStoreNode()
        }
        if (gone) announceDetached(chain, listOf(descendant), report)
    }

    /**
     * An ancestor whose registry closed while [announced] was being told may
     * have walked its subtree before this attach was visible to it: tell it
     * and every store above it the detach (a listener may hear it twice;
     * `onDetached` is idempotent).
     */
    private fun detachFromClosedAncestors(announced: List<LeafNode>) {
        val closedAt = chain.indexOfFirst { it.attachment?.registry?.disposed ?: true }
        if (closedAt >= 0) announceDetached(chain.subList(closedAt, chain.size), announced, report)
    }
}
