package com.vynatix.vault.coroutines

import com.vynatix.vault.Middleware
import com.vynatix.vault.Vault

/**
 * Optional async-hook surface for vault middleware. A class implementing both
 * [Middleware] and [SuspendingMiddlewareHooks] gets BOTH sync and suspending
 * callbacks under [suspendAction]; only the sync callbacks fire under the
 * blocking [Vault.action].
 *
 * ## Concentric-ring ordering
 *
 * For `vault.middlewares(A, B)` where the LAST argument is outermost (per
 * [Vault.middlewares]'s contract), a `suspendAction { body }` produces the
 * full hook trace:
 *
 * ```
 * B.sync.onTransactionStarted
 * B.async.onTransactionStartedAsync
 * A.sync.onTransactionStarted
 * A.async.onTransactionStartedAsync
 *   body
 * A.async.onTransactionCompletedAsync
 * A.sync.onTransactionCompleted
 * B.async.onTransactionCompletedAsync
 * B.sync.onTransactionCompleted
 * ```
 *
 * On the error path, `onTransactionCompletedAsync` / `onTransactionCompleted`
 * are swapped for `onTransactionErrorAsync` / `onTransactionError`. Each
 * middleware's own sync→async pair is interleaved: sync started fires
 * immediately before async startedAsync, async completedAsync fires immediately
 * before sync completed.
 *
 * ## Sync vs suspend asymmetry
 *
 * The blocking [Vault.action] path does NOT invoke async hooks (they would
 * have to block the calling thread, defeating the purpose). A class that
 * implements ONLY [SuspendingMiddlewareHooks] (with the no-op default sync
 * hooks inherited from [Middleware]) WILL BE SILENT under sync `action` —
 * none of its hooks fire. Use sync hooks for cross-cutting concerns that
 * must run on every transaction; use async hooks for I/O-bearing concerns
 * (suspending audit logging, suspending validation calls, etc.) that only
 * make sense on the suspending path.
 *
 * ## Failure isolation
 *
 * Each async hook invocation is wrapped in `runCatching` exactly like the sync
 * hooks. One middleware's `onTransactionStartedAsync` throwing does NOT abort
 * other middlewares' started hooks, the body, or the unwind path. This matches
 * the sync chain's contract.
 *
 * ## Cancellation
 *
 * Async hooks called inside the body window (`onTransactionStartedAsync` and
 * `onTransactionErrorAsync` on the throwing path) run with the calling
 * coroutine's normal cancellation. Async hooks called during the commit window
 * (`onTransactionCompletedAsync` and `onTransactionErrorAsync` on the success
 * path's tail) run inside `withContext(NonCancellable)` along with the rest of
 * the commit, so they complete even if the surrounding scope cancels mid-commit.
 *
 * @see Middleware for the blocking sync hooks.
 */
interface SuspendingMiddlewareHooks<V : Vault<V>> {
    /**
     * Suspending counterpart to [Middleware.onTransactionStarted]. Fires
     * immediately AFTER this middleware's sync `onTransactionStarted` and
     * BEFORE the next inner middleware's started pair. May suspend.
     */
    suspend fun onTransactionStartedAsync(context: Middleware.MiddlewareContext<V>) {}

    /**
     * Suspending counterpart to [Middleware.onTransactionCompleted]. Fires on
     * the success path AFTER the body returns and BEFORE this middleware's
     * sync `onTransactionCompleted`. Runs inside `withContext(NonCancellable)`
     * — it cannot be cancelled mid-commit.
     */
    suspend fun onTransactionCompletedAsync(context: Middleware.MiddlewareContext<V>) {}

    /**
     * Suspending counterpart to [Middleware.onTransactionError]. Fires on the
     * error path AFTER the body throws and BEFORE this middleware's sync
     * `onTransactionError`.
     */
    suspend fun onTransactionErrorAsync(context: Middleware.MiddlewareContext<V>, error: Throwable) {}
}
