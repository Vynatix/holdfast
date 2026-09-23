@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Process-monotonic counter used to assign each [Store] a stable [Store.lockOrderKey]
 * at construction. Used by `atomic(...)` to acquire multi-store locks in a
 * deadlock-safe global order.
 */
private val storeLockOrderKeyGen = atomic(0L)

/**
 * One store's middleware hooks, pre-bound to a frame root transaction. Handed
 * out by [Store.internalFrameMiddlewareSession] so `atomic(...)` and
 * `:holdfast-coroutines.suspendAtomic` can drive per-store middleware without
 * seeing the store's `Self` type. See that hook's KDoc for the exact
 * exception-isolation semantics of each phase.
 */
@StoreInternalApi
class FrameMiddlewareSession internal constructor(
    private val started: () -> Unit,
    private val completed: () -> Unit,
    private val errored: (Throwable) -> Unit,
) {
    /** Fire every middleware's `onTransactionStarted`, outermost-first. */
    fun fireStarted() = started()

    /** Fire every middleware's `onTransactionCompleted`, innermost-first. */
    fun fireCompleted() = completed()

    /** Fire every middleware's `onTransactionError`, innermost-first, each isolated. */
    fun fireError(error: Throwable) = errored(error)
}

/**
 * Base class for transactional state containers.
 *
 * A Store holds a set of named [State] properties created via [state]. Mutations are
 * grouped into transactional [action] blocks: changes buffer, observe the
 * read-your-own-writes view on the action's owner thread, and only become visible
 * to observers and bridges on a successful commit. A throwing action body discards
 * the buffer atomically.
 *
 * Concurrency contract:
 *  - All reads and writes through `mutate`/`action` are serialized via a per-store
 *    reentrant lock.
 *  - [activeTransaction] is volatile; reads from any thread are valid for inspection
 *    but must not be relied on for race-free decisions outside the owner thread.
 *  - `mutate` from a thread that does not own the active transaction synthesizes its
 *    own one-shot transaction (this is intentional, not a bug — middleware fires and
 *    observers see only the committed value).
 *
 * Typical subclass:
 * ```
 * class CounterStore : Store<CounterStore>() {
 *     val count by state { 0 }
 *     val label by state { "init" }
 * }
 * ```
 */
@StoreActionDsl
@Suppress("TooManyFunctions") // The Store DSL is intentionally broad; each member is a single primitive.
abstract class Store<Self : Store<Self>> {
    /**
     * Process-monotonic ordering key, set once at construction. `atomic(v1, v2, …)`
     * sorts its store arguments by this key before acquiring locks, giving
     * deadlock-safe global ordering across any combination of vaults.
     */
    @StoreInternalApi
    val lockOrderKey: Long = storeLockOrderKeyGen.incrementAndGet()

    /**
     * Volatile backing field for the scope bound via [bindToScope]. `null` until the
     * first `bindToScope` call; subsequent calls atomically replace it. Read by the
     * default getter of [scope] as resolution level 3 (between subclass override and
     * process default).
     *
     * Marked `@Volatile` so a write on one thread is immediately visible to readers
     * on other threads — the binding is racy by contract (last writer wins).
     */
    @kotlin.concurrent.Volatile
    private var boundScope: CoroutineScope? = null

    /**
     * The [CoroutineScope] this store's long-running async work runs on. Resolution order
     * (per-call → per-store override → bound → process-global default):
     *
     *  1. **Per-call** — APIs that take an explicit `scope: CoroutineScope` parameter use that.
     *  2. **Per-store** — a subclass may `override val scope: CoroutineScope` (use a getter,
     *     not a `val` initializer, to avoid lazy-init order traps in singleton vaults).
     *     A subclass override sits ABOVE this property in the resolution chain, so it
     *     beats any [bindToScope] call.
     *  3. **Bound** — the scope passed to the most recent [bindToScope] call on this
     *     store instance, if any. Rebindable.
     *  4. **Global** — falls back to [Store.Companion.defaultScope].
     */
    open val scope: CoroutineScope
        get() = boundScope ?: defaultScope

    /**
     * Bind this store to [scope] for resolution level 3 (see [scope]). After this call,
     * `store.scope` returns [scope] (unless a subclass has its own `override val scope`,
     * which beats the bound scope). Calling [bindToScope] again replaces the binding.
     *
     * Thread safety: the binding field is `@Volatile`; the latest write becomes
     * visible to all readers. Concurrent callers race in the obvious way (last write
     * wins) — bind once at app init, or guard externally if multiple components own
     * the binding.
     *
     * Lifecycle note: [bindToScope] does NOT cancel the previously-bound scope and
     * does NOT cancel the new scope when the store is later disposed. Scope lifetimes
     * are owned by the caller. See [dispose] for terminal teardown of the store.
     */
    fun bindToScope(scope: CoroutineScope) {
        boundScope = scope
    }

    /**
     * Atomic disposed flag. CAS'd to `true` exactly once on the first [dispose] call;
     * subsequent calls observe `true` and return without throwing (idempotent contract).
     * Every public entry point reads this — when `true`, they throw
     * `IllegalStateException("store disposed")`.
     */
    private val disposedFlag = atomic(false)

    /**
     * Whether [dispose] has been called on this store. Once `true`, every public
     * mutation entrypoint (`action`, `mutate`, `update`, `effect`, `bridge`,
     * `observeFrom`, `removeState`, `clearStates`, etc.) and every state-registry
     * read throws [IllegalStateException]. Cold APIs in companion modules
     * (e.g. `:holdfast-coroutines.asFlow`/`first`/`awaitValue`) MUST also check this
     * before establishing observer subscriptions.
     */
    val isDisposed: Boolean get() = disposedFlag.value

