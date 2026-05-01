package com.vynatix.vault

import com.vynatix.vault.platform.currentThreadId

abstract class Vault<Self : Vault<Self>> {
    private val transactionLock = VaultLock()
    private val propertiesLock = VaultLock()
    private val middlewareLock = VaultLock()

    @kotlin.concurrent.Volatile
    private var _activeTransaction: Transaction? = null

    /**
     * The transaction currently being built on this Vault, if any. Direct volatile read —
     * cross-thread observers see the most recent set without acquiring a lock.
     */
    val activeTransaction: Transaction?
        get() = _activeTransaction

    private val _properties = mutableMapOf<String, MutableState<*>>()
    val properties: Map<String, State<*>>
        get() = propertiesLock.withLock { _properties.toMap() }

    private val middlewareList = mutableListOf<Middleware<Self>>()

    @Suppress("UNCHECKED_CAST")
    private val self: Self get() = this as Self

    fun middlewares(vararg middleware: Middleware<Self>) {
        middlewareLock.withLock {
            middlewareList.addAll(middleware)
        }
    }

    fun clearMiddleware() {
        middlewareLock.withLock {
            middlewareList.clear()
        }
    }

    /**
     * Run [action] inside a transaction. Mutations are buffered in
     * [Transaction.pendingWrites]; on success they apply to state via
     * [MutableState.applyCommitted] and observers/bridges fire then. On throw,
     * pending writes are dropped — no state, observer, or bridge is touched.
     *
     * Nested actions form a savepoint stack: the inner transaction's parent is the
     * outer transaction. Inner.commit merges its pending writes into the outer.
     * Inner.rollback drops just the savepoint. Outer.rollback discards everything,
     * including merged inner writes.
     */
    infix fun action(action: Self.() -> Unit): TransactionResult = transactionLock.withLock {
        val parent = _activeTransaction
        val txn = Transaction(
            id = action::class.simpleName ?: UUID.randomUUID().toString(),
            parent = parent,
            ownerThreadId = currentThreadId(),
        )

        _activeTransaction = txn
        val outcome: TransactionResult = try {
            runMiddlewareChain { action(self) }
            try {
                txn.commit()
                TransactionResult.Success(txn)
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

    operator fun <R> invoke(block: Self.() -> R): R = block(self)

    fun <T : Any> state(transformer: Transformer<T>? = null, initialize: Initializer<T>): StateDelegate<T> {
        val owningVault: Vault<*> = this
        return StateDelegate { _, property ->
            propertiesLock.withLock {
                val existing = _properties[property.name]
                if (existing != null) {
                    @Suppress("UNCHECKED_CAST")
                    existing as MutableState<T>
                } else {
                    MutableState(initialize(), transformer, owningVault).also { state ->
                        _properties[property.name] = state
                    }
                }
            }
        }
    }

    infix fun <T : Any> State<T>.effect(effect: T.() -> Unit): Disposable = this.getMutableState().observe(effect::invoke)

    infix fun <T : Any> State<T>.bridge(bridge: Bridge<T>) {
        this.getMutableState().apply {
            this@apply.bridge = bridge
        }
    }

    /**
     * Buffer-then-commit mutate. Inside an active transaction owned by this thread,
     * the post-`transformer.set` value is staged in the transaction's pending writes —
     * observers and bridges see nothing until commit (fixes bugs #2, #3, #6, #10).
     *
     * Outside any transaction (or on a non-owner thread), an implicit single-shot
     * transaction wraps the mutation so middleware fires and observers see only the
     * committed value (fixes bug #12).
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

    private fun <T : Any> State<T>.getMutableState(): MutableState<T> = propertiesLock.withLock {
        // Ownership check: a State must have been registered with this Vault.
        // Without this, mutations of foreign-vault states would silently pass the type cast.
        // Fixes bug #5.
        val isOwned = _properties.values.any { it === this@getMutableState }
        if (!isOwned) error("State must be created by this Vault instance")
        @Suppress("UNCHECKED_CAST")
        this@getMutableState as MutableState<T>
    }

    fun getState(name: String): State<*>? = propertiesLock.withLock {
        _properties[name]
    }

    fun hasState(name: String): Boolean = propertiesLock.withLock {
        _properties.containsKey(name)
    }

    fun removeState(name: String) {
        propertiesLock.withLock {
            _properties.remove(name)
        }
    }

    fun clearStates() {
        propertiesLock.withLock {
            _properties.clear()
        }
    }
}
