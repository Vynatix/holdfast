package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.internal.AwaitingRegistry
import com.vynatix.holdfast.testing.internal.BarrierRegistry
import com.vynatix.holdfast.testing.internal.HandleRegistry
import com.vynatix.holdfast.testing.internal.OpenTransactionRegistry
import com.vynatix.holdfast.testing.internal.PendingErrorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/**
 * Test scope produced by [vaultTest]. Wraps the underlying [TestScope] (so the
 * body has full access to the coroutine-test machinery — virtual time, the
 * background scope, the scheduler) and adds a per-test [StoreHandle] registry.
 *
 * Implementation note: `TestScope` is a sealed interface and cannot be
 * implemented directly outside its module, so the wrapper delegates
 * [CoroutineScope] (its non-sealed supertype) and forwards [testScheduler] and
 * [backgroundScope] manually. The full [TestScope] is also exposed as
 * [testScope] so extension helpers like `runCurrent()`, `advanceUntilIdle()`,
 * `advanceTimeBy()`, and `currentTime` can be invoked against it directly.
 *
 * Implements [StoreAutoRegistration] — the member-extension surface that lets
 * tests call `myVault.action { … }`, `myVault.read { … }`, `myVault.timeline`,
 * etc. directly inside a [vaultTest] block without an explicit [track] call.
 * See [StoreAutoRegistration] for the resolution model. The
 * `inline reified middlewareEventsOf<M>()` overload is declared as a member
 * here (not in the interface) because reified type parameters require `inline`
 * and an `inline reified` interface member would inline through the abstract
 * [track] dispatch awkwardly; declaring it on the implementing class lets the
 * inline call directly resolve to the concrete [HandleRegistry.getOrCreate].
 *
 * Constructed exclusively by [vaultTest]; never instantiated directly by user
 * code.
 */
