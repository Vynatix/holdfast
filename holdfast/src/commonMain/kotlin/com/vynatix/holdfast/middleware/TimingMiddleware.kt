package com.vynatix.holdfast.middleware

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.Store
import kotlin.time.Clock

/**
 * Drop-in middleware that records the wall-clock duration of each transaction
 * (in epoch milliseconds, computed as `endMs - startMs`) and reports it via
 * [onResult]. Uses [MiddlewareContext.metadata] to stash the start timestamp
 * so concurrent transactions don't share state.
 *
 * Status passed to [onResult] is one of:
 *  - [TransactionStatus.Committed] — `onTransactionCompleted` fired (will commit shortly)
 *  - [TransactionStatus.RolledBack] — `onTransactionError` fired (body or chain threw)
 *
 * Example:
 * ```
 * vault.middlewares(TimingMiddleware { id, status, ms ->
 *     metrics.histogram("vault.txn.${status.name.lowercase()}").record(ms.toDouble())
 *     if (ms > 100) log.warn("slow transaction $id: ${ms}ms")
 * })
 * ```
 */
class TimingMiddleware<V : Store<V>>(private val onResult: (id: String, status: TransactionStatus, elapsedMs: Long) -> Unit) :
    Middleware<V>() {

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        context.metadata[KEY_START_MS] = Clock.System.now().toEpochMilliseconds()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        report(context, TransactionStatus.Committed)
    }

    override fun onTransactionError(context: MiddlewareContext<V>, error: Throwable) {
        report(context, TransactionStatus.RolledBack)
    }

    private fun report(context: MiddlewareContext<V>, status: TransactionStatus) {
        val start = context.metadata[KEY_START_MS] as? Long ?: return
        val elapsed = Clock.System.now().toEpochMilliseconds() - start
        onResult(context.transaction.id, status, elapsed)
    }

    private companion object {
        const val KEY_START_MS = "TimingMiddleware.startMs"
    }
}
