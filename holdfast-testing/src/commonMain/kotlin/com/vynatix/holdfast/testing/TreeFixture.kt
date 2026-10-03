@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.tree.StoreTree

/**
 * Track a whole tree: the store [tree] was obtained from (the receiver) and
 * every live store of its subtree now, and every store that joins that
 * subtree later (a `store { }` child read for the first time, a keyed store
 * created), becomes a tracked [StoreHandle] (with [capture]), and a tree
 * middleware records every member transaction with its node into the
 * returned [TreeHandle]'s timeline. Listing the subtree materializes the
 * declared children not read yet. Idempotent by receiver identity (a second
 * call answers the same handle, ignoring its arguments). At teardown the
 * store recorders come off, then, with [resetAtTeardown], the receiver and
 * its subtree are reset as one frame — THE RECEIVER'S OWN STATES TOO, not
 * only its descendants', and every hydrator in it, the receiver's included,
 * goes back to Detached (`reset()` does that to a hydrated store) — with
 * the tree middleware still installed, so a vetoed reset names its store —
 * and then that middleware comes off; every middleware the test
 * installed stays, and no store is ever disposed. A store still held by
 * un-joined work when teardown runs is re-probed for a bounded time, then
 * skips the reset and fails the test naming the store — pass
 * [resetAtTeardown] `false` for a test that parks work deliberately (see
 * [TreeHandle]).
 *
 * Resolves beside the member `track(store)`: a [StoreTree] is not a store.
 *
 * @throws IllegalStateException if the tree's store is disposed, or from
 *   inside a transaction of a member or an `atomic` frame (the tree
 *   middleware is installed from outside every entry; a refused call leaves
 *   nothing behind).
 */
@ExperimentalStoreApi
fun StoreTestScope.track(
    tree: StoreTree,
    capture: Capture = Capture.All,
    resetAtTeardown: Boolean = true,
): TreeHandle {
    val owner = checkNotNull(tree.node.store) { "track(tree): the tree's store is disposed" }
    return treeFixtures.register(owner) { TreeHandle(tree, owner, capture, this, resetAtTeardown) }
}

/** `track` for a star-projected store (the tree fixture's leaves); the same handle `track(store)` answers. */
internal fun StoreTestScope.trackAny(
    store: Store<*>,
    capture: Capture,
): StoreHandle<*> = trackAnyTyped(store, capture)

private fun <V : Store<V>> StoreTestScope.trackAnyTyped(
    store: Store<*>,
    capture: Capture,
): StoreHandle<V> {
    @Suppress("UNCHECKED_CAST")
    val asSelf = store as V
    return registry.getOrCreate(asSelf, capture)
}

/** The handle of [store], or `null` when it was never tracked in this scope. */
internal fun StoreTestScope.handleFor(store: Store<*>): StoreHandle<*>? = registry.find(store)
