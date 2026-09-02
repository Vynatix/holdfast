package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.middleware.ProfilingMiddleware
import com.vynatix.holdfast.middleware.TimingMiddleware
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Marker staged by the veto test. No committing writer ever produces it, so a
 * committed observation of it proves a vetoed transaction leaked through fanout.
 */
private const val MW_POISON = Int.MIN_VALUE

private class MwStressStore : Store<MwStressStore>() {
    val count by state { 0 }
}

/**
 * One middleware hook invocation. [transaction] is compared by IDENTITY everywhere:
 * `Transaction.id` is derived from the body's class simple name (Store.kt), so a
 * lambda invoked in a loop yields many transactions sharing one id — the id must
 * never be used as a correlation key.
 */
private class MwHookRecord(
    val transaction: Transaction,
    val index: Int,
    val phase: String,
)

private class MwRecordingMiddleware(
    private val index: Int,
    private val log: MutableList<MwHookRecord>,
) : Middleware<MwStressStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<MwStressStore>) {
        log.add(MwHookRecord(context.transaction, index, "started"))
    }

    override fun onTransactionCompleted(context: MiddlewareContext<MwStressStore>) {
        log.add(MwHookRecord(context.transaction, index, "completed"))
    }

    override fun onTransactionError(
        context: MiddlewareContext<MwStressStore>,
        error: Throwable,
    ) {
        log.add(MwHookRecord(context.transaction, index, "error"))
    }
}

private class MwCountingMiddleware : Middleware<MwStressStore>() {
    val started = AtomicLong(0)
    val completed = AtomicLong(0)
    val errored = AtomicLong(0)

    override fun onTransactionStarted(context: MiddlewareContext<MwStressStore>) {
        started.incrementAndGet()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<MwStressStore>) {
        completed.incrementAndGet()
    }

    override fun onTransactionError(
        context: MiddlewareContext<MwStressStore>,
        error: Throwable,
    ) {
        errored.incrementAndGet()
    }
}

/**
 * Post-body validator. `onTransactionCompleted` runs on the action's owner thread
 * BEFORE the commit applies pending writes, so `count.value` here is the
 * read-your-own-writes view of the staged value (MutableState.kt) — throwing on
 * the marker must roll the whole action back with no observer or state effect.
 */
private class MwVetoMiddleware : Middleware<MwStressStore>() {
    override fun onTransactionCompleted(context: MiddlewareContext<MwStressStore>) {
        if (context.store.count.value == MW_POISON) {
            throw MwVetoException("poison value staged; vetoing commit")
        }
    }
}

private class MwNoopMiddleware : Middleware<MwStressStore>()

private class MwBodyException(
    message: String,
) : Exception(message)

private class MwVetoException(
    message: String,
) : Exception(message)

/**
 * Middleware chain under concurrent load, sync `action` path only.
 *
 * Ordering oracle, derived by reading `Store.runMiddlewareChain` (Store.kt): the
 * fold wraps the body in registration order, so the LAST-registered middleware is
 * the outermost ring. With registration order [m0, m1, m2] every transaction runs
 * `started` as 2, 1, 0 and its terminal hook as 0, 1, 2 (`completed` when the body
 * returned, via `Middleware.execute`'s try; `error` when it threw, via the catch's
 * innermost-first rethrow chain). All hooks for one action run while the owner
 * holds the store's `transactionLock` (`runBlockingActionUnderLock`), so per-store
 * hook streams are serialized — asserted here as group contiguity.
 *
 * ProfilingMiddleware/TimingMiddleware are pinned single-threaded only (exact
 * sequential accounting); their cross-thread aggregate integrity is deliberately
 * NOT asserted by this file.
 */
class MiddlewareStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not
     * finish within [seconds]. A middleware or lock regression that wedges an
     * action would otherwise burn the 10-minute test-task cap before reporting.
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
        worker.name = "middleware-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — an action or middleware call is deadlocked or spinning")
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
     * Every transaction of a 4-thread storm must record exactly the concentric-ring
     * hook sequence (outermost-first `started`, innermost-first terminal), with all
     * six records contiguous in the global log (hooks of two transactions on one
     * store must never interleave) and no transaction appearing twice.
     */
    @Test
    fun `hook ordering holds for every transaction of a mixed commit-rollback storm`() {
        val store = MwStressStore()
        assertEquals(0, store.count.value)

        val log = Collections.synchronizedList(mutableListOf<MwHookRecord>())
        store.middlewares(
            MwRecordingMiddleware(0, log),
            MwRecordingMiddleware(1, log),
            MwRecordingMiddleware(2, log),
        )

        val threadCount = 4
        val iterations = 400
        val errorsPerThread = (0 until iterations).count { it % 4 == 0 }
        val commitsPerThread = iterations - errorsPerThread
        val totalTransactions = threadCount * iterations
        val mismatches = AtomicInteger(0)

        completesWithin(60, "hook-ordering storm") {
            runWorkers(threadCount, "mw-oracle") { _ ->
                for (i in 0 until iterations) {
                    val shouldFail = i % 4 == 0
                    val result =
                        store action {
                            count update { it + 1 }
                            if (shouldFail) throw MwBodyException("seeded rollback")
                        }
                    if ((result is TransactionResult.Error) != shouldFail) mismatches.incrementAndGet()
                }
            }
        }

        assertEquals(0, mismatches.get(), "every action's outcome must match its seeded commit/rollback fate")
        assertEquals(threadCount * commitsPerThread, store.count.value, "rolled-back increments must not commit")

        val entries = log.toList()
        assertEquals(
            6 * totalTransactions,
            entries.size,
            "each of the $totalTransactions transactions must contribute exactly 3 started + 3 terminal records",
        )

        val startedShape = listOf(2 to "started", 1 to "started", 0 to "started")
        val committedShape = startedShape + listOf(0 to "completed", 1 to "completed", 2 to "completed")
        val erroredShape = startedShape + listOf(0 to "error", 1 to "error", 2 to "error")
        val seenTransactions = HashSet<Transaction>()
        var committedGroups = 0
        var erroredGroups = 0

        entries.chunked(6).forEachIndexed { groupIndex, group ->
            val txn = group.first().transaction
            assertTrue(
                group.all { it.transaction === txn },
                "group $groupIndex mixes two transactions — per-store hook streams interleaved under transactionLock",
            )
            assertTrue(
                seenTransactions.add(txn),
                "group $groupIndex repeats a transaction already recorded — a hook fired twice for one transaction",
            )
            when (val shape = group.map { it.index to it.phase }) {
                committedShape -> committedGroups++
                erroredShape -> erroredGroups++
                else -> fail("group $groupIndex ran hooks as $shape — not the concentric-ring commit or error order")
            }
        }
        assertEquals(threadCount * commitsPerThread, committedGroups, "one committed hook group per committed action")
        assertEquals(threadCount * errorsPerThread, erroredGroups, "one errored hook group per rolled-back action")
    }

    /**
     * A throwing `onTransactionCompleted` is a veto: the marked action must return
     * the exact veto exception, its staged write must never reach state or any
     * observer, and concurrent non-marker traffic must commit exactly.
     */
    @Test
    fun `completed-hook veto rolls back marked actions while concurrent traffic commits exactly`() {
        val store = MwStressStore()
        assertEquals(0, store.count.value)
        store.middlewares(MwVetoMiddleware())

        val threadCount = 4
        val iterations = 800
        val poisonPerThread = (0 until iterations).count { it % 8 == 0 }
        val commitsPerThread = iterations - poisonPerThread

        val fires = AtomicInteger(0)
        val poisonObserved = AtomicInteger(0)
        val disposable =
            store.count effect {
                fires.incrementAndGet()
                if (this == MW_POISON) poisonObserved.incrementAndGet()
            }

        val vetoErrors = AtomicInteger(0)
        val mismatches = AtomicInteger(0)
        try {
            completesWithin(60, "completed-hook veto storm") {
                runWorkers(threadCount, "mw-veto") { _ ->
                    for (i in 0 until iterations) {
                        if (i % 8 == 0) {
                            val result = store action { count mutate MW_POISON }
                            if (result is TransactionResult.Error && result.exception is MwVetoException) {
                                vetoErrors.incrementAndGet()
                            } else {
                                mismatches.incrementAndGet()
                            }
                        } else {
                            val result = store action { count update { it + 1 } }
                            if (result !is TransactionResult.Success<*>) mismatches.incrementAndGet()
                        }
                    }
                }
            }
        } finally {
            disposable.dispose()
        }

        assertEquals(0, mismatches.get(), "every poison action must veto and every increment must commit")
        assertEquals(threadCount * poisonPerThread, vetoErrors.get(), "each veto must surface as the MwVetoException")
        assertEquals(
            threadCount * commitsPerThread,
            store.count.value,
            "vetoed writes must roll back; committed increments must be exact",
        )
        assertEquals(0, poisonObserved.get(), "a vetoed transaction fanned its staged write out to an observer")
        assertEquals(
            threadCount * commitsPerThread + 1,
            fires.get(),
            "observer must fire once at subscription plus exactly once per committed action — never for a veto",
        )
    }

    /** started == completed + errored, with exact totals, across a seeded 6-thread mixed storm. */
    @Test
    fun `counting middleware totals are exact across a mixed commit-rollback storm`() {
        val store = MwStressStore()
        assertEquals(0, store.count.value)
        val counting = MwCountingMiddleware()
        store.middlewares(counting)

        val threadCount = 6
        val iterations = 900
        val errorsPerThread = (0 until iterations).count { it % 3 == 0 }
        val commitsPerThread = iterations - errorsPerThread
        val mismatches = AtomicInteger(0)

        completesWithin(60, "counting-middleware storm") {
            runWorkers(threadCount, "mw-counting") { _ ->
                for (i in 0 until iterations) {
                    val shouldFail = i % 3 == 0
                    val result =
                        store action {
                            count update { it + 1 }
                            if (shouldFail) throw MwBodyException("seeded rollback")
                        }
                    if ((result is TransactionResult.Error) != shouldFail) mismatches.incrementAndGet()
                }
            }
        }

        assertEquals(0, mismatches.get(), "every action's outcome must match its seeded fate")
        assertEquals((threadCount * iterations).toLong(), counting.started.get(), "one started per action")
        assertEquals((threadCount * commitsPerThread).toLong(), counting.completed.get(), "one completed per commit")
        assertEquals((threadCount * errorsPerThread).toLong(), counting.errored.get(), "one error per rollback")
        assertEquals(
            counting.started.get(),
            counting.completed.get() + counting.errored.get(),
            "every started transaction must reach exactly one terminal hook",
        )
        assertEquals(threadCount * commitsPerThread, store.count.value, "committed increments must be exact")
        assertNull(store.activeTransaction, "no transaction may remain active after the storm")
    }

    /**
     * Sequential exactness pin for [ProfilingMiddleware]: N commits, M seeded
     * rollbacks (each staging a write before throwing, so the error-hook sample
     * still attributes the state name), K nested-savepoint pairs (chain fires for
     * the inner action too — 2 transactions per pair, 1 savepoint), then a
     * lossless [ProfilingMiddleware.reset]. Cross-thread aggregate integrity is
     * intentionally not asserted in this file.
     */
    @Test
    fun `ProfilingMiddleware records exact sequential aggregates and reset is lossless`() {
        val store = MwStressStore()
        assertEquals(0, store.count.value)
        val profiler = ProfilingMiddleware<MwStressStore>()
        store.middlewares(profiler)

        val commits = 200
        val rollbacks = 50
        val savepointPairs = 30
        val totalTransactions = (commits + rollbacks + 2 * savepointPairs).toLong()

        completesWithin(30, "sequential profiled transactions") {
            repeat(commits) {
                assertIs<TransactionResult.Success<*>>(store action { count update { it + 1 } })
            }
            repeat(rollbacks) {
                val result =
                    store action {
                        count mutate -1
                        throw MwBodyException("seeded rollback")
                    }
                assertIs<TransactionResult.Error>(result)
            }
            repeat(savepointPairs) {
                val result =
                    store action {
                        count update { it + 1 }
                        store action { count update { it + 1 } }
                    }
                assertIs<TransactionResult.Success<*>>(result)
            }
        }
        assertEquals(commits + 2 * savepointPairs, store.count.value)

        val first = profiler.profile()
        assertEquals(totalTransactions, first.transactionCount, "one sample per action, including savepoints")
        assertEquals((commits + 2 * savepointPairs).toLong(), first.committedCount, "one committed per commit")
        assertEquals(rollbacks.toLong(), first.rolledBackCount, "one rolled-back per seeded rollback")
        assertEquals(savepointPairs.toLong(), first.savepointCount, "one savepoint per nested action")
        assertEquals(
            first.transactionCount,
            first.committedCount + first.rolledBackCount,
            "every sample must be attributed to exactly one outcome",
        )
        val slowest = assertNotNull(first.slowest, "slowest must be non-null once anything was profiled")
        assertTrue(first.totalDuration >= slowest.duration, "the sum of durations must dominate the slowest sample")
        assertEquals(
            mapOf("count" to totalTransactions),
            first.stateWriteCounts,
            "every transaction staged 'count' exactly once — rollbacks and savepoints included",
        )

        val drained = profiler.reset()
        assertEquals(first, drained, "reset must return the same snapshot profile() just showed")
        val zeroed = profiler.profile()
        assertEquals(0L, zeroed.transactionCount)
        assertEquals(0L, zeroed.committedCount)
        assertEquals(0L, zeroed.rolledBackCount)
        assertEquals(0L, zeroed.savepointCount)
        assertEquals(Duration.ZERO, zeroed.totalDuration)
        assertNull(zeroed.slowest, "reset must clear the slowest sample")
        assertTrue(zeroed.stateWriteCounts.isEmpty(), "reset must clear state-write attribution")

        repeat(3) {
            assertIs<TransactionResult.Success<*>>(store action { count update { it + 1 } })
        }
        val after = profiler.profile()
        assertEquals(3L, after.transactionCount, "profiling must continue after reset")
        assertEquals(3L, after.committedCount)
    }

    /**
     * Sequential exactness pin for [TimingMiddleware]: exactly one report per
     * transaction, in execution order, with the status matching the seeded fate.
     * Elapsed values are wall-clock and deliberately unasserted; cross-thread
     * integrity is intentionally not asserted in this file.
     */
    @Test
    fun `TimingMiddleware reports each sequential transaction exactly once with the right status`() {
        val store = MwStressStore()
        assertEquals(0, store.count.value)
        val statuses = mutableListOf<TransactionStatus>()
        store.middlewares(TimingMiddleware<MwStressStore> { _, status, _ -> statuses.add(status) })

        val iterations = 140
        val errors = (0 until iterations).count { it % 7 == 0 }
        val mismatches = AtomicInteger(0)

        completesWithin(30, "sequential timed transactions") {
            for (i in 0 until iterations) {
                val shouldFail = i % 7 == 0
                val result =
                    store action {
                        count update { it + 1 }
                        if (shouldFail) throw MwBodyException("seeded rollback")
                    }
                if ((result is TransactionResult.Error) != shouldFail) mismatches.incrementAndGet()
            }
        }

        assertEquals(0, mismatches.get(), "every action's outcome must match its seeded fate")
        assertEquals(iterations, statuses.size, "exactly one onResult report per transaction")
        statuses.forEachIndexed { i, status ->
            val expected = if (i % 7 == 0) TransactionStatus.RolledBack else TransactionStatus.Committed
            assertEquals(expected, status, "transaction $i reported the wrong status or fired out of order")
        }
        assertEquals(iterations - errors, store.count.value)
    }

    /**
     * `middlewares()`/`clearMiddleware()` churn concurrent with a writer storm:
     * only stability is pinned — no exception on any thread, every action commits,
     * the counter is exact, and the store stays live afterwards. What any given
     * action's chain contained mid-churn is deliberately unasserted.
     */
    @Test
    fun `register-clear churn under a writer storm stays stable and loses nothing`() {
        val store = MwStressStore()
        assertEquals(0, store.count.value)

        val threadCount = 4
        val iterations = 1_500
        val stop = AtomicBoolean(false)
        val churnFailure = AtomicReference<Throwable?>(null)
        val churnRunning = CountDownLatch(1)
        val noop = MwNoopMiddleware()
        val churn =
            Thread {
                try {
                    while (!stop.get()) {
                        store.middlewares(noop)
                        store.clearMiddleware()
                        churnRunning.countDown()
                    }
                } catch (e: Throwable) {
                    churnFailure.set(e)
                } finally {
                    churnRunning.countDown()
                }
            }
        churn.isDaemon = true
        churn.name = "mw-churn"
        churn.start()
        assertTrue(churnRunning.await(10, TimeUnit.SECONDS), "churn thread never completed a register/clear cycle")

        val errorCount = AtomicInteger(0)
        try {
            completesWithin(60, "register/clear churn storm") {
                runWorkers(threadCount, "mw-churn-writer") { _ ->
                    for (i in 1..iterations) {
                        val result = store action { count update { it + 1 } }
                        if (result is TransactionResult.Error) errorCount.incrementAndGet()
                    }
                }
            }
        } finally {
            stop.set(true)
            churn.join(10_000)
        }

        churnFailure.get()?.let {
            throw AssertionError("middlewares()/clearMiddleware() threw during the storm", it)
        }
        assertFalse(churn.isAlive, "churn thread failed to stop — middlewares()/clearMiddleware() is wedged")
        assertEquals(0, errorCount.get(), "middleware churn must never fail a writer's action")
        assertEquals(threadCount * iterations, store.count.value, "no increment may be lost to middleware churn")

        val sentinel = store action { count update { it + 1 } }
        assertIs<TransactionResult.Success<*>>(sentinel, "the store must still accept actions after the churn")
        assertEquals(threadCount * iterations + 1, store.count.value)
    }
}
