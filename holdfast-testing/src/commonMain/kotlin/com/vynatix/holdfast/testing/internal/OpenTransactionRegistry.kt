package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.testing.StoreTestScope
import com.vynatix.holdfast.testing.concurrency.OpenTransaction
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Backing list for [OpenTransaction]s created via
 * [com.vynatix.holdfast.testing.concurrency.transaction]. Held inside the
 * [StoreTestScope] so the lifetime mirrors the test body; [rollbackAll] is
 * called from `tearDown` to discard any leaked open transactions before the
 * scope's other resources are released.
 *
 * Thread-safe (an [OpenTransaction] can be opened on the test thread and
 * closed from a [com.vynatix.holdfast.testing.concurrency.parallel] worker, so
 * `add` / `remove` may race). The lock is held only briefly around list
 * mutation; iteration during teardown takes a defensive snapshot first.
 */
internal class OpenTransactionRegistry {
    private val lock = SynchronizedObject()
    private val openTransactions: MutableList<OpenTransaction> = mutableListOf()

    fun add(openTransaction: OpenTransaction) {
        synchronized(lock) {
            openTransactions.add(openTransaction)
        }
    }

    fun remove(openTransaction: OpenTransaction) {
        synchronized(lock) {
            openTransactions.removeAll { it === openTransaction }
        }
    }

    /**
     * Snapshot the current list of open transactions, rollback every one that
     * is still open (skips those already closed via [OpenTransaction.commit] or
     * [OpenTransaction.rollback]), and clear the list.
     *
     * Uses [OpenTransaction.rollbackSilentlyForTearDown] — a non-suspend
     * variant — because [StoreTestScope.tearDown] is non-suspend. The
     * v1 implementation does no suspending work, so the synchronous variant
     * is faithful to the suspending API.
     */
    fun rollbackAll() {
        val snapshot =
            synchronized(lock) {
                val copy = openTransactions.toList()
                openTransactions.clear()
                copy
            }
        for (open in snapshot) {
            if (!open.isClosed) {
                runCatching { open.rollbackSilentlyForTearDown() }
            }
        }
    }
}

/**
 * Bridge from public-API extension functions in `concurrency/` back to the
 * scope's `internal` registry. Co-located here so the seam is obvious and so
 * [OpenTransaction]'s constructor can stay `internal` without leaking. Mirrors
 * the [registerBarrier] shape.
 */
internal fun StoreTestScope.openTransactionsRegistry(): OpenTransactionRegistry = openTransactionRegistry()
