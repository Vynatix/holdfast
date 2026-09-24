@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.displayValue
import com.vynatix.holdfast.observableBacking
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.tags
import kotlin.time.Clock

/**
 * Centralises every `@StoreInternalApi` access used by the testing module so the
 * unsafe surface lives in one auditable file. Call sites elsewhere should reach
 * for these helpers rather than opt in directly — that way new dependencies on
 * store internals are visible at PR time.
 *
 * Four clusters of hooks live here:
 *  - **Recorder reads** — [snapshotCommittedStateValues] and [modifiedStates]
 *    let the timeline recorder observe committed values from inside a
 *    middleware hook without re-entering the read-your-own-writes overlay.
 *    Strict subset of what `:holdfast-coroutines.SuspendAction` uses.
 *  - **Secret redaction** — [isSecret] and [recordedValue] decide what the
 *    harness may record or print of a state's value: every value it records
 *    (timeline events, bridge publish histories) goes through
 *    [recordedValue], and every matcher that would print or compare a value
 *    checks [isSecret] first and withholds it (a failure message without the
 *    values, or a refused value matcher), so a `StateTag.Secret` value never
 *    reaches a timeline or an assertion message.
 *  - **Clock binding** — [boundClock] and [restoreBoundClock] let a handle
 *    remember a store's raw clock binding at track time and put it back at
 *    teardown.
 *  - **Manufactured-transaction hooks** — [openTransaction], [commitOpenTransaction],
 *    and [rollbackOpenTransaction] manufacture a transaction outside the
 *    blocking `action` path so a test can hold a transaction open across
 *    asynchronous boundaries. These mirror the production lifecycle —
 *    `Transaction.createForExternal` + `internalSetActiveTransaction(...)` —
 *    but run brief critical sections under the store's reentrant
 *    `transactionLock` rather than across the entire open-period.
 */
internal object PrivilegedHooks {
    /**
     * Read every state currently registered on [store] mapped to its committed
     * post-`transformer.get` value, bypassing the active transaction's
     * read-your-own-writes overlay.
     *
     * Why we bypass: `MutableState.value` on the owner thread inside an active
     * transaction returns pending writes first (so the body sees its own
     * uncommitted mutations). The recorder needs the COMMITTED view at the
     * boundary between body return and commit apply — that's the `oldValue` of
     * each [com.vynatix.holdfast.testing.EmissionEvent].
     *
     * How we bypass: temporarily clear the store's `_activeTransaction` via
     * `internalSetActiveTransaction(null)`, read every state, then restore.
     * The middleware hook holds the store's `transactionLock` (because we run
     * inside `runBlockingActionUnderLock` -> `runMiddlewareChain`), so no peer
     * action can observe the transient null. Off-owner-thread mutate calls are
     * also blocked on the same lock, so they cannot race with this read.
     *
     * Single-call semantics: this captures the committed view at one instant
     * and returns. The caller MUST keep the hold-lock invariant intact — never
     * call this from outside a middleware hook.
     *
     * Each value is already [recordedValue]: a Secret state maps to
     * [com.vynatix.holdfast.Redacted]. [alsoRead] adds states the store does
     * not list in `properties` (a `derivedState`'s backing, say) that the
     * caller needs the committed values of too.
     */
    fun snapshotCommittedStateValues(
        store: Store<*>,
        alsoRead: Collection<State<*>> = emptyList(),
    ): Map<State<*>, Any?> {
        // Registered states, plus [alsoRead]: the states a transaction writes
        // that the store does not list, such as a derivedState's backing.
        val states = store.properties.values + alsoRead
        val active = store.activeTransaction
        return if (active == null) {
            // No active transaction → state.value already returns the committed
            // view. Cheap path; avoids the toggle.
            states.associateWith { recordedValue(it, it.value) }
        } else {
            store.internalSetActiveTransaction(null)
            try {
                states.associateWith { recordedValue(it, it.value) }
            } finally {
                store.internalSetActiveTransaction(active)
            }
        }
    }

