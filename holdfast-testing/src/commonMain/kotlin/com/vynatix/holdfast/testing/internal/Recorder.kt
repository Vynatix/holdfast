@file:OptIn(HoldfastInternalApi::class)

package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.HoldfastInternalApi
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.EmissionEvent
import com.vynatix.holdfast.testing.MiddlewareCompleted
import com.vynatix.holdfast.testing.MiddlewareErrored
import com.vynatix.holdfast.testing.MiddlewareStarted
import com.vynatix.holdfast.testing.TransactionCommitted
import com.vynatix.holdfast.testing.TransactionErrored
import com.vynatix.holdfast.testing.TransactionRolledBack
import com.vynatix.holdfast.testing.TransactionStarted
import com.vynatix.holdfast.testing.HoldfastEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.SendChannel
import kotlin.time.Clock

/**
 * Privileged middleware that captures the timeline for a single tracked vault.
 *
 * Hook strategy (chosen, with one synthetic event to fill an unobservable gap):
 *
 *  1. **TransactionStarted** + **MiddlewareStarted (self)** — pushed from
 *     `onTransactionStarted`, before the body runs.
 *  2. **EmissionEvent** — pushed from `onTransactionCompleted`, one per state
 *     in [com.vynatix.holdfast.Transaction.modifiedStates]. The hook runs after
 *     the body returns successfully and BEFORE :holdfast calls `txn.commit()`,
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
 *     see that boundary from within :holdfast's existing API. The event is
 *     recorded "about-to-commit" rather than "post-commit"; in practice the
 *     order in [com.vynatix.holdfast.testing.HoldfastHandle.timeline] is identical
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
 *    [com.vynatix.holdfast.Transaction.commit] raising a
 *    [com.vynatix.holdfast.TransactionException]) are not captured — the recorder
 *    has already pushed [TransactionCommitted] and unwound. The action returns
 *    [TransactionResult.Error] but the timeline reflects the body-success
 *    ordering.
 *  - **User-installed middlewares** are NOT wrapped (no public :holdfast hook
 *    exists for replacing entries in the middleware list, and `Middleware.invoke`
 *    is `final`). Their lifecycle events are therefore absent from the timeline.
 *    The recorder pushes self-events for itself so [com.vynatix.holdfast.testing.HoldfastHandle.middlewareEventsOf]
 *    is non-empty, but for any user-class M the typed view returns empty.
 *  - **suspendAction** does not run the middleware chain at all (see
 *    `:holdfast-coroutines.SuspendAction` — middlewares are documented as "NOT
 *    invoked for 1.1"). The recorder therefore sees no events for suspending
 *    actions. This matches the production contract.
 *
 * The buffer is guarded by an [atomicfu][SynchronizedObject] lock so concurrent
 * `parallel { … }` tests don't corrupt it. The recorder only writes to the
 * buffer on the owner thread of the in-flight transaction, but reads (via
 * [snapshot]) can happen from any thread.
 */
internal class Recorder<V : Holdfast<V>>(private val capture: Capture) : Middleware<V>() {

    private val lock = SynchronizedObject()
    private val events: MutableList<HoldfastEvent> = mutableListOf()

    /**
     * Live subscribers receiving every freshly-pushed [HoldfastEvent] via
     * [SendChannel.trySend]. Populated by [snapshotAndSubscribe] (used by the
     * `awaiting { ... }` primitive) and cleared via [unsubscribe]. Guarded by
     * the same [lock] as [events] so the replay-then-subscribe sequence in
     * [snapshotAndSubscribe] is atomic with respect to concurrent [push] calls
     * — no event can land between the snapshot copy and the subscribe.
     */
    private val subscribers: MutableList<SendChannel<HoldfastEvent>> = mutableListOf()

    /** Most recent transaction this recorder observed, regardless of outcome. */
    @kotlin.concurrent.Volatile
    private var _lastTransaction: com.vynatix.holdfast.Transaction? = null
    val lastTransaction: com.vynatix.holdfast.Transaction? get() = _lastTransaction

    /**
     * Most recent [TransactionResult] this recorder observed. Set by
     * [recordResult], called from the [com.vynatix.holdfast.testing.HoldfastHandle]'s
     * action passthrough so the recorder sees the final result (post-commit
     * Success or post-rollback Error).
     */
    @kotlin.concurrent.Volatile
    private var _lastResult: TransactionResult<*>? = null
    val lastResult: TransactionResult<*>? get() = _lastResult

    /** Defensive copy; safe to iterate after return. */
    fun snapshot(): List<HoldfastEvent> = synchronized(lock) { events.toList() }

    /**
     * Append [event] to the buffer, applying [Capture]'s policy. [Capture.None]
     * is short-circuited; [Capture.RingBuffer] truncates from the front so the
     * stored window is the most-recent N. [Capture.All] grows unbounded.
     *
     * After buffering, fans out to every live subscriber via
     * [SendChannel.trySend]. The fan-out runs under [lock] so a concurrent
     * [snapshotAndSubscribe] cannot insert a new subscriber after a buffer
     * append but before its trySend — the subscriber sees either both the
     * replay (containing this event) or the channel send, never neither.
     * trySend is non-blocking: subscribers use [kotlinx.coroutines.channels.Channel.UNLIMITED]
     * capacity so the call should always succeed; if a subscriber's channel is
     * already closed (e.g. an `awaiting` call is mid-cleanup) the failure is
     * silently swallowed by trySend.
     */
    fun push(event: HoldfastEvent) {
        if (capture is Capture.None) return
        synchronized(lock) {
            events.add(event)
            if (capture is Capture.RingBuffer) {
                while (events.size > capture.size) {
                    events.removeAt(0)
                }
            }
            for (subscriber in subscribers) {
                subscriber.trySend(event)
            }
        }
    }

    /**
     * Atomically copy the current [events] buffer into [out] and register
     * [channel] as a live subscriber. Both steps run under the same [lock] so
     * a concurrent [push] cannot land between the snapshot copy and the
     * subscribe — the caller (the `awaiting` primitive) sees the boundary as
     * a clean cut, with each event delivered exactly once via either the
     * replay list or the channel.
     */
    fun snapshotAndSubscribe(channel: SendChannel<HoldfastEvent>, out: MutableList<HoldfastEvent>) {
        synchronized(lock) {
            out.addAll(events)
            subscribers.add(channel)
        }
    }

    /**
     * Remove [channel] from the subscriber list. Safe to call on a channel
     * that was never subscribed (a no-op then) and on one that is already
     * closed — the recorder does not own the channel's lifecycle.
     */
    fun unsubscribe(channel: SendChannel<HoldfastEvent>) {
        synchronized(lock) {
            subscribers.removeAll { it === channel }
        }
    }

    fun recordResult(result: TransactionResult<*>) {
        _lastResult = result
    }

    /**
     * Drop every recorded event and clear bookkeeping. Called from the test
     * scope's tearDown so a leaked handle reference doesn't keep events alive.
     * Does NOT detach this recorder from the vault's middleware list — that
     * happens via [com.vynatix.holdfast.Holdfast.clearMiddleware] in the same
     * tearDown path.
     */
    fun dispose() {
        synchronized(lock) {
            events.clear()
            // Drop subscriber refs but do NOT close the channels — `awaiting`
            // owns its channel lifecycle (close happens in its own
            // try/finally). The scope-level `AwaitingRegistry.cancelAll` runs
            // before recorder dispose and is responsible for closing them.
            subscribers.clear()
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
