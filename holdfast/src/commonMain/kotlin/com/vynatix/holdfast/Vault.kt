@file:OptIn(VaultInternalApi::class)

package com.vynatix.vault

import com.vynatix.vault.platform.currentThreadId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Process-monotonic counter used to assign each [Vault] a stable [Vault.lockOrderKey]
 * at construction. Used by `atomic(...)` to acquire multi-vault locks in a
 * deadlock-safe global order.
 */
private val vaultLockOrderKeyGen = atomic(0L)

/**
 * Base class for transactional state containers.
 *
 * A Vault holds a set of named [State] properties created via [state]. Mutations are
 * grouped into transactional [action] blocks: changes buffer, observe the
 * read-your-own-writes view on the action's owner thread, and only become visible
 * to observers and bridges on a successful commit. A throwing action body discards
 * the buffer atomically.
 *
 * Concurrency contract:
 *  - All reads and writes through `mutate`/`action` are serialized via a per-vault
 *    reentrant lock.
 *  - [activeTransaction] is volatile; reads from any thread are valid for inspection
 *    but must not be relied on for race-free decisions outside the owner thread.
 *  - `mutate` from a thread that does not own the active transaction synthesizes its
 *    own one-shot transaction (this is intentional, not a bug — middleware fires and
 *    observers see only the committed value).
 *
 * Typical subclass:
 * ```
 * class CounterVault : Vault<CounterVault>() {
 *     val count by state { 0 }
 *     val label by state { "init" }
 * }
 * ```
 */
@VaultActionDsl
@Suppress("TooManyFunctions") // The Vault DSL is intentionally broad; each member is a single primitive.
abstract class Vault<Self : Vault<Self>> {
    /**
     * Process-monotonic ordering key, set once at construction. `atomic(v1, v2, …)`
     * sorts its vault arguments by this key before acquiring locks, giving
     * deadlock-safe global ordering across any combination of vaults.
     */
    @VaultInternalApi
    val lockOrderKey: Long = vaultLockOrderKeyGen.incrementAndGet()

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
     * The [CoroutineScope] this vault's long-running async work runs on. Resolution order
     * (per-call → per-vault override → bound → process-global default):
     *
     *  1. **Per-call** — APIs that take an explicit `scope: CoroutineScope` parameter use that.
     *  2. **Per-vault** — a subclass may `override val scope: CoroutineScope` (use a getter,
     *     not a `val` initializer, to avoid lazy-init order traps in singleton vaults).
     *     A subclass override sits ABOVE this property in the resolution chain, so it
     *     beats any [bindToScope] call.
     *  3. **Bound** — the scope passed to the most recent [bindToScope] call on this
     *     vault instance, if any. Rebindable.
     *  4. **Global** — falls back to [Vault.Companion.defaultScope].
     */
    open val scope: CoroutineScope
        get() = boundScope ?: defaultScope

    /**
     * Bind this vault to [scope] for resolution level 3 (see [scope]). After this call,
     * `vault.scope` returns [scope] (unless a subclass has its own `override val scope`,
     * which beats the bound scope). Calling [bindToScope] again replaces the binding.
     *
     * Thread safety: the binding field is `@Volatile`; the latest write becomes
     * visible to all readers. Concurrent callers race in the obvious way (last write
     * wins) — bind once at app init, or guard externally if multiple components own
     * the binding.
     *
     * Lifecycle note: [bindToScope] does NOT cancel the previously-bound scope and
     * does NOT cancel the new scope when the vault is later disposed. Scope lifetimes
     * are owned by the caller. See [dispose] for terminal teardown of the vault.
     */
    fun bindToScope(scope: CoroutineScope) {
        boundScope = scope
    }

    /**
     * Atomic disposed flag. CAS'd to `true` exactly once on the first [dispose] call;
     * subsequent calls observe `true` and return without throwing (idempotent contract).
     * Every public entry point reads this — when `true`, they throw
     * `IllegalStateException("vault disposed")`.
     */
    private val disposedFlag = atomic(false)

