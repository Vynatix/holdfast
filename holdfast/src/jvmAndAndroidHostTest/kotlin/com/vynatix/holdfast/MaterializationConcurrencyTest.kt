package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Both threads must be inside their initializers before either reads on; a retry passes straight through. */
private fun CountDownLatch.meet() {
    countDown()
    check(await(10, TimeUnit.SECONDS)) { "the other initializer never started" }
}

/** `x` reads [OppositeB.w] while [OppositeB.y] reads `z`: opposite directions, no cycle. */
private class OppositeA : Store<OppositeA>() {
    lateinit var other: OppositeB
    val bothInside = CountDownLatch(2)
    val z by state { 1 }
    val x by state {
        bothInside.meet()
        other.w.value + 1
    }
}

private class OppositeB : Store<OppositeB>() {
    lateinit var other: OppositeA
    val w by state { 2 }
    val y by state {
        other.bothInside.meet()
        other.z.value + 1
    }
}

/** `x` needs [CycleB.y], which needs `x`: a genuine cycle across two stores. */
private class CycleA : Store<CycleA>() {
    lateinit var other: CycleB
    val bothInside = CountDownLatch(2)
    val x: State<Int> by state {
        bothInside.meet()
        other.y.value + 1
    }
}

private class CycleB : Store<CycleB>() {
    lateinit var other: CycleA
    val y: State<Int> by state {
        other.bothInside.meet()
        other.x.value + 1
    }
}

private class SlowInitStore : Store<SlowInitStore>() {
    val runs = AtomicInteger()
    val slow by state {
        runs.incrementAndGet()
        Thread.sleep(5)
        "ready"
    }
    val other by state { 0 }
}

/** `slow`'s initializer blocks until [proceed] opens, so a test can dispose the store mid-initializer. */
private class GatedInitStore : Store<GatedInitStore>() {
    val runs = AtomicInteger()
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val slow by state {
        runs.incrementAndGet()
        entered.countDown()
        check(proceed.await(10, TimeUnit.SECONDS)) { "the test never released the initializer" }
        "ready"
    }
}

/** Every commit writes one value to all of [cells], so any mix of values in a snapshot is a torn read. */
private class WideStore : Store<WideStore>() {
    val c0 by state { 0 }
    val c1 by state { 0 }
    val c2 by state { 0 }
    val c3 by state { 0 }
    val c4 by state { 0 }
    val c5 by state { 0 }
    val c6 by state { 0 }
    val c7 by state { 0 }
    val c8 by state { 0 }
    val c9 by state { 0 }
    val c10 by state { 0 }
    val c11 by state { 0 }
    val c12 by state { 0 }
    val c13 by state { 0 }
    val c14 by state { 0 }
    val c15 by state { 0 }

    val cells: List<State<Int>>
        get() = listOf(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15)
}

/** A value whose `equals` always throws, so a `distinct` commit of it fails in the apply pass. */
private class Boom(
    val n: Int,
) {
    override fun equals(other: Any?): Boolean = error("boom")

    override fun hashCode(): Int = n
}

/** `a` is staged before `boom`, so a commit of both applies `a` before `boom`'s `equals` throws. */
private class ApplyFailureStore : Store<ApplyFailureStore>() {
    val a by state { 0 }
    val boom by state(distinct = true) { Boom(0) }
}

private class InboundStore : Store<InboundStore>() {
    val n by state { 0 }
}

/** A bridge whose inbound values the test pushes by hand. */
private class PushBridge : Bridge<Int> {
    private val observers = ConcurrentLinkedQueue<(Int) -> Unit>()

    override fun observe(observer: (Int) -> Unit): Disposable {
        observers += observer
        return Disposable { observers -= observer }
    }

    override fun publish(value: Int): Boolean = true

    fun push(value: Int) = observers.forEach { it(value) }
}

/**
 * Materialization under real threads (issue #20, R5/D3/D7). Every test runs
 * under a watchdog: the regressions these guard against are hangs.
 */
