@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class CoreEagerStore(
    val log: MutableList<String>,
) : Store<CoreEagerStore>() {
    val total by state {
        log += "total"
        base.value + 10
    }
    val base by state {
        log += "base"
        1
    }
    val label by state {
        log += "label"
        "x"
    }
}

private class CoreDependentStore : Store<CoreDependentStore>() {
    val a by state { 0 }
    val b by state { a.value + 1 }
}

private class CoreWritingInitStore : Store<CoreWritingInitStore>() {
    var mode = "mutate"
    var attempts = 0
    val target by state { 0 }
    val writer by state {
        attempts++
        when (mode) {
            "mutate" -> target mutate 1
            "update" -> target update { it + 1 }
            "action" -> action { target mutate 3 }
            "throw" -> throw CoreFailure("attempt $attempts")
        }
        2
    }
}

private class CoreFailure(
    message: String,
) : RuntimeException(message)

private class CoreCycleStore : Store<CoreCycleStore>() {
    val x: State<Int> by state { y.value + 1 }
    val y: State<Int> by state { x.value + 1 }
    val calm by state { 0 }
}

private class CorePingStore : Store<CorePingStore>() {
    var pong: CorePongStore? = null
    val v: State<Int> by state { checkNotNull(pong).v.value + 1 }
}

private class CorePongStore(
    private val ping: CorePingStore,
) : Store<CorePongStore>() {
    val v: State<Int> by state { ping.v.value + 1 }
}

private class CoreSiteStore : Store<CoreSiteStore>() {
    val member by state { 0 }
}

/** Not a store: declares a state on the store it is given, through a member property. */
private class CoreDraftSection(
    store: CoreSiteStore,
    seed: String,
) {
    val draft: State<String> by store.state { seed }
}

/** A different class whose member property has the same name. */
private class CoreOtherDraftSection(
    store: CoreSiteStore,
) {
    val draft: State<String> by store.state { "other" }
}

private class CoreCounterStore : Store<CoreCounterStore>() {
    val count by state { 0 }
    val label by state { "" }
    val deduped by state(distinct = true) { 0 }
    var disposeHooks = 0

    override fun onDispose() {
        super.onDispose()
        disposeHooks++
    }
}

private class CoreRecordingMiddleware(
    private val name: String,
    private val log: MutableList<String>,
    private val rejectAbove: Int? = null,
) : Middleware<CoreCounterStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<CoreCounterStore>) {
        context.metadata["by"] = name
        log += "$name.started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<CoreCounterStore>) {
        check(context.metadata["by"] == name) { "metadata lost" }
        log += "$name.completed"
        val limit = rejectAbove ?: return
        // Read-your-own-writes inside the hook: the pending value is visible.
        val pending = context.store.count.value
        if (pending > limit) throw CoreFailure("$name rejects $pending")
    }

    override fun onTransactionError(
        context: MiddlewareContext<CoreCounterStore>,
        error: Throwable,
    ) {
        log += "$name.error(${error.message})"
    }
}

private class CoreEventStore : EventfulStore<CoreEventStore, String>() {
    val count by state { 0 }
}

private class CoreFixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

private class CoreClockStore : Store<CoreClockStore>() {
    val createdAt by state { clock.now() }
}

private class CoreClockOverrideStore(
    private val pinned: Clock,
) : Store<CoreClockOverrideStore>() {
    override val clock: Clock get() = pinned
    val createdAt by state { clock.now() }
}

/** Subscribes [record] to [state]'s commits, skipping the initial fire. */
private fun <T : Any> coreOnCommit(
    state: State<T>,
    record: (T) -> Unit,
) = run {
    var initial = true
    state effect {
        if (initial) initial = false else record(this)
    }
}

/**
 * Core store behaviour on wasmJs (single-threaded, every thread id 0), with
 * the JVM run as the control: declaration and materialization, initializer
 * rules (no writes, cycles, retries), transactions and savepoints, observers,
 * fanout write refusal (D16), middleware, events, structural removal,
 * dispose and the clock.
 */
