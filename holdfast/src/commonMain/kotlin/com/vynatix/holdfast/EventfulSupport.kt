@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Delegate-friendly counterpart to [EventfulStore]. Use when a store must
 * extend a base class other than [EventfulStore] (e.g. a domain-specific
 * abstract base) but still wants the [Eventful] capability:
 *
 * ```
 * class MyStore private constructor(
 *     private val support: EventfulSupport<MyEvent>,
 * ) : SomeDomainBase(), Eventful<MyEvent> by support {
 *     constructor() : this(EventfulSupport())
 *
 *     init {
 *         support.bindStore(this)
 *     }
 * }
 * ```
 *
 * Behaves identically to [EventfulStore] w.r.t. event staging on transactions
 * and the commit-phase ordering contract:
 *
 * 1. State observers fire (post-commit, post-`MutableState.applyCommitted`).
 * 2. Bridges publish (sync `Bridge.publish` — fire-and-forget; or
 *    `SuspendingBridge.publishAwaited` under `suspendAction`).
 * 3. Events drain to [events] in the order they were [emit]-ted.
 *
 * Same back-pressure policy as [EventfulStore]: lossless events via
 * `BufferOverflow.SUSPEND` by default — slow collectors back-pressure the
 * commit thread rather than dropping events. Tune [extraBufferCapacity] for
 * bursty workloads, or switch [onBufferOverflow] only if a class of events
 * is genuinely droppable.
 *
 * ## Store binding
 *
 * Because [EventfulSupport] does not extend [Store], it has no direct view of
 * the active transaction. The hosting store MUST call [bindStore] exactly
 * once during construction (typically in an `init` block). Calling [emit]
 * before [bindStore] throws [IllegalStateException]. Calling [bindStore]
 * twice on the same instance also throws — one support per store.
 *
 * @param extraBufferCapacity Buffer slots beyond `replay = 0` available before
 *   the producer suspends. Default 16; tune up if commits emit bursts of
 *   events and downstream collectors are bursty too.
 * @param onBufferOverflow What to do when the buffer is full. Default
 *   [BufferOverflow.SUSPEND] (lossless). Use [BufferOverflow.DROP_OLDEST] /
 *   [BufferOverflow.DROP_LATEST] only if a class of events is genuinely
 *   droppable.
 */
class EventfulSupport<E : Any>(
    extraBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
) : Eventful<E> {
    private val _events: MutableSharedFlow<E> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = extraBufferCapacity,
            onBufferOverflow = onBufferOverflow,
        )

    /**
     * The hot [SharedFlow] of events emitted via this support. Subscribers
     * see only events emitted after they subscribe (no replay).
     */
    override val events: SharedFlow<E> = _events.asSharedFlow()

    @kotlin.concurrent.Volatile
    private var boundStore: Store<*>? = null

    /**
     * Wire this support to its hosting [Store] so [emit] can locate the
     * active transaction. Call exactly once, typically in the hosting class's
     * `init` block. Subsequent calls throw [IllegalStateException].
     *
     * The reference is held weakly only by usage convention — the hosting
     * store's lifetime is expected to dominate this support's. The store
     * keeps a reference to `this` via the supertype delegation, so this is
     * not a circular leak: when the store is unreachable, both objects
     * collect together.
     */
    fun bindStore(store: Store<*>) {
        check(boundStore == null) {
            "EventfulSupport.bindStore must be called at most once per instance"
        }
        boundStore = store
    }

    /** Deprecated alias for [bindStore], kept for one minor release. */
    @Deprecated(
        message = "Renamed to bindStore.",
        replaceWith = ReplaceWith("bindStore(store)"),
        level = DeprecationLevel.WARNING,
    )
    fun bindVault(store: Store<*>) = bindStore(store)

    /**
     * Stage [event] onto the bound store's active transaction's pendingEvents
     * buffer. On commit, drained AFTER state observers and AFTER bridge
     * publishes — same commit-phase contract as [EventfulStore].
     *
     * Throws [IllegalStateException] if [bindStore] has not been called or if
     * called outside an `action` / `suspendAction`. Events MUST be
     * transactional so rollback can discard them.
     */
    override fun emit(event: E) {
        val store =
            boundStore ?: error(
                "EventfulSupport.emit called before bindStore. The hosting Store must " +
                    "call support.bindStore(this) in its init block.",
            )
        val txn =
            store.activeTransaction ?: error(
                "emit(event) called outside of an action / suspendAction. " +
                    "Events must be staged inside a transaction so rollback can discard them.",
            )
        txn.stagePendingEvent(_events, event)
    }

    private companion object {
        /** Default `extraBufferCapacity` for the events SharedFlow. Per design §3.6. */
        private const val DEFAULT_EVENT_BUFFER_CAPACITY = 16
    }
}