class StoreTestScope internal constructor(val testScope: TestScope) :
    CoroutineScope by testScope,
    StoreAutoRegistration {

    /** The virtual-time scheduler driving this test. */
    val testScheduler: TestCoroutineScheduler get() = testScope.testScheduler

    /** Background scope whose work is not awaited at test end. */
    val backgroundScope: CoroutineScope get() = testScope.backgroundScope

    private val registry = HandleRegistry()
    private val barriers = BarrierRegistry()
    private val openTransactions = OpenTransactionRegistry()
    private val awaitings = AwaitingRegistry()

    /**
     * Register [store] in this scope and return its [StoreHandle]. Calling
     * `track` again with the same instance returns the previously created
     * handle — idempotent by reference identity, so [capture] on the second
     * call is ignored.
     *
     * On first registration the handle installs a privileged recorder
     * middleware on [store] (unless [capture] is [Capture.None]). The recorder
     * captures every transaction lifecycle, emission, and middleware
     * self-event into [StoreHandle.timeline]; see [StoreHandle] for the typed
     * views built on top. The recorder is detached and its buffer cleared at
     * scope tearDown — see [tearDown].
     *
     * Tests that rely on user middlewares should install them on the store
     * BEFORE calling `track`; see [com.vynatix.holdfast.testing.internal.Recorder]
     * for the v1 wrap-order limit.
     */
    override fun <V : Store<V>> track(store: V, capture: Capture): StoreHandle<V> = registry.getOrCreate(store, capture)

    /**
     * Auto-registering reified-type overload of [StoreHandle.middlewareEventsOf].
     * Auto-registers `this` (via [autoTrackTimeline]) and filters the timeline
     * by [M].
     *
     * The receiver is `Store<*>` rather than `V : Store<V>` so the call site
     * can specify only the reified type parameter — `v.middlewareEventsOf<M>()`
     * — without writing both type arguments. The non-reified work is delegated
     * to [autoTrackTimeline] so this `inline reified` member only carries the
     * `filterIsInstance<MiddlewareEvent>` and the `M` type-check, both of
     * which depend on the reified type parameter.
     *
     * **v1 caveat** carries through from [StoreHandle.middlewareEventsOf]: only
     * events for the recorder itself are captured. User-class [M]s return an
     * empty list; the API is in place so v2 can populate it without ABI churn.
     */
    inline fun <reified M : Middleware<*>> Store<*>.middlewareEventsOf(): List<MiddlewareEvent> = autoTrackTimeline(this)
        .filterIsInstance<MiddlewareEvent>()
        .filter { it.middleware is M }

    /**
     * Internal helper used by the inline reified [middlewareEventsOf] overload.
     * Splits "auto-register the store and read its timeline" out of the inline
     * call site so the inline body can stay narrow (just the reified
     * filterIsInstance).
     *
     * The internal helper has its own recursively-bounded `V` parameter so it
     * can satisfy [HandleRegistry.getOrCreate]'s `V : Store<V>` bound. The
     * `@Suppress("UNCHECKED_CAST")` cast on entry is sound because the
     * registry indexes by reference identity, not by type — the runtime
     * store instance is unchanged and the timeline read-out is type-erased
     * to `List<StoreEvent>`.
     */
    @PublishedApi
    internal fun autoTrackTimeline(store: Store<*>): List<StoreEvent> = autoTrackTimelineTyped(store)

    /**
     * See [autoTrackTimeline]; the unchecked cast bridges the `Store<*>`
     * receiver to a fresh recursively-bounded `V` so [HandleRegistry.getOrCreate]
     * accepts it. Sound because the registry keys by reference identity, not
     * by `V`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <V : Store<V>> autoTrackTimelineTyped(store: Store<*>): List<StoreEvent> {
        val asSelf = store as V
        return registry.getOrCreate(asSelf, Capture.All).timeline
    }

    internal fun barrierRegistry(): BarrierRegistry = barriers

    internal fun openTransactionRegistry(): OpenTransactionRegistry = openTransactions

    internal fun awaitingRegistry(): AwaitingRegistry = awaitings

    /**
     * Snapshot of every tracked [StoreHandle] in this scope. Used by
     * [com.vynatix.holdfast.testing.concurrency.awaiting] to subscribe to every
     * recorder's timeline (and to read the post-timeout last-N-events tail
     * for the augmented error message). Returns the same list as the
     * registry's internal `allHandles()` — defensive copy, safe to iterate.
     */
    internal fun allTrackedHandles(): List<StoreHandle<*>> = registry.allHandles()

    /**
     * Tear down this scope. Always cancels outstanding barriers, closes any
     * live `awaiting { ... }` subscriber channels, rolls back any leaked
     * open transactions, disposes each tracked handle's recorder middleware,
     * removes every tracked handle's entries from the global
     * [PendingErrorRegistry], and clears the handle registry. When
     * [bodyAlreadyFailed] is `false`, also aggregates any unconsumed
     * [TransactionResult.Error] values across all handles and throws an
     * [AssertionError] listing them — forcing tests to actively assert on (or
     * explicitly discard) every error they observe. When the body already
     * threw, the original failure propagates and the unconsumed-error check is
     * suppressed so the user sees the root-cause exception rather than a
     * teardown-time message.
     *
     * Order is fixed: barriers cancel first so coroutines waiting in
     * `arrive()`/`await()` resume; then [AwaitingRegistry.cancelAll] closes
     * any still-live `awaiting` subscriber channels (so a forgotten
     * `awaiting` resumes with [kotlinx.coroutines.channels.ClosedReceiveChannelException]
     * rather than leaking past the test); then [OpenTransactionRegistry.rollbackAll]
     * discards pending writes from any leaked
     * [com.vynatix.holdfast.testing.concurrency.OpenTransaction] (so a forgotten
     * `transaction(...)` never leaks pending writes into the next test); then
     * recorders dispose (stopping further event capture and dropping their
     * subscriber refs); then the handle registry's pending-error bookkeeping
     * is cleared. Recorder disposal swallows exceptions to keep teardown
     * robust even when [bodyAlreadyFailed] is `true`.
     */
    internal fun tearDown(bodyAlreadyFailed: Boolean) {
        barriers.cancelAll()
        awaitings.cancelAll()
        openTransactions.rollbackAll()

        val handles = registry.allHandles()
        val unconsumed: List<Pair<StoreHandle<*>, TransactionResult.Error>> =
            handles.flatMap { handle -> handle.pendingErrors.map { handle to it } }

        for (handle in handles) {
            // Detach + drop recorder before clearing the rest so leaked actions
            // beyond this point don't push more events into the (already
            // teardown-snapshot) timeline.
            handle.disposeRecorderInternal()
            PendingErrorRegistry.unregisterAll(handle)
            handle.clearPendingErrorsInternal()
        }
        registry.clear()

        if (!bodyAlreadyFailed && unconsumed.isNotEmpty()) {
            val msg = buildString {
                appendLine("vaultTest body finished with ${unconsumed.size} unconsumed TransactionResult.Error value(s):")
                unconsumed.forEachIndexed { index, (handle, err) ->
                    val type = err.exception::class.simpleName ?: "Throwable"
                    val message = err.exception.message.orEmpty()
                    val handleTag = handleLabel(handle)
                    val txnId = err.transaction.id
                    appendLine(" - [#${index + 1}] handle=$handleTag $type \"$message\" (txn '$txnId')")
                }
                appendLine("Call .shouldBeError / .shouldBeSuccess / .shouldRollbackWith on each,")
                append("or use handle.consumeAllPendingErrors() to opt out.")
            }
            throw AssertionError(msg)
        }
    }

    private fun handleLabel(handle: StoreHandle<*>): String {
        val cls = handle.store::class.simpleName ?: "Store"
        // Identity tag so two handles to the same store class are distinguishable.
        return "$cls@${handle.hashCode().toString(HEX_RADIX)}"
    }

    private companion object {
        private const val HEX_RADIX = 16
    }
}
