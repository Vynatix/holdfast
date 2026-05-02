package com.vynatix.vault.testing

import com.vynatix.vault.Middleware
import com.vynatix.vault.State
import com.vynatix.vault.Transaction
import com.vynatix.vault.TransactionResult
import com.vynatix.vault.Vault
import com.vynatix.vault.coroutines.suspendAction
import com.vynatix.vault.testing.internal.PendingErrorRegistry
import com.vynatix.vault.testing.internal.Recorder
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.reflect.KProperty1

/**
 * Test-scope handle to a tracked [Vault]. Returned by [VaultTestScope.track]; the
 * registry keeps it alive for the duration of the test so subsequent `track`
 * calls with the same vault instance return the same handle.
 *
 * Every [TransactionResult.Error] returned by [action] or [suspendAction] is
 * recorded as a pending consumption. Calling
 * [shouldBeError][com.vynatix.vault.testing.matcher.shouldBeError],
 * [shouldBeSuccess][com.vynatix.vault.testing.matcher.shouldBeSuccess] or
 * [shouldRollbackWith][com.vynatix.vault.testing.matcher.shouldRollbackWith]
 * clears the mark for that result; any errors left unconsumed when the
 * surrounding [vaultTest] block returns fail the test. Use
 * [consumeAllPendingErrors] as an explicit opt-out when a test deliberately
 * ignores an error.
 *
 * When [captureMode] is anything other than [Capture.None], the handle owns a
 * privileged recorder middleware installed on the tracked vault. The recorder
 * pushes [VaultEvent]s into [timeline] for every transaction lifecycle, with
 * [Capture.RingBuffer] truncating to the configured window. See
 * [com.vynatix.vault.testing.internal.Recorder] for the hook strategy and its
 * known limits (commit-time errors after the body returns, user middlewares
 * not auto-wrapped, suspendAction not running middleware in 1.1).
 */
class VaultHandle<V : Vault<V>> internal constructor(val vault: V, val captureMode: Capture) {

    private val handleLock = SynchronizedObject()
    private val pendingErrorList: MutableList<TransactionResult.Error> = mutableListOf()

    /**
     * Privileged recorder. `null` when [captureMode] is [Capture.None] — the
     * recorder is not installed on the vault in that case, matching the spec
     * that `Capture.None` records nothing and pays no per-action overhead.
     */
    internal val recorder: Recorder<V>? = if (captureMode is Capture.None) null else Recorder(captureMode)

    init {
        // Install the recorder as the FIRST middleware (innermost in the chain).
        // The fold-right wrapping in `Vault.runMiddlewareChain` makes earlier-listed
        // middlewares innermost, so the recorder's `onTransactionStarted` fires
        // closest to the body and its `onTransactionCompleted` fires closest to
        // commit time. This puts emission events at the natural boundary between
        // body return and commit apply.
        recorder?.let { vault.middlewares(it) }
    }

    /**
     * Snapshot of unconsumed [TransactionResult.Error] values produced by this
     * handle. Exposed for the scope-exit guard; stable to iterate (returns a
     * copy taken under the handle's lock).
     */
    internal val pendingErrors: List<TransactionResult.Error>
        get() = synchronized(handleLock) { pendingErrorList.toList() }

    /**
     * Every event the recorder has captured for this vault, in push order.
     * Returns an empty list when [captureMode] is [Capture.None]. The list is
     * a defensive copy taken under the recorder's lock — safe to iterate
     * without contention with concurrent actions.
     */
    val timeline: List<VaultEvent>
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
     * this vault. Resolves [prop] against the live vault instance, so
     * `MyVault::count` returns events for the same `State<*>` reference the
     * recorder pushed at commit time. Order is preserved.
     */
    fun emissions(prop: KProperty1<V, State<*>>): List<EmissionEvent> {
        val target = prop.get(vault)
        return timeline.filterIsInstance<EmissionEvent>().filter { it.state === target }
    }

    /**
     * Filter of [timeline] for [BridgeEvent]s targeting [prop]'s state on this
     * vault. **In v1 always empty** — Issue 12 owns the bridge instrumentation;
     * the typed view is shipped now so Issue 07's matchers can target it
     * without future churn.
     */
    fun bridgeEvents(prop: KProperty1<V, State<*>>): List<BridgeEvent> {
        val target = prop.get(vault)
        return timeline.filterIsInstance<BridgeEvent>().filter { it.state === target }
    }

    /**
     * Filter of [timeline] for [MiddlewareEvent]s whose [MiddlewareEvent.middleware]
     * is an instance of [M].
     *
     * **v1 caveat**: only events for the recorder itself (an internal class) are
     * captured. User middlewares installed via [com.vynatix.vault.Vault.middlewares]
     * are NOT wrapped — :vault has no public hook to enumerate or replace
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
     * Run [block] with the tracked vault as receiver and return its value. The
     * block sees `vault.value` for each [com.vynatix.vault.State] without going
     * through an action — useful for plain read assertions.
     */
    fun <R> read(block: V.() -> R): R = block(vault)

    /**
     * Run [body] inside a blocking [Vault.action] on the tracked vault. Returns
     * the [TransactionResult] verbatim — production-faithful, no transformation
     * or implicit assertion. If the result is an [TransactionResult.Error], it
     * is recorded for the scope-exit unconsumed-error guard; assert on it via a
     * `shouldBe*` matcher (or [consumeAllPendingErrors]) to clear the mark.
     */
    fun <R> action(body: V.() -> R): TransactionResult<R> {
        val result = vault action body
        recorder?.recordResult(result)
        trackResult(result)
        return result
    }

    /**
     * Run [body] inside a [com.vynatix.vault.coroutines.suspendAction] on the
     * tracked vault. Returns the [TransactionResult] verbatim — production-
     * faithful, no transformation or implicit assertion. If the result is an
     * [TransactionResult.Error], it is recorded for the scope-exit
     * unconsumed-error guard; assert on it via a `shouldBe*` matcher (or
     * [consumeAllPendingErrors]) to clear the mark.
     */
    suspend fun <R> suspendAction(body: suspend V.() -> R): TransactionResult<R> {
        val result = vault.suspendAction(body)
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
     * Prefer the [shouldBeError][com.vynatix.vault.testing.matcher.shouldBeError] /
     * [shouldRollbackWith][com.vynatix.vault.testing.matcher.shouldRollbackWith]
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
     * Detach the recorder middleware from the tracked vault and drop every
     * recorded event. Called from [VaultTestScope.tearDown] in a fixed order
     * (after barriers cancel, before the handle registry clears) so no
     * post-teardown action accidentally records events.
     *
     * Detachment is "all-middleware-clear" because :vault only exposes
     * [com.vynatix.vault.Vault.clearMiddleware] for removal — there is no
     * single-entry uninstall. Tests that install user middlewares before
     * `track(v)` and expect them to persist past teardown should re-install
     * them in the next test. (Pragmatically, vault instances rarely outlive a
     * `vaultTest` block.)
     */
    internal fun disposeRecorderInternal() {
        val r = recorder ?: return
        // Order: clear vault's middleware list first (so no subsequent action on
        // this vault can fire the recorder), then drop the recorder's buffer.
        // Catch any throw so teardown stays robust under leaked handles.
        runCatching { vault.clearMiddleware() }
        r.dispose()
    }
}
