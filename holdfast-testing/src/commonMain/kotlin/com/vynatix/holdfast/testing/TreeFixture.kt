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
 * the fixture's middleware and recorders come off — every middleware the
 * test installed stays — and, with [resetAtTeardown], the tree is reset as
 * one frame so the next test finds the initial values; the root is never
 * disposed.
 *
 * @throws IllegalStateException if [root] is disposed.
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