class CoreSmokeTest {
    // States are declared at construction, materialized on first need, in need order.
    @Test
    fun statesMaterializeLazilyInFirstNeedOrder() {
        val log = mutableListOf<String>()
        val store = CoreEagerStore(log)
        assertEquals(emptyList<String>(), log, "construction runs no initializer")
        assertTrue(store.properties.isEmpty(), "properties shows materialized states only")
        assertFalse(store.hasState("base"))

        assertEquals(11, store.total.value)
        assertEquals(listOf("total", "base"), log, "total's initializer first-reads base")
        assertEquals(setOf("total", "base"), store.properties.keys)

        store.snapshot()
        assertEquals(listOf("total", "base", "label"), log, "snapshot() materializes every state not yet materialized")
        assertEquals("x", store.label.value)
        assertEquals(3, log.size, "each initializer ran once")
        assertEquals("MutableState(CoreEagerStore.base)", store.base.toString())
    }

    // An initializer reads committed values only, even inside an action with a pending write.
    @Test
    fun anInitializerReadsCommittedValuesWhileTheActionReadsItsOwnWrites() {
        val store = CoreDependentStore()
        val result =
            store action {
                a mutate 5
                a.value to b.value
            }
        assertEquals(5 to 1, assertIs<TransactionResult.Success<Pair<Int, Int>>>(result).value)
        assertEquals(5, store.a.value)
        assertEquals(1, store.b.value, "b was seeded from the committed a, not the pending 5")
    }

    // NoWriteRegion: every write entrypoint throws inside an initializer; the state stays retryable.
    @Test
    fun anInitializerMayNotWriteAndAFailedInitializerStaysRetryable() {
        val store = CoreWritingInitStore()
        val fired = mutableListOf<Int>()
        coreOnCommit(store.target) { fired += it }

        for (mode in listOf("mutate", "update", "action")) {
            store.mode = mode
            val e = assertFailsWith<IllegalStateException>(mode) { store.writer.value }
            assertContains(e.message.orEmpty(), "the initializer of CoreWritingInitStore.writer is running")
        }
        store.mode = "throw"
        assertEquals("attempt 4", assertFailsWith<CoreFailure> { store.writer.value }.message)

        // Inside an action the initializer's refusal fails the action, which stays usable.
        store.mode = "mutate"
        val inAction = store action { writer.value }
        val error = assertIs<TransactionResult.Error>(inAction)
        assertIs<IllegalStateException>(error.exception)
        assertEquals(TransactionStatus.RolledBack, error.transaction.status)

        store.mode = "none"
        assertEquals(2, store.writer.value)
        assertEquals(6, store.attempts)
        assertEquals(0, store.target.value, "no refused write landed")
        assertEquals(emptyList<Int>(), fired)
        assertIs<TransactionResult.Success<*>>(store action { target mutate 9 })
        assertEquals(listOf(9), fired)
    }

    // A same-thread initializer cycle is found on the materializing stack (a plain global on
    // wasmJs) and throws the teaching message instead of waiting on a latch this thread holds;
    // the failed states stay unmaterialized and the store stays usable.
    @Test
    fun anInitializerCycleThrowsAndLeavesTheStoreUsable() {
        val store = CoreCycleStore()
        val first = assertFailsWith<IllegalStateException> { store.x }
        assertContains(first.message.orEmpty(), "State initializer cycle: CoreCycleStore.x → CoreCycleStore.y → CoreCycleStore.x")
        val again = assertFailsWith<IllegalStateException> { store.y }
        assertContains(again.message.orEmpty(), "State initializer cycle: CoreCycleStore.y → CoreCycleStore.x → CoreCycleStore.y")
        assertNull(store.getState("x"))
        assertNull(store.getState("y"))
        assertEquals(0, store.calm.value)

        // Inside an action (the transaction lock held): the cycle fails the action only.
        val inAction = store action { x.value }
        assertContains(assertIs<TransactionResult.Error>(inAction).exception.message.orEmpty(), "State initializer cycle")
        assertIs<TransactionResult.Success<*>>(store action { calm mutate 1 })
        assertEquals(1, store.calm.value)

        // Across two stores (one process-wide graph).
        val ping = CorePingStore()
        val pong = CorePongStore(ping)
        ping.pong = pong
        val cross = assertFailsWith<IllegalStateException> { ping.v.value }
        assertContains(cross.message.orEmpty(), "CorePingStore.v → CorePongStore.v → CorePingStore.v")
        assertFailsWith<IllegalStateException> { pong.v.value }
    }

