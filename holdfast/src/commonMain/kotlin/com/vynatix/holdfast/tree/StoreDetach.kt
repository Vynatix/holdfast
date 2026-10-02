@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import kotlinx.atomicfu.locks.synchronized

// A store's dispose, as its tree hears it (`TreeLeafAttachment.onStoreDisposed`,
// the last step of `Store.dispose()`, on the disposing thread, under whatever
// locks its caller holds — possibly inside the store's own action, holding
// its `transactionLock`). The store leaves its parent; its children are
// released as subtree roots and keep working (the tree disposes nothing);
// every ancestor's listeners hear each leaf that left their subtree; the
// rings are re-synced; the store's own tree value stops.
//
// Every step runs in its own `runCatching`; failures are collected and
// thrown together at the end, so `AttachmentSlot.notifyDisposed` reports
// them through the store's `uncaughtObserverHandler` (else its default log
// line) — the same route every attachment's dispose failure takes.

/** One run of [TreeLeafAttachment.onStoreDisposed] for [attachment]. */
internal class StoreDetach(
    private val attachment: TreeLeafAttachment,
) {
    private val node = attachment.node
    private val store = attachment.storeRef
    private val failures = ArrayList<Throwable>()

    /** The parent edge as of the start: what the detach goes through even while it is being cleared. */
    private val edge = attachment.parentEdge.value

    /** The store nodes above this one, nearest first, captured under `treeStructureLock` (step 0). */
    private lateinit var chain: List<LeafNode>

    /** The attachments of [chain], captured in the same lock take: what the seqlock bumps hit. */
    private lateinit var chainAttachments: List<TreeLeafAttachment>

    private var wasLive = false
    private var released: List<LeafNode> = emptyList()
    private var descendants: Map<LeafNode, List<LeafNode>> = emptyMap()

    fun run() {
        treeStructureLock.withLock {
            chain = ancestorsOf(node.parent)
            chainAttachments = chain.mapNotNull { it.attachment }
        }
        step("close the store's tree middleware") { closeOwnRing() }
        step("detach from the parent") { detachFromParent() }
        step("release the children") { releaseChildren() }
        step("announce the detach") { announce() }
        step("reset the released children") { resetReleased() }
        step("dispose the tree value") { disposeValue() }
        step("re-sync the released subtrees' middleware") { resyncReleased() }
        node.attachment = null
        reportFailures()
    }

    private inline fun step(
        name: String,
        body: () -> Unit,
    ) {
        runCatching(body).onFailure { failures += IllegalStateException("tree dispose step '$name' failed", it) }
    }

    /**
     * Step 1: forget this store's installs FIRST — so a sync running
     * concurrently computes a ring without them — then sync this store and
     * every store under it: each retires this store's adapters as surplus
     * (this store's own ring refuses, as `dispose()` cleared it: everything
     * there is retired) and keeps its ancestors'.
     */
    private fun closeOwnRing() {
        synchronized(attachment.installLock) { attachment.installed = emptyList() }
        val members = listingOf(node, node, includeDisposing = true).shape.leaves()
        for ((leaf, _) in members) runCatching { syncTreeRing(leaf) }.onFailure { failures += it }
    }

    /** Step 2: leave the parent's registry, then drop the store (so `onDetached` for this leaf sees none). */
    private fun detachFromParent() {
        bumpWriters(chainAttachments)
        try {
            wasLive = edge?.ownerRegistry?.detachLeaf(node, edge) ?: false
        } finally {
            endBumps(chainAttachments)
        }
        attachment.storeRef = null
    }

    /** Step 3: close this store's registry and list what hangs under each released child. */
    private fun releaseChildren() {
        val targets = listOf(attachment) + chainAttachments
        bumpWriters(targets)
        try {
            released = attachment.registry.close()
        } finally {
            endBumps(targets)
        }
        descendants =
            released.associateWith { child ->
                runCatching {
                    listingOf(child, child, includeDisposing = true)
                        .shape
                        .leaves()
                        .drop(1)
                        .map { it.first }
                }.getOrDefault(emptyList())
            }
    }

    /**
     * Step 4: tell the ancestors, nearest first, outside every registry
     * lock — once per attach epoch, through `claimDetach`: this leaf when it
     * was live under its parent (else the parent's own dispose covers it),
     * and each released child with everything under it. A leaf still being
     * announced is left to its announcer; one never announced is skipped.
     */
    private fun announce() {
        // Each callback is isolated (`announceDetached`); the phase reset runs in a
        // `finally` regardless, so a leaf is never left DETACHED (a re-adoption
        // would wait on it forever).
        if (wasLive && node.claimDetach()) {
            try {
                announceDetached(chain, listOf(node)) { failures += it }
            } finally {
                node.attachPhase.compareAndSet(DETACHED, ATTACH_UNANNOUNCED)
            }
        }
        for (child in released) {
            if (!child.claimDetach()) continue
            try {
                announceDetached(chain, listOf(child) + descendants[child].orEmpty()) { failures += it }
            } finally {
                child.attachPhase.compareAndSet(DETACHED, ATTACH_UNANNOUNCED)
            }
        }
    }

    /**
     * Step 5: the released children become subtree roots (parentless,
     * class-named). This store's own node keeps the place it had — a disposed
     * store is never adopted again — and only loses its parent edge.
     */
    private fun resetReleased() {
        treeStructureLock.withLock {
            for (child in released) releaseLocked(child)
            attachment.parentEdge.value = null
        }
    }

    /** Under `treeStructureLock`: [child], when still edged to this store, becomes a parentless, class-named root. */
    private fun releaseLocked(child: LeafNode) {
        val childAttachment = child.attachment
        if (childAttachment?.parentEdge?.value?.ownerRegistry !== attachment.registry) return
        childAttachment.parentEdge.value = null
        if (childAttachment.storeRef?.isDisposed == false) {
            child.parent = null
            child.name = child.defaultName
            child.nameOrigin = NameOrigin.ClassName
            child.key = null
        }
    }

    /** Step 6: stop this store's tree value; its host is disposed where no lock of this store is held. */
    private fun disposeValue() {
        val handle = attachment.handleRef.value ?: return
        handle.onOwnerDisposed(checkNotNull(store) { "the disposing store was already dropped" })
    }

    /**
     * Step 7: every released store's ring, from its now-current ancestry
     * (none above a released child). Each released subtree is listed AGAIN
     * here, after step 5 made its top parentless: a store that attached
     * under a released child after step 3's listing synced through this
     * store's old ancestry and is in no earlier list, while any attach that
     * publishes after this listing syncs against the parentless top. The
     * step-3 lists are synced too (a store there that left meanwhile syncs
     * against wherever it is now).
     */
    private fun resyncReleased() {
        for (child in released) {
            val now =
                runCatching { listingOf(child, child, includeDisposing = true).shape.leaves().map { it.first } }
                    .getOrDefault(listOf(child))
            val earlier = descendants[child].orEmpty().filter { leaf -> now.none { it === leaf } }
            for (leaf in now + earlier) {
                runCatching { syncTreeRing(leaf) }.onFailure { failures += it }
            }
        }
    }

    private fun reportFailures() {
        val first = failures.firstOrNull() ?: return
        val reported =
            IllegalStateException(
                "Holdfast: the store tree could not fully detach ${store?.displayName ?: "a store"} on dispose " +
                    "(${failures.size} failure(s)); the store is disposed regardless",
                first,
            )
        for (other in failures.drop(1)) reported.addSuppressed(other)
        throw reported
    }
}
