package com.vynatix.holdfast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * One unit of atomicity in a [Store]. A transaction holds:
 *  - an [id] (the action's class simple name, falling back to a random UUID),
 *  - a [parent] reference forming the savepoint chain (null for top-level),
 *  - a buffer of pending writes ([pendingWrites]), staged by [ownerThreadId]
 *    (by any thread while a suspending action holds the store) under a lock,
 *    and one of keyed-entry evictions ([stagedEvictions]),
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
         *   root has already applied its writes (it is fanning out, finished,
         *   or — a frame participant — waiting for its turn to fan out), or it
         *   or an ancestor has already ended: the savepoint's commit would
         *   merge into a buffer that is never applied again.
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

    /**
     * Guards [pendingWrites] and [pendingEvents]: every write into them, the
     * commit's apply pass (or merge) that consumes them, and rollback's
     * discard. `internal` for the apply pass (FrameCommit.kt), which holds the
     * pending locks of every root of a frame at once.
     */
    internal val pendingLock = StoreLock()

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
     * Per-transaction buffer of keyed-entry evictions (`KeyedState.evict`):
     * each entry's state, with `true` when this transaction evicts it and
     * `false` when it cancels an eviction an enclosing transaction staged —
     * the last operation on an entry wins (KeyedEviction.kt). Guarded by
     * [pendingLock] like [pendingWrites], and disjoint from it: staging an
     * eviction drops the entry's pending write, and a write to the entry
     * cancels its eviction. A savepoint's commit merges it into the parent's
     * (an evicted entry's pending write in the parent is dropped too), a
     * top-level commit applies the `true` ones inside its write bracket, and
     * rollback discards it.
     */
    internal val evictions: MutableMap<MutableState<*>, Boolean> = LinkedHashMap()

    /**
     * Set when this top-level transaction's writes are assigned in its apply
     * pass (a frame's roots are applied in one pass under all their pending
     * locks, FrameCommit.kt), and never cleared. From then
     * on the transaction only fans out, then ends: anything staged into it —
     * or into a savepoint of it — would never be applied, so every staging
     * path refuses it (see [closedToWrites]).
     */
    @kotlin.concurrent.Volatile
    internal var applied: Boolean = false

    /**
     * The `reset()` (or sterile `restore()`) staging into this transaction
     * right now, or `null`: which of its store's declared states still await
     * their reset, and the reset values of those already staged (see
     * [ResetPass]). Installed and cleared by `stageResetPass` on the owner
     * thread, so a read the reset's re-run initializers make resolves a
     * pending state first ([MutableState.value]). Every other read pays one
     * volatile read for it.
     */
    @kotlin.concurrent.Volatile
    internal var pendingReset: ResetPass? = null

    /**
     * Every state a `reset()` or a sterile `restore()` staging into this
     * transaction's chain has resolved — staged, or left alone because it
     * already held its reset value. Used on the [root] only, and guarded by
     * its store's registry lock:
     * `removeState`/`clearStates` refuse a state in it until the root applies
     * or ends, so no state the reset decided on is dropped and re-created from
     * pre-reset values before the reset commits. Dropped with the root, so
     * never cleared.
     */
    internal val resetHeld: MutableSet<MutableState<*>> = HashSet()

    /**
     * Why `removeState`/`clearStates` are refused on this transaction's owner
     * thread while it — or a savepoint of it — is its store's active
     * transaction, or `null` (the default: they are not). Set once, by
     * library machinery that runs user code inside it whose structural
     * changes would escape its rollback (`internalForbidStructuralWrites`,
     * SealedStates.kt: a hydrator's `adopt`).
     */
    @kotlin.concurrent.Volatile
    internal var structuralRefusal: String? = null

    /**
     * The thread running this transaction's commit fanout right now — observer
     * callbacks, bridge publishes and the synchronous event drain — or
     * [NOT_FANNING_OUT]. Set when [fanOutApplied] starts and cleared when it
     * returns, so it names a thread only while that thread is inside the
     * fanout: a pooled thread that runs something else afterwards never
     * matches. (A frame's root that has applied while an earlier root fans out
     * is not fanning out yet; its [ownerThreadId], or under `suspendAtomic`
     * the fanout marker of the whole frame commit, covers it.)
     *
     * It is how the store tells an observer reacting to this commit, which must
     * not nest into it, from another thread, whose `action`/`atomic` just waits
     * its turn — also under `suspendAction`, whose [ownerThreadId] is the thread
     * it started on, not the one it commits on. It covers only the synchronous
     * [fanOutApplied] window: the bridge publishes and event emits that a
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
    internal var buffersClosed: Boolean = false

    /**
     * What this transaction's apply pass left for its fanout, from the end of
     * that pass until [fanOutApplied] takes it; `null` otherwise (a savepoint
     * never has one: its commit merges into the parent and is done).
     */
    @kotlin.concurrent.Volatile
    internal var applyResult: AppliedWrites? = null

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
     * commits into the enclosing transaction and stays installed while the
     * frame's participants fan out (or is rolled back while the frame's error
     * hooks run). `mutate`, `emit`, nested `action`/`atomic` and the savepoint
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
        pendingLock.withLock {
            pendingWrites[state] = rawValue
            cancelStagedEviction(state)
        }
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
            // A write to a keyed entry whose eviction is staged cancels it: the
            // last operation on an entry wins.
            cancelStagedEviction(state)
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
     * Read-only view of the keyed-state entries (`KeyedState.evict`,
     * `evictAll`) this transaction evicts when it commits — its own staged
     * evictions, not those of an enclosing transaction or of inner savepoints
     * that have not committed into it yet. Disjoint from [modifiedStates]:
     * evicting an entry drops the transaction's pending write to it, and
     * writing the entry again cancels the eviction, and so does getting it —
     * except where a get leaves the eviction staged: inside a
     * `suspendAction`/`suspendAtomic` body, or an `atomic` frame body that
     * does not enroll the store (see `KeyedState.get`). Owner-thread
     * only; throws [IllegalStateException] from non-owner threads. Holds the
     * entries' states, whose `toString` names the family, never the key.
     *
     * Experimental (issue #20, R7).
     */
    @ExperimentalStoreApi
    val stagedEvictions: Set<State<*>>
        get() {
            check(
                ownerThreadId ==
                    com.vynatix.holdfast.platform
                        .currentThreadId(),
            ) {
                "stagedEvictions may only be read on the transaction's owner thread"
            }
            return pendingLock.withLock { evictions.filterValues { it }.keys.toSet() }
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
        commitDispatching(::fanOutBlocking)
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
     *
     * The two passes — apply, then [fanOutApplied] — run here back to back; a
     * frame (`atomic`/`suspendAtomic`) runs the apply pass over all its
     * participants at once ([applyFrameCommit]), then fans each out.
     */
    @StoreInternalApi
    fun commitDispatching(
        fanout: (List<Pair<MutableState<*>, Any>>) -> Unit,
        drainEvents: ((List<Pair<MutableSharedFlow<*>, Any>>) -> Unit)?,
    ) {
        applyAlone()?.let { throw it }
        fanOutApplied(fanout, drainEvents)
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
                evictions.clear()
            }
            updateStatus(TransactionStatus.RolledBack)
        } catch (e: CancellationException) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw e
        } catch (e: Throwable) {
            runCatching { updateStatus(TransactionStatus.Failed) }
            throw TransactionException("Rollback of transaction '$id' failed", e)
        } finally {
            recordEndTime()
        }
    }

    /** Stamp [endTime]: this transaction just reached a terminal status. */
    internal fun recordEndTime() {
        endTimeLock.withLock {
            _endTime = Clock.System.now().toEpochMilliseconds()
        }
    }

    internal fun updateStatus(newStatus: TransactionStatus) {
        statusLock.withLock {
            val oldStatus = _status
            if (!isValidStatusTransition(oldStatus, newStatus)) {
                throw TransactionException("Invalid status transition from $oldStatus to $newStatus")
            }
            _status = newStatus
        }
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

/** [Transaction.fanoutThreadId] while no fanout runs. No platform hands out this thread id. */
internal const val NOT_FANNING_OUT = Long.MIN_VALUE

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
