@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.removeOuterMiddlewareUnchecked
import com.vynatix.holdfast.setOuterMiddlewareUnchecked
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// `Root.middlewares`/`removeMiddleware` (issue #21 decision U12): the tree's
// middleware lives in each leaf's OUTER ring (`OuterMiddlewareRing`, the
// PR 21-1 seam) — always outermost of the leaf's own `middlewares(...)`,
// untouched by its `clearMiddleware()` — as one adapter per installed
// middleware per leaf. The ring below is the source of truth: the installers
// in order (last outermost) and the live members; every change re-syncs
// each live member's ring as a whole-set replace, so interleaving installs
// converge on the last writer. A leaf's ring holds only adapters this ring
// made, so two roots never share a leaf's ring: a store belongs to one tree,
// a member no longer live is never written again, and a root's dispose
// unwinds its adapters by identity before it releases any membership.

/** The ring of one root: what is installed, on which leaves. */
internal class TreeMiddlewareRing(
    private val root: Root,
) : LeafMembershipListener() {
    /** Guards [installed], [members] and [closed]; never held while calling into a store. */
    private val ringLock = SynchronizedObject()

    /** The installers, in install order — last is outermost. */
    private var installed: List<TreeMiddleware> = emptyList()

    /** The live leaves, by identity. */
    private val members = HashMap<LeafNode, Member>()

    /** Set by [close]: no leaf joins after it. */
    private var closed = false

    /** One leaf: its store and the adapters its ring holds now. */
    private class Member(
        val leaf: LeafNode,
        val store: Store<*>,
    ) {
        /** Serializes syncs of this member; taken after [ringLock] is released, never inside it. */
        val syncLock = SynchronizedObject()

        /** Guarded by [syncLock]. */
        var adapters: List<TreeMiddlewareAdapter> = emptyList()
    }

    /** The installed middleware, outermost last. */
    val installedMiddleware: List<TreeMiddleware> get() = synchronized(ringLock) { installed }

    /** How many leaves hold adapters right now. */
    val memberCount: Int get() = synchronized(ringLock) { members.size }

    /** The adapters the leaf [store]'s ring holds, in ring order (innermost first); empty when it is no member. */
    fun adaptersOf(store: Store<*>): List<TreeMiddlewareAdapter> {
        val member = synchronized(ringLock) { members.values.firstOrNull { it.store === store } } ?: return emptyList()
        return synchronized(member.syncLock) { member.adapters }
    }

    /**
     * Install [middleware] on every leaf, attached now or later, outermost of
     * each leaf's own; among the installed, the last is outermost. One
     * installed already moves to the outermost place.
     */
    fun install(middleware: List<TreeMiddleware>) {
        guard("install tree middleware")
        val snapshot =
            synchronized(ringLock) {
                installed = installed.filterNot { m -> middleware.any { it === m } } + middleware
                members.values.toList()
            }
        snapshot.forEach(::sync)
    }

    /** Stop [middleware] on every leaf: no new observation starts after this returns; started ones end. */
    fun remove(middleware: TreeMiddleware): Boolean {
        guard("remove tree middleware")
        val (removed, snapshot) =
            synchronized(ringLock) {
                val next = installed.filterNot { it === middleware }
                val removed = next.size != installed.size
                installed = next
                removed to members.values.toList()
            }
        if (removed) snapshot.forEach(::sync)
        return removed
    }

    override fun onAttached(leaf: LeafNode) {
        val store = leaf.store ?: return
        val member = Member(leaf, store)
        val joined =
            synchronized(ringLock) {
                if (!closed) members[leaf] = member
                !closed
            }
        if (joined) sync(member)
    }

    override fun onDetached(leaf: LeafNode) {
        val member = synchronized(ringLock) { members.remove(leaf) } ?: return
        synchronized(member.syncLock) {
            member.adapters.forEach { it.retire() }
            member.adapters = emptyList()
        }
    }

    /**
     * `Root.dispose()`: retire every adapter and take it off its leaf's ring
     * — by identity, never a whole-set replace: the leaf's ring is another
     * root's the moment its membership here is released — and refuse every
     * later join. Each leaf keeps its own middleware.
     */
    fun close() {
        val all =
            synchronized(ringLock) {
                closed = true
                installed = emptyList()
                members.values.toList().also { members.clear() }
            }
        for (member in all) {
            synchronized(member.syncLock) {
                for (adapter in member.adapters) {
                    adapter.retire()
                    member.store.removeOuterMiddlewareUnchecked(adapter)
                }
                member.adapters = emptyList()
            }
        }
    }

    /**
     * Make [member]'s ring match [installed]: an adapter whose middleware is
     * still installed is kept (an observation it started keeps its mark), a
     * new one is made, a surplus one retired. A member whose store is
     * disposed (its ring refuses) is dropped — self-healing when the detach
     * has not reached the ring yet.
     */
    private fun sync(member: Member) {
        synchronized(member.syncLock) {
            // A member dropped meanwhile — its leaf detached, or the ring
            // closed — is left alone: its store's ring is no longer this
            // ring's to write (another root may hold it by now).
            val desired =
                synchronized(ringLock) {
                    if (members[member.leaf] !== member) return
                    installed
                }
            val current = member.adapters
            val next =
                desired.map { mw ->
                    current.firstOrNull { it.middleware === mw } ?: TreeMiddlewareAdapter(member.leaf, mw)
                }
            val installedOk = runCatching { member.store.setOuterMiddlewareUnchecked(next) }.isSuccess
            if (installedOk) {
                current.filter { adapter -> next.none { it === adapter } }.forEach { it.retire() }
                member.adapters = next
            } else {
                current.forEach { it.retire() }
                member.adapters = emptyList()
                synchronized(ringLock) { if (members[member.leaf] === member) members.remove(member.leaf) }
            }
        }
    }

    /**
     * Refuse a change inside a frame, or an action or hook on any member:
     * chains are snapshotted per transaction. A suspending body resumes on
     * any thread, so no member can tell "inside" it from "beside" it — a
     * holder parked on another coroutine, whose chain was snapshotted at
     * its start, is no reason to refuse — but the settle scope its entry
     * carries across dispatch can: an open one while a member has a
     * suspending owner is that owner's body (or its commit, or its
     * recompute), never a bystander.
     */
    private fun guard(attempt: String) {
        check(FrameMarkers.current() == null) {
            "Cannot $attempt on root '${root.name}' from inside an atomic(...) frame: install or remove it from outside"
        }
        val current = synchronized(ringLock) { members.values.toList() }
        val busy =
            current.firstOrNull { member ->
                member.store.internalOwnsActiveTransaction() || member.store.appliedTransactionNestedHere() != null
            }
        check(busy == null) {
            "Cannot $attempt on root '${root.name}' from inside a transaction of its leaf '${busy?.leaf?.name}' " +
                "(an action body, a middleware hook, an observer): install or remove it from outside"
        }
        val inEntry = SettleScopes.current() != null
        val held = if (inEntry) current.firstOrNull { it.store.suspendingOwner != null } else null
        check(held == null) {
            "Cannot $attempt on root '${root.name}' from inside a suspendAction or suspendAtomic body holding its " +
                "leaf '${held?.leaf?.name}': install or remove it from outside"
        }
    }
}
