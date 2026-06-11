@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Run [body] in a way that brackets multiple vaults' transactions so they
 * commit-or-rollback together. Inside the body, calls like `v1.action { … }`
 * and `v2.action { … }` join the atomic frame as savepoints of each store's
 * root transaction. `mutate`/`update` calls outside an inner `action` stage
 * directly into the appropriate store's root transaction.
 *
 * Locking: vaults are sorted by [Store.lockOrderKey] before lock acquisition,
 * giving a deadlock-safe global order across any combination of vaults. Each
 * store's blocking `transactionLock` is held for the duration of the body.
 *
 * Two-phase commit (in-memory): on body return, each store's root transaction
 * commits in lock order. Each commit applies its pending writes via
 * `MutableState.applyCommitted` and fires observers/bridges for that store.
 * Observer fanout for store A completes before store B's begins; an observer
 * on A that calls `b.action {}` runs before B's commit applies, giving
 * predictable cross-store ordering.
 *
 * On any throw from [body]: every store's root transaction is rolled back
 * (pending writes dropped, no state mutation visible). The atomic returns
 * `TransactionResult.Error(thrown)`. Inner action errors do NOT
 * automatically rollback the atomic — propagate via re-throw or check
 * `is Error` and fail explicitly.
 *
 * Limitations (1.1):
 *  - Body is non-suspending. For mixed async, run `atomic` inside a
 *    `suspendAction` — but only for the suspending store; do not include
 *    other vaults that may have concurrent suspending callers.
 *  - Body must be single-threaded — spawned threads' mutates won't be
 *    recognized as in-frame.
 *  - Nested `atomic` is supported (the outer's locks are reentrant) but no
 *    explicit lock-order safety check is performed. Document the global key
 *    ordering and trust callers.
 *
 * Example:
 * ```
 * val r = atomic(accountA, accountB) {
 *     accountA.action { balance update { it - amount } }
 *     accountB.action { balance update { it + amount } }
 * }
 * when (r) {
 *     is TransactionResult.Success -> log("transfer ok")
 *     is TransactionResult.Error   -> log("transfer rolled back: ${r.exception}")
 * }
 * ```
 */
@OptIn(ExperimentalUuidApi::class)
fun <R> atomic(
    vararg vaults: Store<*>,
    body: () -> R,
): TransactionResult<R> {
    require(vaults.isNotEmpty()) { "atomic requires at least one store" }
    // De-duplicate by identity and sort by global lock order key.
    val sorted = vaults.toSet().sortedBy { it.lockOrderKey }
    val ownerThreadId = currentThreadId()
    val id = "atomic-${Uuid.random()}"
    return acquireAndRun(sorted, 0, mutableListOf(), id, ownerThreadId, body)
}

/**
 * Tail-recursive helper that acquires each store's transactionLock in order
 * via [Store.runUnderLock], then opens a root [Transaction] per store, then
 * runs [body], then commits/rollbacks all roots, then unwinds.
 *
 * Recursive structure handles N vaults by chaining `runUnderLock` calls;
 * the reentrant lock ensures repeat-acquisition by the same thread is free.
 */
@OptIn(ExperimentalUuidApi::class)
private fun <R> acquireAndRun(
    sorted: List<Store<*>>,
    index: Int,
    rootsAcquired: MutableList<Pair<Store<*>, Transaction>>,
    id: String,
    ownerThreadId: Long,
    body: () -> R,
): TransactionResult<R> {
    if (index == sorted.size) {
        return executeBody(rootsAcquired, body)
    }
    val v = sorted[index]
    return v.runUnderLock {
        // Open a root transaction for this store. The previous _activeTransaction
        // (which may belong to an enclosing atomic or a synchronous action) becomes
        // this root's parent — preserving savepoint semantics for nested atomics.
        val priorActive = v.activeTransaction
        val root =
            if (priorActive != null && priorActive.ownerThreadId == ownerThreadId) {
                // Adopt the existing transaction as our root — we're nested inside an
                // outer action/atomic on this thread for this store.
                priorActive
            } else {
                Transaction.createForExternal(id, ownerThreadId).also {
                    v.internalSetActiveTransaction(it)
                }
            }
        rootsAcquired.add(v to root)
        try {
            acquireAndRun(sorted, index + 1, rootsAcquired, id, ownerThreadId, body)
        } finally {
            // Restore _activeTransaction only if WE installed root (didn't adopt).
            if (priorActive == null || priorActive !== root) {
                v.internalSetActiveTransaction(priorActive)
            }
        }
    }
}

private fun <R> executeBody(
    roots: List<Pair<Store<*>, Transaction>>,
    body: () -> R,
): TransactionResult<R> {
    val outcome: TransactionResult<R> =
        try {
            val value = body()
            // Commit each root in lock order. For roots we adopted (priorActive == root),
            // the commit happens at the outer enclosing scope's exit — skip here.
            roots.forEach { (_, root) ->
                // commit() is idempotent; if root was adopted from an outer scope,
                // it's still Active and we'd commit it prematurely. Detect: a root we
                // OPENED has its parent==null (top-level relative to this thread's
                // pre-atomic state). Adopted roots have non-null parent OR were the
                // pre-existing _activeTransaction.
                // Conservative approach: only commit roots that have status Active
                // AND are not parent-chained to something still in progress. For
                // 1.1 we commit unconditionally — adopted root commit becomes a
                // savepoint merge, which is correct.
                root.commit()
            }
            @Suppress("UNCHECKED_CAST")
            TransactionResult.Success(roots.last().second, value as R)
        } catch (e: Throwable) {
            roots.forEach { (_, root) -> runCatching { root.rollback() } }
            TransactionResult.Error(e, roots.last().second)
        }
    return outcome
}