    /**
     * Terminally tear down this store. Idempotent.
     *
     * After `dispose()`:
     *  - Every state-mutation API throws `IllegalStateException("store disposed")`.
     *  - Every state-registry read API throws.
     *  - All registered observers are dropped; all bridges are detached.
     *  - The [Store.scope] / bound scope is **NOT** cancelled — caller owns its lifecycle.
     *    `dispose()` is asymmetric with scope cancellation: cancelling the bound scope is
     *    a soft-pause (subsequent calls fall back to `defaultScope`); `dispose()` is terminal.
     *
     * Subclasses with additional resources (e.g. `EventfulStore`'s events SharedFlow)
     * should override [onDispose] to release them. Always call `super.onDispose()`.
     */
    fun dispose() {
        if (!disposedFlag.compareAndSet(expect = false, update = true)) {
            // Already disposed — idempotent, no work, no throw.
            return
        }
        // Drop in-flight transactional state so any pending writes can never be applied.
        // Acquire the transaction lock briefly so a racing action that's mid-flight
        // (under the lock) finishes before we reach into shared structures.
        transactionLock.withLock {
            _activeTransaction = null
        }
        postCommitQueue.clear()
        // Snapshot the property map under its lock, then call shutdownSilently outside
        // any store-side lock — `shutdownSilently` takes the per-state observer + bridge
        // locks, and we don't want to invert ordering.
        val toShutdown =
            propertiesLock.withLock {
                val snap = _properties.values.toList()
                _properties.clear()
                snap
            }
        toShutdown.forEach { runCatching { it.shutdownSilently() } }
        // Drop middleware so a stray reference to a disposed store can't keep
        // captured state alive.
        middlewareLock.withLock { middlewareList.clear() }
        // Subclass hook: EventfulStore uses this to reset its events SharedFlow.
        runCatching { onDispose() }
    }

    /**
     * Subclass hook invoked once, AFTER the base `dispose()` has cleared all states,
     * observers, bridges, and middleware. Override to release subclass-owned resources
     * (e.g. `EventfulStore` resets its events SharedFlow). Default no-op.
     *
     * Always wrapped in `runCatching` by [dispose] so a misbehaving override can't
     * leave the store half-disposed.
     */
    protected open fun onDispose() {}

    private fun checkNotDisposed() {
        if (disposedFlag.value) error("store disposed")
    }

    private val transactionLock = StoreLock()
    private val propertiesLock = StoreLock()
    private val middlewareLock = StoreLock()

    @kotlin.concurrent.Volatile
    private var _activeTransaction: Transaction? = null

    /**
     * The transaction currently being built on this Store, if any. Direct volatile
     * read — cross-thread observers see the most recent set without acquiring a lock.
     * `null` between actions; non-null only on the action's owner thread for the
     * duration of the action body.
     */
    val activeTransaction: Transaction?
        get() = _activeTransaction

    private val _properties = mutableMapOf<String, MutableState<*>>()

    /**
     * Snapshot view of every state currently registered with this store, keyed by
     * property name. The map is a copy — modifying it does not affect the store.
     * The contained `State<*>` references are LIVE — reading `.value` reflects the
     * current state. Callers MUST NOT cast these back to `MutableState` to bypass
     * the transactional API; doing so leads to undefined behavior.
     */
    val properties: Map<String, State<*>>
        get() {
            checkNotDisposed()
            return propertiesLock.withLock { _properties.toMap() }
        }

    private val middlewareList = mutableListOf<Middleware<Self>>()

    /**
     * Optional handler for failures in post-commit side effects, which cannot
     * undo a commit whose values are already applied:
     *  - a commit-fire observer callback that throws;
     *  - a throwing `Transformer.get` during observer fanout (that state's
     *    observers are skipped);
     *  - a throwing `Bridge.publish` (the commit still succeeds);
     *  - a failed [derived] recompute — a throwing `compute`, or a middleware
     *    rejecting it: rolled back, and the derived keeps its value until the
     *    next source commit.
     *
     * If null (the default), all of these are dropped silently — matching the
     * original library contract. Set to a non-null handler to surface them (e.g.
     * a logger or a test fixture's failure list).
     *
     * Note: this handler does NOT capture exceptions thrown from the initial-fire
     * call inside [State.effect]/[MutableState.observe]. Those propagate to the
     * caller (subscribing is synchronous from the caller's perspective; their bug
     * shouldn't be silently suppressed).
     */
    @kotlin.concurrent.Volatile
    var uncaughtObserverHandler: ((Throwable) -> Unit)? = null

    /**
     * Hook for an external mutual-exclusion mechanism that needs to coordinate
     * with this store's blocking [action]. Set at most once, by the
     * `:holdfast-coroutines` `suspendAction` extension when it first wraps a
     * suspending body — the serializer's blocking acquire/release brackets every
     * blocking [action] call so that concurrent suspending callers see a serial
     * stream of actions.
     *
     * Marked `@StoreInternalApi` because it's an extension point for companion
     * modules, not a user-facing knob. If null (the default), `action` runs
     * unwrapped — the legacy fast path.
     *
     * An implementation must tolerate blocking acquires from several threads
     * at once: each caller holds the serializer exclusively between its own
     * [blockingAcquire] and [blockingRelease], and [blockingRelease] is always
     * called on the thread that acquired.
     */
    interface AsyncSerializer {
        fun blockingAcquire()

