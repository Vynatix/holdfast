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
 * its last count.
 */
@StoreInternalApi
val StoreTree.internalSettleCount: Long get() = impl.treeValue.counters.settles.value

/** How many store captures those recomputes read — a store whose cut stamp had not moved is reused, not read. */
@StoreInternalApi
val StoreTree.internalCaptureCount: Long get() = impl.treeValue.counters.captures.value

/** How many listings and cuts those recomputes had to run again because a write overlapped or an entry came to life. */
@StoreInternalApi
val StoreTree.internalCutRetryCount: Long get() = impl.treeValue.counters.cutRetries.value

/**
 * The store hosting this handle's value: never a member of any tree; its
 * transaction lock is the host lock. Disposed once the handle's store
 * disposed (when that dispose's entry settled).
 */
@StoreInternalApi
fun StoreTree.internalHost(): Store<*> = impl.host

/**
 * Run a queued recompute of the value now, on this thread — as a settle
 * would, committing on the host or handing off to its holder, never
 * waiting — and answer the tree. Call it outside every entry. After the
 * store disposed, a value read before answers its last tree.
 *
 * @throws IllegalStateException if the store is disposed and the value was never read.
 */
@StoreInternalApi
fun StoreTree.internalSettleNow(): TreeSnapshot = impl.treeValue.settleNow()