    /**
     * Whether [dispose] has been called on this vault. Once `true`, every public
     * mutation entrypoint (`action`, `mutate`, `update`, `effect`, `bridge`,
     * `observeFrom`, `removeState`, `clearStates`, etc.) and every state-registry
     * read throws [IllegalStateException]. Cold APIs in companion modules
     * (e.g. `:vault-coroutines.asFlow`/`first`/`awaitValue`) MUST also check this
     * before establishing observer subscriptions.
     */
    val isDisposed: Boolean get() = disposedFlag.value

    /**
     * Terminally tear down this vault. Idempotent.
     *
     * After `dispose()`:
     *  - Every state-mutation API throws `IllegalStateException("vault disposed")`.
     *  - Every state-registry read API throws.
     *  - All registered observers are dropped; all bridges are detached.
     *  - The [Vault.scope] / bound scope is **NOT** cancelled — caller owns its lifecycle.
     *    `dispose()` is asymmetric with scope cancellation: cancelling the bound scope is
     *    a soft-pause (subsequent calls fall back to `defaultScope`); `dispose()` is terminal.
     *
     * Subclasses with additional resources (e.g. `EventfulVault`'s events SharedFlow)
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
            postCommitTasks.clear()
        }
        // Snapshot the property map under its lock, then call shutdownSilently outside
        // any vault-side lock — `shutdownSilently` takes the per-state observer + bridge
        // locks, and we don't want to invert ordering.
        val toShutdown = propertiesLock.withLock {
            val snap = _properties.values.toList()
            _properties.clear()
            snap
        }
        toShutdown.forEach { runCatching { it.shutdownSilently() } }
        // Drop middleware so a stray reference to a disposed vault can't keep
        // captured state alive.
        middlewareLock.withLock { middlewareList.clear() }
        // Subclass hook: EventfulVault uses this to reset its events SharedFlow.
        runCatching { onDispose() }
    }

    /**
     * Subclass hook invoked once, AFTER the base `dispose()` has cleared all states,
     * observers, bridges, and middleware. Override to release subclass-owned resources
     * (e.g. `EventfulVault` resets its events SharedFlow). Default no-op.
     *
     * Always wrapped in `runCatching` by [dispose] so a misbehaving override can't
     * leave the vault half-disposed.
     */
    protected open fun onDispose() {}

    private fun checkNotDisposed() {
        if (disposedFlag.value) error("vault disposed")
    }

    private val transactionLock = VaultLock()
    private val propertiesLock = VaultLock()
    private val middlewareLock = VaultLock()

    @kotlin.concurrent.Volatile
    private var _activeTransaction: Transaction? = null

    /**
     * The transaction currently being built on this Vault, if any. Direct volatile
     * read — cross-thread observers see the most recent set without acquiring a lock.
     * `null` between actions; non-null only on the action's owner thread for the
     * duration of the action body.
     */
    val activeTransaction: Transaction?
        get() = _activeTransaction

    private val _properties = mutableMapOf<String, MutableState<*>>()

    /**
     * Snapshot view of every state currently registered with this vault, keyed by
     * property name. The map is a copy — modifying it does not affect the vault.
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
     * Optional handler invoked when a commit-fire observer callback throws. If null
     * (the default), exceptions thrown from observer bodies during commit are
     * swallowed silently — matching the original library contract. Set to a non-null
     * handler to surface them (e.g. a logger or a test fixture's failure list).
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
     * with this vault's blocking [action]. Set at most once, by the
     * `:vault-coroutines` `suspendAction` extension when it first wraps a
     * suspending body — the serializer's blocking acquire/release brackets every
     * blocking [action] call so that concurrent suspending callers see a serial
     * stream of actions.
     *
     * Marked `@VaultInternalApi` because it's an extension point for companion
     * modules, not a user-facing knob. If null (the default), `action` runs
     * unwrapped — the legacy fast path.
     */
    interface AsyncSerializer {
        fun blockingAcquire()
        fun blockingRelease()
    }

    @VaultInternalApi
    @kotlin.concurrent.Volatile
    var asyncSerializer: AsyncSerializer? = null

