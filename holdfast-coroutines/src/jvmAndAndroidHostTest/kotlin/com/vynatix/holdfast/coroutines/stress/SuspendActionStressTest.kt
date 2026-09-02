package com.vynatix.holdfast.coroutines.stress

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.derived
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class SaCounterStore : Store<SaCounterStore>() {
    val count by state { 0 }
}

private class SaPairStore : Store<SaPairStore>() {
    val a by state { 0 }
    val b by state { 0 }
}

private class SaQtyStore : Store<SaQtyStore>() {
    val qty by state { 0 }
}

/**
 * Exact-count middleware probe. Every hook only bumps an atomic counter, so it
 * can never perturb the transaction it is observing, and the counts admit
 * exact (not statistical) assertions after a storm.
 */
private class SaCountingMiddleware : Middleware<SaCounterStore>() {
    val started = AtomicInteger(0)
    val completed = AtomicInteger(0)
    val errored = AtomicInteger(0)

    override fun onTransactionStarted(context: MiddlewareContext<SaCounterStore>) {
        started.incrementAndGet()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<SaCounterStore>) {
        completed.incrementAndGet()
    }

    override fun onTransactionError(
        context: MiddlewareContext<SaCounterStore>,
        error: Throwable,
    ) {
        errored.incrementAndGet()
    }
}

/** Unstarted named daemon thread — daemon so a regression cannot outlive the failed test. */
private fun saThread(
    name: String,
    body: () -> Unit,
): Thread =
    Thread { body() }.apply {
        this.name = name
        isDaemon = true
    }

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds]. Same shape as `SerializerContractTest`'s watchdog:
 * the serializer's blocking acquire is a `tryLock`/yield spin, so a mutual
 * exclusion regression pins a core in `RUNNABLE` forever and would otherwise
 * burn the module's 10-minute test-task cap before reporting anything.
 */
private fun saCompletesWithin(
    seconds: Long,
    what: String,
    body: () -> Unit,
) {
    val done = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>(null)
    val worker =
        saThread("sa-stress-probe") {
            try {
                body()
            } catch (e: Throwable) {
                thrown.set(e)
            } finally {
                done.countDown()
            }
        }
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — the serializer or the post-commit drain is stuck")
    }
    thrown.get()?.let { throw it }
}

/**
 * Single-thread dispatcher backed by a named daemon thread. Used to make
 * dispatcher hops deterministic: two of these are guaranteed to be two
 * different threads, unlike `withContext(Dispatchers.IO)` from
 * `Dispatchers.Default`, which may elide the thread switch entirely.
 */
private fun saSingleThreadDispatcher(name: String): ExecutorCoroutineDispatcher =
    Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

/**
 * Fixed-size dispatcher over named daemon threads. The derived-storm test
 * needs a pool strictly larger than its writer-coroutine count: a post-commit
 * derived recompute is a blocking `action` whose serializer acquire SPINS on
 * the pool thread it runs on, and kotlinx `Mutex.unlock` hands the lock to a
 * suspended waiter whose resumption must be dispatched — if every pool thread
 * were occupied by spinners, the new lock owner could never run. With more
 * threads than writers, a free worker always exists and progress is provable.
 */
private fun saFixedPoolDispatcher(
    name: String,
    threads: Int,
): ExecutorCoroutineDispatcher {
    val counter = AtomicInteger(0)
    return Executors
        .newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}

/**
 * Stress pins for the `suspendAction` serialization contract, plus two
 * deterministic characterizations of the known read-your-own-writes hole
 * across dispatcher hops.
 *
 * The serializer (`MutexSerializer`) is installed lazily by the first
 * suspending entry point; every mixed test primes the store with one
 * `suspendAction` first, so the separate one-shot first-use install race is
 * never part of what these pins measure.
 */