    /**
     * The state the recorder's events name for [state]: the backing state of
     * a `derivedState`/`merged` state (the state its recompute commits, and
     * so the one [com.vynatix.holdfast.Transaction.modifiedStates] and the
     * timeline hold), [state] itself otherwise. Every lookup of a tracked
     * property in the timeline resolves through this.
     */
    fun recordedState(state: State<*>): State<*> = state.observableBacking() ?: state

    /** Whether [state] is tagged `StateTag.Secret` (a `derived` of one included). */
    @OptIn(ExperimentalStoreApi::class)
    fun isSecret(state: State<*>): Boolean = StateTag.Secret in state.tags

    /**
     * What the harness may record or print of [value], a value of [state]:
     * [com.vynatix.holdfast.Redacted] for a Secret state, [value] otherwise.
     */
    fun recordedValue(
        state: State<*>,
        value: Any?,
    ): Any? = state.displayValue(value)

    /**
     * Owner-thread-only read of the transaction's modified-state set. Wraps
     * [Transaction.modifiedStates] without adding any new logic — provided here
     * so the call site in [com.vynatix.holdfast.testing.internal.Recorder] doesn't
     * need its own `@OptIn`.
     *
     * Throws if invoked off the owner thread; the recorder's hooks always run
     * on the owner thread (the middleware chain is synchronous and on the
     * thread that called [com.vynatix.holdfast.Store.action]).
     */
    fun modifiedStates(transaction: Transaction): Set<State<*>> = transaction.modifiedStates

    /**
     * The clock bound on [store] via `Store.bindClock`, or `null` when none is.
     * Reads the raw binding ([Store.internalBoundClock]), not [Store.clock]: a
     * subclass override of `clock` is not a binding, and restoring it as one
     * would pin the override's current clock onto the store.
     */
    fun boundClock(store: Store<*>): Clock? = store.internalBoundClock

    /**
     * Rebind [store]'s clock to [clock] (a value [boundClock] returned earlier)
     * if the binding has changed since. Skips a disposed store — `bindClock`
     * throws on one, and a disposed store has no next test to leak into — and
     * swallows the throw of a store disposed concurrently, so teardown stays
     * robust. It must never throw: it also runs as the test job's completion
     * handler (see `StoreTestScope`), possibly a second time for the same
     * handle, where the identity check makes it a no-op.
     */
    @OptIn(ExperimentalStoreApi::class)
    fun restoreBoundClock(
        store: Store<*>,
        clock: Clock?,
    ) {
        if (store.isDisposed || store.internalBoundClock === clock) return
        runCatching { store.bindClock(clock) }
    }

