package com.vynatix.holdfast

/**
 * Cross-cutting interceptor that wraps every transaction on a [Store]. Subclass and
 * override the hooks of interest:
 *  - [onTransactionStarted] runs before the action body. Throwing here aborts the
 *    transaction.
 *  - [onTransactionCompleted] runs after the body returns successfully but BEFORE
 *    the commit applies pending writes. Throwing here triggers rollback. This is
 *    where post-body validation lives — pending writes are visible to the owner
 *    thread via read-your-own-writes.
 *  - [onTransactionError] runs when the body throws. Receives the exception. Cannot
 *    swallow the error — re-throwing is automatic.
 *
 * Multiple middlewares form a chain. The LAST-registered middleware is outermost:
 * its `started` fires first, its `completed`/`error` fires last. Earlier-registered
 * middlewares are inner. Same ordering on both [Store.action] and
 * `:holdfast-coroutines.suspendAction`. The shared [MiddlewareContext.metadata] map
 * carries per-transaction values across the same middleware's hooks (e.g. a start
 * time stashed in `started`, read in `completed`).
 */
open class Middleware<T : Store<T>> {
    /**
     * Mutable context passed to each hook. The [metadata] map is per-transaction —
     * a fresh empty map is created for each invocation of [invoke].
     */
    data class MiddlewareContext<T : Store<T>>(
        val store: T,
        val transaction: Transaction,
        val metadata: MutableMap<String, Any> = mutableMapOf(),
    )

    private fun execute(context: MiddlewareContext<T>, next: () -> Unit) {
        try {
            onTransactionStarted(context)
            next()
            onTransactionCompleted(context)
        } catch (e: Throwable) {
            onTransactionError(context, e)
            throw e
        }
    }

    operator fun invoke(store: T, next: () -> Unit) {
        val context = MiddlewareContext(
            store = store,
            transaction = store.activeTransaction
                ?: throw TransactionException("No active transaction for middleware to wrap"),
        )
        execute(context, next)
    }

    protected open fun onTransactionStarted(context: MiddlewareContext<T>) {}
    protected open fun onTransactionCompleted(context: MiddlewareContext<T>) {}
    protected open fun onTransactionError(context: MiddlewareContext<T>, error: Throwable) {
    }

    /**
     * Internal hook for `:holdfast-coroutines.suspendAction`. Invokes the
     * [onTransactionStarted] hook directly so the suspending chain runner can
     * compose hooks in concentric-ring order with per-hook `runCatching`
     * isolation. Not part of the stable public API; sync `action` continues
     * to use [invoke].
     */
    @StoreInternalApi
    fun invokeOnTransactionStarted(context: MiddlewareContext<T>) = onTransactionStarted(context)

    /**
     * Internal hook for `:holdfast-coroutines.suspendAction`. Invokes the
     * [onTransactionCompleted] hook directly. See [invokeOnTransactionStarted].
     */
    @StoreInternalApi
    fun invokeOnTransactionCompleted(context: MiddlewareContext<T>) = onTransactionCompleted(context)

    /**
     * Internal hook for `:holdfast-coroutines.suspendAction`. Invokes the
     * [onTransactionError] hook directly. See [invokeOnTransactionStarted].
     */
    @StoreInternalApi
    fun invokeOnTransactionError(context: MiddlewareContext<T>, error: Throwable) = onTransactionError(context, error)
}
