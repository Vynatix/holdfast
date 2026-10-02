@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

/**
 * The attach/detach seam of a store's subtree, for library machinery: the
 * store's tree value's derivation edges and `:holdfast-testing`'s
 * `track(tree)`. Distinct from the keyed state families'
 * `KeyedMembershipListener`. Registered on one store, it hears every store
 * that joins or leaves that store's subtree, at any depth — never the store
 * itself.
 *
 * [onAttached] runs once per store that joined the subtree, after it is
 * registered under its parent (so captures and listings can list it): a
 * `store { }`/`stores { }` child when it materializes, a keyed store after
 * its factory returned and before `KeyedBranch.get`/`entries`/`getOrCreate`
 * answer it on any other thread (those park until the announcement ends) —
 * and, when a store that already has children joins,
 * once for each live store under it too. [leaf]'s store is set then.
 *
 * [onDetached] runs when a store left the subtree: its own dispose —
 * [LeafNode.store] is `null` by then — or the dispose of a store between it
 * and this listener's store, which releases it as a subtree root (or under
 * one): [LeafNode.store] is still set then. For a leaf's attach, it comes
 * strictly after [onAttached]. It may be delivered TWICE for a deep
 * descendant — when that descendant disposes while a store between it and
 * this listener's store disposes too, or when an ancestor's dispose races
 * the announcement of a deep attach — so it must be idempotent. Neither
 * runs for this listener's own store's dispose, which drops its listeners
 * instead.
 *
 * Both run outside every registry lock but under whatever locks the
 * attaching or disposing caller holds (a store disposed from inside its own
 * action holds its `transactionLock`). A callback may only mark and
 * schedule — through the settle scope — never open an action, frame,
 * `reset` or `restore`, materialize a child, `postCommit`, or re-adopt the
 * leaf it is told about (re-adopting a leaf from inside its own `onDetached`
 * is unsupported). A throwing callback is reported through the
 * attaching or disposing store's `uncaughtObserverHandler`; the other
 * listeners are still told.
 */
@StoreInternalApi
abstract class LeafMembershipListener {
    /** [leaf]'s store joined the subtree of the store this listener is registered on. */
    open fun onAttached(leaf: LeafNode) {}

    /** [leaf]'s store left the subtree of the store this listener is registered on. */
    open fun onDetached(leaf: LeafNode) {}
}

/**
 * Hear of every store that joins or leaves this store's subtree from now on
 * (no replay of the current members). The returned handle stops it; this
 * store's `dispose()` drops every listener too, without telling them.
 *
 * @throws IllegalStateException if the store is disposed.
 */
@StoreInternalApi
fun Store<*>.internalAddMembershipListener(listener: LeafMembershipListener): Disposable {
    checkNotDisposed()
    val registry = treeAttachment().registry
    registry.addListener(listener)
    return Disposable { registry.removeListener(listener) }
}
