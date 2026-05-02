@file:OptIn(VaultInternalApi::class)

package com.vynatix.vault.testing.internal

import com.vynatix.vault.Middleware
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.TransactionStatus
import com.vynatix.vault.Vault
import com.vynatix.vault.VaultInternalApi
import com.vynatix.vault.testing.Capture
import com.vynatix.vault.testing.EmissionEvent
import com.vynatix.vault.testing.MiddlewareCompleted
import com.vynatix.vault.testing.MiddlewareErrored
import com.vynatix.vault.testing.MiddlewareStarted
import com.vynatix.vault.testing.TransactionCommitted
import com.vynatix.vault.testing.TransactionErrored
import com.vynatix.vault.testing.TransactionRolledBack
import com.vynatix.vault.testing.TransactionStarted
import com.vynatix.vault.testing.VaultEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * Privileged middleware that captures the timeline for a single tracked vault.
 *
 * Hook strategy (chosen, with one synthetic event to fill an unobservable gap):
 *
 *  1. **TransactionStarted** + **MiddlewareStarted (self)** — pushed from
 *     `onTransactionStarted`, before the body runs.
 *  2. **EmissionEvent** — pushed from `onTransactionCompleted`, one per state
 *     in [com.vynatix.vault.Transaction.modifiedStates]. The hook runs after
 *     the body returns successfully and BEFORE :vault calls `txn.commit()`,
 *     so `state.value` (read-your-own-writes overlay) returns the pending
 *     post-set value. To compute `oldValue` (the COMMITTED view, pre-action)
 *     we use [PrivilegedHooks.snapshotCommittedStateValues], which briefly
 *     toggles `vault.internalSetActiveTransaction(null)` so the
 *     read-your-own-writes overlay is bypassed and `state.value` returns
 *     `currentValue`. The middleware holds the vault's transaction lock for
 *     the duration of the chain, so no peer can observe the toggle.
 *  3. **TransactionCommitted** + **MiddlewareCompleted (self)** — pushed at
 *     the tail of `onTransactionCompleted`. The actual `txn.commit()` call
 *     (which applies pending writes and fires observers/bridges) runs
 *     immediately after the middleware chain unwinds, but the recorder cannot
 *     see that boundary from within :vault's existing API. The event is
 *     recorded "about-to-commit" rather than "post-commit"; in practice the
 *     order in [com.vynatix.vault.testing.VaultHandle.timeline] is identical
 *     because no observable change happens between the two points.
 *  4. **TransactionErrored** + **MiddlewareErrored (self)** + **synthetic
 *     TransactionRolledBack** — pushed from `onTransactionError`. The
 *     middleware contract guarantees vault will re-throw and call
 *     `txn.rollback()` in the outer `runCatching`. We synthesise
 *     [TransactionRolledBack] here because we cannot observe the actual
 *     rollback boundary (it lives past the middleware return).
 *
 * Limitations of this v1 strategy (escalated to the issue tracker for v2):
 *  - **Commit-time errors** that happen AFTER the body returns (e.g.
 *    [com.vynatix.vault.Transaction.commit] raising a
 *    [com.vynatix.vault.TransactionException]) are not captured — the recorder
 *    has already pushed [TransactionCommitted] and unwound. The action returns
 *    [TransactionResult.Error] but the timeline reflects the body-success
 *    ordering.
 *  - **User-installed middlewares** are NOT wrapped (no public :vault hook
 *    exists for replacing entries in the middleware list, and `Middleware.invoke`
 *    is `final`). Their lifecycle events are therefore absent from the timeline.
 *    The recorder pushes self-events for itself so [com.vynatix.vault.testing.VaultHandle.middlewareEventsOf]
 *    is non-empty, but for any user-class M the typed view returns empty.
 *  - **suspendAction** does not run the middleware chain at all (see
 *    `:vault-coroutines.SuspendAction` — middlewares are documented as "NOT
 *    invoked for 1.1"). The recorder therefore sees no events for suspending
 *    actions. This matches the production contract.
 *
 * The buffer is guarded by an [atomicfu][SynchronizedObject] lock so concurrent
 * `parallel { … }` tests don't corrupt it. The recorder only writes to the
 * buffer on the owner thread of the in-flight transaction, but reads (via
 * [snapshot]) can happen from any thread.
 */
internal class Recorder<V : Vault<V>>(private val capture: Capture) : Middleware<V>() {

    private val lock = SynchronizedObject()
    private val events: MutableList<VaultEvent> = mutableListOf()

    /** Most recent transaction this recorder observed, regardless of outcome. */
    @kotlin.concurrent.Volatile
    private var _lastTransaction: com.vynatix.vault.Transaction? = null
    val lastTransaction: com.vynatix.vault.Transaction? get() = _lastTransaction

    /**
     * Most recent [TransactionResult] this recorder observed. Set by
     * [recordResult], called from the [com.vynatix.vault.testing.VaultHandle]'s
     * action passthrough so the recorder sees the final result (post-commit
     * Success or post-rollback Error).
     */
    @kotlin.concurrent.Volatile
    private var _lastResult: TransactionResult<*>? = null
    val lastResult: TransactionResult<*>? get() = _lastResult

    /** Defensive copy; safe to iterate after return. */
    fun snapshot(): List<VaultEvent> = synchronized(lock) { events.toList() }

    /**
     * Append [event] to the buffer, applying [Capture]'s policy. [Capture.None]
     * is short-circuited; [Capture.RingBuffer] truncates from the front so the
     * stored window is the most-recent N. [Capture.All] grows unbounded.
     */
    fun push(event: VaultEvent) {
        if (capture is Capture.None) return
        synchronized(lock) {
            events.add(event)
            if (capture is Capture.RingBuffer) {
                while (events.size > capture.size) {
                    events.removeAt(0)
                }
            }
        }
    }

    fun recordResult(result: TransactionResult<*>) {
        _lastResult = result
    }

    /**
     * Drop every recorded event and clear bookkeeping. Called from the test
     * scope's tearDown so a leaked handle reference doesn't keep events alive.
     * Does NOT detach this recorder from the vault's middleware list — that
     * happens via [com.vynatix.vault.Vault.clearMiddleware] in the same
     * tearDown path.
     */
    fun dispose() {
        synchronized(lock) {
            events.clear()
        }
        _lastTransaction = null
        _lastResult = null
    }

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        val txn = context.transaction
        _lastTransaction = txn
        if (capture is Capture.None) return

        val now = nowMillis()
        push(TransactionStarted(transaction = txn, timestamp = now))
        push(MiddlewareStarted(middleware = this, transaction = txn, timestamp = now))
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        val txn = context.transaction
        if (capture is Capture.None) return

        val now = nowMillis()

        // Snapshot of committed values — bypasses the active transaction's
        // read-your-own-writes overlay so we see the pre-action state. The
        // middleware holds the vault's transactionLock, so the brief
        // internalSetActiveTransaction(null) toggle inside the helper is safe
        // from concurrent observation.
        val committedSnapshot = PrivilegedHooks.snapshotCommittedStateValues(context.vault)

        // Iterate modifiedStates (owner-thread-only). For each, read the
        // post-set value via the public State.value getter — read-your-own-writes
        // is in effect, so this returns the about-to-be-committed pending value.
        for (state in PrivilegedHooks.modifiedStates(txn)) {
            val newValue: Any = state.value
            val oldValue = committedSnapshot[state]
            push(EmissionEvent(state = state, oldValue = oldValue, newValue = newValue, timestamp = now))
        }
        push(TransactionCommitted(transaction = txn, timestamp = now))
        push(MiddlewareCompleted(middleware = this, transaction = txn, timestamp = now))
    }

    override fun onTransactionError(context: MiddlewareContext<V>, error: Throwable) {
        val txn = context.transaction
        if (capture is Capture.None) return

        val now = nowMillis()
        push(TransactionErrored(transaction = txn, cause = error, timestamp = now))
        push(MiddlewareErrored(middleware = this, transaction = txn, cause = error, timestamp = now))

        // Synthesise rollback: vault will call `txn.rollback()` in the outer
        // catch right after the middleware chain unwinds. We can't observe that
        // boundary, but the rollback IS guaranteed to happen for a body-throw —
        // status will move Active → RolledBack.
        if (txn.status == TransactionStatus.Active || txn.status == TransactionStatus.RolledBack) {
            push(TransactionRolledBack(transaction = txn, timestamp = now))
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