        fun blockingRelease()

        /**
         * Non-blocking [blockingAcquire]: take the serializer and return `true`
         * if it is free, or return `false` at once if anyone else holds it.
         * A `true` return must be paired with [blockingRelease].
         *
         * The store's non-blocking paths (the `derived` recompute hand-off)
         * rely on this never waiting. The default delegates to
         * [blockingAcquire] and therefore DOES block — it exists only so
         * implementations written before this member keep compiling; override
         * it in any serializer a `derived` state can meet.
         */
        @StoreInternalApi
        fun tryBlockingAcquire(): Boolean {
            blockingAcquire()
            return true
        }
    }

    @StoreInternalApi
    @kotlin.concurrent.Volatile
    var asyncSerializer: AsyncSerializer? = null

    /**
     * Set while a `suspendAction` body is in flight. While non-null, `mutate`
     * additionally accepts callers on threads that aren't the txn's owner thread,
     * because the suspending body may resume on different threads via coroutine
     * dispatch. The [AsyncSerializer] guarantees no other action runs concurrently,
     * so the relaxed ownership check is sound.
     */
    @StoreInternalApi
    @kotlin.concurrent.Volatile
    var suspendingOwner: Any? = null

    /**
     * Tasks queued during an in-progress transaction that should run AFTER the
     * current top-level transaction's commit fanout completes and its locks are
     * released. Used by [derived] to defer recomputes out of the parent's commit
     * loop — this avoids re-entering `pendingWrites` while the parent is
     * iterating it.
     */
    private val postCommitQueue = PostCommitQueue()

    /**
     * Schedule [task] to run after the current top-level transaction's commit
     * fanout finishes and its locks are released. If no transaction is active,
     * the task runs immediately on the calling thread.
     *
     * A task instance that is already queued is not queued twice (identity,
     * `===`), so a caller that submits one stable task per consumer gets one
     * run per transaction of this store however many times that transaction's
     * commit triggers it. The dedup only applies while a transaction is
     * active: on an idle store every submission runs inline, so a consumer
     * triggered N times from ANOTHER store's commit fanout runs N times.
     *
     * Used by `derived(...)` to defer its recompute instead of re-entering the
     * parent's commit. Also reachable by companion modules
     * (`:holdfast-coroutines.suspendDerived`) that need the same deferral
     * contract; marked `@StoreInternalApi` because the deferral is an
     * implementation detail of the derived-recompute machinery, not a
     * user-facing knob.
     */
    @StoreInternalApi
    fun postCommit(task: () -> Unit) {
        if (_activeTransaction == null) {
            task()
            return
        }
        postCommitQueue.enqueue(task)
        // Lost-wakeup guard. The transaction seen above may have ended — and its
        // holder already drained an empty queue — between that read and the
        // enqueue, which would strand [task] until some unrelated later
        // transaction drains. If the slot is empty now, nobody is left to drain,
        // so drain here. If it is still occupied, that holder clears it strictly
        // after this read and drains after clearing, so it will see [task].
        if (_activeTransaction == null) drainPostCommitTasks()
    }

    private fun drainPostCommitTasks() {
        postCommitQueue.drain()
    }

    /**
     * Queue [task] for the store's current holder without running it, even
     * when no transaction is visible. For a task that just found the store busy
     * through [tryTopLevelAction]; see there for why the holder is guaranteed
     * to drain it.
     */
    internal fun handOffPostCommit(task: () -> Unit) {
        postCommitQueue.enqueue(task)
    }

    /** Withdraw a still-queued [task]; see [PostCommitQueue.withdraw]. */
    internal fun withdrawPostCommit(task: () -> Unit) {
        postCommitQueue.withdraw(task)
    }

    /**
     * Run [body] as a top-level action if the store can take one right now,
     * and never block or spin trying.
     *
     * Returns [TopLevelAttempt.Disposed] on a disposed store, and
     * [TopLevelAttempt.Busy] while a transaction is active — including this
     * thread's own, since a top-level action cannot nest inside it — or when
     * the serializer or `transactionLock` is taken while one is.
     * [TopLevelAttempt.BusyNoTxn] means one of those is taken with no
     * transaction installed. Otherwise [onAcquired] runs once the store is
     * taken — before the middleware chain, so it runs even when a middleware
     * then rejects the action — and the body runs exactly like a top-level
     * [action] (middleware chain, commit, fanout; failures fold into the
     * [TopLevelAttempt.Ran] result), the locks release in `action`'s order, and
     * the post-commit queue drains. No frame policing: callers are library
     * machinery that runs after commits, not user bodies.
     *
     * **Hand-off invariant.** A caller that gets a busy answer may queue its
     * task with [handOffPostCommit] and retry once; if the retry is busy too,
     * dropping the task is safe, because every top-level holder of this
     * store's serializer, `transactionLock` or active-transaction slot drains
     * the queue after it releases: blocking [action], `atomic` for a store whose
     * root it opened (a savepoint root defers to the enclosing holder),
     * `suspendAction` in its `finally`, `suspendAtomic` for a root it opened
     * (and for a mutex acquire cancelled after kotlinx handed it the mutex) —
     * both frames once they have fully unwound — this function after it ran,
     * and after it backed out busy from a serializer or lock it took (unless
     * the store has another holder by then, which drains instead), and
     * `:holdfast-testing`'s open-transaction commit, rollback and body-throw
     * cleanup. The queue write happens before the busy retry, and that
     * holder's drain after it releases, so the drain sees the task. [dispose]
     * holds the lock only to empty the active-transaction slot, then clears
     * the queue instead of draining it — fine, because a disposed store
     * refuses every later attempt with [TopLevelAttempt.Disposed]. A new
     * holder added to any of these must drain the same way (see
     * [internalDrainPostCommitTasks]).
     */
    internal fun tryTopLevelAction(
        id: String,
        onAcquired: () -> Unit = {},
        body: Self.() -> Unit,
    ): TopLevelAttempt {
        val active = _activeTransaction
        val attempt =
            when {
                isDisposed -> TopLevelAttempt.Disposed
                active != null -> TopLevelAttempt.Busy(active)
                else -> tryTopLevelUnderSerializer(id, onAcquired, body)
            }
        if (attempt is TopLevelAttempt.Ran) drainPostCommitTasks()
        return attempt
    }

