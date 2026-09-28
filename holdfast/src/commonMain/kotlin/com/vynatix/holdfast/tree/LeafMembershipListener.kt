@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

/**
 * The one attach/detach seam of a tree (issue #21 decision U5), for library
 * machinery: `Root.value`'s derivation edges and `:holdfast-testing`'s
 * `trackTree`. Distinct from the keyed state families'
 * `KeyedMembershipListener`.
 *
 * [onAttached] runs once per store that joins the tree: for a branch store
 * after its declaration registered, for a keyed store after its factory
 * returned and its middleware ring synced, strictly before the registry
 * promotes it (so `Root.get`/`entries` answer it only afterwards; [LeafNode.store]
 * already does). [onDetached] runs once per store that leaves — its
 * `dispose()`, after the tree dropped it — strictly after its [onAttached],
 * and never for a store whose construction failed. Neither runs for a root's
 * own `dispose()`, which drops the listeners instead.
 *
 * Both run outside the registry lock but under whatever locks the attaching
 * or disposing caller holds (a keyed store disposed from inside its own
 * action holds its `transactionLock`). A callback may only mark and
 * schedule — through the settle scope, `Root.value`'s route — never open an
 * action, frame, `reset` or `restore` on a store, and never `postCommit`.
 */
@StoreInternalApi
abstract class LeafMembershipListener {
    /** [leaf]'s store joined [leaf]'s root. */
    open fun onAttached(leaf: LeafNode) {}

    /** [leaf]'s store left [leaf]'s root; [LeafNode.store] is `null` by now. */
    open fun onDetached(leaf: LeafNode) {}
}

/**
 * Hear of every store that joins or leaves this root from now on (no
 * replay of the current members). The returned handle stops it; a root's
 * `dispose()` drops every listener too.
 *
 * @throws IllegalStateException if the root is disposed.
 */
@StoreInternalApi
@OptIn(ExperimentalStoreApi::class)
fun Root.internalAddMembershipListener(listener: LeafMembershipListener): Disposable {
    checkNotDisposed()
    registry.addListener(listener)
    return Disposable { registry.removeListener(listener) }
}
