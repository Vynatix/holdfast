@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId

/**
 * The concrete implementation of [State] used by [Store]. Carries:
 *  - the stored value
 *  - the observer set
 *  - an optional [Transformer] that normalizes on `set` and projects on `get`
 *  - an optional [Bridge] for two-way sync with an external system
 *
 * You will rarely instantiate this directly — `store.state { … }` does it for you.
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
    @property:StoreInternalApi
    val owningStore: Store<*>,
    internal val distinct: Boolean = false,
) : State<T> {
    /** Deprecated alias for [owningStore], kept for one minor release. */
    @Deprecated(
        message = "Renamed to owningStore.",
        replaceWith = ReplaceWith("owningStore"),
        level = DeprecationLevel.WARNING,
    )
    @StoreInternalApi
    val owningVault: Store<*>
        get() = owningStore

    private val stateLock = StoreLock()
    private val observersLock = StoreLock()
    private val bridgeLock = StoreLock()

    private val observers = mutableSetOf<(T) -> Unit>()

    /**
     * Test-only window onto the live observer set size. Unlike a "total observers
     * ever attached" counter, this reflects the *current* number of live
     * subscriptions — adds and disposes both move it. Reads under [observersLock]
     * so the count is consistent with concurrent [observe]/dispose.
     *
     * Marked `@StoreInternalApi`: companion-module test code (e.g. `:holdfast-coroutines`)
     * uses it to verify that `Flow`/`StateFlow`/`effect` adapters correctly dispose
     * their underlying observer registration on consumer cancellation. Application
     * code must never read this — depending on observer count couples consumers to
     * the internal subscription model.
     */
    @StoreInternalApi
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
        get() =
            stateLock.withLock {
                val txn = owningStore.activeTransaction
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
     * Used by [Store.snapshot] to capture the on-disk-equivalent representation
     * (ciphertext, post-`transformer.set` form, etc.) so [Store.restore] can
     * round-trip without re-running `transformer.set`.
     */
    internal val rawCurrentValue: T
        get() = stateLock.withLock { currentValue }

    /**
     * Commit pass 1 — **assignment only**. Writes `currentValue` and returns
     * whether the value changed; runs NO user code, so it cannot throw and
     * therefore cannot tear a commit part-way through its pending writes.
     * [Transaction.commitDispatching] applies every pending write with this
     * before any fanout begins.
     *
     * If [distinct] is true and the new processed value is `==` to
     * `currentValue`, nothing is written and this returns `false` — the caller
     * skips both fanout phases for this state (opt-in dedup).
     */
    @StoreInternalApi
    fun applyCommittedValue(processedValue: T): Boolean =
        stateLock.withLock {
            val same = distinct && currentValue == processedValue
            if (!same) currentValue = processedValue
            !same
        }

    /**
     * Commit pass 2 — observer fanout, for a state [applyCommittedValue] just
     * changed. Runs outside `stateLock` to avoid AB-BA with [observe], which
     * acquires `observersLock` then briefly `stateLock`.
     *
     * Never throws: a failing `transformer.get` is reported through
     * [Store.uncaughtObserverHandler] and skips this state's observers. Commit
     * fanout is post-commit side effect, so it must not be able to abort a
     * commit whose values are already applied.
     */
    @StoreInternalApi
    fun fanOutToObservers(processedValue: T) {
        runCatching { afterGet(processedValue) }
            .fold(
                onSuccess = { notifyObservers(it) },
                onFailure = { reportFanoutFailure(it) },
            )
    }

    /**
     * Commit pass 3 — bridge publish, for a state [applyCommittedValue] just
     * changed. Never throws: a failing `publish` (a full disk, an encoder error)
     * is reported through [Store.uncaughtObserverHandler].
     *
     * A bridge is external sync, not a transaction participant — `atomic`'s KDoc
     * already states that persistence publishes carry no crash-consistency — so
     * a failed publish cannot undo an applied value, and must not prevent the
     * remaining states of the same transaction from publishing.
     */
    @StoreInternalApi
    fun publishToBridge(processedValue: T) {
        runCatching { bridgeLock.withLock { currentBridge?.publish(processedValue) } }
            .onFailure { reportFanoutFailure(it) }
    }

    private fun reportFanoutFailure(error: Throwable) {
        owningStore.uncaughtObserverHandler?.invoke(error)
    }

    /**
     * Human-readable identity of the store that owns this state, for failure
     * messages. Falls back to `"Store"` on targets without class simple names.
     */
    internal fun describeOwner(): String = owningStore::class.simpleName ?: "Store"

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
        val handler = owningStore.uncaughtObserverHandler
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
     * Internal: the public surface is the top-level [com.vynatix.holdfast.effect]
     * extension. Companion modules in the same library group (`:holdfast-coroutines`,
     * `:holdfast-hallmark`) reach this directly through the package-internal
     * visibility for adapter implementations (Flow/StateFlow). Application code
     * must use [effect].
     */
    @StoreInternalApi
    fun observe(observer: (T) -> Unit): Disposable =
        observersLock.withLock {
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
        set(value) =
            bridgeLock.withLock {
                // Dispose the previous inbound observer registration so the previous
                // bridge does not keep driving applyFromBridge after replacement/null.
                currentBridgeSubscription?.dispose()
                currentBridgeSubscription = null
                currentBridge = value
                currentBridgeSubscription =
                    value?.observe { receivedValue ->
                        applyFromBridge(receivedValue)
                    }
            }

    /**
     * Internal entrypoint used by `Store.removeState`/`clearStates` to release
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
 * was not produced by `store.state { … }` — only [MutableState] instances
 * carry an observer set.
 *
 * Marked `@StoreInternalApi`: companion-module test code (e.g. `:holdfast-coroutines`)
 * uses it to verify that `Flow`/`StateFlow`/`effect` adapters dispose their
 * underlying observer registration on consumer cancellation. Application code
 * must never opt in.
 */
@StoreInternalApi
val <T : Any> State<T>.observerCount: Int
    get() {
        val ms = (this as? MutableState<T>) ?: error("observerCount is only defined for MutableState (store.state { ... })")
        return ms.observerCount
    }
