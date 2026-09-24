package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * One unit of atomicity in a [Store]. A transaction holds:
 *  - an [id] (the action's class simple name, falling back to a random UUID),
 *  - a [parent] reference forming the savepoint chain (null for top-level),
 *  - a buffer of pending writes ([pendingWrites]), staged by [ownerThreadId]
 *    (by any thread while a suspending action holds the store) under a lock,
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
         *
         * @throws IllegalStateException if [parent] is [closedToWrites] — its
         *   root has already applied its writes (it is fanning out, or
         *   finished), or it or an ancestor has already ended: the savepoint's
         *   commit would merge into a buffer that is never applied again.
         */
        @StoreInternalApi
        fun createSavepointForExternal(
            id: String,
            ownerThreadId: Long,
            parent: Transaction,
            frameId: String? = null,
        ): Transaction {
            check(!parent.closedToWrites) { appliedTransactionMessage("open a savepoint", storeName = null, parent) }
            return Transaction(id, parent = parent, ownerThreadId = ownerThreadId, frameId = frameId)
        }
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
     * Every write into it goes through [pendingLock] ([stagePendingWrite],
     * [stagePendingRaw], a savepoint's merge), as does the commit's apply pass
     * and rollback's discard, so a write either lands before the buffer is
     * consumed or is refused: while a `suspendAction`/`suspendAtomic` holds the
     * store, a bare `mutate` from another thread stages here too (see
     * `Store.stagesInto`). Reads ([findPendingValue]) stay unlocked, on the
     * owner's read-your-own-writes path.
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
     * Set once a top-level [commit] has applied every pending write to state
     * (the end of pass 1 in [commitDispatching]), and never cleared. From then
     * on the transaction only fans out, then ends: anything staged into it —
     * or into a savepoint of it — would never be applied, so every staging
     * path refuses it (see [closedToWrites]).
     */
    @kotlin.concurrent.Volatile
    internal var applied: Boolean = false

    /**
     * The thread running this transaction's commit fanout right now — observer
     * callbacks, bridge publishes and the synchronous event drain — or
     * [NOT_FANNING_OUT]. Set right after the apply pass and cleared when
     * [commitDispatching] returns, so it names a thread only while that thread
     * is inside the fanout: a pooled thread that runs something else
     * afterwards never matches.
     *
     * It is how the store tells an observer reacting to this commit, which must
     * not nest into it, from another thread, whose `action`/`atomic` just waits
     * its turn — also under `suspendAction`, whose [ownerThreadId] is the thread
     * it started on, not the one it commits on. It covers only the synchronous
     * [commitDispatching] window: the bridge publishes and event emits that a
     * suspending commit runs afterwards are recognised by `FanoutMarkers`
     * instead, which follows the committing coroutine. (A bare `mutate` from
     * another thread is refused anyway while a suspending commit holds the
     * store: `suspendingOwner` makes every thread stage into its transaction.)
     * On wasmJs every caller has thread id `0`; on that single thread, whatever
     * runs while this is set is inside the fanout.
     */
    @kotlin.concurrent.Volatile
    internal var fanoutThreadId: Long = NOT_FANNING_OUT

    /**
     * Set under [pendingLock] when this transaction's buffers are consumed for
     * good: applied (top-level commit), merged into the parent (savepoint
     * commit) or discarded (rollback). Checked under the same lock by every
     * staging path, so a write or event either makes it into the buffers before
     * that or is refused — never dropped in between. [status] turns terminal
     * only later, after the lock is released.
     */
    @kotlin.concurrent.Volatile
    private var buffersClosed: Boolean = false

    /**
     * Whether this transaction's root has already applied its writes: [applied]
     * of this transaction when it is top-level, of its root when it is a
     * savepoint.
     */
    internal val rootApplied: Boolean
        get() = root.applied

    /**
     * Whether anything staged into this transaction now would be lost: its root
     * has applied its writes ([rootApplied]), or it or an ancestor has already
     * ended — for example an `atomic` participant's SAVEPOINT entry, which
     * commits into the enclosing transaction and stays installed while later
     * participants fan out (or is rolled back while the frame's error hooks
     * run). `mutate`, `emit`, nested `action`/`atomic` and the savepoint
     * factories refuse such a transaction instead of losing the write silently.
     */
    internal val closedToWrites: Boolean
        get() {
            var t = this
            while (true) {
                if (t.buffersClosed || t._status != TransactionStatus.Active) return true
                t = t.parent ?: return false
            }
        }

    /**
     * The transaction — this one or an ancestor — whose rollback (or failure)
     * closed this one to writes before its root applied anything, or `null`
     * when it is open or closed because the root applied. Picks the wording of
     * the refusal message.
     */
    internal val rolledBackIn: Transaction?
        get() {
            if (rootApplied) return null
            var t: Transaction? = this
            while (t != null) {
                val s = t._status
                if (s == TransactionStatus.RolledBack || s == TransactionStatus.Failed) return t
                t = t.parent
            }
            return null
        }

    /** The top of this transaction's savepoint chain: itself when it is top-level. */
    internal val root: Transaction
        get() {
            var top = this
            while (true) top = top.parent ?: return top
        }

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
        pendingLock.withLock { pendingWrites[state] = rawValue }
    }

    /**
     * Stage [raw], a post-`Transformer.set` value, as [state]'s pending write —
     * unless this transaction is [closedToWrites], in which case nothing is
     * staged and this returns `false`. Checked and staged under [pendingLock],
     * which the commit's apply pass (or merge) and rollback hold while they
     * consume the buffer and close it: a write from another thread racing the
     * commit (possible while a suspending transaction holds the store) is
     * either applied with it or refused, never lost in between. The caller
     * runs `Transformer.set` (user code) before, outside any internal lock.
     */
    internal fun stagePendingWrite(
        state: MutableState<*>,
        raw: Any,
    ): Boolean =
        pendingLock.withLock {
            if (closedToWrites) return@withLock false
            pendingWrites[state] = raw
            true
        }

    /**
     * Stage [event] onto this transaction's [pendingEvents] buffer, to be emitted
     * to [channel] during the commit's event-drain phase. Public-internal because
     * [Eventful.emit] (in `:holdfast` core) needs to call it; user code should not.
     *
     * Callers should be the owner of this transaction (the blocking action's
     * caller, or the suspending action's coroutine while the AsyncSerializer
     * holds the lock). The stage itself runs under [pendingLock], like every
     * write into [pendingWrites], so it cannot tear the commit that consumes it.
     *
     * @throws IllegalStateException if this transaction is [closedToWrites] —
     *   e.g. an observer emitting during the commit fanout, after the root has
     *   applied its writes. The event would never be drained.
     */
    @StoreInternalApi
    fun stagePendingEvent(
        channel: MutableSharedFlow<*>,
        event: Any,
    ) {
        pendingLock.withLock {
            // Under pendingLock, which the apply pass (or merge, or rollback)
            // holds while it consumes the events and closes the buffers: an
            // event either makes that snapshot or is refused here, never
            // dropped in between.
            check(!closedToWrites) { appliedTransactionMessage("emit an event", storeName = null, this) }
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
     * parent's. For a top-level transaction every pending write is applied via
     * [MutableState.applyCommittedValue] first, and only then does fanout run:
     * all observers ([MutableState.fanOutToObservers]), then all bridge
     * publishes ([MutableState.publishToBridge]).
     */
    fun commit() {
        @OptIn(StoreInternalApi::class)
        commitDispatching { committed ->
            @Suppress("UNCHECKED_CAST")
            committed.forEach { (state, value) -> (state as MutableState<Any>).fanOutToObservers(value) }
            @Suppress("UNCHECKED_CAST")
            committed.forEach { (state, value) -> (state as MutableState<Any>).publishToBridge(value) }
        }
    }

    /**
     * Internal commit variant for `:holdfast-coroutines.suspendAction`. Same
     * idempotent semantics as [commit], but the fanout phase is delegated to
     * [fanout] so the suspending path can interpose
     * [com.vynatix.holdfast.coroutines.SuspendingBridge.publishAwaited] between
     * the observer fanout and the bridge publish.
     *
     * For a nested (savepoint) transaction, [fanout] is NOT called — pending
     * writes merge into the parent's buffer just like [commit].
     */
    @StoreInternalApi
    fun commitDispatching(fanout: (List<Pair<MutableState<*>, Any>>) -> Unit) {
        commitDispatching(fanout, drainEvents = null)
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
     *
     * [fanout] receives, in pending-write order, every (state, raw value) pair
     * whose value actually changed — deduped `distinct` states are omitted, so
     * the caller must not fan out for them. It is called ONCE, after every
     * pending write has already been applied to state and outside the pending
     * lock, so a callback that throws can no longer leave the transaction
     * half-applied.
     *
     * From the end of the apply pass (or a savepoint's merge) the transaction
     * is [closedToWrites]: a write, event or savepoint that an observer (or any
     * other code) tries to stage into it is refused loudly rather than dropped,
     * and while [fanout] and the event drain run, [fanoutThreadId] names the
     * calling thread.
     */
    @StoreInternalApi
    fun commitDispatching(
        fanout: (List<Pair<MutableState<*>, Any>>) -> Unit,
        drainEvents: ((List<Pair<MutableSharedFlow<*>, Any>>) -> Unit)?,
    ) {
        val current = statusLock.withLock { _status }
        if (current != TransactionStatus.Active) return

        // Snapshot of events to drain after fanout, populated only on the
        // top-level branch. Captured outside the pendingLock so the drain (which
        // calls `tryEmit` or a caller-supplied suspending emit) runs without
        // holding any internal store lock.
        val eventsToDrain = mutableListOf<Pair<MutableSharedFlow<*>, Any>>()
        // Writes that actually changed a state, in pending-write order. Handed to
        // [fanout] after every write has been applied.
        val committed = mutableListOf<Pair<MutableState<*>, Any>>()
        // Owning store captured before pendingWrites is cleared, so a failure
        // message can name it. Every write in one transaction shares a store.
        val storeLabel = pendingLock.withLock { pendingWrites.keys.firstOrNull() }?.describeOwner()
        var phase = CommitPhase.APPLY
        try {
            pendingLock.withLock {
                val parentTxn = parent
                if (parentTxn != null) {
                    // Savepoint commit: merge into parent, under the parent's
                    // pendingLock too (child before parent, the only nesting
                    // order), since a foreign write may be staging there.
                    // Last-write-wins on shared keys — savepoint mutations
                    // override any earlier parent pending for the same state.
                    // Events: append in order. Outer commit fires this
                    // savepoint's events after its own (preserving stage order
                    // across the whole tree).
                    parentTxn.pendingLock.withLock {
                        parentTxn.pendingWrites.putAll(pendingWrites)
                        parentTxn.pendingEvents.addAll(pendingEvents)
                    }
                } else {
                    // Top-level commit, pass 1 (assignment only; a distinct state's
                    // equals may throw, and the write bracket is closed in a finally).
                    applyPendingWrites(committed, eventsToDrain)
                }
                buffersClosed = true
                pendingWrites.clear()
                pendingEvents.clear()
            }
            // Pass 2+ (top-level only): fanout, outside pendingLock so no user
            // callback ever runs under an internal store lock.
            phase = CommitPhase.FANOUT
            if (committed.isNotEmpty()) fanout(committed)
            // Phase 3 (top-level only): drain events AFTER observer fanout and AFTER
            // bridge publishes. Order matters: a collector subscribed to both
            // `state.asFlow()` and `store.events` will see the state value before
            // the event. If a caller-supplied [drainEvents] is provided, it owns
            // the emit (typically a suspending emit honoring back-pressure).
            // Otherwise we tryEmit each event — sync `commit()` cannot suspend, so
            // a full SUSPEND-policy buffer falls back to drop here. Suspend-action
            // callers should pass a drainEvents that uses `emit(...)` to honor
            // back-pressure.
            phase = CommitPhase.EVENTS
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
        } catch (e: CancellationException) {
            // Cancellation is control flow, not a commit failure: propagate it
            // unwrapped so structured concurrency still sees its own exception.
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: an Error escaping here would otherwise
            // leave the transaction Active while the caller reports a rollback
            // that never happened.
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException(commitFailureMessage(phase, storeLabel, committed), e)
        } finally {
            fanoutThreadId = NOT_FANNING_OUT
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
                buffersClosed = true
                pendingWrites.clear()
                pendingEvents.clear()
            }
            updateStatus(TransactionStatus.RolledBack)
        } catch (e: CancellationException) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw e
        } catch (e: Throwable) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException("Rollback of transaction '$id' failed", e)
        } finally {
            endTimeLock.withLock {
                _endTime = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    /**
     * Failure text that names the transaction, the store, the commit phase and
     * the states involved — a bare "Commit failed" leaves the reader with no way
     * to tell which store, which state or which phase produced it.
     */
    private fun commitFailureMessage(
        phase: CommitPhase,
        storeLabel: String?,
        committed: List<Pair<MutableState<*>, Any>>,
    ): String {
        val where = storeLabel?.let { " on $it" } ?: ""
        val frame = frameId?.let { " of frame '$it'" } ?: ""
        val states = if (committed.isEmpty()) "" else " (${committed.size} state(s) already applied)"
        return "Commit of transaction '$id'$frame$where failed during the ${phase.label} phase$states"
    }

    /** Which stage of [commitDispatching] was running when a commit threw. */
    private enum class CommitPhase(
        val label: String,
    ) {
        APPLY("state-apply"),
        FANOUT("observer/bridge fanout"),
        EVENTS("event drain"),
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

/** [Transaction.fanoutThreadId] while no fanout runs. No platform hands out this thread id. */
private const val NOT_FANNING_OUT = Long.MIN_VALUE

/**
 * Top-level commit, pass 1: apply every pending write to state, collecting the
 * writes that changed a state into [committed] and snapshotting the staged
 * events into [eventsToDrain]; then mark the transaction [Transaction.applied]
 * with the calling thread as its fanout thread. The caller holds `pendingLock`.
 *
 * Assignment only. The only user code that runs here is `equals` on a
 * `distinct` state; if that throws, earlier states in the pass are already
 * assigned, and the write bracket is still closed in the `finally`. Fanout
 * (observers, bridges) happens afterwards, once every state in the
 * transaction already holds its committed value, so an observer reading a
 * sibling state sees the committed
 * value directly rather than through `findPendingValue`. The events are drained
 * after `pendingLock` is released: `tryEmit` may invoke ready collectors
 * synchronously, and no internal lock is ever held across user code.
 *
 * The assignments sit inside one write bracket (ConsistentRead.kt): every
 * state's bracket opens before the first assignment and closes after the
 * last, so a concurrent `snapshot()` sees all of this commit or none of it.
 */
@OptIn(StoreInternalApi::class)
private fun Transaction.applyPendingWrites(
    committed: MutableList<Pair<MutableState<*>, Any>>,
    eventsToDrain: MutableList<Pair<MutableSharedFlow<*>, Any>>,
) {
    // A stable copy: opening and closing must cover exactly the same states.
    val states = pendingWrites.keys.toList()
    openWriteBracket(states)
    try {
        pendingWrites.forEach { (state, value) ->
            @Suppress("UNCHECKED_CAST")
            if ((state as MutableState<Any>).applyCommittedValue(value)) {
                committed += state to value
            }
        }
    } finally {
        // Closed even if a `distinct` state's `equals` throws, so no reader
        // waits forever on a bracket this commit left open.
        closeWriteBracket(states)
    }
    eventsToDrain.addAll(pendingEvents)
    applied = true
    fanoutThreadId = currentThreadId()
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
