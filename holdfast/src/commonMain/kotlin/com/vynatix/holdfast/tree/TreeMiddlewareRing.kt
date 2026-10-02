@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.setOuterMiddlewareUnchecked
import kotlinx.atomicfu.locks.synchronized

// `tree.middlewares(...)`/`removeMiddleware` (issue #21): tree middleware
// lives in each member store's OUTER ring (`OuterMiddlewareRing`) — always
// outermost of the store's own `middlewares(...)`, untouched by its
// `clearMiddleware()` — as one adapter per installed middleware. Each store
// keeps what IT installed (`TreeLeafAttachment.installed`); a store's ring is
// what it and every ancestor installed: its own first, the root-most last
// (last is outermost, so a parent's ring wraps a child's), deduplicated by
// identity keeping the outermost occurrence — a middleware installed on a
// child and on its parent fires once per transaction, at the parent's place.
//
// `syncTreeRing` makes one store's ring match its ancestry as a whole-set
// replace under that store's `syncLock`, reading every ancestor's installs
// there, so the last of two racing syncs sees every earlier install. Attach
// (`TreeAttach.kt`, after registering) and dispose (`StoreDetach.kt`) sync
// the affected subtree directly; an install lists its subtree after writing
// its installs, so either the listing sees a joining child or the child's
// own sync reads the install.

/**
 * Make [leaf]'s store's outer ring match its ancestry now: an adapter whose
 * middleware is still wanted is kept (an observation it started keeps its
 * mark), a new one is made, a surplus one retired. A store that is gone or
 * disposed (its ring refuses) has every adapter retired — self-healing when
 * its dispose has not reached the ring yet.
 */
internal fun syncTreeRing(leaf: LeafNode) {
    val attachment = leaf.attachment ?: return
    synchronized(attachment.syncLock) {
        val store = attachment.storeRef
        val current = attachment.adapters
        val next =
            if (store == null || store.isDisposed) {
                null
            } else {
                val wanted =
                    desiredRing(leaf).map { mw ->
                        current.firstOrNull { it.middleware === mw } ?: TreeMiddlewareAdapter(leaf, mw)
                    }
                wanted.takeIf { runCatching { store.setOuterMiddlewareUnchecked(it) }.isSuccess }
            }
        current.filter { adapter -> next == null || next.none { it === adapter } }.forEach { it.retire() }
        attachment.adapters = next.orEmpty()
    }
}

/**
 * What [leaf]'s ring should hold: own installs first, root-most last, each
 * middleware once at its outermost place. The walk stops at an ancestor
 * whose store is gone or disposed: that store is detaching and releases its
 * children (`StoreDetach.kt`), so neither its installs nor those above it
 * cover anything under it any more — even before its children's links are
 * cleared.
 */
private fun desiredRing(leaf: LeafNode): List<TreeMiddleware> {
    val all =
        generateSequence(leaf) { it.parentStoreNode() }
            .takeWhile { it === leaf || it.attachment?.storeRef?.isDisposed == false }
            .flatMap { it.attachment?.installed.orEmpty() }
            .toList()
    val kept = ArrayList<TreeMiddleware>(all.size)
    for (mw in all.asReversed()) if (kept.none { it === mw }) kept.add(mw)
    return kept.asReversed()
}

/**
 * `tree.middlewares(...)` on [owner]: install [middleware] (one installed
 * already moves to the outermost place among [owner]'s), then sync [owner]
 * and every live store under it.
 *
 * @throws IllegalStateException as [guard] refuses, or if [owner] is disposed.
 */
internal fun installTreeMiddleware(
    owner: Store<*>,
    middleware: List<TreeMiddleware>,
) {
    val attachment = owner.treeAttachment()
    guard(owner, attachment.node, "install tree middleware")
    synchronized(attachment.installLock) {
        attachment.installed = attachment.installed.filterNot { m -> middleware.any { it === m } } + middleware
    }
    for ((leaf, _) in liveLeavesUnder(attachment.node, attachment.node)) syncTreeRing(leaf)
}

/**
 * `tree.removeMiddleware(middleware)` on [owner]: `false` when [owner]
 * never installed it — or is disposed (the documented no-check exception:
 * its dispose already removed everything) — else remove it and sync
 * [owner]'s subtree (a member disposing meanwhile is left to its own
 * dispose).
 *
 * @throws IllegalStateException as [guard] refuses.
 */
internal fun removeTreeMiddleware(
    owner: Store<*>,
    middleware: TreeMiddleware,
): Boolean {
    val attachment = owner.internalAttachment(treeMembershipKey)?.takeIf { !owner.isDisposed } ?: return false
    guard(owner, attachment.node, "remove tree middleware")
    val removed =
        synchronized(attachment.installLock) {
            val next = attachment.installed.filterNot { it === middleware }
            (next.size != attachment.installed.size).also { attachment.installed = next }
        }
    if (removed) {
        val members = runCatching { liveLeavesUnder(attachment.node, attachment.node) }.getOrDefault(emptyList())
        for ((leaf, _) in members) runCatching { syncTreeRing(leaf) }
    }
    return removed
}

/** The adapters [store]'s outer ring holds for its tree, innermost first; empty without tree state. For tests. */
internal fun Store<*>.treeRingAdapters(): List<TreeMiddlewareAdapter> {
    val attachment = internalAttachment(treeMembershipKey)
    return attachment?.adapters.orEmpty()
}

/**
 * Refuse a change inside a frame, or an action or hook on [owner] or any
 * live store under it: chains are snapshotted per transaction. A suspending
 * body resumes on any thread, so no member can tell "inside" it from
 * "beside" it — a holder parked on another coroutine, whose chain was
 * snapshotted at its start, is by itself no reason to refuse — so the probe
 * is the settle scope its entry carries across dispatch: an open scope on
 * this thread while a member has a suspending owner. That is conservative:
 * the scope may be the holder's body, or another entry on this thread
 * beside a holder parked elsewhere, and the probe cannot tell the two
 * apart; from outside every entry a change beside a parked holder is
 * allowed. Not guaranteed on iOS (or wasmJs): the scope rides
 * `slotBracketingInterceptor` there, which a nested
 * `withContext(otherDispatcher)` inside the body replaces.
 */
private fun guard(
    owner: Store<*>,
    ownerNode: LeafNode,
    attempt: String,
) {
    val name = owner.displayName
    check(FrameMarkers.current() == null) {
        "Cannot $attempt on $name from inside an atomic(...) frame: install or remove it from outside"
    }
    val members = liveLeavesUnder(ownerNode, ownerNode)
    val busy =
        members.firstOrNull { (_, store) ->
            store.internalOwnsActiveTransaction() || store.appliedTransactionNestedHere() != null
        }
    check(busy == null) {
        "Cannot $attempt on $name from inside a transaction of its member '${busy?.first?.name}' " +
            "(an action body, a middleware hook, an observer): install or remove it from outside"
    }
    val inEntry = SettleScopes.current() != null
    val held = if (inEntry) members.firstOrNull { (_, store) -> store.suspendingOwner != null } else null
    check(held == null) {
        "Cannot $attempt on $name while an entry is open on this thread and its member " +
            "'${held?.first?.name}' is held by a suspendAction or suspendAtomic body (this call is inside that " +
            "body, or inside another entry beside it): install or remove it from outside every entry"
    }
}