class SuspendActionStressTest {
    /**
     * Mutual exclusion at scale: increments through `suspendAction` (4
     * coroutines) and blocking `action` (2 raw threads) on one store must
     * conserve exactly — a single lost update means the serializer let two
     * read-modify-write bodies interleave.
     */
    @Test
    fun `mixed suspendAction and blocking action increments conserve exactly`() {
        saCompletesWithin(30, "mixed increment storm") {
            val store = SaCounterStore()
            assertEquals(0, store.count.value) // register the state before any cross-thread traffic
            runBlocking {
                assertIs<TransactionResult.Success<*>>(store.suspendAction { }, "serializer prime")
                withTimeout(25_000) {
                    val threadFailure = AtomicReference<Throwable?>(null)
                    val startGate = CountDownLatch(1)
                    val threads =
                        (0 until 2).map { t ->
                            saThread("sa-storm-blocking-$t") {
                                try {
                                    startGate.await(30, TimeUnit.SECONDS)
                                    repeat(300) {
                                        val r = store action { count update { n -> n + 1 } }
                                        assertIs<TransactionResult.Success<*>>(r)
                                    }
                                } catch (e: Throwable) {
                                    threadFailure.compareAndSet(null, e)
                                }
                            }
                        }
                    threads.forEach { it.start() }
                    val jobs =
                        (0 until 4).map {
                            async(Dispatchers.Default) {
                                repeat(300) {
                                    val r = store.suspendAction { count update { n -> n + 1 } }
                                    assertIs<TransactionResult.Success<*>>(r)
                                }
                            }
                        }
                    startGate.countDown()
                    jobs.awaitAll()
                    threads.forEach { it.join(10_000) }
                    threads.forEach { assertTrue(!it.isAlive, "blocking worker ${it.name} did not finish") }
                    threadFailure.get()?.let { throw it }
                }
            }
            assertEquals(1800, store.count.value, "1800 serialized increments must all land — none lost, none doubled")
            assertNull(store.activeTransaction, "no transaction may leak past the storm")
        }
    }

    /**
     * The historic deadlock, at volume: `suspendAction` used to drain the
     * post-commit queue (where `derived` recomputes live) while still holding
     * the serializer mutex, so the recompute's blocking `action` spun on a
     * mutex its own call stack held. The drain now sits outside the bracket;
     * this pins that placement under a concurrent storm and asserts the
     * derived state converges exactly.
     */
    @Test
    fun `derived state converges under a suspendAction storm`() {
        saCompletesWithin(30, "suspendAction storm on a store with a derived state") {
            val store = SaQtyStore()
            assertEquals(0, store.qty.value)
            val (doubled, disposeDerived) = store.derived(store.qty) { qty.value * 2 }
            try {
                assertEquals(0, doubled.value)
                // Dedicated pool, larger than the writer count — see [saFixedPoolDispatcher].
                saFixedPoolDispatcher("sa-derived-worker", 6).use { pool ->
                    runBlocking {
                        withTimeout(25_000) {
                            val jobs =
                                (0 until 4).map {
                                    async(pool) {
                                        repeat(50) {
                                            val r = store.suspendAction { qty update { n -> n + 1 } }
                                            assertIs<TransactionResult.Success<*>>(r)
                                        }
                                    }
                                }
                            jobs.awaitAll()
                        }
                    }
                }
                // Every suspendAction drains its own recompute in its finally, so by
                // here the queue is empty and the last-run recompute read the final qty.
                assertEquals(200, store.qty.value)
                assertEquals(400, doubled.value, "derived must equal f(final sources) once the storm quiesces")
                // Post-chaos liveness: the store still takes a blocking action, and the
                // recompute queued by it drains before `action` returns.
                val sentinel = store action { qty update { n -> n + 1 } }
                assertIs<TransactionResult.Success<*>>(sentinel)
                assertEquals(201, store.qty.value)
                assertEquals(402, doubled.value)
            } finally {
                disposeDerived.dispose()
            }
        }
    }

