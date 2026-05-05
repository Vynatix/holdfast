package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.testing.bridge.BridgeView
import com.vynatix.holdfast.testing.bridge.LatchedBridge
import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.internal.PendingErrorRegistry
import com.vynatix.holdfast.testing.internal.Recorder
import com.vynatix.holdfast.testing.internal.RecordingBridgeWrapper
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.reflect.KProperty1

/**
 * Test-scope handle to a tracked [Store]. Returned by [StoreTestScope.track]; the
 * registry keeps it alive for the duration of the test so subsequent `track`
 * calls with the same store instance return the same handle.
 *
 * Every [TransactionResult.Error] returned by [action] or [suspendAction] is
 * recorded as a pending consumption. Calling
 * [shouldBeError][com.vynatix.holdfast.testing.matcher.shouldBeError],
 * [shouldBeSuccess][com.vynatix.holdfast.testing.matcher.shouldBeSuccess] or
 * [shouldRollbackWith][com.vynatix.holdfast.testing.matcher.shouldRollbackWith]
 * clears the mark for that result; any errors left unconsumed when the
 * surrounding [vaultTest] block returns fail the test. Use
 * [consumeAllPendingErrors] as an explicit opt-out when a test deliberately
 * ignores an error.
 *
 * When [captureMode] is anything other than [Capture.None], the handle owns a
 * privileged recorder middleware installed on the tracked store. The recorder
 * pushes [StoreEvent]s into [timeline] for every transaction lifecycle, with
 * [Capture.RingBuffer] truncating to the configured window. See
 * [com.vynatix.holdfast.testing.internal.Recorder] for the hook strategy and its
 * known limits (commit-time errors after the body returns, user middlewares
 * not auto-wrapped, suspendAction not running middleware in 1.1).
 */
class StoreHandle<V : Store<V>> internal constructor(val store: V, val captureMode: Capture) {

    private val handleLock = SynchronizedObject()
    private val pendingErrorList: MutableList<TransactionResult.Error> = mutableListOf()

    /**
     * Privileged recorder. `null` when [captureMode] is [Capture.None] — the
     * recorder is not installed on the store in that case, matching the spec
     * that `Capture.None` records nothing and pays no per-action overhead.
     */
    internal val recorder: Recorder<V>? = if (captureMode is Capture.None) null else Recorder(captureMode)

    /**
     * Per-state map from the live [State] reference (key by identity) to the
     * [RecordingBridgeWrapper] that wraps any user-attached bridge on that
     * state. Populated at handle install time (this `init` block) for every
     * state currently in [Store.properties] that has a bridge attached.
     *
     * **v1 limitation**: bridges attached AFTER `track(v)` are NOT
     * auto-wrapped — :holdfast has no public hook for late attachment. The
     * documented contract is "attach bridges before track(v)" so the wrapper
     * sees every commit-time publish and inbound observation. This map is
     * populated only at the install boundary; attaching a fresh bridge after
     * track(v) replaces the wrapped reference with the unwrapped one and
     * subsequent BridgePublished/Observed events will not fire for that
     * state.
     */
    private val bridgeWrappers: MutableMap<State<*>, RecordingBridgeWrapper<*>> = mutableMapOf()

