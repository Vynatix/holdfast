@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.middleware

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionStatus
import kotlin.time.Clock

/**
 * Drop-in middleware that records the wall-clock duration of each transaction
 * (in epoch milliseconds, computed as `endMs - startMs`) and reports it via
 * [onResult]. Uses [MiddlewareContext.metadata] to stash the start timestamp
 * so concurrent transactions don't share state.
 *
 * Timing and status are reported truthfully: `onTransactionCompleted` fires
 * BEFORE the commit applies (that is the middleware contract — a throw there
 * still rolls back), so the success report is deferred via `Store.postCommit`
 * until AFTER the commit's observer/bridge/event fanout. This means:
 *  - The reported [TransactionStatus] is the transaction's real terminal state:
 *    [TransactionStatus.Committed] on success, or [TransactionStatus.Failed] if
 *    the commit itself threw (e.g. a `transformer.get` failure during apply).
 *  - The elapsed time INCLUDES commit fanout (observers, bridge publishes,
 *    event drain), not just the body.
 *
 * The error path reports immediately: a body/chain throw fires
 * `onTransactionError`, reported as [TransactionStatus.RolledBack].
 *
 * Status passed to [onResult] is therefore one of:
 *  - [TransactionStatus.Committed] — commit applied successfully
 *  - [TransactionStatus.Failed] — the commit itself threw
 *  - [TransactionStatus.RolledBack] — the body or middleware chain threw
 *
 * Example:
 * ```
 * store.middlewares(TimingMiddleware { id, status, ms ->
 *     metrics.histogram("store.txn.${status.name.lowercase()}").record(ms.toDouble())
 *     if (ms > 100) log.warn("slow transaction $id: ${ms}ms")
 * })
 * ```
 */
class TimingMiddleware<V : Store<V>>(
    private val onResult: (id: String, status: TransactionStatus, elapsedMs: Long) -> Unit,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        context.metadata[KEY_START_MS] = Clock.System.now().toEpochMilliseconds()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        // onTransactionCompleted fires BEFORE the commit. Defer the report past
        // commit fanout so the reported status is the transaction's real terminal
        // state and the elapsed time includes fanout (F31).
        context.store.postCommit {
            report(context, context.transaction.status)
        }
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        report(context, TransactionStatus.RolledBack)
    }

    private fun report(
        context: MiddlewareContext<V>,
        status: TransactionStatus,
    ) {
        val start = context.metadata[KEY_START_MS] as? Long ?: return
        val elapsed = Clock.System.now().toEpochMilliseconds() - start
        onResult(context.transaction.id, status, elapsed)
    }

    private companion object {
        private const val KEY_START_MS = "TimingMiddleware.startMs"
    }
}
