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

    companion object {
        /**
         * Public-but-opt-in factory for `:vault-coroutines.suspendAction`. The
         * primary constructor stays `internal` so user code can't manufacture
         * spurious transactions; companion modules that need to construct
         * one (because they implement their own action variant) opt in here.
         */
        @VaultInternalApi
        fun createForExternal(id: String, ownerThreadId: Long): Transaction = Transaction(id, parent = null, ownerThreadId = ownerThreadId)
    }

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
     * Stage a [rawValue] directly as a pending write, bypassing [MutableState.beforeSet].
     * Used by [Vault.restore] to round-trip raw stored values (ciphertext,
     * post-`transformer.set` form) without re-running the transformer.
     *
     * For symmetric transformers this is equivalent to a normal mutate; for
     * asymmetric ones (e.g. [com.vynatix.vault.crypto.EncryptingTransformer]),
     * the difference is critical — restoring already-encrypted ciphertext via
     * `mutate` would re-encrypt it.
     */
    internal fun stagePendingRaw(state: MutableState<*>, rawValue: Any) {
        pendingWrites[state] = rawValue
    }

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
        @OptIn(VaultInternalApi::class)
        commitDispatching { state, value ->
            @Suppress("UNCHECKED_CAST")
            (state as MutableState<Any>).applyCommitted(value)
        }
    }

    /**
     * Internal commit variant for `:vault-coroutines.suspendAction`. Same
     * idempotent semantics as [commit], but the per-pending-write apply step
     * is delegated to [applyTopLevel]. Used to interpose
     * [com.vynatix.vault.coroutines.SuspendingBridge.publishAwaited] between
     * the observer fanout (via [MutableState.applyCommittedRaw]) and the
     * bridge publish.
     *
     * For a nested (savepoint) transaction, [applyTopLevel] is NOT called —
     * pending writes merge into the parent's buffer just like [commit].
     *
     * The lambda is invoked synchronously from inside the commit lock for each
     * pending write, in iteration order. Throwing from it propagates as
     * [TransactionException] just like the sync path.
     */
    @VaultInternalApi
    fun commitDispatching(applyTopLevel: (MutableState<*>, Any) -> Unit) {
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
                    // Top-level commit: apply each pending write via the caller-supplied
                    // dispatcher. Observers and bridges see the value here, post-commit,
                    // never mid-action. Pending writes remain readable via findPendingValue
                    // during the iteration so observer callbacks reading sibling states
                    // still see the about-to-be-committed values (read-your-own-writes
                    // during fanout).
                    pendingWrites.forEach { (state, value) ->
                        applyTopLevel(state, value)
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
