package com.vynatix.vault.testing

import com.vynatix.vault.Middleware
import com.vynatix.vault.State
import com.vynatix.vault.Transaction

/**
 * Single event recorded by a [VaultHandle]'s privileged recorder. Sealed root of
 * the timeline element hierarchy — every concrete subclass carries a [timestamp]
 * (epoch milliseconds) so callers can reason about temporal ordering across
 * subsystems (transactions, middleware, bridges).
 *
 * The hierarchy splits four ways:
 *  - [TransactionEvent] — the transaction lifecycle (start/commit/rollback/errored).
 *  - [EmissionEvent] — the post-set value buffered for a given [State] in the
 *    just-committed transaction. Emitted at commit time, one per state in
 *    [Transaction.modifiedStates], in iteration order.
 *  - [MiddlewareEvent] — middleware-level lifecycle for a specific [Middleware]
 *    instance. **In v1 only the recorder itself surfaces events here**; user
 *    middlewares installed via [com.vynatix.vault.Vault.middlewares] are not
 *    automatically wrapped (no public `:vault` API exists for that yet). The
 *    typed views ([VaultHandle.middlewareEventsOf]) will return an empty list
 *    for any user class until that gap is filled.
 *  - [BridgeEvent] — bridge publish/observe lifecycle. Bridges attached to
 *    a state **before** [VaultTestScope.track] are wrapped at install time and
 *    fire events through the recorder; bridges attached afterwards are not
 *    wrapped (no public `:vault` hook for late attachments yet). See
 *    [VaultHandle.bridgeEvents] for the typed view.
 *
 * Events are immutable data classes; the timeline returned by
 * [VaultHandle.timeline] is a defensive copy, so callers can iterate and filter
 * without contention with the recorder.
 */
sealed interface VaultEvent {
    /** Epoch milliseconds at which the event was pushed into the timeline. */
    val timestamp: Long
}

/**
 * Lifecycle event carrying the [Transaction] that produced it. The timeline
 * orders these by push time, which mirrors :vault's own transaction lifecycle:
 * [TransactionStarted] before the action body runs, [EmissionEvent]s and
 * [TransactionCommitted] just before commit applies, [TransactionErrored] /
 * [TransactionRolledBack] when the body throws.
 */
sealed interface TransactionEvent : VaultEvent {
    val transaction: Transaction
}

/** Emitted before the action body runs (from the recorder's `onTransactionStarted`). */
data class TransactionStarted(override val transaction: Transaction, override val timestamp: Long) : TransactionEvent

/**
 * Emitted just before [Transaction.commit] applies the pending writes — i.e.
 * inside the recorder's `onTransactionCompleted`, after the body has returned
 * cleanly. The actual write-apply / observer fanout happens immediately after
 * this event but is fired by :vault's own commit path, not by the recorder.
 */
data class TransactionCommitted(override val transaction: Transaction, override val timestamp: Long) : TransactionEvent

/**
 * Emitted when the body throws and :vault rolls back the transaction. **In v1
 * the recorder synthesises this immediately after [TransactionErrored]** — the
 * middleware contract surfaces the error to the recorder before vault's outer
 * `runCatching { txn.rollback() }`, but the rollback itself runs past the
 * middleware boundary, so the recorder can only infer (not observe) it.
 */
data class TransactionRolledBack(override val transaction: Transaction, override val timestamp: Long) : TransactionEvent

/**
 * Emitted from the recorder's `onTransactionError` hook when the body throws.
 * Carries the exception that propagated out of the body.
 */
data class TransactionErrored(override val transaction: Transaction, val cause: Throwable, override val timestamp: Long) : TransactionEvent

/**
 * Emitted at commit time for each state present in [Transaction.modifiedStates].
 * The [oldValue] is the committed value before the body's pending writes were
 * applied — the recorder captures it inside `onTransactionCompleted` by briefly
 * toggling the active-transaction overlay off via
 * `internalSetActiveTransaction(null)` (see
 * [com.vynatix.vault.testing.internal.PrivilegedHooks.snapshotCommittedStateValues]).
 * [newValue] is the post-`transformer.get` view of the about-to-be-committed
 * pending write (read via [State.value] on the owner thread inside the active
 * transaction, so read-your-own-writes returns the pending value).
 */
data class EmissionEvent(val state: State<*>, val oldValue: Any?, val newValue: Any?, override val timestamp: Long) : VaultEvent

/**
 * Lifecycle event carrying the [Middleware] instance that produced it. See
 * [VaultEvent] for the v1 limitation: only the recorder itself currently
 * surfaces events here.
 */
sealed interface MiddlewareEvent : VaultEvent {
    val middleware: Middleware<*>
    val transaction: Transaction
}

data class MiddlewareStarted(override val middleware: Middleware<*>, override val transaction: Transaction, override val timestamp: Long) :
    MiddlewareEvent

data class MiddlewareCompleted(
    override val middleware: Middleware<*>,
    override val transaction: Transaction,
    override val timestamp: Long,
) : MiddlewareEvent

data class MiddlewareErrored(
    override val middleware: Middleware<*>,
    override val transaction: Transaction,
    val cause: Throwable,
    override val timestamp: Long,
) : MiddlewareEvent

/**
 * Bridge lifecycle event carrying the [State] involved. Bridges attached to a
 * state **before** [VaultTestScope.track] are wrapped at install time
 * ([com.vynatix.vault.testing.internal.RecordingBridgeWrapper]) and produce
 * [BridgePublished] / [BridgeObserved] events through the recorder; bridges
 * attached after `track(v)` are not wrapped, so their interactions are invisible
 * to the timeline. The constraint is forced by `:vault` not exposing a hook for
 * late bridge attachments.
 */
sealed interface BridgeEvent : VaultEvent {
    val state: State<*>
}

data class BridgePublished(override val state: State<*>, val value: Any?, override val timestamp: Long) : BridgeEvent

data class BridgeObserved(override val state: State<*>, val value: Any?, override val timestamp: Long) : BridgeEvent