    // A member property of a helper class instantiated twice is ONE declaration site: the
    // KProperty references compare equal (DeclarationSite.isSameSiteAs), and so must they on wasmJs.
    @Test
    fun aHelperClassInstantiatedTwiceBindsToTheSameState() {
        val store = CoreSiteStore()
        val first = CoreDraftSection(store, "first")
        val second = CoreDraftSection(store, "second")
        assertEquals("first", second.draft.value, "the first declaration's initializer wins")
        assertSame(first.draft, second.draft)

        val e = assertFailsWith<IllegalStateException> { CoreOtherDraftSection(store) }
        assertContains(e.message.orEmpty(), "CoreSiteStore already declares a state named 'draft'")

        val seen = mutableListOf<State<Int>>()
        repeat(2) {
            val local: State<Int> by store.state { 7 }
            seen += local
        }
        assertSame(seen[0], seen[1], "a local delegate evaluated twice is one state")
        assertEquals(7, seen[0].value)
    }

    // Success/Error, savepoints: inner commit merges, outer rollback discards, inner failure is contained.
    @Test
    fun actionsAndNestedSavepoints() {
        val store = CoreCounterStore()
        val fired = mutableListOf<Int>()
        coreOnCommit(store.count) { fired += it }

        val ok =
            store action {
                count mutate 1
                "done"
            }
        assertEquals("done", assertIs<TransactionResult.Success<String>>(ok).value)
        assertEquals(TransactionStatus.Committed, ok.transaction.status)

        val failed =
            store action {
                count mutate 99
                throw CoreFailure("boom")
            }
        val error = assertIs<TransactionResult.Error>(failed)
        assertEquals("boom", error.exception.message)
        assertEquals(TransactionStatus.RolledBack, error.transaction.status)
        assertNull(failed.valueOrNull)
        assertFailsWith<CoreFailure> { failed.getOrThrow() }
        assertEquals(1, store.count.value)

        val merged =
            store action {
                count mutate 2
                val inner =
                    action {
                        count mutate 3
                        label mutate "inner"
                    }
                assertIs<TransactionResult.Success<*>>(inner)
                count.value
            }
        assertEquals(3, merged.getOrThrow(), "the outer body reads the merged savepoint")
        assertEquals(3, store.count.value)
        assertEquals("inner", store.label.value)

        store action {
            action { count mutate 4 }
            throw CoreFailure("outer")
        }
        assertEquals(3, store.count.value, "an outer rollback discards the merged savepoint")

        val contained =
            store action {
                label mutate "outer"
                val inner =
                    action {
                        count mutate 5
                        throw CoreFailure("inner")
                    }
                assertIs<TransactionResult.Error>(inner)
                count.value
            }
        assertEquals(3, contained.getOrThrow(), "the failed savepoint's write is gone inside the outer body too")
        assertEquals(3, store.count.value)
        assertEquals("outer", store.label.value)
        assertEquals(listOf(1, 3), fired, "one fire per committed top-level action that changed count")
        assertNull(store.activeTransaction)
    }

