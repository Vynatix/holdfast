@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StoreInternalApi

/**
 * Middleware over a whole tree (`tree.middlewares`): the same three hooks as
 * a store's [Middleware], each with the [StoreNode] of the leaf whose
 * transaction it is — every transaction of every leaf, attached now or
 * later, on the blocking and the suspending paths alike, always outermost
 * of the leaf's own middleware (§17.9). A hook sees the leaf's
 * `MiddlewareContext`: its store, its transaction (a frame's roots share a
 * `frameId`), and the per-transaction `metadata` map. Throwing in
 * `onTransactionStarted` or `onTransactionCompleted` aborts that leaf's
 * transaction as a store middleware's throw does — inside a frame, the whole
 * frame. Inbound bridge writes, a keyed store's transactions before its
 * factory returned, and a tree value's own settles are never seen.
 *
 * The `@StoreInternalApi` `invokeOn*` members are how the tree's adapters
 * (and `:holdfast-testing`'s recorder) reach the protected hooks.
 */
@ExperimentalStoreApi
abstract class TreeMiddleware {
    /** [node]'s store began a transaction; runs before its body. */
    protected open fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
    }

    /** [node]'s transaction body returned; runs before its commit. */
    protected open fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
    }

    /** [node]'s transaction failed: its body, a middleware or (in a frame) another participant threw. */
    protected open fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
    }

    @StoreInternalApi
    fun invokeOnTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) = onTransactionStarted(node, context)

    @StoreInternalApi
    fun invokeOnTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) = onTransactionCompleted(node, context)

    @StoreInternalApi
    fun invokeOnTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) = onTransactionError(node, context, error)
}