    private fun tryTopLevelUnderSerializer(
        id: String,
        onAcquired: () -> Unit,
        body: Self.() -> Unit,
    ): TopLevelAttempt {
        val serializer = asyncSerializer
        if (serializer != null && !serializer.tryBlockingAcquire()) return busyAttempt()
        val underLock =
            try {
                tryTopLevelUnderLock(id, onAcquired, body)
            } finally {
                serializer?.blockingRelease()
            }
        val attempt = underLock ?: busyAttempt()
        // This attempt held the store (the serializer, or the lock with a
        // transaction installed) and then backed out busy. While it held it,
        // another holder's drain may have found the store busy and handed a
        // task to it, so it owes the store a drain like any releasing holder.
        // Without this, a lock-only holder (the harness's open-transaction
        // commit, or an `action` that read the serializer as not yet installed)
        // whose drain ran during our hold would leave that task stranded.
        if (attempt !is TopLevelAttempt.Ran && (serializer != null || underLock != null)) drainIfUnheld()
        return attempt
    }

    /** `null` when the lock is taken by someone else, i.e. nothing was held. */
    private fun tryTopLevelUnderLock(
        id: String,
        onAcquired: () -> Unit,
        body: Self.() -> Unit,
    ): TopLevelAttempt? {
        if (!transactionLock.tryAcquire()) return null
        return try {
            // Re-read under the lock: a holder may have installed a transaction
            // after the unlocked read in tryTopLevelAction (e.g. a test harness
            // transaction held open without the lock).
            val active = _activeTransaction
            if (active != null) {
                TopLevelAttempt.Busy(active)
            } else {
                onAcquired()
                TopLevelAttempt.Ran(runTransaction(id, body))
            }
        } finally {
            transactionLock.release()
        }
    }

    private fun busyAttempt(): TopLevelAttempt {
        val active = _activeTransaction
        return if (active != null) TopLevelAttempt.Busy(active) else TopLevelAttempt.BusyNoTxn
    }

    /**
     * Drain the post-commit queue unless the active-transaction slot or
     * `transactionLock` has a holder right now. That holder releases after this
     * check and drains after it releases, so its drain sees everything queued
     * before it.
     *
     * The lock is probed with [StoreLock.tryAcquire] rather than read: its
     * `locked` flag is written after the mutex is taken and cleared before it
     * is released, so a read can miss a holder. Probing instead of draining
     * unconditionally is also what keeps this from recursing: while a
     * lock-only holder keeps the lock, an unconditional drain would re-run the
     * caller's own handed-off task, which would back out busy and drain again.
     * With the probe, a nested drain needs the store to change hands in
     * between. A holder of the serializer alone is not probed for: a drained
     * task meets it before taking anything, backs out without draining, and
     * that holder drains after it releases.
     */
    private fun drainIfUnheld() {
        if (_activeTransaction != null || !transactionLock.tryAcquire()) return
        transactionLock.release()
        drainPostCommitTasks()
    }

    @Suppress("UNCHECKED_CAST")
    private val self: Self get() = this as Self

    /**
     * Append [middleware] to the chain. Order matters: the LAST argument is the
     * outermost middleware (its `onTransactionStarted` runs first; its `completed`
     * or `onTransactionError` runs last). Earlier-listed middlewares are inner.
     *
     * Same ordering applies to both blocking [action] and suspending
     * `:holdfast-coroutines.suspendAction` (issue 31).
     *
     * Practical implication: for an `onTransactionError` handler to see exceptions
     * thrown by another middleware, it must be listed AFTER that middleware. Place
     * a logging/audit middleware LAST so it sees errors from validation middleware
     * placed earlier.
     */
    fun middlewares(vararg middleware: Middleware<Self>) {
        checkNotDisposed()
        middlewareLock.withLock {
            middlewareList.addAll(middleware)
        }
    }

    /** Drop every registered middleware. */
    fun clearMiddleware() {
        checkNotDisposed()
        middlewareLock.withLock {
            middlewareList.clear()
        }
    }

