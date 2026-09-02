package com.vynatix.holdfast.coroutines.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class SuAccountStore(
    initial: Long,
) : Store<SuAccountStore>() {
    val balance by state { initial }
}

private class SuCounterStore : Store<SuCounterStore>() {
    val count by state { 0 }
}

private class SuPairStore : Store<SuPairStore>() {
    val left by state { 0 }
    val right by state { 0 }
}

/** Marker for deliberately seeded aborts, so the tests can tell them apart from real failures. */
private class SuAbortException : RuntimeException("seeded abort")

/** One planned transfer: participant indices, amount, and whether the body throws after staging. */
private data class SuTransfer(
    val from: Int,
    val to: Int,
    val amount: Long,
    val abort: Boolean,
)

/**
 * Run [body] on a daemon worker and fail — rather than hang — if it does not
 * finish within [seconds].
 *
 * The failure modes stressed here (a leaked serializer mutex, a blocking
 * `atomic` spinning on `blockingAcquire`, a cancellation that strands a frame
 * root) manifest as hangs or 100%-CPU spins, not as thrown exceptions. Without
 * this watchdog a regression would burn the whole 10-minute test-task cap
 * before reporting anything. The worker is a daemon so a regression cannot
 * keep the JVM alive after the failure is reported.
 */
private fun suCompletesWithin(
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
    worker.name = "su-suspend-atomic-stress-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — a serializer mutex or store lock was leaked")
    }
    thrown.get()?.let { throw it }
}

/**
 * Stress pins for [suspendAtomic] against its blocking peer [atomic] and
 * against cancellation storms on [suspendAction].
 *
 * Every test here pins a contract the current code satisfies:
 *  - blocking `atomic()`, blocking `action {}`, and `suspendAtomic` on shared
 *    stores are mutually exclusive via the per-store serializer (the 0.2.0
 *    "serialize blocking atomic() against suspending work" fix), so a seeded
 *    mixed-flavor transfer mesh conserves every balance EXACTLY;
 *  - cancellation rolls staged writes back with no trace, while the commit
 *    phase is NonCancellable and indivisible — accounted exactly, never
 *    probabilistically;
 *  - a cancelled `suspendAtomic` body rolls back EVERY participant and
 *    releases every serializer mutex.
 *
 * Determinism: workloads are seeded (`Random` with fixed seeds) or gated by
 * latches/deferreds. No assertion depends on which side of a race wins — where
 * cancellation races commit, both outcomes are legal and the accounting must
 * balance either way.
 */