    /**
     * Serialization across suspension points. Every writer bumps the pair
     * (a, b) by exactly +1 per transaction, staging `a` before `b`; suspending
     * writers park (`yield` + `delay`) between the two stages. Because commit
     * applies pending writes in stage order and writes are read-committed+1
     * under mutual exclusion, committed `a` is monotone and `b == k` can only
     * be observed after `a == k` was applied — so a reader that loads `b`
     * first may see `b` lag `a` but never lead it. If a blocking action ever
     * interleaved with a suspended body, its increment would fork the
     * sequence and `b` would overtake `a` (and the final totals would drift).
     */
    @Test
    fun `suspension points never let blocking writers tear the committed pair`() {
        saCompletesWithin(30, "suspension-point storm with paired invariant") {
            val store = SaPairStore()
            assertEquals(0, store.a.value)
            assertEquals(0, store.b.value)
            val threadFailure = AtomicReference<Throwable?>(null)
            val violations = AtomicReference<String?>(null)
            val stop = AtomicBoolean(false)
            val readers =
                (0 until 2).map { t ->
                    saThread("sa-pair-reader-$t") {
                        try {
                            while (!stop.get()) {
                                val bv = store.b.value
                                val av = store.a.value
                                if (av < bv) {
                                    violations.compareAndSet(
                                        null,
                                        "committed read saw b=$bv leading a=$av — a is always applied " +
                                            "before b, so a writer interleaved with a suspended body",
                                    )
                                    return@saThread
                                }
                            }
                        } catch (e: Throwable) {
                            threadFailure.compareAndSet(null, e)
                        }
                    }
                }
            readers.forEach { it.start() }
            try {
                runBlocking {
                    assertIs<TransactionResult.Success<*>>(store.suspendAction { }, "serializer prime")
                    withTimeout(25_000) {
                        val startGate = CountDownLatch(1)
                        val writers =
                            (0 until 2).map { t ->
                                saThread("sa-pair-blocking-$t") {
                                    try {
                                        startGate.await(30, TimeUnit.SECONDS)
                                        repeat(200) {
                                            val r =
                                                store action {
                                                    val next = a.value + 1
                                                    a mutate next
                                                    b mutate next
                                                }
                                            assertIs<TransactionResult.Success<*>>(r)
                                        }
                                    } catch (e: Throwable) {
                                        threadFailure.compareAndSet(null, e)
                                    }
                                }
                            }
                        writers.forEach { it.start() }
                        val jobs =
                            (0 until 2).map {
                                async(Dispatchers.Default) {
                                    repeat(40) {
                                        val r =
                                            store.suspendAction {
                                                val next = a.value + 1
                                                a mutate next
                                                yield()
                                                delay(1)
                                                b mutate next
                                            }
                                        assertIs<TransactionResult.Success<*>>(r)
                                    }
                                }
                            }
                        startGate.countDown()
                        jobs.awaitAll()
                        writers.forEach { it.join(10_000) }
                        writers.forEach { assertTrue(!it.isAlive, "writer ${it.name} did not finish") }
                    }
                }
            } finally {
                stop.set(true)
            }
            readers.forEach { it.join(10_000) }
            readers.forEach { assertTrue(!it.isAlive, "reader ${it.name} did not stop") }
            threadFailure.get()?.let { throw it }
            violations.get()?.let { fail(it) }
            assertEquals(480, store.a.value, "480 paired increments must all land on a")
            assertEquals(480, store.b.value, "480 paired increments must all land on b")
            assertNull(store.activeTransaction)
        }
    }

