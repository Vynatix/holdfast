package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

private class DvPairStore : Store<DvPairStore>() {
    val x by state { 0 }
    val y by state { 0 }
}

private class DvPeerStore : Store<DvPeerStore>() {
    val z by state { 0 }
}

/**
 * Stress coverage for `derived()` recompute correctness (same-store sources only).
 *
 * The mechanics under test (Derived.kt + Store.kt): each source commit's fanout calls
 * `postCommit { self action { backing mutate compute() } }`; the task queues while the
 * committing transaction is still active and drains at top-level action exit — after the
 * serializer bracket (Store.action) or per-store at `atomic` unwind. Because the queueing
 * fanout runs inside the committing thread's own action, every queued task is executed
 * before SOME in-flight action call returns, so joining all writers implies a drained
 * queue — the convergence asserts below are deterministic at join (the short poll before
 * them is pure grace, not a correctness crutch).
 *
 * Pinned invariants:
 *  - eventual consistency after quiesce, including a derived chained off another
 *    derived's backing state (a valid source arg by signature);
 *  - same-store snapshot consistency: a recompute holds the store's `transactionLock`
 *    for its whole body, so it can never read a torn mix of two commits;
 *  - disposing the handle stops recomputation without disturbing concurrent writers;
 *  - recomputes for sources committed via `atomic` frames drain at frame unwind.
 *
 * Plus one deterministic characterization of the swallowed-recompute-failure defect.
 */
class DerivedStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not finish
     * within [seconds]. A drain or lock regression in the recompute path would otherwise
     * burn the 10-minute test-task cap before reporting anything.
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
        worker.name = "derived-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — a recompute drain is deadlocked or spinning")
        }
        thrown.get()?.let { throw it }
    }

    /** Start [threadCount] named daemon workers, release them together, and join them all. */
    private fun runWorkers(
        threadCount: Int,
        namePrefix: String,
        body: (Int) -> Unit,
    ) {
        val start = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>(null)
        val workers =
            (0 until threadCount).map { t ->
                Thread {
                    try {
                        start.await(10, TimeUnit.SECONDS)
                        body(t)
                    } catch (e: Throwable) {
                        firstFailure.compareAndSet(null, e)
                    }
                }.apply {
                    isDaemon = true
                    name = "$namePrefix-$t"
                }
            }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        firstFailure.get()?.let { throw it }
    }

    /**
     * Observation grace after quiesce: poll [condition] for up to [timeoutMs], returning
     * as soon as it holds. Callers follow with exact assertEquals so a non-convergence
     * failure reports the actual stale values.
     */
    private fun awaitQuiesce(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
    }

    /**
     * A two-level derived chain (d2 sources d1's backing state directly) must converge to
     * the final committed sources after a 4-thread commit storm, with zero recomputes lost
     * or stranded — asserted WITHOUT running any further action, which is what would mask
     * a stranded postCommit task.
     */
    @Test
    fun `chained derived states converge exactly under a commit storm`() {
        val store = DvPairStore()
        assertEquals(0, store.x.value)
        assertEquals(0, store.y.value)
        val (d1, d1Handle) = store.derived(store.x, store.y) { x.value + y.value }
        val (d2, d2Handle) = store.derived(d1, store.x) { d1.value + x.value }
        assertEquals(0, d1.value)
        assertEquals(0, d2.value)

        val threadCount = 4
        val iterations = 500
        val errors = AtomicInteger(0)
        try {
            completesWithin(60, "chained-derived commit storm") {
                runWorkers(threadCount, "dv-chain-writer") { _ ->
                    for (i in 1..iterations) {
                        val result =
                            store action {
                                x update { it + 1 }
                                y update { it + 1 }
                            }
                        if (result is TransactionResult.Error) errors.incrementAndGet()
                    }
                }
            }

            val finalEach = threadCount * iterations
            awaitQuiesce(5_000) { d1.value == 2 * finalEach && d2.value == 3 * finalEach }
            assertEquals(0, errors.get(), "every writer action must commit")
            assertEquals(finalEach, store.x.value, "x must not lose increments")
            assertEquals(finalEach, store.y.value, "y must not lose increments")
            assertEquals(2 * finalEach, d1.value, "d1 recompute was lost or stranded in postCommitTasks")
            assertEquals(3 * finalEach, d2.value, "chained d2 recompute was lost or stranded in postCommitTasks")
        } finally {
            d2Handle.dispose()
            d1Handle.dispose()
        }
    }

    /**
     * Same-store snapshot consistency: writers only ever commit (x, y) pairs with x == y,
     * so x + y is even in every committed snapshot. A recompute holds the store's
     * `transactionLock` across both source reads, so no observed derived value — whether
     * delivered to an effect or read off-thread — may ever be odd (a torn x-new/y-old mix).
     */
    @Test
    fun `every observed derived value comes from one committed snapshot`() {
        val store = DvPairStore()
        assertEquals(0, store.x.value)
        assertEquals(0, store.y.value)
        val (sum, sumHandle) = store.derived(store.x, store.y) { x.value + y.value }

        val oddDeliveries = AtomicInteger(0)
        val effectHandle = sum effect { if (this % 2 != 0) oddDeliveries.incrementAndGet() }

        val oddReads = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val reader =
            Thread {
                while (!stop.get()) {
                    if (sum.value % 2 != 0) oddReads.incrementAndGet()
                }
            }.apply {
                isDaemon = true
                name = "dv-parity-reader"
            }

        val threadCount = 4
        val iterations = 500
        val errors = AtomicInteger(0)
        try {
            reader.start()
            completesWithin(60, "parity commit storm") {
                runWorkers(threadCount, "dv-parity-writer") { _ ->
                    for (i in 1..iterations) {
                        val result =
                            store action {
                                x update { it + 1 }
                                y update { it + 1 }
                            }
                        if (result is TransactionResult.Error) errors.incrementAndGet()
                    }
                }
            }
            stop.set(true)
            reader.join(10_000)
            assertTrue(!reader.isAlive, "parity reader failed to stop")

            val finalEach = threadCount * iterations
            awaitQuiesce(5_000) { sum.value == 2 * finalEach }
            assertEquals(0, errors.get(), "every writer action must commit")
            assertEquals(0, oddDeliveries.get(), "an effect received a derived value torn across two commits")
            assertEquals(0, oddReads.get(), "an off-thread read saw a derived value torn across two commits")
            assertEquals(2 * finalEach, sum.value, "derived must converge to the final committed pair")
        } finally {
            stop.set(true)
            effectHandle.dispose()
            sumHandle.dispose()
        }
    }

    /**
     * Disposing the derived handle mid-storm must not throw, must not disturb writers,
     * and must stop recomputation: after all writers join (which drains every task queued
     * before dispose), the derived value is frozen and stays frozen while the source
     * moves on under fresh commits.
     */
    @Test
    fun `disposing the derived handle mid-storm freezes it without disturbing writers`() {
        val store = DvPairStore()
        assertEquals(0, store.x.value)
        val (d, handle) = store.derived(store.x) { x.value }
        assertEquals(0, d.value)

        val threadCount = 4
        val iterations = 500
        val total = threadCount * iterations
        val commits = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val halfway = CountDownLatch(1)
        val halfPoint = total / 2

        completesWithin(60, "derived-dispose storm") {
            val start = CountDownLatch(1)
            val firstFailure = AtomicReference<Throwable?>(null)
            val workers =
                (0 until threadCount).map { t ->
                    Thread {
                        try {
                            start.await(10, TimeUnit.SECONDS)
                            for (i in 1..iterations) {
                                val result = store action { x update { it + 1 } }
                                if (result is TransactionResult.Error) errors.incrementAndGet()
                                if (commits.incrementAndGet() == halfPoint) halfway.countDown()
                            }
                        } catch (e: Throwable) {
                            firstFailure.compareAndSet(null, e)
                        }
                    }.apply {
                        isDaemon = true
                        name = "dv-dispose-writer-$t"
                    }
                }
            workers.forEach { it.start() }
            start.countDown()
            assertTrue(halfway.await(30, TimeUnit.SECONDS), "writers never reached the halfway point")
            handle.dispose()
            workers.forEach { it.join() }
            firstFailure.get()?.let { throw it }
        }

        assertEquals(0, errors.get(), "disposing the derived handle must not fail any writer action")
        assertEquals(total, store.x.value, "disposing the derived handle must not lose writer increments")

        // Join implies quiesce: every recompute queued before dispose drained before its
        // queuing thread's action returned, and dispose removed the source subscription,
        // so no further task can be queued. The derived value is now frozen.
        val frozen = d.value
        assertTrue(frozen in 0..total, "frozen value $frozen was never a committed x")

        repeat(200) { store action { x update { it + 1 } } }
        assertEquals(total + 200, store.x.value)
        assertEquals(frozen, d.value, "a disposed derived must not recompute while its source moves on")
    }

    /**
     * Sources committed through `atomic(a, b)` frames queue their recomputes during the
     * frame's per-store commit fanout (the store's active transaction is still installed)
     * and drain per store at frame unwind (Atomic.kt `acquireAndRun` finally) — so after
     * every frame returns, a same-store derived over a frame participant has converged.
     */
    @Test
    fun `derived over an atomic frame participant converges after quiesce`() {
        val a = DvPairStore()
        val b = DvPeerStore()
        assertEquals(0, a.x.value)
        assertEquals(0, b.z.value)
        val offset = 1_000_000
        val (da, daHandle) = a.derived(a.x) { x.value + offset }
        assertEquals(offset, da.value)

        val threadCount = 4
        val iterations = 300
        val errors = AtomicInteger(0)
        try {
            completesWithin(60, "atomic-frame derived storm") {
                runWorkers(threadCount, "dv-atomic-writer") { _ ->
                    for (i in 1..iterations) {
                        val result =
                            atomic(a, b) {
                                a.action { x update { it + 1 } }
                                b.action { z update { it + 1 } }
                            }
                        if (result is TransactionResult.Error) errors.incrementAndGet()
                    }
                }
            }

            val finalEach = threadCount * iterations
            awaitQuiesce(5_000) { da.value == finalEach + offset }
            assertEquals(0, errors.get(), "every atomic frame must commit")
            assertEquals(finalEach, a.x.value, "frame commits must not lose x increments")
            assertEquals(finalEach, b.z.value, "frame commits must not lose z increments")
            assertEquals(finalEach + offset, da.value, "recompute queued during frame fanout was lost or stranded")
        } finally {
            daHandle.dispose()
        }
    }

    /**
     * BUG (characterization — flip when fixed): a derived recompute whose compute throws
     * is silently swallowed and the derived state stays stale.
     *
     * Derived.kt queues `self action { backing mutate self.compute() }` and discards the
     * action's [TransactionResult]; `Store.drainPostCommitTasks` additionally wraps each
     * task in `runCatching`. When compute throws, the recompute action rolls back, the
     * source commit still returns Success, and NOTHING surfaces the failure — not even
     * [Store.uncaughtObserverHandler], because the throw happens inside an action body,
     * not observer fanout (only a middleware `onTransactionError` hook could see it).
     *
     * Expected once fixed: the failure surfaces (e.g. through uncaughtObserverHandler) or
     * the derived is repaired — flip the frozen-value and zero-surfaced assertions then.
     * Actual today, asserted below: derived frozen at the stale value, zero errors
     * surfaced anywhere, and the next successful recompute silently heals it.
     */
    @Test
    fun `BUG - a throwing recompute is silently swallowed and the derived stays stale`() {
        val store = DvPairStore()
        assertEquals(0, store.x.value)
        val surfaced = AtomicInteger(0)
        store.uncaughtObserverHandler = { surfaced.incrementAndGet() }

        val (d, handle) =
            store.derived(store.x) {
                val v = x.value
                check(v != 1) { "poisoned recompute" }
                v * 10
            }
        try {
            assertEquals(0, d.value)

            completesWithin(15, "poisoned recompute commit") {
                val poisoned = store action { x mutate 1 }
                assertIs<TransactionResult.Success<*>>(poisoned, "the source commit itself succeeds")
            }
            // BUG: the recompute threw, rolled back, and was discarded — the derived is
            // frozen at its stale value with no error surfaced anywhere. Expected 10 (or
            // a surfaced error) once the defect is fixed.
            assertEquals(0, d.value, "recompute failure currently leaves the derived silently stale")
            assertEquals(0, surfaced.get(), "recompute failure is currently invisible to uncaughtObserverHandler")

            completesWithin(15, "healing recompute commit") {
                val healed = store action { x mutate 2 }
                assertIs<TransactionResult.Success<*>>(healed)
            }
            assertEquals(20, d.value, "the next successful recompute heals the derived")
        } finally {
            handle.dispose()
        }
    }
}