    // A bare mutate/update synthesizes a one-shot action: middleware and observers see a commit.
    @Test
    fun mutateOutsideAnActionCommitsThroughMiddleware() {
        val store = CoreCounterStore()
        val log = mutableListOf<String>()
        store.middlewares(CoreRecordingMiddleware("m", log))
        val fired = mutableListOf<Int>()
        coreOnCommit(store.count) { fired += it }

        store { count mutate 7 }
        store { count update { it + 1 } }

        assertEquals(8, store.count.value)
        assertEquals(listOf(7, 8), fired)
        assertEquals(listOf("m.started", "m.completed", "m.started", "m.completed"), log)
        assertNull(store.activeTransaction)
    }

    // Observers: initial fire, one fire per commit with the final value, distinct dedup, order, dispose.
    @Test
    fun observersFireOncePerCommitInSubscriptionOrder() {
        val store = CoreCounterStore()
        val log = mutableListOf<String>()
        val first = store.count effect { log += "first:$this" }
        store.count effect { log += "second:$this" }
        store.deduped effect { log += "deduped:$this" }
        assertEquals(listOf("first:0", "second:0", "deduped:0"), log)
        log.clear()

        store action {
            count mutate 0
            deduped mutate 0
        }
        assertEquals(listOf("first:0", "second:0"), log, "a non-distinct state fires on an equal value, a distinct one does not")
        log.clear()

        store action {
            count mutate 1
            count mutate 2
            deduped mutate 5
        }
        assertEquals(listOf("first:2", "second:2", "deduped:5"), log, "one fire per state per commit, with the last value")
        log.clear()

        store action {
            count mutate 3
            throw CoreFailure("rolled back")
        }
        assertEquals(emptyList<String>(), log, "a rollback fires nothing")

        first.dispose()
        first.dispose()
        store action { count mutate 4 }
        assertEquals(listOf("second:4"), log)
    }

    // A throwing observer goes to uncaughtObserverHandler (or the platform log); the commit stands.
    @Test
    fun aThrowingObserverIsReportedAndTheCommitStands() {
        val store = CoreCounterStore()
        val failures = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        coreOnCommit(store.count) { throw CoreFailure("observer $it") }
        val later = mutableListOf<Int>()
        coreOnCommit(store.count) { later += it }

        assertIs<TransactionResult.Success<*>>(store action { count mutate 1 })
        assertEquals(listOf("observer 1"), failures.map { it.message })
        assertEquals(listOf(1), later, "the other observers still run")

        // No handler: logged by the platform default (standard error on the JVM, standard output on
        // wasmJs, so this run prints one expected "observer 2"); the commit still stands.
        store.uncaughtObserverHandler = null
        assertIs<TransactionResult.Success<*>>(store action { count mutate 2 })
        assertEquals(2, store.count.value)
        assertEquals(listOf(1, 2), later)
        assertEquals(1, failures.size)

        // A throwing handler ends the fanout and fails the action; the value stays applied
        // (documented on Store.uncaughtObserverHandler).
        store.uncaughtObserverHandler = { throw CoreFailure("handler") }
        val r = store action { count mutate 3 }
        val wrapped = assertIs<TransactionResult.Error>(r).exception
        assertContains(wrapped.message.orEmpty(), "failed during the observer/bridge fanout phase")
        assertEquals("handler", wrapped.cause?.message)
        assertEquals(TransactionStatus.Failed, r.transaction.status)
        assertEquals(3, store.count.value)
        store.uncaughtObserverHandler = { }
        assertIs<TransactionResult.Success<*>>(store action { count mutate 4 })
    }

