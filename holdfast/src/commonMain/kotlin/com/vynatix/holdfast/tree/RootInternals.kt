@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi

// `@StoreInternalApi` views of a root's value machinery, for companion
// modules' tests and the `:holdfast-testing` harness (issue #21 PR 21-5).

/**
 * How many times this root's `value` has been recomputed: each a settle,
 * one consistent capture committed on the host.
 */
@StoreInternalApi
val Root.internalSettleCount: Long get() = rootValue.counters.settles.value

/** How many leaf captures those recomputes read — a leaf whose cut stamp had not moved is reused, not read. */
@StoreInternalApi
val Root.internalCaptureCount: Long get() = rootValue.counters.captures.value

/** How many listings and cuts those recomputes had to run again because a write overlapped or an entry came to life. */
@StoreInternalApi
val Root.internalCutRetryCount: Long get() = rootValue.counters.cutRetries.value

/** The store hosting this root's `value`: never a member of the tree; its transaction lock is the host lock. */
@StoreInternalApi
fun Root.internalHost(): Store<*> = rootValue.host

/**
 * Run a queued recompute of `value` now, on this thread — as a settle would,
 * committing on the host or handing off to its holder, never waiting — and
 * answer the tree. Call it outside every entry.
 *
 * @throws IllegalStateException if the root is disposed and its value was never read.
 */
@StoreInternalApi
fun Root.internalSettleNow(): TreeSnapshot = rootValue.settleNow()
