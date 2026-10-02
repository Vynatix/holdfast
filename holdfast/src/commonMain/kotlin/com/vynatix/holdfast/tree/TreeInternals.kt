@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

// `@StoreInternalApi` views of a `tree` handle's value machinery, for
// companion modules' tests and the `:holdfast-testing` harness. All of them
// are on the handle, so they keep working after its store disposed (the
// store's attachment slot is closed by then).

private val StoreTree.impl: StoreTreeImpl get() = this as StoreTreeImpl

/**
 * How many times this handle's value has been recomputed: each a settle,
 * one consistent capture committed on the host. After the store disposed,
 * its last count; `0` while nothing has read or observed the value.
 */
@StoreInternalApi
val StoreTree.internalSettleCount: Long get() =
    impl.treeValueOrNull
        ?.counters
        ?.settles
        ?.value ?: 0L

/** How many store captures those recomputes read — a store whose cut stamp had not moved is reused, not read. */
@StoreInternalApi
val StoreTree.internalCaptureCount: Long get() =
    impl.treeValueOrNull
        ?.counters
        ?.captures
        ?.value ?: 0L

/** How many listings and cuts those recomputes had to run again because a write overlapped or an entry came to life. */
@StoreInternalApi
val StoreTree.internalCutRetryCount: Long get() =
    impl.treeValueOrNull
        ?.counters
        ?.cutRetries
        ?.value ?: 0L

/**
 * The store hosting this handle's value: never a member of any tree; its
 * transaction lock is the host lock. Created lazily with the value machinery
 * — by the first value read or observation, or by this call (so a test can
 * install middleware on the host before the first read). Disposed once the
 * handle's store disposed (when that dispose's entry settled).
 *
 * @throws IllegalStateException if the store is disposed and the host was never created.
 */
@StoreInternalApi
fun StoreTree.internalHost(): Store<*> = impl.treeValue().host

/**
 * Run a queued recompute of the value now, on this thread — as a settle
 * would, committing on the host or handing off to its holder, never
 * waiting — and answer the tree. Call it outside every entry. After the
 * store disposed, a value read before answers its last tree.
 *
 * @throws IllegalStateException if the store is disposed and the value was never read.
 */
@StoreInternalApi
fun StoreTree.internalSettleNow(): TreeSnapshot = impl.treeValue().settleNow()

/**
 * [StoreTree.stores] of [node], each store paired with the node it sits at
 * — from ONE materialization of the subtree and ONE listing, so a caller
 * that needs both never asks [StoreTree.nodeOf] per store (each of which
 * materializes and walks again).
 *
 * @throws IllegalStateException if the store is disposed.
 * @throws IllegalArgumentException if [node] is not in the receiver's subtree.
 */
@StoreInternalApi
fun StoreTree.internalLeaves(node: StoreNode = this.node): List<Pair<LeafNode, Store<*>>> = impl.leaves(node)
