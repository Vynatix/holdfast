package com.vynatix.holdfast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Clock

/**
 * One unit of atomicity in a [Store]. A transaction holds:
 *  - an [id] (the action's class simple name, falling back to a random UUID),
 *  - a [parent] reference forming the savepoint chain (null for top-level),
 *  - a buffer of pending writes ([pendingWrites]), thread-confined to [ownerThreadId],
 *  - a status ([TransactionStatus]) advancing Active → Committed/RolledBack/Failed.
 *
 * Top-level transactions apply their pending writes to state on [commit];
 * nested (savepoint) transactions merge their pending writes into the parent's
 * on [commit], and drop them entirely on [rollback].
 *
 * Both [commit] and [rollback] are idempotent on a non-Active transaction
 * (no-op).
 */
class Transaction internal constructor(
    val id: String,
    internal val parent: Transaction?,
    internal val ownerThreadId: Long,
    /**
     * Identity of the cross-store atomic frame this transaction belongs to, or
     * `null` for ordinary single-store transactions. Every participant root
     * (and in-frame savepoint) of one `atomic`/`suspendAtomic` call shares the
     * same value, so middleware and the testing harness can correlate the N
     * per-store transactions of one frame.
     */
    val frameId: String? = null,
) {
    companion object {
        /**
         * Public-but-opt-in factory for `:holdfast-coroutines.suspendAction`. The
         * primary constructor stays `internal` so user code can't manufacture
         * spurious transactions; companion modules that need to construct
         * one (because they implement their own action variant) opt in here.
         */
        @StoreInternalApi
        fun createForExternal(
            id: String,
            ownerThreadId: Long,
            frameId: String? = null,
        ): Transaction = Transaction(id, parent = null, ownerThreadId = ownerThreadId, frameId = frameId)

        /**
         * Public-but-opt-in savepoint factory for `:holdfast-coroutines`
         * (`suspendAtomic` nesting and in-frame `suspendAction`). The savepoint's
         * commit merges into [parent]'s pending buffers; its rollback discards
         * only the savepoint — exactly the nested-`action` contract.
         */
        @StoreInternalApi
        fun createSavepointForExternal(
            id: String,
            ownerThreadId: Long,
            parent: Transaction,
            frameId: String? = null,
        ): Transaction = Transaction(id, parent = parent, ownerThreadId = ownerThreadId, frameId = frameId)
    }

    private val statusLock = StoreLock()
    private val endTimeLock = StoreLock()
    private val pendingLock = StoreLock()

    @kotlin.concurrent.Volatile
    private var _status = TransactionStatus.Active

    /** Current status of the transaction. */
    val status: TransactionStatus
        get() = statusLock.withLock { _status }

    @kotlin.concurrent.Volatile
    private var _endTime: Long? = null

    /**
     * Epoch milliseconds at which this transaction reached a terminal status
     * (Committed/RolledBack/Failed). `null` while the transaction is still Active.
     */
    val endTime: Long?
        get() = endTimeLock.withLock { _endTime }

    /**
     * Per-transaction buffer of writes (state → post-`transformer.set` value).
     * Owner-thread-confined; never read or written from another thread.
     * For nested (savepoint) transactions, [commit] merges this into
     * `parent.pendingWrites`. For top-level transactions, [commit] applies via
     * [MutableState.applyCommitted].
     */
    internal val pendingWrites: MutableMap<MutableState<*>, Any> = mutableMapOf()

    /**
     * Per-transaction event buffer (channel → event) staged via [stagePendingEvent].
     * Owner-thread-confined; ordered by insertion. On top-level [commit], drained
     * AFTER state observers and AFTER bridge publishes — the third and final phase
     * of commit fanout. On nested [commit], merged into the parent's buffer (so the
     * outermost commit fires events from inner savepoints in the order they were
     * staged across the whole transaction tree). On [rollback], discarded.
     *
     * The list elements are `(MutableSharedFlow<*>, Any)` rather than tied to a
     * single typed channel because a store may host multiple [Eventful] surfaces
     * in the future (today: one per store). The unchecked cast on emit is sound:
     * [Eventful.emit]'s signature is `(E)` and the channel is `MutableSharedFlow<E>`.
     */
    internal val pendingEvents: MutableList<Pair<MutableSharedFlow<*>, Any>> = mutableListOf()

    /**
     * Stage a [rawValue] directly as a pending write, bypassing [MutableState.beforeSet].
     * Used by [Store.restore] to round-trip raw stored values (ciphertext,
     * post-`transformer.set` form) without re-running the transformer.
     *
     * For symmetric transformers this is equivalent to a normal mutate; for
     * asymmetric ones (e.g. [com.vynatix.holdfast.crypto.EncryptingTransformer]),
     * the difference is critical — restoring already-encrypted ciphertext via
     * `mutate` would re-encrypt it.
     */
    internal fun stagePendingRaw(
        state: MutableState<*>,
        rawValue: Any,
    ) {
        pendingWrites[state] = rawValue
    }

    /**
     * Stage [event] onto this transaction's [pendingEvents] buffer, to be emitted
     * to [channel] during the commit's event-drain phase. Public-internal because
     * [Eventful.emit] (in `:holdfast` core) needs to call it; user code should not.
     *
     * Owner-thread-confined: callers MUST be the owner of this transaction (the
     * blocking action's caller, or the suspending action's coroutine while the
     * AsyncSerializer holds the lock). Concurrent stages from non-owner threads
     * are undefined — same contract as [pendingWrites].
     */
    @StoreInternalApi
    fun stagePendingEvent(
        channel: MutableSharedFlow<*>,
        event: Any,
    ) {
        pendingLock.withLock {
            pendingEvents += channel to event
        }
    }

    /**
     * Read-only view of the states modified by this transaction (or its
     * not-yet-committed inner savepoints, via the savepoint chain). Owner-thread
     * only; throws [IllegalStateException] from non-owner threads. Useful for
     * audit middleware that wants to log what was touched.
     */
    val modifiedStates: Set<State<*>>
        get() {
            check(
                ownerThreadId ==
                    com.vynatix.holdfast.platform
                        .currentThreadId(),
            ) {
                "modifiedStates may only be read on the transaction's owner thread"
            }
            return pendingLock.withLock { pendingWrites.keys.toSet() }
        }

    /**
     * Walk the savepoint chain (this → parent → … → root) for a pending write.
     * Used by [MutableState.value] when a caller wants in-transaction
     * read-your-own-writes.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> findPendingValue(state: MutableState<T>): T? {
        var current: Transaction? = this
        while (current != null) {
            val pending = current.pendingWrites[state]
            if (pending != null) return pending as T
            current = current.parent
        }
        return null
    }

    /**
     * Idempotent commit. Active → Committed.
     * On a non-Active transaction this is a no-op — preventing a misused commit
     * from corrupting state or replaying side effects.
     *
     * For a nested (savepoint) transaction, pending writes are merged into the
     * parent's. For a top-level transaction, pending writes are applied to state
     * via [MutableState.applyCommitted], which is the single place observers and
     * bridges fire.
     */
    fun commit() {
        @OptIn(StoreInternalApi::class)
        commitDispatching { state, value ->
            @Suppress("UNCHECKED_CAST")
            (state as MutableState<Any>).applyCommitted(value)
        }
    }

    /**
     * Internal commit variant for `:holdfast-coroutines.suspendAction`. Same
     * idempotent semantics as [commit], but the per-pending-write apply step
     * is delegated to [applyTopLevel]. Used to interpose
     * [com.vynatix.holdfast.coroutines.SuspendingBridge.publishAwaited] between
     * the observer fanout (via [MutableState.applyCommittedRaw]) and the
     * bridge publish.
     *
     * For a nested (savepoint) transaction, [applyTopLevel] is NOT called —
     * pending writes merge into the parent's buffer just like [commit].
     *
     * The lambda is invoked synchronously from inside the commit lock for each
     * pending write, in iteration order. Throwing from it propagates as
     * [TransactionException] just like the sync path.
     */
    @StoreInternalApi
    fun commitDispatching(applyTopLevel: (MutableState<*>, Any) -> Unit) {
        commitDispatching(applyTopLevel, drainEvents = null)
    }

    /**
     * Internal commit variant that lets the caller take over the event-drain
     * phase (used by `:holdfast-coroutines.suspendAction` to interpose its
     * suspending bridge publish between observer fanout and the event drain,
     * and to honor SUSPEND back-pressure on the events SharedFlow).
     *
     * If [drainEvents] is null (the default), the event-drain phase calls
     * `MutableSharedFlow.tryEmit` synchronously for each staged event — sync
     * `commit()` semantics.
     *
     * If non-null, [drainEvents] is invoked synchronously with the
     * pre-snapshotted event list AFTER observer fanout and BEFORE the status
     * transition to Committed. The caller may either emit immediately, or simply
     * stash the snapshot to a captured variable and emit later — typical usage in
     * `suspendAction` is to stash the list, complete the bridge-publish phase,
     * and then suspendingly emit the events so back-pressure is honored. The
     * snapshot list is owned by the caller and reflects insertion order.
     */
    @StoreInternalApi
    fun commitDispatching(
        applyTopLevel: (MutableState<*>, Any) -> Unit,
        drainEvents: ((List<Pair<MutableSharedFlow<*>, Any>>) -> Unit)?,
    ) {
        val current = statusLock.withLock { _status }
        if (current != TransactionStatus.Active) return

        // Snapshot of events to drain after fanout, populated only on the
        // top-level branch. Captured outside the pendingLock so the drain (which
        // calls `tryEmit` or a caller-supplied suspending emit) runs without
        // holding any internal store lock.
        val eventsToDrain = mutableListOf<Pair<MutableSharedFlow<*>, Any>>()
        try {
            pendingLock.withLock {
                val parentTxn = parent
                if (parentTxn != null) {
                    // Savepoint commit: merge into parent. Last-write-wins on shared keys —
                    // savepoint mutations override any earlier parent pending for the same state.
                    parentTxn.pendingWrites.putAll(pendingWrites)
                    // Events: append in order. Outer commit fires this savepoint's events
                    // after its own (preserving stage order across the whole tree).
                    parentTxn.pendingEvents.addAll(pendingEvents)
                } else {
                    // Top-level commit: apply each pending write via the caller-supplied
                    // dispatcher. Observers and bridges see the value here, post-commit,
                    // never mid-action. Pending writes remain readable via findPendingValue
                    // during the iteration so observer callbacks reading sibling states
                    // still see the about-to-be-committed values (read-your-own-writes
                    // during fanout).
                    pendingWrites.forEach { (state, value) ->
                        applyTopLevel(state, value)
                    }
                    // Snapshot the events to drain after we release pendingLock. The drain
                    // itself happens outside the lock — `tryEmit` may suspend or invoke
                    // ready collectors synchronously, and we never want to hold an internal
                    // lock across user code.
                    eventsToDrain.addAll(pendingEvents)
                }
                pendingWrites.clear()
                pendingEvents.clear()
            }
            // Phase 3 (top-level only): drain events AFTER observer fanout and AFTER
            // bridge publishes. Order matters: a collector subscribed to both
            // `state.asFlow()` and `store.events` will see the state value before
            // the event. If a caller-supplied [drainEvents] is provided, it owns
            // the emit (typically a suspending emit honoring back-pressure).
            // Otherwise we tryEmit each event — sync `commit()` cannot suspend, so
            // a full SUSPEND-policy buffer falls back to drop here. Suspend-action
            // callers should pass a drainEvents that uses `emit(...)` to honor
            // back-pressure.
            if (eventsToDrain.isNotEmpty()) {
                if (drainEvents != null) {
                    drainEvents(eventsToDrain)
                } else {
                    for ((channel, event) in eventsToDrain) {
                        @Suppress("UNCHECKED_CAST")
                        (channel as MutableSharedFlow<Any>).tryEmit(event)
                    }
                }
            }
            updateStatus(TransactionStatus.Committed)
        } catch (e: Exception) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException("Commit failed", e)
        } finally {
            endTimeLock.withLock {
                _endTime = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    /**
     * Idempotent rollback. Active → RolledBack.
     * On a non-Active transaction this is a no-op.
     *
     * Discards pending writes without touching state, observers, or bridges.
     */
    fun rollback() {
        val current = statusLock.withLock { _status }
        if (current != TransactionStatus.Active) return

        try {
            pendingLock.withLock {
                pendingWrites.clear()
                pendingEvents.clear()
            }
            updateStatus(TransactionStatus.RolledBack)
        } catch (e: Exception) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException("Rollback failed", e)
        } finally {
            endTimeLock.withLock {
                _endTime = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    private fun updateStatus(newStatus: TransactionStatus) {
        statusLock.withLock {
            val oldStatus = _status
            if (!isValidStatusTransition(oldStatus, newStatus)) {
                throw TransactionException("Invalid status transition from $oldStatus to $newStatus")
            }
            _status = newStatus
        }
    }

    private fun isValidStatusTransition(
        from: TransactionStatus,
        to: TransactionStatus,
    ): Boolean =
        when (from) {
            TransactionStatus.Active ->
                to in
                    setOf(
                        TransactionStatus.Committed,
                        TransactionStatus.RolledBack,
                        TransactionStatus.Failed,
                    )
            TransactionStatus.Committed -> false
            TransactionStatus.RolledBack -> false
            TransactionStatus.Failed -> false
        }
}

/** Lifecycle status of a [Transaction]. Active is the only non-terminal state. */
enum class TransactionStatus {
    Active,
    Committed,
    RolledBack,
    Failed,
}

/** Thrown by [Transaction] when commit/rollback fail or status transitions are invalid. */
class TransactionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The outcome of a [Store.action]. Either [Success] (the body returned without
 * throwing and the commit succeeded — carrying the body's computed `value`) or
 * [Error] (the body or commit threw, the transaction is RolledBack).
 *
 * Generic in `R` (the body's return type) and covariant in it, so a
 * `TransactionResult<Int>` is assignable to `TransactionResult<Number>` and to
 * `TransactionResult<Any>`. [Error] does not carry a value and extends
 * `TransactionResult<Nothing>`, making it the bottom type that fits any `R`.
 *
 * Don't fire-and-forget: an [Error] you never look at is a rollback you never
 * hear about. Either branch on the result with `when`, surface failures with
 * [onError], or rethrow them with [getOrThrow].
 */
sealed interface TransactionResult<out R> {
    data class Success<R>(
        val transaction: Transaction,
        val value: R,
    ) : TransactionResult<R>

    data class Error(
        val exception: Throwable,
        val transaction: Transaction,
    ) : TransactionResult<Nothing>

    /**
     * The body's computed value on [Success], or throws the **original**
     * exception (the exact [Error.exception] instance — not a wrapper) on
     * [Error].
     *
     * Use this when a failure should propagate instead of being silently
     * dropped: `store action { … }` returns a result that is easy to ignore,
     * and an ignored [Error] makes the rollback invisible. `getOrThrow()`
     * converts it back into an ordinary thrown exception.
     */
    fun getOrThrow(): R =
        when (this) {
            is Success -> value
            is Error -> throw exception
        }

    /**
     * The body's computed value on [Success], or `null` on [Error].
     *
     * Note that this conflates "the action failed" with "the body returned
     * `null`" — prefer [onError] or [getOrThrow] when the failure itself
     * matters.
     */
    val valueOrNull: R?
        get() =
            when (this) {
                is Success -> value
                is Error -> null
            }
}

/**
 * Runs [block] with the [TransactionResult.Error] if this result is a failure;
 * does nothing on [TransactionResult.Success]. Returns `this` so calls chain
 * with [onSuccess].
 *
 * Prefer this (or [TransactionResult.getOrThrow]) over ignoring the result of
 * a fire-and-forget `store action { … }` — an unobserved [TransactionResult.Error]
 * means the rollback happened silently.
 */
inline fun <R> TransactionResult<R>.onError(block: (TransactionResult.Error) -> Unit): TransactionResult<R> {
    if (this is TransactionResult.Error) block(this)
    return this
}

/**
 * Runs [block] with the committed body value if this result is a
 * [TransactionResult.Success]; does nothing on [TransactionResult.Error].
 * Returns `this` so calls chain with [onError].
 */
inline fun <R> TransactionResult<R>.onSuccess(block: (R) -> Unit): TransactionResult<R> {
    if (this is TransactionResult.Success) block(value)
    return this
}