    /**
     * OPEN DEFECT: read-your-own-writes is thread-keyed, not coroutine-keyed.
     *
     * `MutableState.value` returns a pending write only when
     * `txn.ownerThreadId == currentThreadId()`, but a `suspendAction` body may
     * legally resume on any dispatcher thread (`mutate` was relaxed for
     * exactly that via `suspendingOwner`; the read path was not). Expected:
     * the body reads its own staged 42 everywhere. Actual: after a hop to a
     * different thread the read silently returns the committed value 0.
     *
     * Deterministic here because the transaction opens on a pinned
     * single-thread dispatcher and the hop targets a different pinned thread.
     * When the read path learns about `suspendingOwner`, flip `seenOnHop`
     * to 42 — the other assertions must keep passing unchanged.
     */
    @Test
    fun `read across a dispatcher hop sees the committed value not the staged write`() {
        saCompletesWithin(15, "RYOW dispatcher-hop probe") {
            saSingleThreadDispatcher("sa-ryow-owner").use { ownerDispatcher ->
                saSingleThreadDispatcher("sa-ryow-hop").use { hopDispatcher ->
                    val store = SaCounterStore()
                    assertEquals(0, store.count.value)
                    var ownerThreadName = ""
                    var hopThreadName = ""
                    var seenOnOwner = -1
                    var seenOnHop = -1
                    var seenBackOnOwner = -1
                    runBlocking {
                        withTimeout(10_000) {
                            val r =
                                withContext(ownerDispatcher) {
                                    store.suspendAction {
                                        ownerThreadName = Thread.currentThread().name
                                        count mutate 42
                                        seenOnOwner = count.value
                                        withContext(hopDispatcher) {
                                            hopThreadName = Thread.currentThread().name
                                            seenOnHop = count.value
                                        }
                                        seenBackOnOwner = count.value
                                    }
                                }
                            assertIs<TransactionResult.Success<*>>(r)
                        }
                    }
                    assertNotEquals(ownerThreadName, hopThreadName, "the hop must land on a different thread")
                    assertEquals(42, seenOnOwner, "RYOW holds on the thread that opened the transaction")
                    assertEquals(42, seenBackOnOwner, "RYOW holds again after hopping back")
                    assertEquals(
                        0,
                        seenOnHop,
                        "OPEN DEFECT pinned: a read on a hopped thread ignores the staged write; " +
                            "flip this to 42 when RYOW becomes coroutine-keyed",
                    )
                    assertEquals(42, store.count.value, "the write side is unaffected: commit applies the staged 42")
                }
            }
        }
    }

    /**
     * OPEN DEFECT: the write-side consequence of thread-keyed RYOW — a bare
     * `update { }` after a dispatcher hop reads the committed value, ignores
     * the transaction's own staged write, and silently clobbers it.
     *
     * Expected: stage +100, then +1 → commit 101. Actual: the hopped
     * `update` reads committed 0, stages 1, and 1 overwrites the staged 100
     * in `pendingWrites` — the commit applies 1. Flip the assertion to 101
     * when the read path is fixed.
     */
    @Test
    fun `update across a dispatcher hop silently clobbers the staged write`() {
        saCompletesWithin(15, "update dispatcher-hop probe") {
            saSingleThreadDispatcher("sa-clobber-owner").use { ownerDispatcher ->
                saSingleThreadDispatcher("sa-clobber-hop").use { hopDispatcher ->
                    val store = SaCounterStore()
                    assertEquals(0, store.count.value)
                    runBlocking {
                        withTimeout(10_000) {
                            val r =
                                withContext(ownerDispatcher) {
                                    store.suspendAction {
                                        count update { n -> n + 100 }
                                        withContext(hopDispatcher) {
                                            count update { n -> n + 1 }
                                        }
                                    }
                                }
                            assertIs<TransactionResult.Success<*>>(r)
                        }
                    }
                    assertEquals(
                        1,
                        store.count.value,
                        "OPEN DEFECT pinned: the hopped update clobbered the staged +100; " +
                            "flip this to 101 when RYOW becomes coroutine-keyed",
                    )
                }
            }
        }
    }

