package com.vynatix.holdfast.stress

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Nesting depth for the recursion tests — far past the 3 levels NestedActionTest covers. */
private const val SP_MAX_DEPTH = 50

/** Rounds in the seeded commit-vs-rollback mixes. */
private const val SP_MIX_ROUNDS = 200

private class SpNestStore : Store<SpNestStore>() {
    val counter by state { 0 }

    /**
     * Conflict probe. Staged values are depths (`1..SP_MAX_DEPTH`) and negated
     * depths (`-1..-SP_MAX_DEPTH`), so the initializer `0` is never a staged
     * value — a committed `0` proves a write was discarded, not applied.
     */
    val marker by state { 0 }
}

private class SpEventStore : EventfulStore<SpEventStore, Int>() {
    val n by state { 0 }
}

/**
 * Pins of the savepoint contract (Transaction.kt `commitDispatching` savepoint
 * branch, Store.kt `runBlockingActionUnderLock`) under depth, seeded rollback
 * mixes, and cross-thread contention:
 *
 *  - inner commit merges `pendingWrites` into the parent last-write-wins and
 *    appends `pendingEvents` in stage order;
 *  - inner rollback discards only the savepoint; outer rollback discards the
 *    whole tree, merged inner commits and staged events included;
 *  - read-your-own-writes walks the savepoint chain innermost-to-outermost
 *    (`Transaction.findPendingValue`);
 *  - the whole nested tree runs under one reentrant `transactionLock` hold, so
 *    depth-3 trees from 4 threads serialize with exact conservation.
 *
 * Every test carries a watchdog: a reentrancy regression in `StoreLock` would
 * park the nested acquire forever and otherwise burn the 10-minute test-task
 * cap before reporting anything.
 *
 * Serializer interplay (savepoints after `suspendAction` installs the
 * `AsyncSerializer`) is deliberately NOT covered here — `suspendAction` lives
 * in `:holdfast-coroutines`, which this module's tests cannot import; see
 * SerializerContractTest there.
 */
class SavepointStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does
     * not finish within [seconds].
     */
    private fun completesWithin(
        seconds: Long,
        what: String,
        body: () -> Unit,
    ) {
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val worker =
            Thread {
                try {
                    body()
                } catch (e: Throwable) {
                    thrown.set(e)
                } finally {
                    done.countDown()
                }
            }
        worker.isDaemon = true
        worker.name = "sp-savepoint-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — the savepoint machinery is deadlocked or spinning")
        }
        thrown.get()?.let { throw it }
    }

    /**
     * Depth-first commit recursion. Every level stages a conflicting `marker`
     * write BEFORE recursing (positive depth) and restages AFTER the inner
     * savepoint merges back (negated depth), asserting the chain-walk view at
     * each step. In-body assertion failures roll the level back and propagate
     * out through [TransactionResult.getOrThrow] with the original message.
     */
    private fun deepNestCommit(
        store: SpNestStore,
        depth: Int,
    ): TransactionResult<Unit> =
        store action {
            counter update { it + 1 }
            marker mutate depth
            assertEquals(depth, counter.value, "chain-walked counter read at depth $depth")
            assertEquals(depth, marker.value, "own pending write shadows every outer level at depth $depth")
            if (depth < SP_MAX_DEPTH) {
                deepNestCommit(store, depth + 1).getOrThrow()
                assertEquals(
                    SP_MAX_DEPTH,
                    counter.value,
                    "inner savepoint increments must all be merged up by depth $depth",
                )
                assertEquals(
                    -(depth + 1),
                    marker.value,
                    "the inner savepoint's final write must win its merge into depth $depth",
                )
            }
            marker mutate (-depth)
            assertEquals(-depth, marker.value, "restaging after the merge shadows the merged value at depth $depth")
        }

    /** Depth-first recursion whose innermost level throws; every level propagates via getOrThrow. */
    private fun deepNestFailAtBottom(
        store: SpNestStore,
        depth: Int,
    ): TransactionResult<Unit> =
        store action {
            counter update { it + 1 }
            marker mutate depth
            if (depth < SP_MAX_DEPTH) {
                deepNestFailAtBottom(store, depth + 1).getOrThrow()
            } else {
                error("bottom rollback")
            }
        }

    @Test
    fun `savepoints nest to depth 50 with last-write-wins merges at every level`() {
        completesWithin(30, "depth-50 nested commit") {
            val store = SpNestStore()
            assertEquals(0, store.counter.value)
            assertEquals(0, store.marker.value)

            deepNestCommit(store, 1).getOrThrow()

            assertEquals(
                SP_MAX_DEPTH,
                store.counter.value,
                "each of the $SP_MAX_DEPTH levels must contribute exactly one increment",
            )
            assertEquals(
                -1,
                store.marker.value,
                "the outermost restage is the last write in program order and must win the whole merge chain",
            )
        }
    }

    @Test
    fun `rollback at depth 50 discards the whole savepoint tree`() {
        completesWithin(30, "depth-50 rollback") {
            val store = SpNestStore()
            store action { counter mutate 5 }
            assertEquals(5, store.counter.value)

            val result = deepNestFailAtBottom(store, 1)

            val ex = assertIs<TransactionResult.Error>(result).exception
            assertEquals("bottom rollback", ex.message, "the original bottom exception must propagate unwrapped")
            assertEquals(5, store.counter.value, "all $SP_MAX_DEPTH staged increments must be discarded")
            assertEquals(0, store.marker.value, "no level's marker write may survive the propagated rollback")
        }
    }

    /**
     * Seeded pattern of inner savepoints that either commit or throw, with the
     * expected value computed in plain code alongside. The fixed seed makes the
     * pattern reproducible; the per-round read-your-own-writes assert pins the
     * chain walk after every merge and every discarded savepoint.
     */
    @Test
    fun `seeded mix of committing and failing savepoints nets exactly the committed count`() {
        completesWithin(30, "seeded savepoint mix") {
            val store = SpNestStore()
            assertEquals(0, store.counter.value)

            val rng = Random(1234)
            var committed = 0
            val outer =
                store action {
                    repeat(SP_MIX_ROUNDS) { round ->
                        val shouldFail = rng.nextBoolean()
                        val inner =
                            store action {
                                counter update { it + 1 }
                                if (shouldFail) error("seeded savepoint rollback")
                            }
                        if (shouldFail) {
                            val err = assertIs<TransactionResult.Error>(inner, "round $round must roll back")
                            assertEquals("seeded savepoint rollback", err.exception.message)
                        } else {
                            assertIs<TransactionResult.Success<*>>(inner, "round $round must commit")
                            committed += 1
                        }
                        assertEquals(committed, counter.value, "read-your-own-writes after round $round")
                    }
                }
            outer.getOrThrow()

            assertEquals(committed, store.counter.value, "only committed savepoints may survive the outer commit")
            assertTrue(committed in 1 until SP_MIX_ROUNDS, "seed 1234 must produce a mix of commits and rollbacks")
        }
    }

    @Test
    fun `outer rollback discards every merged inner commit`() {
        completesWithin(30, "outer rollback after inner commits") {
            val store = SpNestStore()
            store action { counter mutate 7 }
            assertEquals(7, store.counter.value)

            val rng = Random(99)
            var merged = 0
            val outer =
                store action {
                    repeat(SP_MIX_ROUNDS) {
                        val shouldFail = rng.nextBoolean()
                        store action {
                            counter update { it + 1 }
                            if (shouldFail) error("seeded savepoint rollback")
                        }
                        if (!shouldFail) merged += 1
                    }
                    assertEquals(7 + merged, counter.value, "every committed savepoint visible before the outer throw")
                    error("outer rolls back")
                }

            val ex = assertIs<TransactionResult.Error>(outer).exception
            // Surface an in-body assertion failure directly rather than as a message mismatch.
            if (ex is AssertionError) throw ex
            assertEquals("outer rolls back", ex.message)
            assertTrue(merged in 1 until SP_MIX_ROUNDS, "seed 99 must produce a mix of commits and rollbacks")
            assertEquals(7, store.counter.value, "outer rollback must discard every merged inner commit")
        }
    }

    /**
     * Cross-thread serialization of whole savepoint trees: 4 threads x 300
     * iterations, each iteration a depth-3 nest doing +1 at every level with
     * the innermost level rolling back — net exactly +2 per iteration. The
     * outermost action holds the reentrant `transactionLock` across the whole
     * tree, so the base-relative asserts inside the body are race-free and the
     * final count is exact conservation, not a statistical bound.
     */
    @Test
    fun `four threads of depth-3 nested actions net exactly two increments per iteration`() {
        val store = SpNestStore()
        // First delegate read registers the state before any cross-thread race.
        assertEquals(0, store.counter.value)

        val threadCount = 4
        val iterations = 300
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val firstFailure = AtomicReference<Throwable?>(null)

        repeat(threadCount) { t ->
            val worker =
                Thread {
                    try {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(iterations) {
                            val outer =
                                store action {
                                    val base = counter.value
                                    counter update { it + 1 }
                                    val middle =
                                        store action {
                                            counter update { it + 1 }
                                            val innermost =
                                                store action {
                                                    counter update { it + 1 }
                                                    error("discard the innermost savepoint")
                                                }
                                            assertIs<TransactionResult.Error>(innermost)
                                            assertEquals(
                                                "discard the innermost savepoint",
                                                innermost.exception.message,
                                            )
                                        }
                                    middle.getOrThrow()
                                    assertEquals(
                                        base + 2,
                                        counter.value,
                                        "levels 1+2 staged, level 3 discarded",
                                    )
                                }
                            outer.getOrThrow()
                        }
                    } catch (e: Throwable) {
                        firstFailure.compareAndSet(null, e)
                    } finally {
                        done.countDown()
                    }
                }
            worker.isDaemon = true
            worker.name = "sp-depth3-worker-$t"
            worker.start()
        }

        start.countDown()
        if (!done.await(60, TimeUnit.SECONDS)) {
            fail("depth-3 nested-action storm did not finish within 60s — savepoint nesting deadlocks under contention")
        }
        firstFailure.get()?.let { throw it }
        assertEquals(
            threadCount * iterations * 2,
            store.counter.value,
            "each iteration must net exactly +2 (the innermost level rolled back)",
        )
    }

    /**
     * Events staged across a savepoint tree fire only on the outer commit, in
     * stage order, with a discarded middle savepoint's event dropped. The
     * empty-check inside the body is deterministic: the event drain is the
     * commit's phase 3, which has not run yet. Three events stay far below the
     * default 16-slot buffer, so the sync path's tryEmit fallback cannot drop.
     */
    @Test
    fun `savepoint events drain once on outer commit in stage order`() {
        completesWithin(30, "savepoint event drain") {
            runBlocking {
                val store = SpEventStore()
                assertEquals(0, store.n.value)
                val received = CopyOnWriteArrayList<Int>()
                val subscribed = CompletableDeferred<Unit>()
                val collector =
                    launch(Dispatchers.Default) {
                        store.events
                            .onSubscription { subscribed.complete(Unit) }
                            .take(3)
                            .toList(received)
                    }
                subscribed.await()

                val outer =
                    store action {
                        emit(1)
                        val inner = store action { emit(2) }
                        inner.getOrThrow()
                        val discarded =
                            store action {
                                emit(99)
                                error("discard this savepoint's event")
                            }
                        assertIs<TransactionResult.Error>(discarded)
                        assertTrue(
                            received.isEmpty(),
                            "no event may reach a collector before the outer commit; got $received",
                        )
                        emit(3)
                        n mutate 5
                    }
                outer.getOrThrow()

                withTimeout(10_000) { collector.join() }
                assertEquals(listOf(1, 2, 3), received.toList(), "events must drain once, in stage order, 99 discarded")
                assertEquals(5, store.n.value)
            }
        }
    }

    @Test
    fun `outer rollback discards savepoint events even after inner commits`() {
        completesWithin(30, "savepoint event rollback") {
            runBlocking {
                val store = SpEventStore()
                val received = CopyOnWriteArrayList<Int>()
                val subscribed = CompletableDeferred<Unit>()
                val collector =
                    launch(Dispatchers.Default) {
                        store.events
                            .onSubscription { subscribed.complete(Unit) }
                            .take(1)
                            .toList(received)
                    }
                subscribed.await()

                val outer =
                    store action {
                        emit(10)
                        val inner = store action { emit(20) }
                        inner.getOrThrow()
                        error("outer rolls back")
                    }
                val ex = assertIs<TransactionResult.Error>(outer).exception
                if (ex is AssertionError) throw ex
                assertEquals("outer rolls back", ex.message)

                // SharedFlow delivery is ordered: had the rolled-back events drained,
                // one of them — not the sentinel — would be the single collected value.
                val sentinel = store action { emit(777) }
                sentinel.getOrThrow()

                withTimeout(10_000) { collector.join() }
                assertEquals(listOf(777), received.toList(), "only the post-rollback sentinel may reach collectors")
            }
        }
    }
}