class MaterializationConcurrencyTest {
    @Test
    fun `opposite cross-store initializers do not deadlock`() {
        val a = OppositeA()
        val b = OppositeB()
        a.other = b
        b.other = a
        val results = ConcurrentLinkedQueue<Any>()

        // Before per-declaration latches, each initializer ran under its own
        // store's propertiesLock, so x (holding A's) waited for B's to read w
        // while y (holding B's) waited for A's to read z: AB-BA.
        completesWithin(20, "two opposite cross-store initializers") {
            val t1 = daemon("materialize-a-x") { results += runCatching { a.x.value } }
            val t2 = daemon("materialize-b-y") { results += runCatching { b.y.value } }
            t1.join()
            t2.join()
        }
        assertEquals(setOf(Result.success(3), Result.success(2)), results.toSet())
    }

    @Test
    fun `a genuine two-thread cycle throws instead of hanging`() {
        val a = CycleA()
        val b = CycleB()
        a.other = b
        b.other = a
        val failures = ConcurrentLinkedQueue<Throwable>()

        completesWithin(20, "a two-thread initializer cycle") {
            val t1 = daemon("cycle-a-x") { runCatching { a.x.value }.exceptionOrNull()?.let { failures += it } }
            val t2 = daemon("cycle-b-y") { runCatching { b.y.value }.exceptionOrNull()?.let { failures += it } }
            t1.join()
            t2.join()
        }
        // The thread whose wait would close the cycle reports it; the other
        // then takes over the released latch, meets its own state again and
        // reports the cycle on its own thread. Either way, nobody hangs, and
        // no state was published.
        assertEquals(2, failures.size, "both reads fail: no evaluation order can finish a cycle")
        assertTrue(
            failures.all { it is IllegalStateException && "initializer cycle" in it.message.orEmpty() },
            "$failures",
        )
        assertTrue(
            failures.any { "across threads" in it.message.orEmpty() && "CycleA.x" in it.message.orEmpty() },
            "the cross-thread report names the states: $failures",
        )
        assertNull(a.getState("x"))
        assertNull(b.getState("y"))
        assertEquals(0, InitializerGraph.Process.waitingCount, "no thread is left registered as waiting")
    }

    @Test
    fun `each initializer runs once under racing first reads and snapshots`() {
        val threads = 12
        completesWithin(60, "racing first reads") {
            repeat(40) { round ->
                val store = SlowInitStore()
                val start = CyclicBarrier(threads)
                val seen = ConcurrentLinkedQueue<Any>()
                val workers =
                    List(threads) { i ->
                        daemon("race-$round-$i") {
                            start.await(10, TimeUnit.SECONDS)
                            seen += if (i % 2 == 0) store.slow else store.snapshot().rawValues.getValue("slow")
                        }
                    }
                workers.forEach { it.join() }
                assertEquals(1, store.runs.get(), "round $round: the initializer ran once")
                assertEquals(threads, seen.size)
                val states = seen.filterIsInstance<State<*>>()
                assertTrue(states.all { it === states.first() }, "round $round: every reader got the one state")
                assertTrue(seen.filterIsInstance<String>().all { it == "ready" })
            }
        }
        assertEquals(0, InitializerGraph.Process.waitingCount)
    }

    @Test
    fun `dispose while an initializer runs publishes nothing and fails its waiter`() {
        val store = GatedInitStore()
        // A private graph, so waiters of other tests cannot disturb the count polled below.
        val graph = InitializerGraph(::currentThreadId)
        store.initializerGraph = graph
        val owner = AtomicReference<Result<Any>>()
        val waiter = AtomicReference<Result<Any>>()

        completesWithin(20, "dispose during materialization") {
            val t1 = daemon("latch-owner") { owner.set(runCatching { store.slow }) }
            check(store.entered.await(10, TimeUnit.SECONDS)) { "the initializer never started" }
            val t2 = daemon("latch-waiter") { waiter.set(runCatching { store.slow }) }
            while (graph.waitingCount < 1) Thread.yield() // the waiter is parked on the latch
            store.dispose()
            store.proceed.countDown()
            t1.join()
            t2.join()
        }
        // The owner's initializer finished, but publishing onto a disposed store is refused.
        assertEquals("store disposed", owner.get().exceptionOrNull()?.message, "owner: ${owner.get()}")
        assertTrue(owner.get().exceptionOrNull() is IllegalStateException)
        // The waiter re-checks after the latch is released, rather than running the initializer itself.
        assertEquals("store disposed", waiter.get().exceptionOrNull()?.message, "waiter: ${waiter.get()}")
        assertTrue(waiter.get().exceptionOrNull() is IllegalStateException)
        assertEquals(1, store.runs.get(), "no initializer ran for the disposed store")
        assertTrue(store.registry.states.isEmpty(), "nothing was published")
        assertEquals(emptyList(), store.declarations())
        assertEquals(0, graph.waitingCount, "no thread is left registered as waiting")
    }

