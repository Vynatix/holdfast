package com.vynatix.holdfast.middleware

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store

/**
 * Drop-in middleware for invariant checks that should run AFTER the action body
 * but BEFORE commit. Throwing from [check] propagates up the middleware chain
 * and triggers rollback — so a violated invariant cleanly discards the
 * transaction's pending writes.
 *
 * Inside [check] you can read state via the store's normal API; the read sees
 * pending writes (the action body's mutations are staged but not yet committed).
 * Use ordinary `require`/`check` to assert, or build a custom error.
 *
 * Example: enforcing a non-negative balance.
 * ```
 * store.middlewares(ValidationMiddleware<AccountStore> {
 *     require(balanceCents.value >= 0) { "balance cannot go negative" }
 * })
 * ```
 */
class ValidationMiddleware<V : Store<V>>(
    private val check: V.() -> Unit,
) : Middleware<V>() {
    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        context.store.check()
    }
}