    // D16: inside a commit's fanout, a nested action on the committing store returns Error without
    // running its body, and a bare mutate or an emit throws (reported as that observer's failure);
    // a write into another store commits on its own.
    @Test
    fun writesFromAnObserverIntoItsCommittingStoreAreRefused() {
        val store = CoreCounterStore()
        val other = CoreCounterStore()
        val events = CoreEventStore()
        val failures = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        events.uncaughtObserverHandler = { failures += it }
        var nested: TransactionResult<*>? = null
        var bodyRan = false
        coreOnCommit(store.count) { _ ->
            nested =
                store action {
                    bodyRan = true
                    label mutate "nested"
                }
            store { label mutate "bare" }
        }
        coreOnCommit(store.count) { value -> other { count mutate value * 100 } }
        coreOnCommit(events.count) { events.emit("from observer") }

        assertIs<TransactionResult.Success<*>>(store action { count mutate 1 })
        val refused = assertIs<TransactionResult.Error>(nested)
        assertContains(refused.exception.message.orEmpty(), "has already applied its writes")
        assertFalse(bodyRan)
        assertEquals(1, failures.size)
        assertContains(failures.single().message.orEmpty(), "Cannot write CoreCounterStore.label")
        assertEquals("", store.label.value)
        assertEquals(100, other.count.value, "another store's write from the fanout commits on its own")

        assertIs<TransactionResult.Success<*>>(events action { count mutate 1 })
        assertEquals(2, failures.size)
        assertContains(failures.last().message.orEmpty(), "emit an event on CoreEventStore")

        // After the fanout, the store is open for writes again.
        assertIs<TransactionResult.Success<*>>(store action { label mutate "after" })
        assertEquals("after", store.label.value)
    }

    // Last registered is outermost; a throwing onTransactionCompleted rolls back.
    @Test
    fun middlewareOrderAndACompletedHookThatThrowsRollsBack() {
        val store = CoreCounterStore()
        val log = mutableListOf<String>()
        store.middlewares(CoreRecordingMiddleware("inner", log, rejectAbove = 10), CoreRecordingMiddleware("outer", log))
        val fired = mutableListOf<Int>()
        coreOnCommit(store.count) { fired += it }

        store action {
            log += "body"
            count mutate 1
        }
        assertEquals(listOf("outer.started", "inner.started", "body", "inner.completed", "outer.completed"), log)
        log.clear()

        val r = store action { count mutate 11 }
        assertEquals("inner rejects 11", assertIs<TransactionResult.Error>(r).exception.message)
        assertEquals(
            listOf(
                "outer.started",
                "inner.started",
                "inner.completed",
                "inner.error(inner rejects 11)",
                "outer.error(inner rejects 11)",
            ),
            log,
        )
        assertEquals(1, store.count.value)
        assertEquals(listOf(1), fired)

        store.clearMiddleware()
        log.clear()
        store action { count mutate 12 }
        assertEquals(emptyList<String>(), log)
        assertEquals(12, store.count.value)
    }

    // Events drain after observers, only on commit; an emit outside an action throws.
    @Test
    fun eventfulStoreDeliversEventsOnlyOnCommit() =
        runTest {
            val store = CoreEventStore()
            val received = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.events.collect { received += "$it@${store.count.value}" }
            }

            store action {
                count mutate 1
                emit("a")
                emit("b")
            }
            testScheduler.runCurrent()
            assertEquals(listOf("a@1", "b@1"), received, "events drain after the state applies, in emit order")

            store action {
                emit("lost")
                throw CoreFailure("rollback")
            }
            store action {
                action {
                    emit("savepoint")
                    throw CoreFailure("inner")
                }
                emit("kept")
            }
            testScheduler.runCurrent()
            assertEquals(listOf("a@1", "b@1", "kept@1"), received)

