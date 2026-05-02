package com.vynatix.vault

import kotlin.time.Clock

/**
 * One unit of atomicity in a [Vault]. A transaction holds:
 *  - an [id] (the action's class simple name, falling back to a random UUID),
 *  - a [parent] reference forming the savepoint chain (null for top-level),
 *  - a buffer of pending writes ([pendingWrites]), thread-confined to [ownerThreadId],
 *  - a status ([TransactionStatus]) advancing Active → Committed/RolledBack/Failed.
 *
 * Top-level transactions apply their pending writes to state on [commit];
 * nested (savepoint) transactions merge their pending writes into the parent's
 * on [commit], and drop them entirely on [rollback].
 *
 * Both [commit] and [rollback] are idempotent on a non-Active transaction
 * (no-op).
 */
class Transaction internal constructor(val id: String, internal val parent: Transaction?, internal val ownerThreadId: Long) {
    private val statusLock = VaultLock()
    private val endTimeLock = VaultLock()
    private val pendingLock = VaultLock()

    @kotlin.concurrent.Volatile
    private var _status = TransactionStatus.Active

    /** Current status of the transaction. */
    val status: TransactionStatus
        get() = statusLock.withLock { _status }

    @kotlin.concurrent.Volatile
    private var _endTime: Long? = null

    /**
     * Epoch milliseconds at which this transaction reached a terminal status
     * (Committed/RolledBack/Failed). `null` while the transaction is still Active.
     */
    val endTime: Long?
        get() = endTimeLock.withLock { _endTime }

    /**
     * Per-transaction buffer of writes (state → post-`transformer.set` value).
     * Owner-thread-confined; never read or written from another thread.
     * For nested (savepoint) transactions, [commit] merges this into
     * `parent.pendingWrites`. For top-level transactions, [commit] applies via
     * [MutableState.applyCommitted].
     */
    internal val pendingWrites: MutableMap<MutableState<*>, Any> = mutableMapOf()

    /**
     * Read-only view of the states modified by this transaction (or its
     * not-yet-committed inner savepoints, via the savepoint chain). Owner-thread
     * only; throws [IllegalStateException] from non-owner threads. Useful for
     * audit middleware that wants to log what was touched.
     */
    val modifiedStates: Set<State<*>>
        get() {
            check(ownerThreadId == com.vynatix.vault.platform.currentThreadId()) {
                "modifiedStates may only be read on the transaction's owner thread"
            }
            return pendingLock.withLock { pendingWrites.keys.toSet() }
        }

    /**
     * Walk the savepoint chain (this → parent → … → root) for a pending write.
     * Used by [MutableState.value] when a caller wants in-transaction
     * read-your-own-writes.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> findPendingValue(state: MutableState<T>): T? {
        var current: Transaction? = this
        while (current != null) {
            val pending = current.pendingWrites[state]
            if (pending != null) return pending as T
            current = current.parent
        }
        return null
    }

    /**
     * Idempotent commit. Active → Committed.
     * On a non-Active transaction this is a no-op — preventing a misused commit
     * from corrupting state or replaying side effects.
     *
     * For a nested (savepoint) transaction, pending writes are merged into the
     * parent's. For a top-level transaction, pending writes are applied to state
     * via [MutableState.applyCommitted], which is the single place observers and
     * bridges fire.
     */
    fun commit() {
        val current = statusLock.withLock { _status }
        if (current != TransactionStatus.Active) return

        try {
            pendingLock.withLock {
                val parentTxn = parent
                if (parentTxn != null) {
                    // Savepoint commit: merge into parent. Last-write-wins on shared keys —
                    // savepoint mutations override any earlier parent pending for the same state.
                    parentTxn.pendingWrites.putAll(pendingWrites)
                } else {
                    // Top-level commit: apply each pending write. Observers and bridges
                    // see the value here, post-commit, never mid-action.
                    pendingWrites.forEach { (state, value) ->
                        @Suppress("UNCHECKED_CAST")
                        (state as MutableState<Any>).applyCommitted(value)
                    }
                }
                pendingWrites.clear()
            }
            updateStatus(TransactionStatus.Committed)
        } catch (e: Exception) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException("Commit failed", e)
        } finally {
            endTimeLock.withLock {
                _endTime = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    /**
     * Idempotent rollback. Active → RolledBack.
     * On a non-Active transaction this is a no-op.
     *
     * Discards pending writes without touching state, observers, or bridges.
     */
    fun rollback() {
        val current = statusLock.withLock { _status }
        if (current != TransactionStatus.Active) return

        try {
            pendingLock.withLock {
                pendingWrites.clear()
            }
            updateStatus(TransactionStatus.RolledBack)
        } catch (e: Exception) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException("Rollback failed", e)
        } finally {
            endTimeLock.withLock {
                _endTime = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    private fun updateStatus(newStatus: TransactionStatus) {
        statusLock.withLock {
            val oldStatus = _status
            if (!isValidStatusTransition(oldStatus, newStatus)) {
                throw TransactionException("Invalid status transition from $oldStatus to $newStatus")
            }
            _status = newStatus
        }
    }

    private fun isValidStatusTransition(from: TransactionStatus, to: TransactionStatus): Boolean = when (from) {
        TransactionStatus.Active -> to in setOf(
            TransactionStatus.Committed,
            TransactionStatus.RolledBack,
            TransactionStatus.Failed,
        )
        TransactionStatus.Committed -> false
        TransactionStatus.RolledBack -> false
        TransactionStatus.Failed -> false
    }
}

/** Lifecycle status of a [Transaction]. Active is the only non-terminal state. */
enum class TransactionStatus {
    Active,
    Committed,
    RolledBack,
    Failed,
}

/** Thrown by [Transaction] when commit/rollback fail or status transitions are invalid. */
class TransactionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The outcome of a [Vault.action]. Either [Success] (the body returned without
 * throwing and the commit succeeded — carrying the body's computed `value`) or
 * [Error] (the body or commit threw, the transaction is RolledBack).
 *
 * Generic in `R` (the body's return type) and covariant in it, so a
 * `TransactionResult<Int>` is assignable to `TransactionResult<Number>` and to
 * `TransactionResult<Any>`. [Error] does not carry a value and extends
 * `TransactionResult<Nothing>`, making it the bottom type that fits any `R`.
 */
sealed interface TransactionResult<out R> {
    data class Success<R>(val transaction: Transaction, val value: R) : TransactionResult<R>
    data class Error(val exception: Throwable, val transaction: Transaction) : TransactionResult<Nothing>
}
