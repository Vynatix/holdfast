package com.vynatix.holdfast

import kotlinx.coroutines.flow.SharedFlow

/**
 * Capability marker for vaults that emit one-shot, lossless events on commit.
 *
 * Distinct from `State<T>` (which is "current value, recoverable, conflated under
 * pressure"), an event is "this happened, never to be replayed; if you missed it,
 * it's gone." The two carry opposite back-pressure policies:
 *  - `State.asFlow()` uses `DROP_OLDEST` — fast emit + slow collect drops intermediates,
 *    but the latest value is always recoverable.
 *  - `Eventful.events` uses `SUSPEND` — fast emit + slow collect back-pressures the
 *    emitter (the commit thread), so no event is dropped.
 *
 * Pick the right tool: state for "what is now," events for "what happened."
 *
 * Implementations:
 *  - [EventfulHoldfast] — base class wiring an internal `MutableSharedFlow<E>` and the
 *    [emit] DSL that stages onto the active transaction's pendingEvents buffer.
 *  - Issue 15 will add `EventfulSupport<E>` — a delegate so a vault can mix this
 *    capability in alongside another base class.
 *
 * The single event type per vault is intentional. Multiple kinds of events are
 * expressed via a `sealed class E` hierarchy in user code.
 */
interface Eventful<E : Any> {
    /**
     * Hot [SharedFlow] of events. Subscribers see only events emitted AFTER they
     * subscribe (replay = 0). Slow subscribers back-pressure the emitter — the
     * commit thread suspends until the subscriber catches up — so events are
     * never silently dropped (the opposite policy of `State.asFlow()`).
     */
    val events: SharedFlow<E>

    /**
     * Stage [event] onto the active transaction's pendingEvents buffer. On commit,
     * after observer fanout and after bridge publish, the buffered events are
     * tryEmit-ted to [events] in the order they were staged.
     *
     * Throws [IllegalStateException] if called outside of [Holdfast.action] /
     * `vault-coroutines.suspendAction`. Events MUST be transactional — emitting
     * outside a transaction would bypass the rollback-discards-events guarantee.
     */
    fun emit(event: E)
}