    /**
     * Rollback purity under a storm of throwing suspending bodies: every body
     * stages a write, suspends, then throws — every result must be an Error,
     * no staged value may ever surface, and the store must remain fully
     * usable afterwards (mutex released, transaction slot clean).
     */
    @Test
    fun `throwing suspendAction bodies roll back cleanly and release the serializer`() {
        saCompletesWithin(20, "suspending rollback storm") {
            val store = SaCounterStore()
            assertEquals(0, store.count.value)
            runBlocking {
                withTimeout(15_000) {
                    val jobs =
                        (0 until 4).map { w ->
                            async(Dispatchers.Default) {
                                repeat(25) { i ->
                                    val r =
                                        store.suspendAction {
                                            count mutate 1_000 + w * 100 + i
                                            delay(1)
                                            error("sa-rollback-$w-$i")
                                        }
                                    val err = assertIs<TransactionResult.Error>(r)
                                    assertIs<IllegalStateException>(err.exception)
                                }
                            }
                        }
                    jobs.awaitAll()
                }
            }
            assertEquals(0, store.count.value, "a rolled-back staged write must never surface")
            assertNull(store.activeTransaction, "no transaction may leak out of a rollback")
            runBlocking {
                withTimeout(10_000) {
                    val r = store.suspendAction { count mutate 7 }
                    assertIs<TransactionResult.Success<*>>(r, "the store must stay usable after 100 rollbacks")
                }
            }
            assertEquals(7, store.count.value)
        }
    }

    /**
     * Middleware accounting on the suspending path is exact under load: for
     * 600 mixed transactions (suspending and blocking, succeeding and
     * throwing), `started` fires exactly once per transaction and exactly one
     * of `completed`/`errored` follows — 600 = 460 + 140 with no slack.
     */
    @Test
    fun `middleware accounting is exact across a mixed suspending and blocking storm`() {
        saCompletesWithin(30, "middleware accounting storm") {
            val store = SaCounterStore()
            assertEquals(0, store.count.value)
            // Prime BEFORE registering the middleware so the install transaction
            // does not perturb the exact counts below.
            runBlocking {
                assertIs<TransactionResult.Success<*>>(store.suspendAction { }, "serializer prime")
            }
            val mw = SaCountingMiddleware()
            store.middlewares(mw)
            runBlocking {
                withTimeout(25_000) {
                    val threadFailure = AtomicReference<Throwable?>(null)
                    val startGate = CountDownLatch(1)
                    val threads =
                        (0 until 2).map { t ->
                            saThread("sa-mw-blocking-$t") {
                                try {
                                    startGate.await(30, TimeUnit.SECONDS)
                                    repeat(100) { i ->
                                        val r =
                                            store action {
                                                count update { n -> n + 1 }
                                                if (i % 5 == 0) error("sa-mw-blocking-$t-$i")
                                            }
                                        if (i % 5 == 0) {
                                            assertIs<TransactionResult.Error>(r)
                                        } else {
                                            assertIs<TransactionResult.Success<*>>(r)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    threadFailure.compareAndSet(null, e)
                                }
                            }
                        }
                    threads.forEach { it.start() }
                    val jobs =
                        (0 until 4).map { w ->
                            async(Dispatchers.Default) {
                                repeat(100) { i ->
                                    val r =
                                        store.suspendAction {
                                            count update { n -> n + 1 }
                                            if (i % 4 == 0) error("sa-mw-suspend-$w-$i")
                                        }
                                    if (i % 4 == 0) {
                                        assertIs<TransactionResult.Error>(r)
                                    } else {
                                        assertIs<TransactionResult.Success<*>>(r)
                                    }
                                }
                            }
                        }
                    startGate.countDown()
                    jobs.awaitAll()
                    threads.forEach { it.join(10_000) }
                    threads.forEach { assertTrue(!it.isAlive, "worker ${it.name} did not finish") }
                    threadFailure.get()?.let { throw it }
                }
            }
            assertEquals(600, mw.started.get(), "started must fire exactly once per transaction")
            assertEquals(140, mw.errored.get(), "errored must fire exactly once per throwing body")
            assertEquals(460, mw.completed.get(), "completed must fire exactly once per committing body")
            assertEquals(460, store.count.value, "exactly the successful bodies commit their increment")
            assertNull(store.activeTransaction)
        }
    }
}
