@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.settling
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject

// A store's tree state (the dynamic store tree, issue #21): ONE attachment
// per store, created on demand from exactly these sites — a child
// declaration's `provideDelegate` (on the declaring store), `store.tree`, a
// store's adoption as a child (on the child, before anything else of the
// attach), a keyed creation (on the produced store) and
// `internalAddMembershipListener`. Never from a walk, a listing, a decode or
// a restore: those reach a store's tree state through `LeafNode.attachment`
// only, because `Store.dispose()` closes the attachment slot FIRST and tells
// the attachment LAST. Its presence says nothing about membership; the
// parent edge does.

/** The one attachment key a store's tree state lives under. */
internal val treeMembershipKey = StoreAttachmentKey<TreeLeafAttachment>("tree membership")

/**
 * This store's tree state, created if absent. The slot's `create` only
 * builds objects (a registry, a node) and takes no store lock, so it works
 * from a base class's `init` too.
 *
 * @throws IllegalStateException if the store is disposed.
 */
internal fun Store<*>.treeAttachment(): TreeLeafAttachment {
    val store = this
    return internalAttachIfAbsent(treeMembershipKey) { TreeLeafAttachment(store) }
}

/**
 * Where a store hangs: under [parentNode] — the owner's [LeafNode] for a
 * `store { }` child, the group's [Branch], or the [KeyedBranch] (with its
 * keyed [entry]) — declared by [ownerStore], whose registry is captured at
 * attach so a detach works whatever state the owner's attachment slot is in.
 */
internal class ParentEdge(
    val parentNode: StoreNode,
    val ownerStore: Store<*>,
    val ownerRegistry: ChildRegistry,
    val entry: LeafEntry?,
    /** `Owner/property` — the declaration the store hangs under — for the double-parent refusal. */
    val place: String,
)

/**
 * One store's tree state: its [node] (for the store's whole life), its
 * [parentEdge] (the one-parent rule: set by compare-and-set under
 * `treeStructureLock`), its children ([registry]), the seqlock pair walks
 * validate against, its tree middleware, and its `tree` handle with the
 * handle's value machinery. Its [onStoreDisposed] detaches the store from
 * its parent and releases its children as subtree roots (`StoreDetach.kt`).
 */
internal class TreeLeafAttachment(
    store: Store<*>,
) : StoreAttachment {
    /** The store; dropped by its dispose, before any detach is announced. */
    @kotlin.concurrent.Volatile
    var storeRef: Store<*>? = store

    val node: LeafNode = LeafNode(this, parent = null, defaultNodeName(store), NameOrigin.ClassName, key = null)

    val parentEdge = atomic<ParentEdge?>(null)

    val registry = ChildRegistry(store)

    /** Open structural writes on this store's subtree (see `TreeWalk.kt`). */
    val structuralWriters = atomic(0)

    /** Completed structural writes on this store's subtree (see `TreeWalk.kt`). */
    val structuralGeneration = atomic(0L)

    /** The tree middleware this store installed: copy-on-write, written under [installLock], read lock-free. */
    @kotlin.concurrent.Volatile
    var installed: List<TreeMiddleware> = emptyList()

    val installLock = SynchronizedObject()

    /** Serializes ring syncs of this store (`TreeMiddlewareRing.kt`). */
    val syncLock = SynchronizedObject()

    /** The adapters this store's outer ring holds; written under [syncLock]. */
    @kotlin.concurrent.Volatile
    var adapters: List<TreeMiddlewareAdapter> = emptyList()

    /** Guards the creation of [handleRef]'s handle. */
    val handleLock = SynchronizedObject()

    /** This store's `tree` handle with its value and value host, once created. */
    val handleRef = atomic<StoreTreeImpl?>(null)

    /**
     * The whole detach is ONE entry: it joins the settle scope open on this
     * thread (a dispose inside an action), else opens one that settles once
     * every step has run — so a recompute the detach queues (an ancestor's
     * tree value dropping the store) and the value host's dispose run after
     * the released children are subtree roots with their rings re-synced,
     * never halfway through, wherever `dispose()` was called from. The one
     * exception keeps the host's dispose off this store's lock: a thread
     * that holds this store's transaction lock with no scope open (no entry
     * of the library's does) runs the steps unscoped, and step 6 hands the
     * host's dispose to that holder's post-commit drain.
     */
    override fun onStoreDisposed() {
        val heldUnscoped =
            SettleScopes.current() == null && storeRef?.transactionLock?.isHeldByCurrentThread() == true
        if (heldUnscoped) StoreDetach(this).run() else settling { StoreDetach(this).run() }
    }
}
