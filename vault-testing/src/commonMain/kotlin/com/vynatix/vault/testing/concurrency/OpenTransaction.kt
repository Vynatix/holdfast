package com.vynatix.vault.testing.concurrency

import com.vynatix.vault.Transaction
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.testing.VaultHandle
import com.vynatix.vault.testing.VaultTestScope
import com.vynatix.vault.testing.internal.PrivilegedHooks
import com.vynatix.vault.testing.internal.openTransactionsRegistry
import kotlinx.atomicfu.atomic

/**
 * Handle to an open, not-yet-committed transaction on a tracked vault.
 *
 * Returned from [com.vynatix.vault.testing.concurrency.transaction]; the test
 * decides whether to [commit] (apply pending writes, fire observers and
 * bridges) or [rollback] (discard pending writes). Both calls are terminal —
 * a second close call on the same instance throws [IllegalStateException]
 * "OpenTransaction already closed".
 *
 * Auto-rollback safety net: every [OpenTransaction] created inside a
 * [com.vynatix.vault.testing.vaultTest] block is registered with the hosting
 * [com.vynatix.vault.testing.VaultTestScope]. If the body returns without
 * having closed it, the scope's `tearDown` invokes [rollback] before clearing
 * the handle registry, so the vault is left in the same state as if no open
 * transaction had ever existed. This matches the
 * "off-thread reads see committed, never pending" invariant: a leaked open
 * transaction never accidentally leaks pending writes into a sibling test.
 *
 * Concurrency model:
 *  - Mutations inside the open body stage into [transaction]'s pending
 *    writes; observers and bridges see nothing until [commit].
 *  - Off-owner-thread reads (`MutableState.value` checks
 *    `txn.ownerThreadId == currentThreadId()`) bypass the read-your-own-writes
 *    overlay and return the COMMITTED value, never the pending one — the
 *    `offThreadStateValueReadDuringActiveActionReturnsCommittedNotPending`
 *    invariant.
 *  - A peer `action` from another thread takes the vault's `transactionLock`,
 *    sees `activeTransaction = ourOpenTxn`, and nests as a savepoint — its
 *    inner commit MERGES into our pending writes rather than firing observers
 *    or bridges. This is the production-faithful nested-action behavior;
 *    tests that want the peer to commit independently should close this open
 *    transaction first.
 *
 * Constructed exclusively by [com.vynatix.vault.testing.concurrency.transaction];
 * never instantiated by user code.
 */
class OpenTransaction internal constructor(
    /**
     * The manufactured [Transaction] — useful for asserting on
     * [Transaction.status] (e.g. that [rollback] flipped it to RolledBack).
     * Read-only; mutate the open state through this class's [commit] /
     * [rollback] / via the body's receiver inside the original
     * `transaction { ... }` call.
     */
    val transaction: Transaction,
    private val handle: VaultHandle<*>,
    private val onClose: (OpenTransaction) -> Unit,
) {

    private val closedFlag = atomic(false)

    /**
     * `true` once [commit] or [rollback] has run (or scope-exit auto-rollback
     * has happened). After this point, every close call throws.
     */
    val isClosed: Boolean get() = closedFlag.value

    /**
     * Apply the pending writes staged in [transaction] to the vault, fire
     * observers and bridges in the same single-fanout boundary that a
     * production [Vault.action] commit produces.
     *
     * Returns:
     *  - [TransactionResult.Success] with `Unit` value when commit succeeds.
     *  - [TransactionResult.Error] when the commit itself throws (e.g. an
     *    observer body raises, or [Transaction.commit] hits a status
     *    transition error). Mirrors the production action contract — the
     *    returned [Transaction] in the result is the same as [transaction].
     *
     * Subsequent calls to [commit] or [rollback] throw
     * [IllegalStateException] "OpenTransaction already closed".
     */
    @Suppress("TooGenericExceptionCaught", "RedundantSuspendModifier")
    suspend fun commit(): TransactionResult<Unit> {
        if (!closedFlag.compareAndSet(expect = false, update = true)) {
            error("OpenTransaction already closed")
        }
        return try {
            PrivilegedHooks.commitOpenTransaction(handle.vault, transaction)
            TransactionResult.Success(transaction, Unit)
        } catch (e: Throwable) {
            TransactionResult.Error(e, transaction)
        } finally {
            onClose(this)
        }
    }

    /**
     * Discard the pending writes staged in [transaction]. The vault's
     * committed values stay unchanged; observers and bridges receive no
     * notification. After return, [Transaction.status] is `RolledBack`.
     *
     * Subsequent calls to [commit] or [rollback] throw
     * [IllegalStateException] "OpenTransaction already closed".
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun rollback() {
        if (!closedFlag.compareAndSet(expect = false, update = true)) {
            error("OpenTransaction already closed")
        }
        try {
            PrivilegedHooks.rollbackOpenTransaction(handle.vault, transaction)
        } finally {
            onClose(this)
        }
    }

    /**
     * Synchronous rollback, used by the test scope's `tearDown` auto-rollback
     * path. Mirrors [rollback]'s body without the suspend wrapping. Idempotent
     * — a no-op if the transaction is already closed.
     *
     * Internal because callers from outside the testing module should always
     * use the suspending [rollback] (the suspend marker preserves the option
     * to add coroutine-aware bookkeeping in v2 without an ABI break).
     */
    internal fun rollbackSilentlyForTearDown() {
        if (!closedFlag.compareAndSet(expect = false, update = true)) return
        try {
            PrivilegedHooks.rollbackOpenTransaction(handle.vault, transaction)
        } finally {
            onClose(this)
        }
    }
}

