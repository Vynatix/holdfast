package com.vynatix.vault

import com.vynatix.vault.platform.currentThreadId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
        get() = propertiesLock.withLock { _properties.toMap() }

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

    @Suppress("UNCHECKED_CAST")
    private val self: Self get() = this as Self

    /**
     * Append [middleware] to the chain. Order matters: the LAST argument is the
     * outermost middleware (its `onTransactionStarted` runs first; its `completed`
     * or `onTransactionError` runs last). Earlier-listed middlewares are inner.
     *
     * Practical implication: for an `onTransactionError` handler to see exceptions
     * thrown by another middleware, it must be listed AFTER that middleware. Place
     * a logging/audit middleware LAST so it sees errors from validation middleware
     * placed earlier.
     */
    fun middlewares(vararg middleware: Middleware<Self>) {
        middlewareLock.withLock {
            middlewareList.addAll(middleware)
        }
    }

    /** Drop every registered middleware. */
    fun clearMiddleware() {
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
    infix fun <R> action(body: Self.() -> R): TransactionResult<R> = transactionLock.withLock {
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
     * Subscribe to commits on this state. The receiver `T` of [effect] is the new
     * value (post-`transformer.get`). The returned [Disposable] removes the observer.
     */
    infix fun <T : Any> State<T>.effect(effect: T.() -> Unit): Disposable = this.getMutableState().observe(effect::invoke)

    /**
     * Attach (or detach, when null) a [Bridge] for two-way external sync.
     * On attach, the bridge's `observe` is invoked immediately — implementations
     * typically replay any persisted value here for load-on-attach.
     * On detach, the previous bridge's inbound observer is disposed.
     */
    infix fun <T : Any> State<T>.bridge(bridge: Bridge<T>?) {
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
        val state = this.getMutableState()
        val txn = _activeTransaction
        val onOwnerThread = txn != null && txn.ownerThreadId == currentThreadId()

        if (txn != null && onOwnerThread) {
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
    fun getState(name: String): State<*>? = propertiesLock.withLock {
        _properties[name]
    }

    /** Whether a state with [name] has been registered. */
    fun hasState(name: String): Boolean = propertiesLock.withLock {
        _properties.containsKey(name)
    }

    /**
     * Drop the named state from the registry and silently dispose its observers
     * and bridge. A subsequent delegate read recreates the state from its initializer.
     *
     * Throws [IllegalStateException] if the state has pending writes in an active
     * transaction (caller must commit or roll back first).
     */
    fun removeState(name: String) {
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
}
