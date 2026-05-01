package com.vynatix.vault

import kotlin.time.Clock

class Timestamp private constructor(private val millisSinceEpoch: Long) {
    companion object {
        fun now(): Timestamp = Timestamp(Clock.System.now().toEpochMilliseconds())
    }

    override fun toString(): String = millisSinceEpoch.toString()
}

class Transaction internal constructor(val id: String, internal val parent: Transaction?, internal val ownerThreadId: Long) {
    private val statusLock = VaultLock()
    private val endTimeLock = VaultLock()
    private val pendingLock = VaultLock()

    @kotlin.concurrent.Volatile
    private var _status = TransactionStatus.Active
    val status: TransactionStatus
        get() = statusLock.withLock { _status }

    @kotlin.concurrent.Volatile
    private var _endTime: String? = null
    val endTime: String?
        get() = endTimeLock.withLock { _endTime }

    // Per-transaction buffer of writes (state → post-transformer.set value).
    // Owner-thread-confined; never read or written from another thread.
    // For nested (savepoint) transactions, commit merges this into parent.pendingWrites.
    // For top-level transactions, commit applies via state.applyCommitted.
    internal val pendingWrites: MutableMap<MutableState<*>, Any> = mutableMapOf()

    /**
     * Walk the savepoint chain (this → parent → … → root) for a pending write.
     * Used by Vault.readValue when a caller wants in-transaction read-your-own-writes.
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
     * On a non-Active transaction this is a no-op — fixes bugs #8 and #11 by preventing
     * a misused commit from corrupting state or replaying side effects.
     *
     * For a nested (savepoint) transaction, pending writes are merged into the parent's.
     * For a top-level transaction, pending writes are applied to state via
     * MutableState.applyCommitted, which is the single place observers and bridges fire.
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
                _endTime = Timestamp.now().toString()
            }
        }
    }

    /**
     * Idempotent rollback. Active → RolledBack.
     * On a non-Active transaction this is a no-op — fixes bugs #8 and #11.
     *
     * Discards pending writes without touching state, observers, or bridges. This is
     * the architectural fix for bugs #2, #3, #6, #10.
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
                _endTime = Timestamp.now().toString()
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

enum class TransactionStatus {
    Active,
    Committed,
    RolledBack,
    Failed,
}

class TransactionException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed class TransactionResult {
    data class Success(val transaction: Transaction) : TransactionResult()
    data class Error(val exception: Throwable, val transaction: Transaction) : TransactionResult()
}
