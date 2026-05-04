@file:OptIn(com.vynatix.holdfast.HoldfastInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Run [body] inside a transaction, allowing the body to suspend.
 *
 * Mutations stage in the transaction's pending writes; on success they apply
 * via [com.vynatix.holdfast.MutableState.applyCommitted] and observers/bridges
 * fire then. On throw, pending writes are dropped — same semantics as the
 * blocking [Holdfast.action].
 *
 * Concurrency contract for 1.1:
 *  - **Mutually exclusive with blocking [Holdfast.action]** on the same vault: a
 *    blocking `action` will block until the in-flight `suspendAction` completes,
 *    and vice versa. Coordination is via an internal coroutine [Mutex] installed
 *    lazily on first use.
 *  - **Cancellation**: a `CancellationException` thrown from the body rolls back
 *    the transaction. Cancellation BETWEEN body return and commit is suppressed
 *    via [NonCancellable] on the commit phase — once the body completes, the
 *    commit's observer/bridge fanout runs to completion to avoid mid-fanout desync.
 *  - **Middleware**: `Middleware<V>` sync hooks fire on the suspending path
 *    in concentric-ring order — last-registered middleware is outermost (its
 *    `onTransactionStarted` fires first), matching `Holdfast.middlewares`'s
 *    contract and the sync `action` path. `onTransactionCompleted` (success)
 *    or `onTransactionError` (throw / cancellation) unwind in chain order
 *    (innermost-first), so the outermost middleware sees `completed`/`error`
 *    last. Each hook invocation is wrapped in `runCatching`: one middleware's
 *    failure does not abort other middlewares' hooks.
 *  - **Concurrent threads inside the body**: undefined. Stick to single-flight
 *    bodies.
 *
 * Example:
 * ```
 * val r = vault.suspendAction {
 *     val data = api.fetch()              // suspending I/O
 *     items mutate (items.value + data)   // staged into the transaction
 *     val saved = persistence.save()      // suspending I/O
 *     saved
 * }
 * ```
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun <V : Holdfast<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R> {
    val serializer = ensureSerializer(this)
    val owner: Any = coroutineContext[Job] ?: SuspendActionFallbackOwner

    return serializer.mutex.withLock(owner) {
        val txn = Transaction.createForExternal(
            id = body::class.simpleName ?: Uuid.random().toString(),
            ownerThreadId = currentThreadId(),
        )
        // We own the serializer; no other action can run on this vault until we
        // release. Set the active transaction and the suspending-owner key so
        // mutate() inside the body recognizes us as the owner.
        internalSetActiveTransaction(txn)
        suspendingOwner = owner

        // Snapshot the middleware chain once at the start of the action — same
        // semantics as the blocking path. Concurrent middleware registration
        // during this action does not retroactively apply.
        val middlewareChain: List<Middleware<V>> = snapshotMiddleware()
        // One MiddlewareContext per middleware, reused across that middleware's
        // started/completed/error hooks so metadata stashed in one hook is
        // visible to the next, matching the sync `Middleware.invoke` contract.
        val contexts: List<Middleware.MiddlewareContext<V>> = middlewareChain.map { _ ->
            Middleware.MiddlewareContext(
                vault = selfForExternal,
                transaction = txn,
            )
        }

        // Concentric outermost-first: iterate REVERSED so the LAST-registered
        // middleware fires `started` first — matching `Holdfast.middlewares`'s contract
        // and the sync `action` path's fold-derived nesting (issue 31). Each
        // invocation is run-caught so one middleware's failure does not abort others.
        // For middlewares that ALSO implement `SuspendingMiddlewareHooks<V>` (issue
        // 09), the async `onTransactionStartedAsync` fires IMMEDIATELY AFTER its
        // sync sibling — interleaved per-middleware so the master ordering is
        //   B.sync.started, B.async.startedAsync, A.sync.started, A.async.startedAsync, body, ...
        for (i in middlewareChain.indices.reversed()) {
            runCatching { middlewareChain[i].invokeOnTransactionStarted(contexts[i]) }
            val mw = middlewareChain[i]
            if (mw is SuspendingMiddlewareHooks<*>) {
                @Suppress("UNCHECKED_CAST")
                val async = mw as SuspendingMiddlewareHooks<V>
                runCatching { async.onTransactionStartedAsync(contexts[i]) }
            }
        }

        val outcome: TransactionResult<R> = try {
            val value: R = try {
                selfForExternal.body()
            } catch (ce: CancellationException) {
                // Concentric forward (innermost-first) on the error path so the
                // outermost middleware that ran `started` first sees `error` last —
                // mirrors the sync `Middleware.invoke` unwind order. Cancellation is
                // propagated after rollback; error hooks run for symmetry with the
                // throwing path so middleware sees one consistent "errored
                // transaction" view. Per-middleware async-then-sync interleave:
                //   A.async.errorAsync, A.sync.error, B.async.errorAsync, B.sync.error
                for (i in middlewareChain.indices) {
                    val mw = middlewareChain[i]
                    if (mw is SuspendingMiddlewareHooks<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val async = mw as SuspendingMiddlewareHooks<V>
                        runCatching { async.onTransactionErrorAsync(contexts[i], ce) }
                    }
                    runCatching { middlewareChain[i].invokeOnTransactionError(contexts[i], ce) }
                }
                runCatching { txn.rollback() }
                throw ce
            } catch (e: Throwable) {
                for (i in middlewareChain.indices) {
                    val mw = middlewareChain[i]
                    if (mw is SuspendingMiddlewareHooks<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val async = mw as SuspendingMiddlewareHooks<V>
                        runCatching { async.onTransactionErrorAsync(contexts[i], e) }
                    }
                    runCatching { middlewareChain[i].invokeOnTransactionError(contexts[i], e) }
                }
                runCatching { txn.rollback() }
                return@withLock TransactionResult.Error(e, txn)
            }
            // Body returned. Commit under NonCancellable so observer/bridge
            // fanout completes even if the surrounding scope cancels here.
            // Sync `Middleware.invoke` runs `onTransactionCompleted` BEFORE
            // commit; we preserve that ordering here, with innermost-first
            // unwind so the outermost middleware sees `completed` last.
            // Per-middleware async-then-sync interleave:
            //   A.async.completedAsync, A.sync.completed, B.async.completedAsync, B.sync.completed
            withContext(NonCancellable) {
                for (i in middlewareChain.indices) {
                    val mw = middlewareChain[i]
                    if (mw is SuspendingMiddlewareHooks<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val async = mw as SuspendingMiddlewareHooks<V>
                        runCatching { async.onTransactionCompletedAsync(contexts[i]) }
                    }
                    runCatching { middlewareChain[i].invokeOnTransactionCompleted(contexts[i]) }
                }
                try {
                    suspendingCommit(txn)
                    TransactionResult.Success(txn, value)
                } catch (e: Throwable) {
                    TransactionResult.Error(e, txn)
                }
            }
        } finally {
            internalSetActiveTransaction(null)
            suspendingOwner = null
            internalDrainPostCommitTasks()
        }
        outcome
    }
}

/** Owner sentinel for suspendAction calls that have no enclosing Job. */
private object SuspendActionFallbackOwner
