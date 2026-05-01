package com.vynatix.vault

import com.vynatix.vault.platform.currentThreadId

class MutableState<T : Any>(initialValue: T, private val transformer: Transformer<T>? = null, internal val owningVault: Vault<*>) :
    State<T> {
    private val stateLock = VaultLock()
    private val observersLock = VaultLock()
    private val bridgeLock = VaultLock()

    private val observers = mutableSetOf<(T) -> Unit>()

    @kotlin.concurrent.Volatile
    private var currentValue: T = initialValue

    @kotlin.concurrent.Volatile
    private var currentBridge: Bridge<T>? = null

    /**
     * Read-your-own-writes-aware view of the state.
     *
     * On the owning thread of an active transaction, walks the savepoint chain for any
     * pending write and returns post-`transformer.get` of it. Otherwise returns
     * post-`transformer.get` of the committed `currentValue`.
     *
     * Off-owner-thread reads only see the committed value, never another thread's
     * uncommitted pending writes.
     */
    override val value: T
        get() = stateLock.withLock {
            val txn = owningVault.activeTransaction
            if (txn != null && txn.ownerThreadId == currentThreadId()) {
                val pending = txn.findPendingValue(this)
                if (pending != null) return@withLock afterGet(pending)
            }
            afterGet(currentValue)
        }

    private fun afterGet(rawValue: T): T = transformer?.takeIf { it.shouldTransform(rawValue) }?.get(rawValue) ?: rawValue

    // Pure: applies transformer.set. Used by Vault.mutate to compute the post-set
    // value to buffer in the transaction. No lock — transformer.set is assumed pure.
    internal fun beforeSet(newValue: T): T = transformer?.takeIf { it.shouldTransform(newValue) }?.set(newValue) ?: newValue

    // Commit-time apply: writes currentValue, then notifies observers and bridge.
    // Lock-order fix (#14): snapshot under stateLock, release, then notify
    // outside stateLock. Avoids AB-BA deadlock with observe().
    internal fun applyCommitted(processedValue: T) {
        stateLock.withLock {
            currentValue = processedValue
        }
        notifyObservers(afterGet(processedValue))
        bridgeLock.withLock { currentBridge?.publish(processedValue) }
    }

    // Bridge-driven update: writes currentValue, notifies observers, but does NOT republish
    // (preventing publish loops with the source bridge). Bridges bypass the
    // transactional path entirely — they're an external sync mechanism.
    internal fun applyFromBridge(rawValue: T) {
        val processed = beforeSet(rawValue)
        stateLock.withLock {
            currentValue = processed
        }
        notifyObservers(afterGet(processed))
    }

    private fun notifyObservers(value: T) = observersLock.withLock {
        observers.toSet().forEach { observer ->
            try {
                observer(value)
            } catch (_: Exception) {
                // Observer notification failure is intentionally swallowed.
            }
        }
    }

    fun observe(observer: (T) -> Unit): Disposable = observersLock.withLock {
        observers.add(observer)
        // Initial callback uses the same view as the value getter — post-transformer.get.
        // Fixes bug #7 (3-way observer/getter inconsistency).
        val current = stateLock.withLock { afterGet(currentValue) }
        observer(current)

        return Disposable {
            observersLock.withLock {
                observers.remove(observer)
            }
        }
    }

    var bridge: Bridge<T>?
        get() = bridgeLock.withLock { currentBridge }
        set(value) = bridgeLock.withLock {
            currentBridge = value
            value?.observe { receivedValue ->
                applyFromBridge(receivedValue)
            }
        }
}
