@file:OptIn(com.vynatix.vault.VaultInternalApi::class)

package com.vynatix.vault.coroutines

import com.vynatix.vault.Transaction
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.platform.currentThreadId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
 *  - **Middleware**: NOT invoked for 1.1. Use blocking `action` if you need
 *    middleware plus async work.
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

        val outcome: TransactionResult<R> = try {
            val value: R = try {
                @Suppress("UNCHECKED_CAST")
                (selfForExternal as V).body()
            } catch (ce: CancellationException) {
                runCatching { txn.rollback() }
                throw ce
            } catch (e: Throwable) {
                runCatching { txn.rollback() }
                return@withLock TransactionResult.Error(e, txn)
            }
            // Body returned. Commit under NonCancellable so observer/bridge
            // fanout completes even if the surrounding scope cancels here.
            withContext(NonCancellable) {
                try {
                    txn.commit()
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
