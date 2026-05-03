@file:OptIn(com.vynatix.vault.VaultInternalApi::class)

package com.vynatix.vault.coroutines

import com.vynatix.vault.Middleware
import com.vynatix.vault.MutableState
import com.vynatix.vault.Transaction
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.platform.currentThreadId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * via [com.vynatix.vault.MutableState.applyCommitted] and observers/bridges
 * fire then. On throw, pending writes are dropped — same semantics as the
 * blocking [Vault.action].
 *
 * Concurrency contract for 1.1:
 *  - **Mutually exclusive with blocking [Vault.action]** on the same vault: a
 *    blocking `action` will block until the in-flight `suspendAction` completes,
 *    and vice versa. Coordination is via an internal coroutine [Mutex] installed
 *    lazily on first use.
 *  - **Cancellation**: a `CancellationException` thrown from the body rolls back
 *    the transaction. Cancellation BETWEEN body return and commit is suppressed
 *    via [NonCancellable] on the commit phase — once the body completes, the
 *    commit's observer/bridge fanout runs to completion to avoid mid-fanout desync.
 *  - **Middleware**: `Middleware<V>` sync hooks fire on the suspending path
 *    in concentric-ring order — `onTransactionStarted` in chain order before
 *    the body; `onTransactionCompleted` (success) or `onTransactionError`
 *    (throw / cancellation) in reverse chain order. Each hook invocation is
 *    wrapped in `runCatching`: one middleware's failure does not abort other
 *    middlewares' hooks.
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
suspend fun <V : Vault<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R> {
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

        // Concentric forward: chain order. Each invocation is run-caught so one
        // middleware's failure does not abort others.
        for (i in middlewareChain.indices) {
            runCatching { middlewareChain[i].invokeOnTransactionStarted(contexts[i]) }
        }

        val outcome: TransactionResult<R> = try {
            val value: R = try {
                selfForExternal.body()
            } catch (ce: CancellationException) {
                // Concentric reverse on the error path. Cancellation is propagated
                // after rollback; error hooks run for symmetry with the throwing
                // path so middleware sees one consistent "errored transaction" view.
                for (i in middlewareChain.indices.reversed()) {
                    runCatching { middlewareChain[i].invokeOnTransactionError(contexts[i], ce) }
                }
                runCatching { txn.rollback() }
                throw ce
            } catch (e: Throwable) {
                for (i in middlewareChain.indices.reversed()) {
                    runCatching { middlewareChain[i].invokeOnTransactionError(contexts[i], e) }
                }
                runCatching { txn.rollback() }
                return@withLock TransactionResult.Error(e, txn)
            }
            // Body returned. Commit under NonCancellable so observer/bridge
            // fanout completes even if the surrounding scope cancels here.
            // Sync `Middleware.invoke` runs `onTransactionCompleted` BEFORE
            // commit; we preserve that ordering here.
            withContext(NonCancellable) {
                for (i in middlewareChain.indices.reversed()) {
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

/**
 * Lazy installation of the [Vault.AsyncSerializer] hook on each vault. The
 * hook is installed on first use and persists for the vault's lifetime — the
 * coroutine [Mutex] inside it serializes both blocking action and suspending
 * action.
 */
private val installLock = object : SynchronizedObject() {}

private fun ensureSerializer(vault: Vault<*>): MutexSerializer {
    val installed = vault.asyncSerializer as? MutexSerializer
    if (installed != null) return installed
    return synchronized(installLock) {
        val again = vault.asyncSerializer as? MutexSerializer
        if (again != null) return@synchronized again
        val fresh = MutexSerializer()
        vault.asyncSerializer = fresh
        fresh
    }
}

/**
 * Commit the transaction with the `suspendAction`-specific bridge interpose.
 *
 * Identical to [Transaction.commit] for nested (savepoint) transactions —
 * pending writes merge into the parent's buffer. For a top-level transaction,
 * each pending write is applied via [MutableState.applyCommittedRaw] (replace
 * value + observer fanout, NO bridge publish), and the bridge publish is
 * dispatched separately:
 *
 *  - If the bound bridge is a [SuspendingBridge], call its [SuspendingBridge.publishAwaited]
 *    directly here — the surrounding `withContext(NonCancellable)` ensures the
 *    write completes even if the calling scope cancels.
 *  - Otherwise (sync [com.vynatix.vault.Bridge] or no bridge), call [com.vynatix.vault.Bridge.publish]
 *    fire-and-forget, matching the sync action contract.
 *
 * Pending writes are pre-snapshotted before observer fanout so the suspend
 * call out of [Transaction.commitDispatching] does not run inside its
 * `pendingLock.withLock` block — `publishAwaited` is genuinely suspending and
 * could deadlock or cause re-entrant lock issues otherwise. The downside is
 * one extra map allocation; the upside is correctness.
 */
@Suppress("UNCHECKED_CAST")
private suspend fun suspendingCommit(txn: Transaction) {
    // Snapshot pending writes and pending events outside the commit's
    // pendingLock, so we can suspend in publishAwaited and the events drain
    // without holding any internal vault lock. The commit itself runs observer
    // fanout, clears pendingWrites, and clears pendingEvents — but our
    // drainEvents lambda intercepts the snapshot before the clear.
    val publishQueue = mutableListOf<Pair<MutableState<Any>, Any>>()
    val eventsQueue = mutableListOf<Pair<MutableSharedFlow<*>, Any>>()
    txn.commitDispatching(
        applyTopLevel = { state, value ->
            val ms = state as MutableState<Any>
            // Step 1+2: replace + observers (no bridge publish yet).
            ms.applyCommittedRaw(value)
            publishQueue += ms to value
        },
        drainEvents = { snapshot ->
            // Stash for after-bridge drain. Do NOT emit here — `commitDispatching`
            // is non-suspending, and we want bridge publishes (which may suspend)
            // to complete first per the commit-phase ordering contract.
            eventsQueue.addAll(snapshot)
        },
    )
    // Step 3a: bridge publish phase. SuspendingBridge gets awaited;
    // every other Bridge falls back to fire-and-forget Bridge.publish.
    for ((ms, value) in publishQueue) {
        val br = ms.bridge ?: continue
        if (br is SuspendingBridge<*>) {
            (br as SuspendingBridge<Any>).publishAwaited(value)
        } else {
            br.publish(value)
        }
    }
    // Step 3b: events drain. Use suspending `emit` so `BufferOverflow.SUSPEND`
    // back-pressure is honored — slow collectors block the producer (this
    // commit thread) rather than dropping events. We're already inside
    // `withContext(NonCancellable)` from the caller, so cancellation between
    // bridge publish and events does not interrupt the drain.
    for ((channel, event) in eventsQueue) {
        (channel as MutableSharedFlow<Any>).emit(event)
    }
}

/**
 * AsyncSerializer impl backed by a coroutine [Mutex]. Blocking `action` callers
 * spin via `tryLock` + platform yield; suspending `suspendAction` callers use
 * the natural `Mutex.withLock` suspending wait.
 */
private class MutexSerializer : Vault.AsyncSerializer {
    val mutex = Mutex()

    override fun blockingAcquire() {
        while (!mutex.tryLock(SPIN_OWNER)) {
            com.vynatix.vault.platform.threadYield()
        }
    }

    override fun blockingRelease() {
        runCatching { mutex.unlock(SPIN_OWNER) }
    }

    private companion object {
        private val SPIN_OWNER = Any()
    }
}
