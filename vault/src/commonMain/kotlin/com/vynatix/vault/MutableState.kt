package com.vynatix.vault

import com.vynatix.vault.platform.currentThreadId

/**
 * The concrete implementation of [State] used by [Vault]. Carries:
 *  - the stored value
 *  - the observer set
 *  - an optional [Transformer] that normalizes on `set` and projects on `get`
 *  - an optional [Bridge] for two-way sync with an external system
 *
 * You will rarely instantiate this directly — `vault.state { … }` does it for you.
 * This class is `public` (not `internal`) only because the `bridge` setter must be
 * reachable from extension code; everything else of interest is on the [State] interface.
 *
 * Concurrency: every public access is guarded by per-instance locks. The internal
 * commit-time entrypoint ([applyCommitted]) snapshots the value under one lock then
 * notifies observers under another, avoiding the AB-BA risk that nested acquisition
 * would create.
 */
class MutableState<T : Any>(
    initialValue: T,
    private val transformer: Transformer<T>? = null,
    internal val owningVault: Vault<*>,
    internal val distinct: Boolean = false,
) : State<T> {
    private val stateLock = VaultLock()
    private val observersLock = VaultLock()
    private val bridgeLock = VaultLock()

    private val observers = mutableSetOf<(T) -> Unit>()

    /**
     * Test-only window onto the live observer set size. Unlike a "total observers
     * ever attached" counter, this reflects the *current* number of live
     * subscriptions — adds and disposes both move it. Reads under [observersLock]
     * so the count is consistent with concurrent [observe]/dispose.
     *
     * Marked `@VaultInternalApi`: companion-module test code (e.g. `:vault-coroutines`)
     * uses it to verify that `Flow`/`StateFlow`/`effect` adapters correctly dispose
     * their underlying observer registration on consumer cancellation. Application
     * code must never read this — depending on observer count couples consumers to
     * the internal subscription model.
     */
    @VaultInternalApi
    val observerCount: Int
        get() = observersLock.withLock { observers.size }

    @kotlin.concurrent.Volatile
    private var currentValue: T = initialValue

    @kotlin.concurrent.Volatile
    private var currentBridge: Bridge<T>? = null

    /**
     * The Disposable returned by `currentBridge?.observe { … }` when a bridge was
     * attached. We dispose this when the bridge is replaced or set to null;
     * otherwise the previous bridge would keep an active observer registration
     * that drives [applyFromBridge] indefinitely.
     */
    @kotlin.concurrent.Volatile
    private var currentBridgeSubscription: Disposable? = null

    /**
     * Read-your-own-writes-aware view of the state.
     *
     * On the owning thread of an active transaction, this walks the savepoint chain
     * (innermost → outermost) for any pending write and returns post-`transformer.get`
     * of it. Otherwise returns post-`transformer.get` of the committed `currentValue`.
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

    /**
     * Pure: applies `transformer.set` to compute the post-set value to buffer in the
     * transaction. No lock — `transformer.set` is assumed pure.
     */
    internal fun beforeSet(newValue: T): T = transformer?.takeIf { it.shouldTransform(newValue) }?.set(newValue) ?: newValue

    /**
     * Raw access to the committed `currentValue`, bypassing `transformer.get`.
     * Used by [Vault.snapshot] to capture the on-disk-equivalent representation
     * (ciphertext, post-`transformer.set` form, etc.) so [Vault.restore] can
     * round-trip without re-running `transformer.set`.
     */
    internal val rawCurrentValue: T
        get() = stateLock.withLock { currentValue }

    /**
     * Commit-time apply (raw): writes `currentValue` and notifies observers, but
     * **does NOT publish to the bridge**. Strict subset of [applyCommitted]
     * exposed as a `@VaultInternalApi` extension hook so companion modules
     * (notably `:vault-coroutines.suspendAction`) can interpose between the
     * observer fanout (step 1+2) and the bridge publish (step 3) — necessary
     * for [com.vynatix.vault.coroutines.SuspendingBridge.publishAwaited] to be
     * awaited under `withContext(NonCancellable)` instead of fire-and-forget.
     *
     * If [distinct] is true and the new processed value is `==` to
     * `currentValue`, skips observer fanout (opt-in dedup). Bridge publish is
     * the caller's responsibility — they must not call `bridge.publish` either
     * if this returns silently due to dedup.
     *
     * Returns `true` if the value was applied (observer fanout fired), `false`
     * if it was deduped. The boolean lets the caller skip their own bridge
     * publish in the dedup case.
     */
    @VaultInternalApi
    fun applyCommittedRaw(processedValue: T): Boolean {
        val unchanged = stateLock.withLock {
            val same = distinct && currentValue == processedValue
            if (!same) currentValue = processedValue
            same
        }
        if (unchanged) return false
        notifyObservers(afterGet(processedValue))
        return true
    }

    /**
     * Commit-time apply: writes `currentValue`, notifies observers, publishes to bridge.
     * The single observable side effect of a successful commit. Lock-order: snapshot
     * under `stateLock`, release, then notify outside `stateLock` to avoid AB-BA with
     * [observe] which acquires `observersLock` then briefly `stateLock`.
     *
     * If [distinct] is true and the new processed value is `==` to `currentValue`,
     * skips both observer fanout and bridge publish (opt-in dedup).
     */
    internal fun applyCommitted(processedValue: T) {
        @OptIn(VaultInternalApi::class)
        if (!applyCommittedRaw(processedValue)) return
        bridgeLock.withLock { currentBridge?.publish(processedValue) }
    }

    /**
     * Bridge-driven update: writes `currentValue` and notifies observers, but does
     * NOT call `currentBridge?.publish` — preventing a publish loop with the source
     * that originated this update. Bridges bypass the transactional path entirely;
     * they are an external sync mechanism.
     */
    internal fun applyFromBridge(rawValue: T) {
        val processed = beforeSet(rawValue)
        stateLock.withLock {
            currentValue = processed
        }
        notifyObservers(afterGet(processed))
    }

    private fun notifyObservers(value: T) {
        val handler = owningVault.uncaughtObserverHandler
        observersLock.withLock {
            observers.toSet().forEach { observer ->
                try {
                    observer(value)
                } catch (e: Throwable) {
                    handler?.invoke(e)
                }
            }
        }
    }

    /**
     * Subscribe to commits. Fires once immediately with the current value
     * (post-`transformer.get`), then once for every successful top-level commit
     * that includes this state in its pending writes.
     *
     * Returns a [Disposable] that removes the observer when called. Double-dispose
     * is safe (idempotent).
     *
     * Internal: the public surface is the top-level [com.vynatix.vault.effect]
     * extension. Companion modules in the same library group (`:vault-coroutines`,
     * `:vault-validation`) reach this directly through the package-internal
     * visibility for adapter implementations (Flow/StateFlow). Application code
     * must use [effect].
     */
    internal fun observe(observer: (T) -> Unit): Disposable = observersLock.withLock {
        observers.add(observer)
        // Initial callback uses the same view as the value getter — post-transformer.get —
        // so an observer never sees a value the getter wouldn't return for the same state.
        val current = stateLock.withLock { afterGet(currentValue) }
        observer(current)

        return Disposable {
            observersLock.withLock {
                observers.remove(observer)
            }
        }
    }

    /**
     * Two-way bridge to an external system. Setting attaches:
     *  - the bridge's [Observable.observe] is invoked, which typically fires once
     *    with any persisted/replayed value (load-on-attach). The returned
     *    [Disposable] is captured so it can be disposed on swap or null-set.
     *  - on every successful commit, [Publisher.publish] is called with the raw
     *    stored value (save-on-commit).
     *
     * Setting to null detaches: the previous bridge's inbound observer is disposed
     * and no further commits are published.
     */
    var bridge: Bridge<T>?
        get() = bridgeLock.withLock { currentBridge }
        set(value) = bridgeLock.withLock {
            // Dispose the previous inbound observer registration so the previous
            // bridge does not keep driving applyFromBridge after replacement/null.
            currentBridgeSubscription?.dispose()
            currentBridgeSubscription = null
            currentBridge = value
            currentBridgeSubscription = value?.observe { receivedValue ->
                applyFromBridge(receivedValue)
            }
        }

    /**
     * Internal entrypoint used by `Vault.removeState`/`clearStates` to release
     * resources without firing any observer notifications. Drops the observer set
     * and detaches any attached bridge (disposing its inbound subscription).
     */
    internal fun shutdownSilently() {
        observersLock.withLock { observers.clear() }
        bridgeLock.withLock {
            currentBridgeSubscription?.dispose()
            currentBridgeSubscription = null
            currentBridge = null
        }
    }
}

/**
 * Test-only window onto the live observer count for a [State]. Convenience
 * extension so test code can read it from a [State] reference (the public
 * surface) without an explicit cast to [MutableState]. Throws if the [State]
 * was not produced by `vault.state { … }` — only [MutableState] instances
 * carry an observer set.
 *
 * Marked `@VaultInternalApi`: companion-module test code (e.g. `:vault-coroutines`)
 * uses it to verify that `Flow`/`StateFlow`/`effect` adapters dispose their
 * underlying observer registration on consumer cancellation. Application code
 * must never opt in.
 */
@VaultInternalApi
val <T : Any> State<T>.observerCount: Int
    get() {
        val ms = (this as? MutableState<T>) ?: error("observerCount is only defined for MutableState (vault.state { ... })")
        return ms.observerCount
    }