    /**
     * Run [body] inside a transaction and return its computed value (along with the
     * transaction) on success. Mutations are buffered in [Transaction.pendingWrites];
     * on success they apply to state via [MutableState.applyCommitted] and observers
     * fire then. On throw, pending writes are dropped — no state, observer, or bridge
     * is touched.
     *
     * The body's value is captured into [TransactionResult.Success.value] for direct
     * read-after-action use:
     * ```
     * val r = store action { compute() }
     * when (r) {
     *     is TransactionResult.Success -> useResult(r.value)
     *     is TransactionResult.Error   -> handle(r.exception)
     * }
     * ```
     *
     * Nested actions form a savepoint stack: the inner transaction's parent is the
     * outer transaction. Inner.commit merges its pending writes into the outer.
     * Inner.rollback drops just the savepoint. Outer.rollback discards everything,
     * including merged inner writes.
     */
    @OptIn(ExperimentalUuidApi::class)
    infix fun <R> action(body: Self.() -> R): TransactionResult<R> {
        checkNotDisposed()
        // Frame policing (body-only: the marker is cleared before commit fanout,
        // so observer-triggered actions never land here). Ordered BEFORE the
        // serializer acquire — a blocking acquire on a suspendAtomic
        // participant's mutex would deadlock, which is exactly what the
        // interop check converts into a teaching exception.
        val frame = FrameMarkers.current()
        if (frame != null) checkFrameAllowsBlockingAction(frame)
        // A nested action is a savepoint of a transaction this thread already
        // owns, so it is already inside the region the serializer brackets.
        // Re-acquiring there is never correct and is actively fatal: the
        // serializer is not reentrant, so the inner acquire would wait forever
        // for the outer acquire this very call stack holds.
        val nested = ownsActiveTransaction()
        val serializer = if (nested) null else asyncSerializer
        serializer?.blockingAcquire()
        val result =
            try {
                runBlockingActionUnderLock(body)
            } finally {
                serializer?.blockingRelease()
            }
        // Deferred work (derived recomputes) opens FRESH top-level actions, so it
        // must run only after this call's serializer bracket is released —
        // inside it, the recompute would find the store busy and hand itself
        // back to this very call. Draining after the release is also what makes
        // this call a valid hand-off target (see tryTopLevelAction). Same
        // placement, and same reason, as suspendAtomic's drain.
        if (!nested) drainPostCommitTasks()
        escalateInFrameError(frame, result)
        return result
    }

    /**
     * Whether this thread already owns the store's active transaction — i.e. this
     * call is nested inside an `action`/`atomic` body running on this thread.
     *
     * A suspending body disqualifies the check outright. While [suspendingOwner]
     * is set, the transaction's `ownerThreadId` records the thread that OPENED it,
     * not a thread currently executing it: the body may be parked at a suspension
     * point with its thread handed back to the pool. An unrelated blocking caller
     * that happens to be scheduled onto that same pooled thread would otherwise
     * look nested and skip the serializer, interleaving with the suspending body
     * — the exact mutual exclusion the serializer exists to provide.
     */
    private fun ownsActiveTransaction(): Boolean {
        val txn = _activeTransaction
        return suspendingOwner == null && txn != null && txn.ownerThreadId == currentThreadId()
    }

    /**
     * Internal hook for `atomic(...)`, which brackets each participant with the
     * store's [AsyncSerializer] and needs the same nesting test [action] uses to
     * decide whether this thread is already inside the serialized region.
     */
    @StoreInternalApi
    fun internalOwnsActiveTransaction(): Boolean = ownsActiveTransaction()

    /**
     * Frame-entry gate for blocking [action]. Inside an active frame body:
     *  - an UNENROLLED store must not be written (unless the frame's policy
     *    says [FramePolicy.allowUnenrolled]) — its commit would escape the frame;
     *  - a store enrolled in a SUSPENDING frame must not run a blocking action —
     *    it would deadlock on the store's suspend mutex, so fail fast instead.
     */
    private fun checkFrameAllowsBlockingAction(frame: FrameMarker) {
        val enrolling = frame.enrollingFrame(this)
        if (enrolling == null) {
            if (!frame.policy.allowUnenrolled) {
                throw UnenrolledStoreException(unenrolledMessage(frame, "action"))
            }
        } else if (enrolling.suspending) {
            val name = this::class.simpleName ?: "Store"
            throw FrameInteropException(
                "Blocking action { } on $name inside suspendAtomic${enrolling.describeParticipants()} " +
                    "would deadlock on the store's suspend mutex (held by frame '${enrolling.frameId}'). " +
                    "Use `mutate`/`update` (e.g. `store { state mutate value }`) or `suspendAction { }` " +
                    "inside a suspendAtomic body.",
            )
        }
    }

    /**
     * Fail-fast escalation of in-frame action errors ([FramePolicy.tolerateInnerErrors]
     * inverts it back to check-the-result-yourself). [FrameContractException]s always
     * escalate — a contract violation swallowed into a tolerated inner error would
     * recreate the silent-escape hole the contract exists to close.
     */
    private fun escalateInFrameError(
        frame: FrameMarker?,
        result: TransactionResult<*>,
    ) {
        if (frame == null || result !is TransactionResult.Error) return
        val exception = result.exception
        val escalate =
            exception is FrameContractException ||
                (frame.isEnrolled(this) && !frame.policy.tolerateInnerErrors)
        if (escalate) throw exception
    }