            val e = assertFailsWith<IllegalStateException> { store.emit("outside") }
            assertContains(e.message.orEmpty(), "outside of an action")
        }

    // removeState/clearStates drop materialized states; the declaration re-materializes them.
    @Test
    fun removeStateAndClearStatesRecreateFromTheInitializer() {
        val store = CoreCounterStore()
        val fired = mutableListOf<Int>()
        coreOnCommit(store.count) { fired += it }
        store action {
            count mutate 5
            label mutate "kept"
        }
        assertEquals(listOf(5), fired)

        store.removeState("count")
        assertFalse(store.hasState("count"))
        assertTrue(store.hasState("label"))
        assertEquals(0, store.count.value, "re-materialized from its initializer")
        store action { count mutate 6 }
        assertEquals(listOf(5), fired, "the removed state's observers were dropped silently")

        val r =
            store action {
                count mutate 7
                removeState("count")
            }
        assertIs<IllegalStateException>(assertIs<TransactionResult.Error>(r).exception)
        assertEquals(6, store.count.value)

        store.clearStates()
        assertTrue(store.properties.isEmpty())
        assertEquals(0, store.count.value)
        assertEquals("", store.label.value)
        store.removeState("nope") // an unknown name is a no-op
    }

    // dispose(): terminal, idempotent; entrypoints throw "store disposed"; the clock stays readable.
    @Test
    fun aDisposedStoreRefusesItsEntrypoints() {
        val store = CoreCounterStore()
        val fired = mutableListOf<Int>()
        coreOnCommit(store.count) { fired += it }
        val heldCount = store.count
        store.dispose()
        store.dispose()
        assertTrue(store.isDisposed)
        assertEquals(1, store.disposeHooks)

        val failures =
            listOf(
                assertFailsWith<IllegalStateException> { store action { label mutate "x" } },
                assertFailsWith<IllegalStateException> { store { heldCount mutate 1 } },
                assertFailsWith<IllegalStateException> { store.count },
                assertFailsWith<IllegalStateException> { store.removeState("count") },
                assertFailsWith<IllegalStateException> { store.clearStates() },
                assertFailsWith<IllegalStateException> { store.middlewares() },
                assertFailsWith<IllegalStateException> { store.bindClock(null) },
                assertFailsWith<IllegalStateException> { heldCount effect { } },
            )
        assertEquals(List(failures.size) { "store disposed" }, failures.map { it.message })
        assertSame(Clock.System, store.clock, "reading the clock never throws")
        assertEquals(emptyList<Int>(), fired)

        // An action that disposes its own store.
        val self = CoreCounterStore()
        val r =
            self action {
                count mutate 1
                dispose()
                "disposed inside"
            }
        assertEquals("disposed inside", assertIs<TransactionResult.Success<String>>(r).value)
        assertEquals(TransactionStatus.Committed, r.transaction.status, "the action's own commit still completes")
        assertTrue(self.isDisposed)
        assertEquals("store disposed", assertFailsWith<IllegalStateException> { self action { } }.message)
    }

    // Store.clock: subclass override, then bindClock, then Clock.System; initializers read it lazily.
    @Test
    fun clockResolutionAndLazyInitializers() {
        val t1 = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val t2 = Instant.fromEpochMilliseconds(1_800_000_000_000)
        val store = CoreClockStore()
        assertSame(Clock.System, store.clock)
        val fixed = CoreFixedClock(t1)
        store.bindClock(fixed)
        assertSame(fixed, store.clock)
        assertEquals(t1, store.createdAt.value, "the initializer reads the clock bound before its first read")
        store.bindClock(null)
        assertSame(Clock.System, store.clock)
        assertEquals(t1, store.createdAt.value)

        val pinned = CoreClockOverrideStore(CoreFixedClock(t2))
        pinned.bindClock(fixed)
        assertEquals(t2, pinned.createdAt.value, "the subclass getter beats a bound clock")
    }

    // The D16 teaching message's recipe: an observer launches the follow-up action on
    // store.scope + Dispatchers.Default, which runs once the commit has finished.
    @Test
    fun aFollowUpActionLaunchedFromAnObserverCommitsAfterTheFanout() =
        runTest {
            val store = CoreCounterStore()
            var job: Job? = null
            var followUp: TransactionResult<*>? = null
            coreOnCommit(store.count) { value ->
                if (value == 1) {
                    job = store.scope.launch(Dispatchers.Default) { followUp = store action { label mutate "follow-up" } }
                }
            }
            assertIs<TransactionResult.Success<*>>(store action { count mutate 1 })
            checkNotNull(job).join()
            assertIs<TransactionResult.Success<*>>(followUp)
            assertEquals("follow-up", store.label.value)
        }
}
