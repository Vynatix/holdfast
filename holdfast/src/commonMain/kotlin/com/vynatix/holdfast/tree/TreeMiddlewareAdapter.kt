@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.atomicfu.atomic

/**
 * One [TreeMiddleware] installed on one leaf: a store [Middleware] in the
 * leaf's outer ring that forwards each hook with the leaf's node. Written
 * against no store type (`Middleware<Nothing>`), so one class serves every
 * leaf. Retired by `Root.removeMiddleware`, a leaf's departure or the
 * root's dispose: a retired adapter starts no new observation, while one it
 * started still gets its terminal hook — the mark it stamped in the
 * transaction's `metadata` at `started` (the `ProfilingMiddleware` idiom)
 * says so, and keeps a transaction to one `completed` and one `error` per
 * adapter, whatever re-fires a frame's unwind does.
 */
internal class TreeMiddlewareAdapter(
    val leaf: LeafNode,
    val middleware: TreeMiddleware,
) : Middleware<Nothing>() {
    private val retired = atomic(false)

    /** This adapter's key in a transaction's metadata: per instance, so two adapters on one leaf never share a mark. */
    private val markKey = "holdfast.tree.middleware#${adapterCounter.incrementAndGet()}"

    val isRetired: Boolean get() = retired.value

    fun retire() {
        retired.value = true
    }

    override fun onTransactionStarted(context: MiddlewareContext<Nothing>) {
        if (retired.value) return
        context.metadata[markKey] = Phase.STARTED
        middleware.invokeOnTransactionStarted(leaf, context)
    }

    override fun onTransactionCompleted(context: MiddlewareContext<Nothing>) {
        if (context.metadata[markKey] != Phase.STARTED) return
        context.metadata[markKey] = Phase.COMPLETED
        middleware.invokeOnTransactionCompleted(leaf, context)
    }

    override fun onTransactionError(
        context: MiddlewareContext<Nothing>,
        error: Throwable,
    ) {
        val phase = context.metadata[markKey]
        if (phase == null || phase == Phase.ERRORED) return
        context.metadata[markKey] = Phase.ERRORED
        middleware.invokeOnTransactionError(leaf, context, error)
    }

    private enum class Phase { STARTED, COMPLETED, ERRORED }

    private companion object {
        val adapterCounter = atomic(0L)
    }
}