    /**
     * Manufacture a transaction on [store] and run [body] against it without
     * committing. Backs [com.vynatix.holdfast.testing.transaction] in the public
     * DSL.
     *
     * Lifecycle:
     *  1. Build a top-level [Transaction] via [Transaction.createForExternal],
     *     owned by the calling thread.
     *  2. Under the store's `transactionLock`, install it as the active
     *     transaction. Refuses to clobber an existing in-flight action; an
     *     already-set `activeTransaction` indicates an outer `action` body or
     *     a still-open `transaction(...)` and the test must close that first.
     *  3. Release the lock and run [body] with [Store.selfForExternal] as the
     *     receiver — mutations stage into the new transaction's
     *     `pendingWrites`, identical to the staging that `store.action`'s body
     *     performs.
     *  4. On a body throw: re-acquire the lock, clear the active transaction,
     *     rollback the new transaction so its status flips to RolledBack, drain
     *     the post-commit queue once the lock is released (work such as a
     *     `derived` recompute may have been queued behind the open transaction
     *     — see [commitOpenTransaction]), and propagate. The store is left in a
     *     clean state: no active txn, and the body's writes discarded (a
     *     drained post-commit task such as a `derived` recompute may still
     *     commit its own transaction).
     *
     * No middleware runs — this matches `:holdfast-coroutines.suspendAction`'s
     * v1 contract for externally-manufactured transactions. The recorder
     * therefore does NOT see an open transaction in the timeline; assertions
     * about open transactions must be made via [OpenTransaction] directly.
     *
     * @return the manufactured transaction. Caller is responsible for invoking
     *   [commitOpenTransaction] or [rollbackOpenTransaction] exactly once;
     *   leaking the open transaction past the test scope's `tearDown` triggers
     *   the auto-rollback path.
     */
    fun <V : Store<V>> openTransaction(
        store: V,
        body: V.() -> Unit,
    ): Transaction {
        val txn =
            Transaction.createForExternal(
                id = body::class.simpleName ?: "open-transaction",
                ownerThreadId = currentThreadId(),
            )
        store.runUnderLock {
            check(store.activeTransaction == null) {
                "Cannot open a transaction on a store that already has an active transaction; " +
                    "commit or rollback the in-flight transaction first."
            }
            store.internalSetActiveTransaction(txn)
        }
        try {
            store.selfForExternal.body()
        } catch (e: Throwable) {
            // Body threw — leave the store in a clean state for the next test.
            try {
                store.runUnderLock {
                    if (store.activeTransaction === txn) {
                        store.internalSetActiveTransaction(null)
                    }
                }
                runCatching { txn.rollback() }
            } finally {
                // This exit releases the active-transaction slot too, so it is a
                // hand-off holder like commit/rollbackOpenTransaction: drain the
                // post-commit work queued behind the open transaction
                // (Store.tryTopLevelAction). The drain swallows task failures, so
                // `e` still propagates unchanged.
                store.internalDrainPostCommitTasks()
            }
            throw e
        }
        return txn
    }

    /**
     * Apply [transaction]'s pending writes to [store] under the store's
     * `transactionLock`, fire observers and bridges, then clear the active
     * transaction and — once the lock is released — drain any post-commit
     * tasks.
     *
     * Mirrors the tail of a production `Store.action`: the lock guards
     * against a peer `action` interleaving with the apply-and-fanout, the
     * `internalDrainPostCommitTasks` call lets `derived` recompute fan out
     * exactly like a production action would. The drain runs after the lock
     * is released, like `action`'s: an open transaction occupies the store's
     * active-transaction slot, so a `derived` recompute that found the store
     * busy handed its work to this transaction, and the store relies on every
     * holder draining after it releases (`Store.tryTopLevelAction`).
     *
     * Throws if [Transaction.commit] throws (commit-time `TransactionException`
     * or any state's `applyCommitted` throwing). The caller wraps this in
     * `try/catch` to translate to [com.vynatix.holdfast.TransactionResult.Error].
     */
    fun commitOpenTransaction(
        store: Store<*>,
        transaction: Transaction,
    ) {
        try {
            store.runUnderLock {
                try {
                    transaction.commit()
                } finally {
                    if (store.activeTransaction === transaction) {
                        store.internalSetActiveTransaction(null)
                    }
                }
            }
        } finally {
            store.internalDrainPostCommitTasks()
        }
    }

    /**
     * Rollback [transaction] on [store] under the store's `transactionLock`,
     * then clear the active transaction and drain any post-commit tasks
     * handed to it while it was open (see [commitOpenTransaction]). A drained
     * task such as a `derived` recompute commits as its own transaction, so
     * it runs the store's middleware — including the recorder, when teardown's
     * auto-rollback runs this while the recorder is still installed.
     * Idempotent on a non-Active transaction — matching
     * [Transaction.rollback]'s contract.
     *
     * Used both by the user-facing [com.vynatix.holdfast.testing.concurrency.OpenTransaction.rollback]
     * and by the test-scope `tearDown` auto-rollback path. Catches and
     * swallows rollback exceptions to keep teardown robust under partially
     * failed tests.
     */
    fun rollbackOpenTransaction(
        store: Store<*>,
        transaction: Transaction,
    ) {
        store.runUnderLock {
            runCatching { transaction.rollback() }
            if (store.activeTransaction === transaction) {
                store.internalSetActiveTransaction(null)
            }
        }
        store.internalDrainPostCommitTasks()
    }
}