class SuspendAtomicStressTest {
    /**
     * Mixed-flavor mesh over three stores: coroutines running `suspendAtomic`
     * transfers, raw threads running blocking `atomic` transfers, and raw
     * threads running single-store no-net `action`s, ~15% of each seeded to
     * abort after staging. Because every op either fully commits or fully
     * rolls back, the final balances are exactly computable from the seeded
     * plans — any interleaving of the three entry points (lost update, torn
     * frame, unserialized overlap) shows up as drift.
     */
    @Test
    fun `mixed flavor transfer mesh conserves every balance exactly`() {
        suCompletesWithin(60, "mixed suspendAtomic/atomic/action mesh") {
            runBlocking {
                val initialBalance = 10_000L
                val stores =
                    listOf(
                        SuAccountStore(initialBalance),
                        SuAccountStore(initialBalance),
                        SuAccountStore(initialBalance),
                    )
                stores.forEach { assertEquals(initialBalance, it.balance.value) }
                // Install every store's serializer up-front so the mesh exercises
                // steady-state blocking-vs-suspending serialization rather than the
                // one-shot first-use install race (a separate hazard).
                val warmUp = suspendAtomic(stores[0], stores[1], stores[2]) {}
                assertIs<TransactionResult.Success<*>>(warmUp, "serializer warm-up frame failed")

                fun transferPlan(seed: Int): List<SuTransfer> {
                    val rng = Random(seed)
                    return List(100) {
                        val from = rng.nextInt(stores.size)
                        val to = (from + 1 + rng.nextInt(stores.size - 1)) % stores.size
                        SuTransfer(from, to, (rng.nextInt(9) + 1).toLong(), rng.nextInt(100) < 15)
                    }
                }
                val suspendPlans = List(3) { transferPlan(1_000 + it) }
                val atomicPlans = List(3) { transferPlan(2_000 + it) }
                val actionPlans =
                    List(2) { w ->
                        val rng = Random(3_000 + w)
                        List(200) { rng.nextInt(100) < 15 }
                    }

                // Replay the seeded plans to compute the exact expected outcome:
                // aborted ops are all-or-nothing rollbacks, no-net actions cancel out.
                val expectedNet = LongArray(stores.size)
                (suspendPlans + atomicPlans).forEach { plan ->
                    plan.forEach { t ->
                        if (!t.abort) {
                            expectedNet[t.from] -= t.amount
                            expectedNet[t.to] += t.amount
                        }
                    }
                }
                val seededTransferAborts = (suspendPlans + atomicPlans).sumOf { plan -> plan.count { it.abort } }
                val seededActionAborts = actionPlans.sumOf { plan -> plan.count { it } }

                val failures = ConcurrentLinkedQueue<String>()
                val observedTransferAborts = AtomicInteger(0)
                val observedActionAborts = AtomicInteger(0)

                fun recordTransfer(
                    t: SuTransfer,
                    r: TransactionResult<*>,
                ) {
                    if (t.abort) {
                        if (r is TransactionResult.Error && r.exception is SuAbortException) {
                            observedTransferAborts.incrementAndGet()
                        } else {
                            failures.add("seeded-abort transfer $t expected Error(SuAbortException), got $r")
                        }
                    } else if (r !is TransactionResult.Success<*>) {
                        failures.add("transfer $t expected Success, got $r")
                    }
                }

                val goThreads = CountDownLatch(1)
                val goSuspend = CompletableDeferred<Unit>()

                val atomicThreads =
                    atomicPlans.mapIndexed { w, plan ->
                        Thread {
                            goThreads.await()
                            for (t in plan) {
                                val from = stores[t.from]
                                val to = stores[t.to]
                                val r =
                                    atomic(from, to) {
                                        from.action { balance update { it - t.amount } }
                                        to.action { balance update { it + t.amount } }
                                        if (t.abort) throw SuAbortException()
                                    }
                                recordTransfer(t, r)
                            }
                        }.apply {
                            isDaemon = true
                            name = "su-mesh-atomic-$w"
                        }
                    }
                val actionThreads =
                    actionPlans.mapIndexed { w, plan ->
                        Thread {
                            goThreads.await()
                            plan.forEachIndexed { idx, abort ->
                                val s = stores[(w + idx) % stores.size]
                                val r =
                                    s action {
                                        balance update { it + 7 }
                                        balance update { it - 7 }
                                        if (abort) throw SuAbortException()
                                    }
                                if (abort) {
                                    if (r is TransactionResult.Error && r.exception is SuAbortException) {
                                        observedActionAborts.incrementAndGet()
                                    } else {
                                        failures.add("seeded-abort action expected Error(SuAbortException), got $r")
                                    }
                                } else if (r !is TransactionResult.Success<*>) {
                                    failures.add("no-net action expected Success, got $r")
                                }
                            }
                        }.apply {
                            isDaemon = true
                            name = "su-mesh-action-$w"
                        }
                    }

                val suspendJobs =
                    suspendPlans.map { plan ->
                        async(Dispatchers.Default) {
                            goSuspend.await()
                            for (t in plan) {
                                val from = stores[t.from]
                                val to = stores[t.to]
                                val r =
                                    suspendAtomic(from, to) {
                                        from { balance update { it - t.amount } }
                                        to { balance update { it + t.amount } }
                                        if (t.abort) throw SuAbortException()
                                    }
                                recordTransfer(t, r)
                            }
                        }
                    }

                atomicThreads.forEach { it.start() }
                actionThreads.forEach { it.start() }
                goThreads.countDown()
                goSuspend.complete(Unit)
                suspendJobs.awaitAll()
                atomicThreads.forEach { it.join() }
                actionThreads.forEach { it.join() }

                assertTrue(failures.isEmpty(), "unexpected outcomes:\n${failures.joinToString("\n")}")
                assertEquals(
                    seededTransferAborts,
                    observedTransferAborts.get(),
                    "every seeded transfer abort must surface as exactly one Error",
                )
                assertEquals(
                    seededActionAborts,
                    observedActionAborts.get(),
                    "every seeded action abort must surface as exactly one Error",
                )
                stores.forEachIndexed { k, s ->
                    assertEquals(initialBalance + expectedNet[k], s.balance.value, "store $k drifted from the plan")
                    assertNull(s.activeTransaction, "store $k left a dangling active transaction")
                }
                assertEquals(3 * initialBalance, stores.sumOf { it.balance.value }, "total balance not conserved")

                // Liveness sentinels: both flavors must still commit after the mesh.
                val s0 = stores[0]
                val s1 = stores[1]
                val suspendSentinel =
                    suspendAtomic(s0, s1) {
                        s0 { balance update { it - 1 } }
                        s1 { balance update { it + 1 } }
                    }
                assertIs<TransactionResult.Success<*>>(suspendSentinel, "suspendAtomic wedged after the mesh")
                val blockingSentinel =
                    stores[2] action {
                        balance update { it + 5 }
                        balance update { it - 5 }
                    }
                assertIs<TransactionResult.Success<*>>(blockingSentinel, "blocking action wedged after the mesh")
                assertEquals(3 * initialBalance, stores.sumOf { it.balance.value }, "sentinels broke conservation")
            }
        }
    }

