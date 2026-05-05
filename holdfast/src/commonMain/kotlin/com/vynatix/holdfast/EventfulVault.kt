@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [Store] base class with a built-in [Eventful] surface. Subclass with a sealed
 * event hierarchy:
 *
 * ```
 * sealed class CounterEvent {
 *     object Saved : CounterEvent()
 *     data class Failed(val cause: Throwable) : CounterEvent()
 * }
 *
 * class CounterVault : EventfulStore<CounterVault, CounterEvent>() {
 *     val count by state { 0 }
 *
 *     fun increment() = action {
 *         count update { it + 1 }
 *         emit(CounterEvent.Saved)
 *     }
 * }
 * ```
 *
 * Commit-phase ordering is the load-bearing contract:
 * 1. State observers fire (post-commit, post-`MutableState.applyCommitted`).
 * 2. Bridges publish (sync `Bridge.publish` — fire-and-forget; or
 *    `SuspendingBridge.publishAwaited` under `suspendAction`).
 * 3. Events drain to [events] in the order they were [emit]-ted.
 *
 * A collector subscribed to BOTH `state.asFlow()` and `events` always observes
 * the state value before the event. This is the master verticality test of
 * issue 14.
 *
 * Lossless event delivery: the underlying [MutableSharedFlow] uses
 * `replay = 0`, [extraBufferCapacity] (default 16), and [onBufferOverflow]
 * (default [BufferOverflow.SUSPEND]). Slow collectors back-pressure the emitter
 * — events are unrecoverable, so the producer must wait rather than drop. This
 * is the OPPOSITE policy of `State.asFlow()` (which uses `DROP_OLDEST`):
 * state is recoverable, events are not.
 *
 * Off-action [emit] throws [IllegalStateException] — events MUST be
 * transactional so rollback discards them.
 *
 * @param extraBufferCapacity Buffer slots beyond `replay = 0` available before
 *   the producer suspends. Default 16; tune up if commits emit bursts of events
 *   and downstream collectors are bursty too.
 * @param onBufferOverflow What to do when the buffer is full. Default
 *   [BufferOverflow.SUSPEND] (lossless — producer waits). Use
 *   [BufferOverflow.DROP_OLDEST] / [BufferOverflow.DROP_LATEST] only if you've
 *   decided a class of events is genuinely droppable.
 */
abstract class EventfulStore<Self : EventfulStore<Self, E>, E : Any>(
    extraBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
) : Store<Self>(),
    Eventful<E> {

    private val _events: MutableSharedFlow<E> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = onBufferOverflow,
    )

    /**
     * The hot [SharedFlow] of events emitted by this store. Subscribers see only
     * events emitted after they subscribe (no replay).
     *
     * Observable from outside the store. Internal staging uses [_events]
     * directly so [emit] doesn't pay an `asSharedFlow()` indirection cost on the
     * hot path.
     */
    final override val events: SharedFlow<E> = _events.asSharedFlow()

    /**
     * Stage [event] onto the active transaction's pendingEvents buffer. On
     * commit, drained AFTER state observers and AFTER bridge publishes — see
     * the commit-phase ordering contract on the class KDoc.
     *
     * Throws [IllegalStateException] if called outside `action` /
     * `suspendAction`. Events MUST be transactional: a non-transactional emit
     * could not honor the rollback-discards-events guarantee.
     */
    final override fun emit(event: E) {
        val txn = activeTransaction ?: error(
            "emit(event) called outside of an action / suspendAction. " +
                "Events must be staged inside a transaction so rollback can discard them.",
        )
        txn.stagePendingEvent(_events, event)
    }

    /**
     * On dispose, clear any replay slot the events SharedFlow may hold. We use
     * `replay = 0` by default so this is normally a no-op, but a subclass that
     * configures `replay > 0` would otherwise leave stale events visible to
     * late subscribers after the store is gone. After this returns, no further
     * emits land — every entrypoint that could call [emit] (action / mutate /
     * suspendAction) is already gated by the disposed check on [Store].
     */
    override fun onDispose() {
        super.onDispose()
        _events.resetReplayCache()
    }

    private companion object {
        /** Default `extraBufferCapacity` for the events SharedFlow. Per design §3.6. */
        private const val DEFAULT_EVENT_BUFFER_CAPACITY = 16
    }
}