    /**
     * Set while a `suspendAction` body is in flight. While non-null, `mutate`
     * additionally accepts callers on threads that aren't the txn's owner thread,
     * because the suspending body may resume on different threads via coroutine
     * dispatch. The [AsyncSerializer] guarantees no other action runs concurrently,
     * so the relaxed ownership check is sound.
     */
    @VaultInternalApi
    @kotlin.concurrent.Volatile
    var suspendingOwner: Any? = null

    /**
     * Tasks queued during an in-progress action that should run AFTER the current
     * top-level action's commit fanout completes. Used by [derived] to defer
     * recompute actions out of the parent's commit loop — this avoids re-entering
     * `pendingWrites` while the parent is iterating it.
     *
     * Owner-thread-confined; safe to read/write only on the owning thread.
     */
    private val postCommitTasks = mutableListOf<() -> Unit>()

    /**
     * Schedule [task] to run after the current top-level action's commit fanout
     * finishes. If called outside any action, the task runs immediately.
     *
     * Used by `derived(...)` to enqueue its recompute on a fresh top-level action
     * instead of re-entering the parent's commit. Also reachable by companion
     * modules (`:vault-coroutines.suspendDerived`) that need the same deferral
     * contract; marked `@VaultInternalApi` because the deferral is an
     * implementation detail of the derived-recompute machinery, not a
     * user-facing knob.
     */
    @VaultInternalApi
    fun postCommit(task: () -> Unit) {
        if (_activeTransaction != null) {
            postCommitTasks.add(task)
        } else {
            task()
        }
    }

