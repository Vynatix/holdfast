@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.tree.Root

/**
 * Track a whole tree: every leaf of [root] now, and every keyed store
 * created under it later, becomes a tracked [StoreHandle] (with [capture]),
 * and a tree middleware records every leaf transaction with its node into
 * the returned [TreeHandle]'s timeline. Idempotent by root identity (a
 * second call answers the same handle, ignoring its arguments). At teardown
 * the leaf recorders come off, then, with [resetAtTeardown], the tree is
 * reset as one frame — with the tree middleware still installed, so a
 * vetoed reset names its leaf — and then that middleware comes off; every
 * middleware the test installed stays, and the root is never disposed. A
 * leaf still held by un-joined work when teardown runs is re-probed for a
 * bounded time, then skips the reset and fails the test naming the leaf —
 * pass [resetAtTeardown] `false` for a test that parks work deliberately
 * (see [TreeHandle]).
 *
 * @throws IllegalStateException if [root] is disposed, or from inside a
 *   transaction of a leaf or an `atomic` frame (the tree middleware is
 *   installed from outside every entry; a refused call leaves nothing
 *   behind).
 */
@ExperimentalStoreApi
fun StoreTestScope.trackTree(
    root: Root,
    capture: Capture = Capture.All,
    resetAtTeardown: Boolean = true,
): TreeHandle {
    check(!root.isDisposed) { "trackTree: root '${root.name}' is disposed" }
    return treeFixtures.register(root) { TreeHandle(root, capture, this, resetAtTeardown) }
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
