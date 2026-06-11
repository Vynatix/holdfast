package com.vynatix.holdfast.middleware

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store

/**
 * Drop-in middleware that logs every transaction's lifecycle to a sink. Default
 * sink is stdout via `println`; pass a custom `log` for routing into a real
 * logging framework or a test fixture's recording list.
 *
 * Each transaction emits exactly two lines (started + completed) on success, or
 * two (started + errored) on failure. The transaction's id is included so
 * concurrent traces can be untangled.
 *
 * Example:
 * ```
 * store.middlewares(LoggingMiddleware("CounterVault"))
 * ```
 */
class LoggingMiddleware<V : Store<V>>(
    private val tag: String,
    private val log: (String) -> Unit = ::println,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        val parent = context.transaction.parent?.id
        val parentSuffix = if (parent != null) " (savepoint of $parent)" else ""
        log("$tag → ${context.transaction.id}$parentSuffix")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        log("$tag ✓ ${context.transaction.id}")
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        log("$tag ✗ ${context.transaction.id} → ${error::class.simpleName}: ${error.message}")
    }
}
