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
class MutableState<T : Any> internal constructor(
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

    /**
     * The property name this state was registered under, set once at registration
     * by [Store.state] / [Store.registerInternalState]. Used only to name the state
     * in diagnostic messages (ownership mismatch, restore type mismatch, commit
     * failure phase). `null` for a state that was constructed but never registered.
     */
    internal var debugName: String? = null

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
     * Suspending bodies (`suspendAction` / `suspendAtomic`) keep read-your-own-writes
     * across dispatcher hops: while [Store.suspendingOwner] is set AND the calling
     * thread carries the body's frame marker enrolling this store
     * (`FrameMarkers.current()?.isEnrolled`), pending writes are visible even though
     * the resuming thread differs from the transaction's owner thread. The marker is
     * installed exactly on the thread currently resuming the single-flight body, so
     * this never widens visibility to concurrent readers. Platform caveat: on
     * iOS/wasmJs the marker rides a delegating interceptor, so a nested
     * `withContext(otherDispatcher)` section inside a suspending body loses both
     * enforcement and this relaxed view (same documented gap as GUIDE §15.1).
     *
     * Any other off-owner-thread read only sees the committed value, never another
     * thread's uncommitted pending writes.
     */
    override val value: T
        get() =
            stateLock.withLock {
                val txn = owningStore.activeTransaction
                if (txn != null && callerOwnsTransactionView(txn)) {
                    val pending = txn.findPendingValue(this)
                    if (pending != null) return@withLock afterGet(pending)
                }
                afterGet(currentValue)
            }

    /**
     * Whether the calling thread is entitled to the read-your-own-writes view of
     * [txn]. True on the transaction's owner thread; also true on the thread
     * currently resuming an in-flight suspending body — detected by the
     * suspending-owner handshake PLUS the thread-local frame marker enrolling
     * [owningStore]. Never relax on [Store.suspendingOwner] alone: that would leak
     * uncommitted staged values to arbitrary reader threads (UI, observers) while
     * a suspending body is in flight.
     */
    private fun callerOwnsTransactionView(txn: Transaction): Boolean {
        if (txn.ownerThreadId == currentThreadId()) return true
        return owningStore.suspendingOwner != null &&
            FrameMarkers.current()?.isEnrolled(owningStore) == true
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
     * Commit-time apply (raw): writes `currentValue` and notifies observers, but
     * **does NOT publish to the bridge**. Strict subset of [applyCommitted]
     * exposed as a `@StoreInternalApi` extension hook so companion modules
     * (notably `:holdfast-coroutines.suspendAction`) can interpose between the
     * observer fanout (step 1+2) and the bridge publish (step 3) — necessary
     * for [com.vynatix.holdfast.coroutines.SuspendingBridge.publishAwaited] to be
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
    @StoreInternalApi
    fun applyCommittedRaw(processedValue: T): Boolean {
        val unchanged =
            stateLock.withLock {
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
        @OptIn(StoreInternalApi::class)
        if (!applyCommittedRaw(processedValue)) return
        // Bridge publish is fire-and-forget by contract. A throwing publish (e.g. a
        // KvBridge encode/persist failure) must NOT abort the commit after the value
        // and observers have already landed — that would leave a partial commit that
        // rollback cannot undo. Contain it: route the failure to the store's
        // uncaught-error policy (loud by default) and let the commit succeed.
        try {
            bridgeLock.withLock { currentBridge?.publish(processedValue) }
        } catch (e: Throwable) {
            owningStore.reportUncaughtObserverError(e)
        }
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
        observersLock.withLock {
            observers.toSet().forEach { observer ->
                try {
                    observer(value)
                } catch (e: Throwable) {
                    // Null handler no longer means silence: a swallowed observer
                    // exception is invisible commit corruption. Route through the
                    // store's policy — loud default, or an assigned handler.
                    owningStore.reportUncaughtObserverError(e)
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
                // Attaching/replacing a bridge on a disposed store must fail loudly —
                // the same rule mutate/action enforce (`shutdownSilently` writes the
                // fields directly, so dispose itself still works).
                check(!owningStore.isDisposed) {
                    "${owningStore::class.simpleName ?: "Store"} is disposed — dispose() is " +
                        "terminal; create a new instance."
                }
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
 * Loud built-in fallback for an observer/effect callback that throws during
 * commit fanout, used when [Store.uncaughtObserverHandler] is null. Prints the
 * store identity and the full stack trace so the failure is visible instead of
 * silently swallowed. Assign any handler (including a no-op `{ }`) to
 * [Store.uncaughtObserverHandler] to take over — that is the explicit opt-out
 * of this default.
 */
internal fun defaultLogUncaughtObserverError(
    store: Store<*>,
    error: Throwable,
) {
    println(
        "Holdfast: an observer/effect callback threw during commit fanout on " +
            "${store::class.simpleName ?: "Store"} and was swallowed to protect the commit. " +
            "Set Store.uncaughtObserverHandler to route these; assign a no-op lambda to silence.\n" +
            error.stackTraceToString(),
    )
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