    private fun unenrolledMessage(
        frame: FrameMarker,
        via: String,
    ): String {
        val name = this::class.simpleName ?: "Store"
        val fn = if (frame.suspending) "suspendAtomic" else "atomic"
        return "$name was mutated (via $via) inside $fn${frame.describeParticipants()} but is not " +
            "enrolled. Its writes would commit independently and would NOT roll back with the frame. " +
            "Fix: add $name to the $fn(...) participant list. (Mid-frame enrollment is not possible — " +
            "it would acquire a lock outside the sorted global order.) To deliberately run an " +
            "independent side-transaction, pass policy = FramePolicy.AllowUnenrolled."
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun <R> runBlockingActionUnderLock(body: Self.() -> R): TransactionResult<R> =
        transactionLock.withLock {
            // NOTE: post-commit tasks are NOT drained here. The drain runs in
            // [action], after the serializer bracket is released — see the comment
            // there. Nested actions inherit the parent's deferred queue and let it
            // drain at the outermost boundary either way.
            runTransaction(body::class.simpleName ?: Uuid.random().toString(), body)
        }

    /**
     * Open a transaction on top of whatever is active (a savepoint if anything
     * is), run [body] through the middleware chain, then commit or roll back.
     * The caller holds `transactionLock` and owns the post-commit drain.
     */
    private fun <R> runTransaction(
        id: String,
        body: Self.() -> R,
    ): TransactionResult<R> {
        val parent = _activeTransaction
        val txn = Transaction(id = id, parent = parent, ownerThreadId = currentThreadId())

        _activeTransaction = txn
        // Box for capturing the body's return value so we can pipe it into Success.
        // Holds null before the body runs; holds (result) after.
        val box = arrayOfNulls<Any?>(1)
        return try {
            runMiddlewareChain { box[0] = body(self) }
            try {
                txn.commit()
                @Suppress("UNCHECKED_CAST")
                TransactionResult.Success(txn, box[0] as R)
            } catch (e: Throwable) {
                TransactionResult.Error(e, txn)
            }
        } catch (e: Throwable) {
            runCatching { txn.rollback() }
            TransactionResult.Error(e, txn)
        } finally {
            _activeTransaction = parent
        }
    }

    private fun runMiddlewareChain(block: () -> Unit) {
        middlewareLock.withLock {
            val currentMiddleware = middlewareList.toList()
            currentMiddleware
                .fold(block) { acc, middleware ->
                    { middleware(self, acc) }
                }.invoke()
        }
    }

    /**
     * Plain context block — runs `block(self)` with no locks and no transaction.
     * Provides the `Self` receiver so member-extensions like [effect] and [bridge]
     * can be called fluently: `store { count effect { … } }`.
     */
    operator fun <R> invoke(block: Self.() -> R): R = block(self)

    /**
     * Declare a state property. The first read of the delegate creates a
     * [MutableState] from [initialize]; subsequent reads return the same instance.
     *
     *  - Pass a [transformer] to normalize on write or project on read.
     *  - Pass [distinct] = true to skip observer fanout and bridge publish when a
     *    commit re-applies the same value (StateFlow-style dedup). Default is
     *    false — every commit fires observers, matching the library's original
     *    contract.
     */
    fun <T : Any> state(
        transformer: Transformer<T>? = null,
        distinct: Boolean = false,
        initialize: Initializer<T>,
    ): StateDelegate<T> {
        val owningStore: Store<*> = this
        return StateDelegate { _, property ->
            checkNotDisposed()
            propertiesLock.withLock {
                val existing = _properties[property.name]
                if (existing != null) {
                    @Suppress("UNCHECKED_CAST")
                    existing as MutableState<T>
                } else {
                    MutableState(initialize(), transformer, owningStore, distinct).also { state ->
                        _properties[property.name] = state
                    }
                }
            }
        }
    }

    /**
     * Create-or-fetch a state under an arbitrary name. Used by [derived] to
     * register synthetic backing states whose names ("__derived_N") never
     * collide with user-declared property names (since Kotlin identifiers
     * can't start with `__`). Also reachable by companion modules
     * (`:holdfast-coroutines.suspendDerived`) for the suspending-derived backing
     * state; marked `@StoreInternalApi` because the synthesized name scheme
     * is an implementation detail.
     */
    @StoreInternalApi
    fun <T : Any> registerInternalState(
        name: String,
        initial: T,
        transformer: Transformer<T>? = null,
        distinct: Boolean = false,
    ): MutableState<T> =
        propertiesLock.withLock {
            val existing = _properties[name]
            if (existing != null) {
                @Suppress("UNCHECKED_CAST")
                existing as MutableState<T>
            } else {
                MutableState(initial, transformer, this, distinct).also {
                    _properties[name] = it
                }
            }
        }

    /**
     * Attach (or detach, when null) a [Bridge] for two-way external sync.
     * On attach, the bridge's `observe` is invoked immediately — implementations
     * typically replay any persisted value here for load-on-attach.
     * On detach, the previous bridge's inbound observer is disposed.
     */
    infix fun <T : Any> State<T>.bridge(bridge: Bridge<T>?) {
        checkNotDisposed()
        this.getMutableState().bridge = bridge
    }

    /**
     * Inbound-only adapter to an external [Observable]. Use this instead of [bridge]
     * when an external system only needs to push values into the state (e.g. an
     * admin override channel) and the state should not echo back via `publish`.
     *
     * Returns a [Disposable] that detaches the inbound subscription.
     */
    infix fun <T : Any> State<T>.observeFrom(observable: Observable<T>): Disposable {
        checkNotDisposed()
        val ms = this.getMutableState()
        return observable.observe { value -> ms.applyFromBridge(value) }
    }

    /**
     * Read-modify-write convenience. Equivalent to `mutate(block(value))` but reads
     * the current value once and threads it through [block]. Inside an active
     * transaction owned by this thread, the read sees pending writes
     * (read-your-own-writes); outside, an implicit single-shot transaction wraps
     * the operation.
     *
     * ```
     * store action {
     *     count update { it + 1 }
     *     items update { it + entry }
     * }
     * ```
     */
    infix fun <T : Any> State<T>.update(block: (T) -> T) {
        checkNotDisposed()
        this mutate block(this.value)
    }

    /**
     * Buffer-then-commit mutate. Inside an active transaction owned by this thread,
     * the post-`transformer.set` value is staged in the transaction's pending writes —
     * observers and bridges see nothing until commit.
     *
     * Outside any transaction (or on a non-owner thread), an implicit single-shot
     * transaction wraps the mutation so middleware fires and observers see only the
     * committed value.
     */
    infix fun <T : Any> State<T>.mutate(that: T) {
        checkNotDisposed()
        val state = this.getMutableState()
        val txn = _activeTransaction
        val onOwnerThread = txn != null && txn.ownerThreadId == currentThreadId()
        // Suspending body may resume on a different thread; AsyncSerializer ensures
        // no other action runs concurrently while suspendingOwner != null, so the
        // relaxed check is sound.
        val onOwnerCoroutine = txn != null && suspendingOwner != null

        if (txn != null && (onOwnerThread || onOwnerCoroutine)) {
            // Frame policing for the direct-stage path: a store with an active
            // transaction from an ENCLOSING action can be written here without
            // ever passing through `action` — e.g. `c.action { atomic(a, b) {
            // c.x mutate 1 } }` — and those writes would commit with c's outer
            // action regardless of the frame's outcome. Same rule as the
            // action-path check: unenrolled writes inside a frame body are an
            // escape unless the policy explicitly allows them.
            val frame = FrameMarkers.current()
            if (frame != null && !frame.isEnrolled(this@Store) && !frame.policy.allowUnenrolled) {
                throw UnenrolledStoreException(unenrolledMessage(frame, "mutate"))
            }
            // Defensive: a transaction that's been manually committed or rolled back
            // shouldn't accept further mutations. Throw rather than silently lose the write.
            check(txn.status == TransactionStatus.Active) {
                "Cannot mutate state on a ${txn.status} transaction"
            }
            txn.pendingWrites[state] = state.beforeSet(that)
            return
        }

        // No active transaction on this thread: wrap in a one-shot action so middleware
        // fires and observers see only the committed value. The recursive call lands
        // in the branch above on the second pass.
        action { this@mutate mutate that }
    }

    /**
     * O(1) ownership check via [MutableState.owningStore]. Throws if [State] was
     * created by a different store — without this, a foreign-store state would
     * silently pass the type cast and corrupt either store's state.
     */
    private fun <T : Any> State<T>.getMutableState(): MutableState<T> {
        @Suppress("UNCHECKED_CAST")
        val ms = (this as? MutableState<T>) ?: error("State must be created by this Store instance")
        if (ms.owningStore !== this@Store) error("State must be created by this Store instance")
        return ms
    }

    /**
     * Look up a state by its property name. Returns null if not registered yet
     * (states are registered lazily on first delegate read). Caller MUST NOT cast
     * the returned [State] back to [MutableState].
     */
    fun getState(name: String): State<*>? {
        checkNotDisposed()
        return propertiesLock.withLock { _properties[name] }
    }

    /** Whether a state with [name] has been registered. */
    fun hasState(name: String): Boolean {
        checkNotDisposed()
        return propertiesLock.withLock { _properties.containsKey(name) }
    }

    /**
     * Drop the named state from the registry and silently dispose its observers
     * and bridge. A subsequent delegate read recreates the state from its initializer.
     *
     * Throws [IllegalStateException] if the state has pending writes in an active
     * transaction (caller must commit or roll back first).
     */
    fun removeState(name: String) {
        checkNotDisposed()
        propertiesLock.withLock {
            val state = _properties[name] ?: return@withLock
            checkNoPendingWrites(state, name)
            state.shutdownSilently()
            _properties.remove(name)
        }
    }

    /**
     * Drop every registered state and silently dispose all observers and bridges.
     * Subsequent delegate reads recreate fresh states.
     *
     * Throws [IllegalStateException] if any state has pending writes in an active
     * transaction.
     */
    fun clearStates() {
        checkNotDisposed()
        propertiesLock.withLock {
            _properties.values.forEach { state ->
                checkNoPendingWrites(state, state.toString())
            }
            _properties.values.forEach { it.shutdownSilently() }
            _properties.clear()
        }
    }

    private fun checkNoPendingWrites(
        state: MutableState<*>,
        name: String,
    ) {
        val txn = _activeTransaction ?: return
        if (state in txn.pendingWrites) {
            error("Cannot remove state '$name' with pending writes in an active transaction; commit or rollback first")
        }
    }

    /**
     * Internal hook for `atomic`, `:holdfast-coroutines` (`suspendAction`,
     * `suspendAtomic`) and `:holdfast-testing`'s open transactions. Sets the
     * active transaction directly without going through the blocking lock —
     * the caller is responsible for serialization (via [asyncSerializer] or
     * [runUnderLock]).
     *
     * Installing a top-level transaction makes the caller a post-commit
     * holder: after clearing the slot and releasing, it must call
     * [internalDrainPostCommitTasks].
     */
    @StoreInternalApi
    fun internalSetActiveTransaction(txn: Transaction?) {
        _activeTransaction = txn
    }

    /**
     * Drain this store's post-commit queue on the calling thread — the same
     * drain as the blocking [action]'s tail. Used by `atomic`,
     * `:holdfast-coroutines` (`suspendAction`, `suspendAtomic`) and
     * `:holdfast-testing`'s open transactions.
     *
     * Any caller that takes this store's serializer, `transactionLock` or
     * active-transaction slot as a TOP-LEVEL holder must call this once it has
     * released all three, on every exit: commit, rollback, body throw and
     * cancellation, including a cancelled mutex acquire. A caller that only
     * opened a savepoint of an enclosing root leaves the drain to that root's
     * holder. Never call it while still holding the serializer or the lock: a
     * queued `derived` recompute would find the store busy, hand itself back
     * to this caller, and be stranded. `derived` recomputes that found the
     * store busy are queued for its current holder and rely on this drain
     * (see `Store.tryTopLevelAction`).
     */
    @StoreInternalApi
    fun internalDrainPostCommitTasks() {
        drainPostCommitTasks()
    }

    @StoreInternalApi
    @Suppress("UNCHECKED_CAST")
    val selfForExternal: Self get() = self

    /**
     * Internal hook for `:holdfast-coroutines.suspendAction`. Returns a stable
     * snapshot of the currently-registered middleware list, taken under the
     * middleware lock — same snapshot semantics as [runMiddlewareChain] uses
     * for the blocking [action] path. The suspending chain runner uses this
     * to invoke each hook directly with its own `runCatching` wrapper, in
     * concentric-ring order matching the sync path: reverse chain order on
     * `started` (LAST-registered = outermost fires first), forward chain
     * order on `completed`/`error` (innermost first; outermost last).
     */
    @StoreInternalApi
    fun snapshotMiddleware(): List<Middleware<Self>> =
        middlewareLock.withLock {
            middlewareList.toList()
        }

    /**
     * Internal hook for `atomic(...)` and `:holdfast-testing`'s open
     * transactions. Runs [block] under this store's `transactionLock`. The
     * reentrant lock makes this safe to call when the same thread already
     * holds the lock (e.g., nested `atomic` calls overlap on a store).
     *
     * A caller that takes the lock as a top-level holder must call
     * [internalDrainPostCommitTasks] once `runUnderLock` has returned or
     * thrown (and once any active-transaction slot it installed is cleared
     * again), not inside [block].
     */
    @StoreInternalApi
    fun <R> runUnderLock(block: () -> R): R = transactionLock.withLock(block)

    /**
     * Internal hook for `atomic(...)` / `:holdfast-coroutines.suspendAtomic`.
     * Snapshots this store's middleware chain once (same semantics as an
     * action-start snapshot) and returns a session whose hooks the frame
     * drives around [txn]:
     *
     *  - [FrameMiddlewareSession.fireStarted] — concentric outermost-first
     *    (LAST-registered fires first), matching `action`'s fold order. NOT
     *    exception-isolated: a throwing `started` aborts the frame.
     *  - [FrameMiddlewareSession.fireCompleted] — innermost-first unwind,
     *    after the body returns and BEFORE any participant commits. NOT
     *    exception-isolated: a throwing `completed` (e.g. validation) rolls
     *    the whole frame back — for frames, `completed` does NOT mean
     *    durably-committed.
     *  - [FrameMiddlewareSession.fireError] — innermost-first, each hook
     *    `runCatching`-isolated, matching the suspending action path.
     *
     * One [Middleware.MiddlewareContext] per middleware is reused across the
     * session so metadata stashed in `started` is readable in `completed`/`error`.
     */
    @StoreInternalApi
    fun internalFrameMiddlewareSession(txn: Transaction): FrameMiddlewareSession {
        val chain = snapshotMiddleware()
        val contexts =
            chain.map {
                Middleware.MiddlewareContext(store = self, transaction = txn)
            }
        return FrameMiddlewareSession(
            started = {
                for (i in chain.indices.reversed()) chain[i].invokeOnTransactionStarted(contexts[i])
            },
            completed = {
                for (i in chain.indices) chain[i].invokeOnTransactionCompleted(contexts[i])
            },
            errored = { e ->
                for (i in chain.indices) runCatching { chain[i].invokeOnTransactionError(contexts[i], e) }
            },
        )
    }

    companion object {
        /**
         * Process-singleton lazy default scope. Initialized on first read of
         * [defaultScope] when no custom value has been assigned. Backs the lazy
         * fallback in the resolution chain.
         */
        private val processScope: CoroutineScope by lazy {
            CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("VaultProcessScope"))
        }

        private val customDefaultScope = atomic<CoroutineScope?>(null)

        /**
         * Process-global default scope used by every [Store] that has neither a
         * per-store override nor a per-call scope argument. Settable at most once
         * per process via CAS — the first non-null assignment wins; subsequent
         * assignments throw [IllegalStateException].
         *
         * Typical app-init pattern:
         * ```
         * val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
         * Store.defaultScope = appScope
         * ```
         *
         * Reads before any explicit assignment return a lazy
         * `SupervisorJob() + Dispatchers.Default` process scope. Reading the lazy
         * default does NOT prevent a subsequent assignment — only an explicit
         * assignment freezes the value.
         */
        var defaultScope: CoroutineScope
            get() = customDefaultScope.value ?: processScope
            set(value) {
                check(customDefaultScope.compareAndSet(null, value)) {
                    "Store.defaultScope is settable-once; it has already been assigned."
                }
            }
    }
}