    /**
     * Cancellation storm: 100 suspendActions that each stage one increment and
     * then suspend, cancelled mid-flight as a group. The split between
     * committed and cancelled is timing-dependent, so the assertions are
     * split-invariant: the counter must equal the number of Success results
     * EXACTLY (each cancelled action leaves no trace), the observer must have
     * fired once per commit, no cancellation may fold into an Error result,
     * and the store must stay fully usable. A "hostage" action that parks on a
     * never-completed deferred after staging a write guarantees the
     * cancelled-mid-body path is covered deterministically, and a firstCommit
     * gate guarantees the committed path is too.
     */
    @Test
    fun `cancellation storm leaves exactly the committed increments and a usable store`() {
        suCompletesWithin(30, "suspendAction cancellation storm") {
            runBlocking {
                val store = SuCounterStore()
                assertEquals(0, store.count.value)
                val effectFires = AtomicInteger(0)
                val sub = store.count effect { effectFires.incrementAndGet() }
                val baseline = effectFires.get()
                val started = AtomicInteger(0)
                val committed = AtomicInteger(0)
                val errored = AtomicInteger(0)
                val firstCommit = CompletableDeferred<Unit>()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val jobs =
                        List(100) {
                            scope.launch {
                                val r =
                                    store.suspendAction {
                                        started.incrementAndGet()
                                        count mutate count.value + 1
                                        delay(2)
                                    }
                                if (r is TransactionResult.Success<*>) {
                                    committed.incrementAndGet()
                                    firstCommit.complete(Unit)
                                } else {
                                    errored.incrementAndGet()
                                }
                            }
                        }
                    withTimeout(10_000) { firstCommit.await() }
                    // Stages a write, then parks on a deferred that is never
                    // completed: this action can only ever leave via cancellation.
                    val blocker = CompletableDeferred<Unit>()
                    val hostage =
                        scope.launch {
                            store.suspendAction {
                                count mutate count.value + 1_000_000
                                blocker.await()
                            }
                        }
                    scope.cancel()
                    withTimeout(15_000) { (jobs + hostage).joinAll() }

                    assertTrue(hostage.isCancelled, "the hostage can only finish via cancellation")
                    assertEquals(0, errored.get(), "cancellation must never fold into an Error result")
                    val commits = committed.get()
                    assertTrue(commits >= 1, "the firstCommit gate guarantees at least one commit")
                    assertEquals(commits, store.count.value, "count must equal Success results exactly")
                    assertEquals(commits, effectFires.get() - baseline, "observer fires must equal commits exactly")
                    assertTrue(started.get() >= commits, "every commit must have run a body")
                    val completedNormally = jobs.count { !it.isCancelled }
                    assertTrue(completedNormally <= commits, "a job completed normally without recording a commit")
                    assertNull(store.activeTransaction, "storm left a dangling active transaction")

                    sub.dispose()
                    val suspendSentinel = store.suspendAction { count mutate count.value + 1 }
                    assertIs<TransactionResult.Success<*>>(suspendSentinel, "suspendAction wedged after the storm")
                    val blockingSentinel = store action { count mutate count.value + 1 }
                    assertIs<TransactionResult.Success<*>>(blockingSentinel, "blocking action wedged after the storm")
                    assertEquals(commits + 2, store.count.value, "sentinels must build on the storm's commits")
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    /**
     * Cancel-during-commit pin: a suspendAction whose body stages two writes
     * and returns immediately, raced against `job.cancel()` on every even
     * iteration. Whether the cancellation lands before the body or after the
     * NonCancellable commit is scheduling-dependent; what is pinned is that
     * the outcome is always binary — a Success result with BOTH states
     * advanced, or no result with BOTH states untouched. `left == right` is
     * asserted after every iteration, so a torn commit fails on the exact
     * iteration that produced it.
     */
    @Test
    fun `racing cancellation never tears or spuriously duplicates a two-state commit`() {
        suCompletesWithin(30, "cancel-vs-commit race accounting") {
            runBlocking {
                val store = SuPairStore()
                assertEquals(0, store.left.value)
                assertEquals(0, store.right.value)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val outcome = AtomicReference<TransactionResult<*>?>(null)
                    var lastCommitted = 0
                    for (i in 1..200) {
                        outcome.set(null)
                        val job =
                            scope.launch {
                                val r =
                                    store.suspendAction {
                                        left mutate i
                                        right mutate i
                                    }
                                outcome.set(r)
                            }
                        if (i % 2 == 0) job.cancel()
                        withTimeout(10_000) { job.join() }
                        val leftNow = store.left.value
                        val rightNow = store.right.value
                        assertEquals(leftNow, rightNow, "torn commit at iteration $i: left=$leftNow right=$rightNow")
                        when (val r = outcome.get()) {
                            is TransactionResult.Success<*> -> {
                                assertEquals(i, leftNow, "Success at iteration $i must be fully applied")
                            }
                            is TransactionResult.Error -> {
                                fail("iteration $i unexpectedly returned Error: ${r.exception}")
                            }
                            null -> {
                                assertTrue(job.isCancelled, "iteration $i recorded no result yet was not cancelled")
                                assertEquals(lastCommitted, leftNow, "cancelled iteration $i left a partial trace")
                            }
                        }
                        if (i % 2 != 0) {
                            assertIs<TransactionResult.Success<*>>(outcome.get(), "un-cancelled iteration $i must commit")
                        }
                        lastCommitted = leftNow
                    }
                    assertTrue(lastCommitted >= 199, "iteration 199 is never cancelled, so at least 199 must commit")
                    val sentinel =
                        store action {
                            left mutate 777
                            right mutate 777
                        }
                    assertIs<TransactionResult.Success<*>>(sentinel, "store wedged after the race loop")
                    assertEquals(777, store.left.value)
                    assertEquals(777, store.right.value)
                    assertNull(store.activeTransaction, "race loop left a dangling active transaction")
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    /**
     * suspendAtomic cancelled mid-body: the frame stages writes into all three
     * participants, signals, then parks on a deferred that is never completed
     * — so the cancellation deterministically lands after staging and before
     * commit. Every participant must roll back, every serializer mutex must be
     * released (proved by an immediately-following successful frame), and the
     * seeded successful transfers keep the balances exactly computable.
     */
    @Test
    fun `suspendAtomic cancelled mid-body rolls back every participant and releases every lock`() {
        suCompletesWithin(30, "suspendAtomic mid-body cancellation loop") {
            runBlocking {
                val a = SuAccountStore(1_000)
                val b = SuAccountStore(1_000)
                val c = SuAccountStore(1_000)
                assertEquals(1_000L, a.balance.value)
                assertEquals(1_000L, b.balance.value)
                assertEquals(1_000L, c.balance.value)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    var expectedA = 1_000L
                    var expectedB = 1_000L
                    var expectedC = 1_000L
                    repeat(50) { i ->
                        val entered = CompletableDeferred<Unit>()
                        val blocker = CompletableDeferred<Unit>()
                        val job =
                            scope.launch {
                                suspendAtomic(a, b, c) {
                                    a { balance update { it - 6 } }
                                    b { balance update { it + 2 } }
                                    c { balance update { it + 4 } }
                                    entered.complete(Unit)
                                    blocker.await()
                                }
                            }
                        withTimeout(10_000) { entered.await() }
                        job.cancel()
                        withTimeout(10_000) { job.join() }
                        assertTrue(job.isCancelled, "iteration $i: cancellation must propagate out of suspendAtomic")
                        assertEquals(expectedA, a.balance.value, "iteration $i: store a must roll back")
                        assertEquals(expectedB, b.balance.value, "iteration $i: store b must roll back")
                        assertEquals(expectedC, c.balance.value, "iteration $i: store c must roll back")
                        val transfer =
                            suspendAtomic(a, b, c) {
                                a { balance update { it - 3 } }
                                b { balance update { it + 1 } }
                                c { balance update { it + 2 } }
                            }
                        assertIs<TransactionResult.Success<*>>(transfer, "iteration $i: locks not released after cancel")
                        expectedA -= 3
                        expectedB += 1
                        expectedC += 2
                        assertEquals(expectedA, a.balance.value, "iteration $i: transfer must land on a")
                        assertEquals(expectedB, b.balance.value, "iteration $i: transfer must land on b")
                        assertEquals(expectedC, c.balance.value, "iteration $i: transfer must land on c")
                    }
                    assertEquals(850L, a.balance.value)
                    assertEquals(1_050L, b.balance.value)
                    assertEquals(1_100L, c.balance.value)
                    assertEquals(3_000L, a.balance.value + b.balance.value + c.balance.value, "conservation broken")
                    assertNull(a.activeTransaction)
                    assertNull(b.activeTransaction)
                    assertNull(c.activeTransaction)
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