    private fun drainPostCommitTasks() {
        // Drain to a local copy so any task that queues another doesn't perturb
        // our iteration. Tasks scheduled by tasks land in postCommitTasks and
        // are picked up by the next iteration of this loop.
        while (postCommitTasks.isNotEmpty()) {
            val drained = postCommitTasks.toList()
            postCommitTasks.clear()
            drained.forEach { runCatching { it() } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val self: Self get() = this as Self

    /**
     * Append [middleware] to the chain. Order matters: the LAST argument is the
     * outermost middleware (its `onTransactionStarted` runs first; its `completed`
     * or `onTransactionError` runs last). Earlier-listed middlewares are inner.
     *
     * Same ordering applies to both blocking [action] and suspending
     * `:vault-coroutines.suspendAction` (issue 31).
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
     * val r = vault action { compute() }
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
        // If a suspending caller (vault-coroutines.suspendAction) has installed a
        // serializer, block here until they release. This makes blocking action and
        // suspending action mutually exclusive on the same vault.
        val serializer = asyncSerializer
        serializer?.blockingAcquire()
        try {
            return runBlockingActionUnderLock(body)
        } finally {
            serializer?.blockingRelease()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun <R> runBlockingActionUnderLock(body: Self.() -> R): TransactionResult<R> = transactionLock.withLock {
        val parent = _activeTransaction
        val txn = Transaction(
            id = body::class.simpleName ?: Uuid.random().toString(),
            parent = parent,
            ownerThreadId = currentThreadId(),
        )

        _activeTransaction = txn
        // Box for capturing the body's return value so we can pipe it into Success.
        // Holds null before the body runs; holds (result) after.
        val box = arrayOfNulls<Any?>(1)
        val outcome: TransactionResult<R> = try {
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
        // Drain post-commit tasks only at top-level exit — nested actions inherit
        // the parent's deferred queue and let it drain at the outermost boundary.
        if (parent == null) drainPostCommitTasks()
        outcome
    }

    private fun runMiddlewareChain(block: () -> Unit) {
        middlewareLock.withLock {
            val currentMiddleware = middlewareList.toList()
            currentMiddleware.fold(block) { acc, middleware ->
                { middleware(self, acc) }
            }.invoke()
        }
    }

    /**
     * Plain context block — runs `block(self)` with no locks and no transaction.
     * Provides the `Self` receiver so member-extensions like [effect] and [bridge]
     * can be called fluently: `vault { count effect { … } }`.
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
    fun <T : Any> state(transformer: Transformer<T>? = null, distinct: Boolean = false, initialize: Initializer<T>): StateDelegate<T> {
        val owningVault: Vault<*> = this
        return StateDelegate { _, property ->
            checkNotDisposed()
            propertiesLock.withLock {
                val existing = _properties[property.name]
                if (existing != null) {
                    @Suppress("UNCHECKED_CAST")
                    existing as MutableState<T>
                } else {
                    MutableState(initialize(), transformer, owningVault, distinct).also { state ->
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
     * (`:vault-coroutines.suspendDerived`) for the suspending-derived backing
     * state; marked `@VaultInternalApi` because the synthesized name scheme
     * is an implementation detail.
     */
    @VaultInternalApi
    fun <T : Any> registerInternalState(
        name: String,
        initial: T,
        transformer: Transformer<T>? = null,
        distinct: Boolean = false,
    ): MutableState<T> = propertiesLock.withLock {
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
     * vault action {
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
     * O(1) ownership check via [MutableState.owningVault]. Throws if [State] was
     * created by a different vault — without this, a foreign-vault state would
     * silently pass the type cast and corrupt either vault's state.
     */
    private fun <T : Any> State<T>.getMutableState(): MutableState<T> {
        @Suppress("UNCHECKED_CAST")
        val ms = (this as? MutableState<T>) ?: error("State must be created by this Vault instance")
        if (ms.owningVault !== this@Vault) error("State must be created by this Vault instance")
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

    private fun checkNoPendingWrites(state: MutableState<*>, name: String) {
        val txn = _activeTransaction ?: return
        if (state in txn.pendingWrites) {
            error("Cannot remove state '$name' with pending writes in an active transaction; commit or rollback first")
        }
    }

    /**
     * Internal hook for `:vault-coroutines.suspendAction`. Sets the active
     * transaction directly without going through the blocking lock — the caller
     * is responsible for serialization (via [asyncSerializer]).
     */
    @VaultInternalApi
    fun internalSetActiveTransaction(txn: Transaction?) {
        _activeTransaction = txn
    }

    /**
     * Internal hook for `:vault-coroutines.suspendAction`. Drains any post-commit
     * tasks queued during the suspending action — same semantics as the
     * blocking action's tail drain.
     */
    @VaultInternalApi
    fun internalDrainPostCommitTasks() {
        drainPostCommitTasks()
    }

    @VaultInternalApi
    @Suppress("UNCHECKED_CAST")
    val selfForExternal: Self get() = self

    /**
     * Internal hook for `:vault-coroutines.suspendAction`. Returns a stable
     * snapshot of the currently-registered middleware list, taken under the
     * middleware lock — same snapshot semantics as [runMiddlewareChain] uses
     * for the blocking [action] path. The suspending chain runner uses this
     * to invoke each hook directly with its own `runCatching` wrapper, in
     * concentric-ring order matching the sync path: reverse chain order on
     * `started` (LAST-registered = outermost fires first), forward chain
     * order on `completed`/`error` (innermost first; outermost last).
     */
    @VaultInternalApi
    fun snapshotMiddleware(): List<Middleware<Self>> = middlewareLock.withLock {
        middlewareList.toList()
    }

    /**
     * Internal hook for `atomic(...)`. Runs [block] under this vault's
     * `transactionLock`. The reentrant lock makes this safe to call when the
     * same thread already holds the lock (e.g., nested `atomic` calls overlap
     * on a vault).
     */
    @VaultInternalApi
    fun <R> runUnderLock(block: () -> R): R = transactionLock.withLock(block)

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
         * Process-global default scope used by every [Vault] that has neither a
         * per-vault override nor a per-call scope argument. Settable at most once
         * per process via CAS — the first non-null assignment wins; subsequent
         * assignments throw [IllegalStateException].
         *
         * Typical app-init pattern:
         * ```
         * val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
         * Vault.defaultScope = appScope
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
                    "Vault.defaultScope is settable-once; it has already been assigned."
                }
            }
    }
}
