@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.tree.StoreNode

/**
 * One hook of a tree's middleware as the tree fixture recorded it
 * ([TreeHandle.timeline]): which leaf ([node], its [store]), which
 * [transaction] (a frame's roots share its `frameId`), at which [phase] —
 * [Phase.Completed] is pushed before the commit applies, [Phase.Errored]
 * carries the [cause]. Never a state value: a `Secret` never reaches a tree
 * event, and a failure message the library writes never quotes a value.
 */
@ExperimentalStoreApi
data class TreeEvent(
    val node: StoreNode,
    val store: Store<*>,
    val phase: Phase,
    val transaction: Transaction,
    val cause: Throwable?,
    /** Epoch milliseconds at which the event was recorded. */
    val timestamp: Long,
) {
    @ExperimentalStoreApi
    enum class Phase { Started, Completed, Errored }
}
