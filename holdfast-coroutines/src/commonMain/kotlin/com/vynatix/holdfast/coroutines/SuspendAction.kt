@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FrameContractException
import com.vynatix.holdfast.FrameInteropException
import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.UnenrolledStoreException
import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Run [body] inside a transaction, allowing the body to suspend.
 *
 * Mutations stage in the transaction's pending writes; on success they apply
 * via [com.vynatix.holdfast.MutableState.applyCommitted] and observers/bridges
 * fire then. On throw, pending writes are dropped — same semantics as the
 * blocking [Store.action].
 *
 * Concurrency contract:
 *  - **Mutually exclusive with blocking [Store.action]** on the same store: a
 *    blocking `action` on ANOTHER thread will block until the in-flight
 *    `suspendAction` completes, and vice versa. Coordination is via an internal
 *    coroutine [Mutex] installed lazily on first use. A blocking `action` (or
 *    blocking `atomic` enrolling this store) called from INSIDE the body throws
 *    [FrameInteropException] immediately — it would self-deadlock on the mutex
 *    this suspendAction already holds.
 *  - **The body runs inside a single-store suspending scope.** A relaxed frame
 *    marker (`AllowUnenrolled + TolerateInnerErrors`, so it never polices
 *    writes to OTHER stores or escalates their errors) travels with the body
 *    across dispatcher hops (JVM/Android: `ThreadContextElement`; iOS/wasmJs:
 *    a delegating interceptor — there a nested `withContext(otherDispatcher)`
 *    section is not covered). It gives the body:
 *     - **nesting**: a nested `suspendAction` on the SAME store joins as a
 *       savepoint (inner commit merges into this transaction; inner rollback
 *       discards only inner writes; one observer fanout, at the outer commit);
 *     - **read-your-own-writes across hops**: `state.value` sees this
 *       transaction's pending writes on whatever thread resumes the body;
 *     - **fail-fast interop**: `suspendAtomic` enrolling THIS store inside the
 *       body throws [FrameInteropException] (hoist the frame: call
 *       `suspendAtomic` first and `suspendAction` inside it). Disjoint-store
 *       `suspendAtomic`/`atomic` calls inside the body remain legal, subject
 *       to the global lock-order rule.
 *  - **Cancellation**: a `CancellationException` thrown from (or observed at
 *    the end of) the body rolls back the transaction. Once the body scope
 *    completes normally, the commit runs under [NonCancellable] so
 *    observer/bridge fanout completes even if the surrounding scope cancels.
 *  - **Middleware**: `Middleware<V>` sync hooks fire on the suspending path
 *    in concentric-ring order — last-registered middleware is outermost (its
 *    `onTransactionStarted` fires first), matching `Store.middlewares`'s
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
 * val r = store.suspendAction {
 *     val data = api.fetch()              // suspending I/O
 *     items mutate (items.value + data)   // staged into the transaction
 *     val saved = persistence.save()      // suspending I/O
 *     saved
 * }
 * ```
 *
 * (Block bodies below, not `= suspendActionImpl(...)`: with no configured
 * max_line_length ktlint would inline the long signature+body onto one line
 * that exceeds detekt's MaxLineLength; the block form keeps both linters happy,
 * hence the `function-expression-body` suppressions.)
 */
@Suppress("ktlint:standard:function-expression-body")
suspend fun <V : Store<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R> {
    return suspendActionImpl(name = null, body = body)
}

/**
 * Named variant of [suspendAction]: [name] becomes the resulting
 * [Transaction.id] verbatim, instead of the lambda-derived
 * `body::class.simpleName`/random-UUID fallback — use it when a suspending
 * transaction needs a stable, human-readable id for middleware logs, the
 * testing-harness timeline, or frame diagnostics. Mirrors the blocking
 * `Store.action(name, body)` overload.
 */
@Suppress("ktlint:standard:function-expression-body")
suspend fun <V : Store<V>, R> V.suspendAction(
    name: String,
    body: suspend V.() -> R,
): TransactionResult<R> {
    return suspendActionImpl(name = name, body = body)
}

@OptIn(ExperimentalUuidApi::class)
// Single-sourced middleware ordering + frame-gate setup — splitting it would scatter the contract.
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
private suspend fun <V : Store<V>, R> V.suspendActionImpl(
    name: String?,
    body: suspend V.() -> R,
): TransactionResult<R> {
    // Disposed-store contract: match blocking `action`'s entry check.
    internalCheckNotDisposed()
    // Frame policing (body-only: the marker travels with the frame's coroutine
    // and is popped before commit fanout). A participant of a suspendAtomic
    // frame runs as a SAVEPOINT of its frame root — no mutex re-acquisition
    // (the frame already holds it; re-locking here would deadlock). An
    // unenrolled store is an escape unless the frame's policy allows it.
    val frame = FrameMarkers.current()
    if (frame != null && frameGateAllowsOnlySavepoint(frame)) {
        return suspendActionInFrame(frame, name, body)
    }
    val serializer = ensureSerializer(this)
    val owner: Any = coroutineContext[Job] ?: SuspendActionFallbackOwner

    return serializer.mutex.withLock(owner) {
        // Re-check after acquiring the mutex: a dispose can land while parked here.
        internalCheckNotDisposed()
        val txn =
            Transaction.createForExternal(
                id = name ?: body::class.simpleName ?: Uuid.random().toString(),
                ownerThreadId = currentThreadId(),
            )
        // We own the serializer; no other action can run on this store until we
        // release. Set the active transaction and the suspending-owner key so
        // mutate() inside the body recognizes us as the owner.
        internalSetActiveTransaction(txn)
        suspendingOwner = owner

        // Single-store suspending scope installed around the BODY only (F4).
        // The relaxed policy (AllowUnenrolled + TolerateInnerErrors) means the
        // marker never polices writes to other stores or escalates their
        // errors — it exists so the existing frame gates recognize this scope:
        // nested suspendAction on this store savepoints instead of
        // re-locking the mutex; blocking action/atomic on this store fails
        // fast instead of self-deadlocking; and the RYOW getter follows the
        // body across dispatcher hops.
        val marker =
            FrameMarker(
                frameId = "$SUSPEND_ACTION_FRAME_ID_PREFIX${Uuid.random()}",
                participants = setOf(this),
                policy = FramePolicy.AllowUnenrolled + FramePolicy.TolerateInnerErrors,
                suspending = true,
                parent = frame,
            )
        // Held-stores element: a nested suspendAtomic inside the body reuses
        // this owner key and sees this store as already held (its own gate
        // rejects enrolling it; adoption is the safe fallback).
        val heldFrame = SuspendAtomicFrame(owner).also { it.heldVaults += this }

        // Snapshot the middleware chain once at the start of the action — same
        // semantics as the blocking path. Concurrent middleware registration
        // during this action does not retroactively apply.
        val middlewareChain: List<Middleware<V>> = snapshotMiddleware()
        // One MiddlewareContext per middleware, reused across that middleware's
        // started/completed/error hooks so metadata stashed in one hook is
        // visible to the next, matching the sync `Middleware.invoke` contract.
        val contexts: List<Middleware.MiddlewareContext<V>> =
            middlewareChain.map { _ ->
                Middleware.MiddlewareContext(
                    store = selfForExternal,
                    transaction = txn,
                )
            }

        // Concentric outermost-first: iterate REVERSED so the LAST-registered
        // middleware fires `started` first — matching `Store.middlewares`'s contract
        // and the sync `action` path's fold-derived nesting (issue 31). Each
        // invocation is run-caught so one middleware's failure does not abort others.
        // For middlewares that ALSO implement `SuspendingMiddlewareHooks<V>` (issue
        // 09), the async `onTransactionStartedAsync` fires IMMEDIATELY AFTER its
        // sync sibling — interleaved per-middleware so the master ordering is
        //   B.sync.started, B.async.startedAsync, A.sync.started, A.async.startedAsync, body, ...
        fireStartedHooks(middlewareChain, contexts)

        val outcome: TransactionResult<R> =
            try {
                // Capture the body's throwable where it is thrown, before it crosses
                // the withContext coroutine boundary — kotlinx stacktrace recovery
                // hands the outer catch a *copy* otherwise, and callers (mirroring
                // blocking `action`) rely on the exact instance surviving into
                // TransactionResult.Error and getOrThrow.
                var bodyError: Throwable? = null
                val value: R =
                    try {
                        // The marker context installs the thread-local marker on
                        // every resume and restores the prior value on suspend —
                        // same mechanism as suspendAtomic's body wrapper.
                        withContext(heldFrame + frameMarkerContext(marker, coroutineContext[ContinuationInterceptor])) {
                            try {
                                selfForExternal.body()
                            } catch (t: Throwable) {
                                bodyError = t
                                throw t
                            }
                        }
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
                        // Prefer the pre-recovery instance captured inside the body.
                        val original = bodyError ?: e
                        for (i in middlewareChain.indices) {
                            val mw = middlewareChain[i]
                            if (mw is SuspendingMiddlewareHooks<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val async = mw as SuspendingMiddlewareHooks<V>
                                runCatching { async.onTransactionErrorAsync(contexts[i], original) }
                            }
                            runCatching { middlewareChain[i].invokeOnTransactionError(contexts[i], original) }
                        }
                        runCatching { txn.rollback() }
                        return@withLock TransactionResult.Error(original, txn)
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

/**
 * Frame-entry gate for [suspendAction]. Returns `true` when this store is a
 * participant of an active suspending frame — the caller must then run as a
 * savepoint of the frame root instead of acquiring the mutex. Throws for the
 * two contract violations: enrollment in a BLOCKING frame (the lock
 * disciplines don't compose) and an unenrolled write under a strict policy.
 */
private fun <V : Store<V>> V.frameGateAllowsOnlySavepoint(frame: FrameMarker): Boolean {
    val enrolling = frame.enrollingFrame(this)
    if (enrolling != null) {
        if (!enrolling.suspending) {
            throw FrameInteropException(
                "suspendAction on ${frameIdentity()} inside blocking " +
                    "atomic${enrolling.describeParticipants()} (frame '${enrolling.frameId}') is not " +
                    "supported: the blocking frame's lock discipline does not compose with the " +
                    "suspend mutex. Use blocking `action { }` or bare `mutate`/`update` inside an " +
                    "atomic body, or make the whole composition suspending via suspendAtomic.",
            )
        }
        return true
    }
    if (!frame.policy.allowUnenrolled) {
        val name = frameIdentity()
        val fn = if (frame.suspending) "suspendAtomic" else "atomic"
        throw UnenrolledStoreException(
            "$name was mutated (via suspendAction) inside $fn${frame.describeParticipants()} but is " +
                "not enrolled. Its writes would commit independently and would NOT roll back with " +
                "the frame. Fix: add $name to the $fn(...) participant list. (Mid-frame enrollment " +
                "is not possible — it would acquire a lock outside the sorted global order.) To " +
                "deliberately run an independent side-transaction, pass " +
                "policy = FramePolicy.AllowUnenrolled.",
        )
    }
    return false
}

/**
 * In-frame [suspendAction]: the store is a participant of an active
 * suspendAtomic frame, so this action runs as a SAVEPOINT of the store's frame
 * root — commit merges pending writes into the root (observers/bridges fire
 * only at frame commit); rollback discards just the savepoint. No mutex work:
 * the frame already holds the store's serializer mutex.
 *
 * Middleware fires per savepoint exactly like a nested blocking action would:
 * sync + [SuspendingMiddlewareHooks] async hooks, concentric order, per-hook
 * `runCatching` isolation.
 *
 * Error escalation: a [TransactionResult.Error] from this savepoint aborts the
 * whole frame (the exception is rethrown so the frame's unwind rolls every
 * participant back) unless the frame's policy is
 * [FramePolicy.tolerateInnerErrors]. [FrameContractException]s always
 * escalate.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun <V : Store<V>, R> V.suspendActionInFrame(
    frame: FrameMarker,
    name: String?,
    body: suspend V.() -> R,
): TransactionResult<R> {
    val parentTxn =
        activeTransaction
            ?: error(
                "Internal: ${this::class.simpleName} is enrolled in frame '${frame.frameId}' " +
                    "but has no active root transaction.",
            )
    val txn =
        Transaction.createSavepointForExternal(
            id = name ?: body::class.simpleName ?: Uuid.random().toString(),
            ownerThreadId = currentThreadId(),
            parent = parentTxn,
            frameId = parentTxn.frameId,
        )
    internalSetActiveTransaction(txn)

    val middlewareChain: List<Middleware<V>> = snapshotMiddleware()
    val contexts: List<Middleware.MiddlewareContext<V>> =
        middlewareChain.map {
            Middleware.MiddlewareContext(store = selfForExternal, transaction = txn)
        }
    fireStartedHooks(middlewareChain, contexts)

    val outcome: TransactionResult<R> =
        try {
            val value: R = selfForExternal.body()
            for (i in middlewareChain.indices) {
                val mw = middlewareChain[i]
                if (mw is SuspendingMiddlewareHooks<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val async = mw as SuspendingMiddlewareHooks<V>
                    runCatching { async.onTransactionCompletedAsync(contexts[i]) }
                }
                runCatching { middlewareChain[i].invokeOnTransactionCompleted(contexts[i]) }
            }
            // Savepoint commit: merge into the frame root — non-suspending,
            // no fanout (that happens once, at frame commit).
            txn.commit()
            TransactionResult.Success(txn, value)
        } catch (ce: CancellationException) {
            fireErrorHooks(middlewareChain, contexts, ce)
            runCatching { txn.rollback() }
            throw ce
        } catch (e: Throwable) {
            fireErrorHooks(middlewareChain, contexts, e)
            runCatching { txn.rollback() }
            TransactionResult.Error(e, txn)
        } finally {
            internalSetActiveTransaction(parentTxn)
        }

    if (outcome is TransactionResult.Error) {
        val e = outcome.exception
        if (e is FrameContractException || !frame.policy.tolerateInnerErrors) throw e
    }
    return outcome
}

/**
 * Concentric outermost-first `started` dispatch (reversed chain order), sync
 * hook then async [SuspendingMiddlewareHooks] sibling per middleware, each
 * `runCatching`-isolated. Shared by the mutex path and the in-frame
 * savepoint path so the ordering contract is single-sourced.
 */
private suspend fun <V : Store<V>> fireStartedHooks(
    middlewareChain: List<Middleware<V>>,
    contexts: List<Middleware.MiddlewareContext<V>>,
) {
    for (i in middlewareChain.indices.reversed()) {
        runCatching { middlewareChain[i].invokeOnTransactionStarted(contexts[i]) }
        val mw = middlewareChain[i]
        if (mw is SuspendingMiddlewareHooks<*>) {
            @Suppress("UNCHECKED_CAST")
            val async = mw as SuspendingMiddlewareHooks<V>
            runCatching { async.onTransactionStartedAsync(contexts[i]) }
        }
    }
}

private suspend fun <V : Store<V>> fireErrorHooks(
    middlewareChain: List<Middleware<V>>,
    contexts: List<Middleware.MiddlewareContext<V>>,
    error: Throwable,
) {
    for (i in middlewareChain.indices) {
        val mw = middlewareChain[i]
        if (mw is SuspendingMiddlewareHooks<*>) {
            @Suppress("UNCHECKED_CAST")
            val async = mw as SuspendingMiddlewareHooks<V>
            runCatching { async.onTransactionErrorAsync(contexts[i], error) }
        }
        runCatching { middlewareChain[i].invokeOnTransactionError(contexts[i], error) }
    }
}

/** Owner sentinel for suspendAction calls that have no enclosing Job. */
private object SuspendActionFallbackOwner

/**
 * FrameMarker.frameId prefix identifying the single-store pseudo-frame a
 * [suspendAction] installs around its body — as opposed to a real
 * `suspendAtomic-` frame. [suspendAtomic] uses it to reject enrolling a store
 * whose mutex the enclosing suspendAction already holds.
 */
internal const val SUSPEND_ACTION_FRAME_ID_PREFIX = "suspendAction-"

/**
 * Stable human-readable store identity for frame diagnostics, mirroring the
 * core module's rendering: `SimpleName#<lockOrderKey>`. The key suffix
 * distinguishes multiple instances of one store class.
 */
internal fun Store<*>.frameIdentity(): String = "${this::class.simpleName ?: "Store"}#$lockOrderKey"
