@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FatalHold
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.SettleTask
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import kotlinx.atomicfu.locks.synchronized

// A store's dispose, as its tree hears it (`TreeLeafAttachment.onStoreDisposed`,
// the last step of `Store.dispose()`, on the disposing thread, under whatever
// locks its caller holds — possibly inside the store's own action, holding
// its `transactionLock`). The store leaves its parent; its children are
// released as subtree roots and keep working; every ancestor's listeners
// hear each leaf that left their subtree; the rings are re-synced; the
// store's own tree value stops. Last, the keyed stores the store's keyed
// branches OWN (`KeyedDisposal.Dispose`, the default) are disposed — never
// under a tree lock and never inside a step: through the settle scope open
// on this thread (so inside an action or frame they dispose when that entry
// settles, after it released every lock), else handed to the post-commit
// queue of this store when this thread holds its transaction lock with no
// scope open (as the value host's dispose is), else inline, last.
//
// Every step runs in its own `runCatching`; failures are collected and
// thrown together at the end, so `AttachmentSlot.notifyDisposed` reports
// them through the store's `uncaughtObserverHandler` (else its default log
// line) — the same route every attachment's dispose failure takes. A fatal
// error (issue #61) is held instead, never wrapped or collected: every later
// step still runs, and the end throws it, carrying the collected failures.

/** One run of [TreeLeafAttachment.onStoreDisposed] for [attachment]. */
internal class StoreDetach(
    private val attachment: TreeLeafAttachment,
) {
    private val node = attachment.node
    private val store = attachment.storeRef
    private val failures = ArrayList<Throwable>()

    /** The first fatal error a step threw (issue #61): thrown after the last step, in place of [failures]. */
    private val fatal = FatalHold()

    /** The parent edge as of the start: what the detach goes through even while it is being cleared. */
    private val edge = attachment.parentEdge.value

    /** The store nodes above this one, nearest first, captured under `treeStructureLock` (step 0). */
    private lateinit var chain: List<LeafNode>

    /** The attachments of [chain], captured in the same lock take: what the seqlock bumps hit. */
    private lateinit var chainAttachments: List<TreeLeafAttachment>

    private var wasLive = false
    private var released: List<LeafNode> = emptyList()
    private var descendants: Map<LeafNode, List<LeafNode>> = emptyMap()

    /** The released keyed stores whose branch owns them (`KeyedDisposal.Dispose`): disposed last (step 9). */
    private var owned: List<Store<*>> = emptyList()

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
        // Step 9: the keyed stores this store's branches owned, each already a released subtree root.
        step("dispose the owned keyed stores") { if (owned.isNotEmpty()) OwnedKeyedDisposeTask(owned).dispatch(store) }
        reportFailures()
    }

    private inline fun step(
        name: String,
        body: () -> Unit,
    ) {
        runCatching(body).onFailure {
            if (!fatal.holdIfFatal(it)) failures += IllegalStateException("tree dispose step '$name' failed", it)
        }
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
        for ((leaf, _) in members) runCatching { syncTreeRing(leaf) }.onFailure { fatal.collectInto(failures, it) }
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
        val registry = attachment.registry
        owned = released.mapNotNull { child -> child.store?.takeIf { child.isOwnedByKeyedBranchOf(registry) } }
        descendants =
            released.associateWith { child ->
                fatal.listedOr(emptyList()) {
                    listingOf(child, child, includeDisposing = true)
                        .shape
                        .leaves()
                        .drop(1)
                        .map { it.first }
                }
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
                announceDetached(chain, listOf(node)) { fatal.collectInto(failures, it) }
            } finally {
                node.attachPhase.compareAndSet(DETACHED, ATTACH_UNANNOUNCED)
            }
        }
        for (child in released) {
            if (!child.claimDetach()) continue
            try {
                announceDetached(chain, listOf(child) + descendants[child].orEmpty()) {
                    fatal.collectInto(failures, it)
                }
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
                fatal.listedOr(listOf(child)) {
                    listingOf(child, child, includeDisposing = true).shape.leaves().map { it.first }
                }
            val earlier = descendants[child].orEmpty().filter { leaf -> now.none { it === leaf } }
            for (leaf in now + earlier) {
                runCatching { syncTreeRing(leaf) }.onFailure { fatal.collectInto(failures, it) }
            }
        }
    }

    /** Throw what the steps failed with: the fatal error one held (issue #61), carrying the rest, else the rest. */
    private fun reportFailures() {
        val reported =
            failures.firstOrNull()?.let { first ->
                IllegalStateException(
                    "Holdfast: the store tree could not fully detach ${store?.displayName ?: "a store"} on dispose " +
                        "(${failures.size} failure(s)); the store is disposed regardless",
                    first,
                ).apply { failures.drop(1).forEach { addSuppressed(it) } }
            }
        fatal.rethrowHeld(alongside = reported)
        if (reported != null) throw reported
    }
}

/** Collect [failure] into [failures] for the end of a detach — a fatal error is held instead. */
private fun FatalHold.collectInto(
    failures: MutableList<Throwable>,
    failure: Throwable,
) {
    if (!holdIfFatal(failure)) failures += failure
}

/** [listing]'s answer, or [fallback] when it throws: ignored, except a fatal error, which is held. */
private inline fun <T> FatalHold.listedOr(
    fallback: T,
    listing: () -> T,
): T = runCatching(listing).onFailure { holdIfFatal(it) }.getOrDefault(fallback)

/**
 * Whether this released leaf hangs under a keyed branch of the store whose
 * children [registry] holds, and that branch disposes its stores with it
 * (`KeyedDisposal.Dispose`).
 */
private fun LeafNode.isOwnedByKeyedBranchOf(registry: ChildRegistry): Boolean {
    val edge = attachment?.parentEdge?.value
    val branch = edge?.parentNode as? KeyedBranch<*, *>
    return branch != null && edge.ownerRegistry === registry && branch.onParentDispose == KeyedDisposal.Dispose
}

/**
 * The keyed stores a disposed store's branches owned, disposed once — when
 * the settle scope the parent's dispose ran in settles, or by whoever runs
 * it directly. `Store.dispose()` is idempotent and never throws, except a
 * fatal error (issue #61): every store is still disposed, then the first
 * such error is thrown.
 */
internal class OwnedKeyedDisposeTask(
    private val stores: List<Store<*>>,
) : SettleTask {
    override val settleRank: Int get() = 0

    override fun settle() = disposeEach(stores)

    override fun deferPastSettle(report: Boolean) = settle()

    /**
     * Report through the first store of the list, the one this task was
     * disposing when it threw (`dispose()` throws nothing but a fatal error,
     * which a settle never reports, so this is a backstop — a library bug,
     * but not a silent one). Works on a store the task already disposed.
     */
    override fun reportUncaught(error: Throwable) {
        stores.first().internalReportUncaughtFailure(error)
    }

    /**
     * Dispose the stores where no lock [disposing]'s dispose holds is held:
     * when the settle scope open on this thread settles, else from
     * [disposing]'s post-commit queue when this thread holds its transaction
     * lock with no scope open, else now.
     */
    fun dispatch(disposing: Store<*>?) {
        when {
            SettleScopes.current()?.enqueue(this) == true -> Unit
            disposing != null && disposing.transactionLock.isHeldByCurrentThread() ->
                disposing.handOffPostCommit(::settle)
            else -> settle()
        }
    }
}