/**
 * Open a transaction on the tracked vault behind [on], run [body] inside it
 * (staging mutations into the transaction's pending writes), and return an
 * [OpenTransaction] handle. Subsequent calls to
 * [OpenTransaction.commit] or [OpenTransaction.rollback] decide the outcome.
 *
 * What the body does:
 *  - Receiver is the tracked vault. Mutations like `count mutate 999` stage
 *    into the new transaction's pending writes — same staging as inside a
 *    production [com.vynatix.vault.Vault.action] body.
 *  - Reads honor read-your-own-writes on the body's owner thread; an
 *    off-owner-thread read (e.g. via [parallel]) sees the COMMITTED value,
 *    not the pending one.
 *
 * What it does NOT do:
 *  - Middleware does NOT run on the open transaction. This matches
 *    `:vault-coroutines.suspendAction`'s v1 contract — middleware is "NOT
 *    invoked" for externally-manufactured transactions.
 *  - The body must NOT throw — this method propagates the throw immediately
 *    and rolls back the manufactured transaction (so the vault is left in a
 *    clean state). Tests asserting body-failure behavior should wrap the
 *    `transaction { ... }` call in `assertFailsWith`.
 *
 * Concurrency:
 *  - The vault's `transactionLock` is held only briefly at open (to install
 *    the manufactured transaction as `activeTransaction`) and at
 *    commit/rollback (to apply or discard pending writes). The body runs
 *    without holding the lock, so async work between open and close is
 *    unrestricted — but a peer blocking [com.vynatix.vault.Vault.action]
 *    from another thread will lock-wait until [OpenTransaction.commit] or
 *    [OpenTransaction.rollback] runs.
 *  - [transaction] itself is `suspend` so callers can compose it with other
 *    suspending primitives ([parallel], [eventually]). The implementation
 *    does no suspending work in v1.
 *
 * Auto-rollback at scope exit: leaking an [OpenTransaction] past the
 * surrounding [com.vynatix.vault.testing.vaultTest] block triggers a
 * synchronous rollback during `tearDown`. The rollback runs after the
 * barrier-cancel step but before the recorder-dispose step, so a vault that
 * never committed still has its pending writes discarded cleanly.
 *
 * Example:
 * ```
 * val ctr = track(CountVault())
 * val open = transaction(on = ctr) { count mutate 999 }   // pending, not committed
 * parallel(1) { ctr.read { count.value } shouldBe 0 }     // off-thread sees committed
 * open.commit().shouldBeSuccess()
 * ctr.read { count.value } shouldBe 999
 * ```
 */
@Suppress("RedundantSuspendModifier")
suspend fun <V : Vault<V>> VaultTestScope.transaction(on: VaultHandle<V>, body: V.() -> Unit): OpenTransaction {
    val txn = PrivilegedHooks.openTransaction(on.vault, body)
    val open = OpenTransaction(
        transaction = txn,
        handle = on,
        onClose = { tx -> openTransactionsRegistry().remove(tx) },
    )
    openTransactionsRegistry().add(open)
    return open
}