    init {
        // Install the recorder as the FIRST middleware (innermost in the chain).
        // The fold-right wrapping in `Store.runMiddlewareChain` makes earlier-listed
        // middlewares innermost, so the recorder's `onTransactionStarted` fires
        // closest to the body and its `onTransactionCompleted` fires closest to
        // commit time. This puts emission events at the natural boundary between
        // body return and commit apply.
        recorder?.let { store.middlewares(it) }

        // Wrap every currently-attached bridge so the recorder sees publish /
        // observe events on the timeline. We iterate store.properties, cast
        // each State to MutableState (the only concrete State implementation
        // produced by Store.state — the cast is sound for any state created
        // by the store DSL). Setting state.bridge re-runs the attach path,
        // which disposes the old inbound subscription and calls our wrapper's
        // observe — the wrap is therefore visible to production code as a
        // single re-attach. See RecordingBridgeWrapper KDoc for the
        // implications.
        recorder?.let { rec -> wrapAttachedBridges(rec) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapAttachedBridges(recorder: Recorder<V>) {
        // Re-wrap of an already-wrapped bridge is a no-op (idempotent track
        // calls land here via HandleRegistry.getOrCreate returning the same
        // handle, so this init runs only once per store — but defensive in
        // case future entry points cycle here).
        val wrappable = store.properties.values
            .mapNotNull { state -> (state as? MutableState<Any>)?.let { state to it } }
            .mapNotNull { (state, mutable) -> mutable.bridge?.let { Triple(state, mutable, it) } }
            .filter { (_, _, attached) -> attached !is RecordingBridgeWrapper<*> }
        for ((state, mutable, attached) in wrappable) {
            val wrapper = RecordingBridgeWrapper(state = state, delegate = attached, recorder = recorder)
            bridgeWrappers[state] = wrapper
            // Replace the attached bridge — store setter disposes the old
            // inbound subscription and calls wrapper.observe.
            mutable.bridge = wrapper as Bridge<Any>
        }
    }

    /**
     * Snapshot of unconsumed [TransactionResult.Error] values produced by this
     * handle. Exposed for the scope-exit guard; stable to iterate (returns a
     * copy taken under the handle's lock).
     */
    internal val pendingErrors: List<TransactionResult.Error>
        get() = synchronized(handleLock) { pendingErrorList.toList() }

    /**
     * Every event the recorder has captured for this store, in push order.
     * Returns an empty list when [captureMode] is [Capture.None]. The list is
     * a defensive copy taken under the recorder's lock — safe to iterate
     * without contention with concurrent actions.
     */
    val timeline: List<StoreEvent>
        get() = recorder?.snapshot().orEmpty()

    /**
     * Filter of [timeline] containing only [TransactionEvent]s
     * ([TransactionStarted], [TransactionCommitted], [TransactionRolledBack],
     * [TransactionErrored]).
     */
    val transactions: List<TransactionEvent>
        get() = timeline.filterIsInstance<TransactionEvent>()

    /**
     * The transaction the recorder most recently observed in
     * `onTransactionStarted`. Reflects the latest transaction regardless of
     * outcome — including rolled-back ones. `null` when no action has run yet
     * (or when [captureMode] is [Capture.None]).
     */
    val lastTransaction: Transaction?
        get() = recorder?.lastTransaction

    /**
     * The most recent [TransactionResult] recorded by the handle's [action] /
     * [suspendAction] passthroughs. `null` when no action has run yet.
     */
    val lastResult: TransactionResult<*>?
        get() = recorder?.lastResult

    /**
     * Filter of [timeline] for [EmissionEvent]s targeting [prop]'s state on
     * this store. Resolves [prop] against the live store instance, so
     * `MyVault::count` returns events for the same `State<*>` reference the
     * recorder pushed at commit time. Order is preserved.
     */
    fun emissions(prop: KProperty1<V, State<*>>): List<EmissionEvent> {
        val target = prop.get(store)
        return timeline.filterIsInstance<EmissionEvent>().filter { it.state === target }
    }

    /**
     * Filter of [timeline] for [BridgeEvent]s targeting [prop]'s state on this
     * store. Populated by the [Recorder] when bridges attached to tracked
     * states publish / observe — see [RecordingBridgeWrapper] for the wrap
     * strategy and the v1 limit (bridges attached AFTER `track(v)` are not
     * wrapped).
     */
    fun bridgeEvents(prop: KProperty1<V, State<*>>): List<BridgeEvent> {
        val target = prop.get(store)
        return timeline.filterIsInstance<BridgeEvent>().filter { it.state === target }
    }

    /**
     * Look up the bridge attached to the [State] referenced by [prop] on this
     * handle's store and return a [BridgeView] facade for inspecting publish
     * history and synthesising inbound updates.
     *
     * The lookup walks two sources, in order:
     *  1. The handle's [bridgeWrappers] map — populated at install time for
     *     every state with a bridge attached BEFORE `track(v)`.
     *  2. The state's current `MutableState.bridge` — used as a fallback for
     *     states whose bridge was attached after track(v); the resulting
     *     [BridgeView] still works for direct inspection but does NOT receive
     *     [BridgePublished] / [BridgeObserved] events on the timeline.
     *
     * If the underlying bridge is one of the test bridges
     * ([com.vynatix.holdfast.testing.bridge.RecordingBridge] /
     * [com.vynatix.holdfast.testing.bridge.LatchedBridge]) the [BridgeView] reads
     * its publish history; for arbitrary bridges (e.g. a real
     * [com.vynatix.holdfast.bridge.KvBridge]) only the wrapper-tracked publishes
     * are visible — but only if the bridge was wrapped at install time.
     *
     * @throws IllegalStateException if the state has no bridge attached.
     */
    fun bridge(prop: KProperty1<V, State<*>>): BridgeView<*> {
        val state = prop.get(store)
        val wrapper = bridgeWrappers[state]
        if (wrapper != null) {
            return BridgeView(BridgeView.WrappedSource(wrapper))
        }
        // Fallback: no install-time wrapper, but the state may have a bridge
        // attached after track(v). Probe the MutableState.bridge directly and
        // try to construct a view from a known test-bridge type.
        @Suppress("UNCHECKED_CAST")
        val mutable = (state as? MutableState<Any>)
            ?: error("bridge(${prop.name}): state was not created by this store — cannot inspect its bridge")
        val attached = mutable.bridge ?: error(
            "bridge(${prop.name}): no bridge attached. Attach via `state bridge bridge` before calling.",
        )
        return adaptBridge(attached)
    }

    @Suppress("UNCHECKED_CAST")
    private fun adaptBridge(attached: Bridge<*>): BridgeView<*> = when (attached) {
        is RecordingBridge<*> -> BridgeView(BridgeView.RecordingSource(attached as RecordingBridge<Any>))
        is LatchedBridge<*> -> BridgeView(BridgeView.LatchedSource(attached as LatchedBridge<Any>))
        is RecordingBridgeWrapper<*> -> BridgeView(BridgeView.WrappedSource(attached as RecordingBridgeWrapper<Any>))
        else -> error(
            "bridge(...): underlying bridge is a ${attached::class.simpleName} which does not " +
                "support introspection. Attach it BEFORE track(v) so the recorder can wrap it, " +
                "or use a RecordingBridge / LatchedBridge in tests.",
        )
    }

    /**
     * Filter of [timeline] for [MiddlewareEvent]s whose [MiddlewareEvent.middleware]
     * is an instance of [M].
     *
     * **v1 caveat**: only events for the recorder itself (an internal class) are
     * captured. User middlewares installed via [com.vynatix.holdfast.Holdfast.middlewares]
     * are NOT wrapped — :holdfast has no public hook to enumerate or replace
     * entries in the chain, and `Middleware.invoke` is final, so a peer wrapper
     * cannot observe a user middleware's started/completed/error events. For
     * any user-class [M] this view returns an empty list. The API is in place
     * so v2 can populate it without ABI churn.
     */
    inline fun <reified M : Middleware<V>> middlewareEventsOf(): List<MiddlewareEvent> = timeline
        .filterIsInstance<MiddlewareEvent>()
        .filter { it.middleware is M }

    /**
     * Filter of [timeline] for [MiddlewareEvent]s whose [MiddlewareEvent.middleware]
     * IS [instance] (referential equality). See [middlewareEventsOf] for the v1
     * caveat — for any non-recorder instance this returns empty.
     */
    fun <M : Middleware<V>> middlewareEventsOf(instance: M): List<MiddlewareEvent> = timeline
        .filterIsInstance<MiddlewareEvent>()
        .filter { it.middleware === instance }

    /**
     * Run [block] with the tracked store as receiver and return its value. The
     * block sees `store.value` for each [com.vynatix.holdfast.State] without going
     * through an action — useful for plain read assertions.
     */
    fun <R> read(block: V.() -> R): R = block(store)

    /**
     * Run [body] inside a blocking [Store.action] on the tracked store. Returns
     * the [TransactionResult] verbatim — production-faithful, no transformation
     * or implicit assertion. If the result is an [TransactionResult.Error], it
     * is recorded for the scope-exit unconsumed-error guard; assert on it via a
     * `shouldBe*` matcher (or [consumeAllPendingErrors]) to clear the mark.
     */
    fun <R> action(body: V.() -> R): TransactionResult<R> {
        val result = store action body
        recorder?.recordResult(result)
        trackResult(result)
        return result
    }

    /**
     * Run [body] inside a [com.vynatix.holdfast.coroutines.suspendAction] on the
     * tracked store. Returns the [TransactionResult] verbatim — production-
     * faithful, no transformation or implicit assertion. If the result is an
     * [TransactionResult.Error], it is recorded for the scope-exit
     * unconsumed-error guard; assert on it via a `shouldBe*` matcher (or
     * [consumeAllPendingErrors]) to clear the mark.
     */
    suspend fun <R> suspendAction(body: suspend V.() -> R): TransactionResult<R> {
        val result = store.suspendAction(body)
        recorder?.recordResult(result)
        trackResult(result)
        return result
    }

    /**
     * Discard every pending [TransactionResult.Error] on this handle without
     * asserting on them. Use when a test deliberately wants to skip the
     * scope-exit guard for an error it knows about (e.g. a fixture that
     * intentionally surfaces a failure but is asserted on out-of-band).
     *
     * Prefer the [shouldBeError][com.vynatix.holdfast.testing.matcher.shouldBeError] /
     * [shouldRollbackWith][com.vynatix.holdfast.testing.matcher.shouldRollbackWith]
     * matchers when the goal is to inspect the failure.
     */
    fun consumeAllPendingErrors() {
        PendingErrorRegistry.unregisterAll(this)
        synchronized(handleLock) { pendingErrorList.clear() }
    }

    private fun trackResult(result: TransactionResult<*>) {
        if (result is TransactionResult.Error) {
            synchronized(handleLock) { pendingErrorList.add(result) }
            PendingErrorRegistry.register(result, this)
        }
    }

    /**
     * Drop [error] from the pending list. Called by [PendingErrorRegistry]
     * when a matcher consumes the result; identity-based removal so identical
     * structurally-equal Error data classes don't collapse into one.
     */
    internal fun markConsumedInternal(error: TransactionResult.Error) {
        synchronized(handleLock) {
            pendingErrorList.removeAll { it === error }
        }
    }

    /**
     * Drop every pending error. Called from scope tearDown after the
     * unconsumed-error report has been built; ensures the handle releases its
     * collected results so a leaked handle reference does not prolong them.
     */
    internal fun clearPendingErrorsInternal() {
        synchronized(handleLock) { pendingErrorList.clear() }
    }

    /**
     * Detach the recorder middleware from the tracked store and drop every
     * recorded event. Called from [StoreTestScope.tearDown] in a fixed order
     * (after barriers cancel, before the handle registry clears) so no
     * post-teardown action accidentally records events.
     *
     * Detachment is "all-middleware-clear" because :holdfast only exposes
     * [com.vynatix.holdfast.Holdfast.clearMiddleware] for removal — there is no
     * single-entry uninstall. Tests that install user middlewares before
     * `track(v)` and expect them to persist past teardown should re-install
     * them in the next test. (Pragmatically, store instances rarely outlive a
     * `holdfastTest` block.)
     */
    internal fun disposeRecorderInternal() {
        val r = recorder ?: return
        // Order: clear store's middleware list first (so no subsequent action on
        // this store can fire the recorder), then drop the recorder's buffer.
        // Catch any throw so teardown stays robust under leaked handles.
        runCatching { store.clearMiddleware() }
        // Drop the wrapper map; the wrappers remain attached to states (the
        // store still references them) but they hold a reference to a
        // recorder we are about to clear, so future publishes from a leaked
        // post-teardown action would push events into an empty buffer (no
        // observable effect). Clearing the map releases our reference so a
        // leaked handle does not retain wrappers indefinitely.
        bridgeWrappers.clear()
        r.dispose()
    }
}