    @Test
    fun `a snapshot never captures a half-applied commit`() {
        val store = WideStore()
        val cells = store.cells
        val commits = 4_000
        val stop = AtomicBoolean(false)
        val torn = ConcurrentLinkedQueue<List<Any?>>()
        val readersRunning = CountDownLatch(3)
        // Snapshots that saw a commit other than the first or the last: proof
        // that the readers really ran while the writer was committing.
        val midRun = AtomicInteger()

        completesWithin(120, "snapshots racing wide commits") {
            val readers =
                List(3) { r ->
                    daemon("snapshot-reader-$r") {
                        while (!stop.get()) {
                            val snap = store.snapshot()
                            val values = snap.rawValues.values.toList()
                            readersRunning.countDown()
                            val distinctValues = values.toSet()
                            if (distinctValues.size != 1) {
                                torn += values
                            } else if ((distinctValues.single() as Int) in 1 until commits) {
                                midRun.incrementAndGet()
                            }
                        }
                    }
                }
            val writer =
                daemon("wide-writer") {
                    try {
                        check(readersRunning.await(10, TimeUnit.SECONDS)) { "the snapshot readers never started" }
                        for (i in 1..commits) {
                            store action { cells.forEach { it mutate i } }
                        }
                    } finally {
                        stop.set(true)
                    }
                }
            writer.join()
            readers.forEach { it.join() }
        }
        if (torn.isNotEmpty()) fail("${torn.size} snapshot(s) mixed two commits, e.g. ${torn.first()}")
        assertTrue(midRun.get() > 0, "no snapshot saw an intermediate commit: the readers never overlapped the writer")
        assertEquals(List(cells.size) { commits }, cells.map { it.value })
    }

    @Test
    fun `a commit that fails in its apply pass leaves no write bracket open`() {
        val store = ApplyFailureStore()
        store.a
        store.boom // both live, so the commit below writes two materialized states

        val failed =
            store action {
                a mutate 1
                boom mutate Boom(1) // applying it calls Boom(0).equals, which throws
            }
        assertIs<TransactionResult.Error>(failed)
        assertTrue("apply" in failed.exception.message.orEmpty(), "failed in the apply pass: ${failed.exception}")

        // An unbalanced bracket would make every later snapshot() spin forever.
        lateinit var snap: StoreSnapshot
        completesWithin(10, "snapshot after a failed apply pass") { snap = store.snapshot() }
        assertEquals(store.a.value, snap.rawValues["a"], "the snapshot holds what the failed commit left applied")
        completesWithin(10, "a second snapshot after a failed apply pass") { store.snapshot() }

        // The store still commits, and snapshots still see it.
        store action { a mutate 2 }
        completesWithin(10, "snapshot after a later commit") { snap = store.snapshot() }
        assertEquals(2, snap.rawValues["a"])
    }

    @Test
    fun `an inbound bridge write leaves no write bracket open`() {
        val store = InboundStore()
        val pushBridge = PushBridge()
        store { n bridge pushBridge }
        pushBridge.push(7)
        assertEquals(7, store.n.value)

        lateinit var snap: StoreSnapshot
        completesWithin(10, "snapshot after an inbound bridge write") { snap = store.snapshot() }
        assertEquals(mapOf("n" to 7), snap.rawValues, "the snapshot holds the inbound raw value")

        // observeFrom takes the same inbound path.
        val inbound = PushBridge()
        val sub = store { n observeFrom inbound }
        inbound.push(8)
        completesWithin(10, "snapshot after an observeFrom write") { snap = store.snapshot() }
        assertEquals(mapOf("n" to 8), snap.rawValues)
        sub.dispose()
    }
}
