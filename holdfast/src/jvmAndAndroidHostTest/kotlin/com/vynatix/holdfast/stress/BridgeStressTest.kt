package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private class BrTripleStore : Store<BrTripleStore>() {
    val a by state { 0 }
    val b by state { 0 }
    val c by state { 0 }
}

private class BrCounterStore : Store<BrCounterStore>() {
    val count by state { 0 }
}

/**
 * Bridge that records every outbound publish. Its `observe` registers nothing and
 * never replays — deliberately, so attaching it can never run user code while the
 * state's `bridgeLock` is held (see the class KDoc on [BridgeStressTest]).
 */
private class BrRecordingBridge<T : Any> : Bridge<T> {
    val published = ConcurrentLinkedQueue<T>()
    val lastPublished = AtomicReference<T?>(null)

    override fun publish(value: T): Boolean {
        published.add(value)
        lastPublished.set(value)
        return true
    }

    override fun observe(observer: (T) -> Unit): Disposable = Disposable { }
}

/** Bridge whose `publish` always fails, as a full disk or encoder error would. */
private class BrThrowingBridge<T : Any> : Bridge<T> {
    override fun publish(value: T): Boolean = throw IllegalStateException("publish rejected")

    override fun observe(observer: (T) -> Unit): Disposable = Disposable { }
}

/**
 * Bridge that records publishes and captures the inbound callback handed to
 * `observe` on attach, so a test can drive the `applyFromBridge` path directly
 * from arbitrary threads via [drive].
 */
private class BrDrivableBridge : Bridge<Int> {
    val published = ConcurrentLinkedQueue<Int>()
    private val inbound = AtomicReference<((Int) -> Unit)?>(null)

    override fun publish(value: Int): Boolean {
        published.add(value)
        return true
    }

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound.set(observer)
        return Disposable { inbound.compareAndSet(observer, null) }
    }

    /** Push [value] through the captured inbound callback — the `applyFromBridge` path. */
    fun drive(value: Int) {
        checkNotNull(inbound.get()) { "bridge is not attached" }.invoke(value)
    }
}

/**
 * Bridge outbound/inbound contracts under thread storms (in-memory bridges only):
 *
 *  1. Phased commit vs a throwing publish (ROADMAP 0.2.0 "done" claim): state
 *     application is all-or-nothing (Transaction.kt `commitDispatching` pass 1 is
 *     assignment-only), a throwing `Bridge.publish` is swallowed into
 *     [Store.uncaughtObserverHandler] (MutableState.kt `publishToBridge`) and must
 *     neither fail the action nor drop sibling states' publishes.
 *  2. Inbound `applyFromBridge` bypasses transactions entirely and races commits on
 *     `currentValue` with only `stateLock` — last whole write wins, never a torn value.
 *  3. Loop prevention: `applyFromBridge` never re-publishes to the bridge.
 *  4. Attach/detach churn: the `MutableState.bridge` setter swaps under `bridgeLock`
 *     while commit fanout publishes under `transactionLock` then `bridgeLock`.
 *
 * Deliberately NOT exercised here: attaching a bridge that synchronously REPLAYS a
 * value while any observer performs a blocking write — the bridge setter holds
 * `bridgeLock` across the replay (MutableState.kt), commit fanout holds
 * `transactionLock` and then wants `bridgeLock`, and the replaying attacher would
 * want `transactionLock`: a real AB-BA deadlock, left to the dedicated detectors
 * file. Every bridge in this file therefore has a no-op, non-replaying `observe`.
 * Notification ORDER after an inbound/commit race is also not asserted: both
 * fanout paths notify observers outside `stateLock`, so the last notification may
 * lag the final value by design (a documented desync hazard, not deterministic).
 */
class BridgeStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not finish
     * within [seconds]. A bridge-path deadlock regression parks threads silently and
     * would otherwise burn the 10-minute test-task cap before reporting anything.
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
        worker.name = "bridge-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — a commit, publish, or attach is deadlocked")
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
     * Three states committed together while the middle state's bridge throws on every
     * publish. Single-threaded first (the exact 0.2.0 claim), then a 4-thread storm.
     *
     * Every action writes the SAME tag to a, b and c, so the observer on c can detect a
     * torn commit by comparing sibling reads at notification time: fanout runs entirely
     * under the store's `transactionLock` and pass 1 applied every write before any
     * observer fired, so a, b and c must already agree — deterministically, because no
     * other commit and no inbound source can move them mid-fanout.
     */
    @Test
    fun `a throwing bridge publish never tears a phased commit under a storm`() {
        val store = BrTripleStore()
        // First delegate reads register the states before any cross-thread race.
        assertEquals(0, store.a.value)
        assertEquals(0, store.b.value)
        assertEquals(0, store.c.value)

        val handlerFailures = AtomicInteger(0)
        store.uncaughtObserverHandler = { handlerFailures.incrementAndGet() }
        val aBridge = BrRecordingBridge<Int>()
        val cBridge = BrRecordingBridge<Int>()
        store {
            a bridge aBridge
            b bridge BrThrowingBridge()
            c bridge cBridge
        }

        val tearCount = AtomicInteger(0)
        val tearProbe =
            store.c effect {
                if (store.a.value != this || store.b.value != this) tearCount.incrementAndGet()
            }

        try {
            // Phase 1: the single-action contract.
            val single =
                store action {
                    a mutate 42
                    b mutate 42
                    c mutate 42
                }
            assertIs<TransactionResult.Success<*>>(single, "a failed publish must not fail the commit")
            assertEquals(
                listOf(42, 42, 42),
                listOf(store.a.value, store.b.value, store.c.value),
                "every state in the transaction must be applied — a mid-fanout throw must not tear it",
            )
            assertEquals(1, handlerFailures.get(), "the publish failure must be reported, not swallowed")
            assertEquals(1, aBridge.published.size, "the sibling bridge before the throwing one must still publish")
            assertEquals(1, cBridge.published.size, "the sibling bridge after the throwing one must still publish")
            assertEquals(42, aBridge.lastPublished.get())
            assertEquals(42, cBridge.lastPublished.get())

            // Phase 2: the same contract under contention.
            val threadCount = 4
            val iterations = 500
            val stride = 1_000_000
            val errors = AtomicInteger(0)
            completesWithin(30, "throwing-bridge commit storm") {
                runWorkers(threadCount, "br-throwing-publish") { t ->
                    for (i in 1..iterations) {
                        val result =
                            store action {
                                val tag = t * stride + i
                                a mutate tag
                                b mutate tag
                                c mutate tag
                            }
                        if (result is TransactionResult.Error) errors.incrementAndGet()
                    }
                }
            }

            val commits = 1 + threadCount * iterations
            assertEquals(0, errors.get(), "every action must commit despite b's bridge throwing on each publish")
            assertNull(store.activeTransaction, "no action may leak an active transaction")
            assertEquals(0, tearCount.get(), "an observer saw a, b, c diverge inside one commit's fanout")

            val finalA = store.a.value
            assertEquals(finalA, store.b.value, "a and b must land the same last committed tag — no tear")
            assertEquals(finalA, store.c.value, "a and c must land the same last committed tag — no tear")
            assertTrue(
                finalA / stride in 0 until threadCount && finalA % stride in 1..iterations,
                "final value $finalA was never staged by any storm action",
            )

            assertEquals(commits, handlerFailures.get(), "the throwing publish must be reported exactly once per commit")
            assertEquals(commits, aBridge.published.size, "bridge a must see exactly one publish per commit")
            assertEquals(commits, cBridge.published.size, "bridge c must see exactly one publish per commit")
            val expectedPublishes =
                buildSet {
                    add(42)
                    for (t in 0 until threadCount) {
                        for (i in 1..iterations) {
                            add(t * stride + i)
                        }
                    }
                }
            assertEquals(expectedPublishes, aBridge.published.toSet(), "bridge a must publish every commit's tag exactly")
            assertEquals(expectedPublishes, cBridge.published.toSet(), "bridge c must publish every commit's tag exactly")
            assertEquals(
                finalA,
                aBridge.lastPublished.get(),
                "publishes run under transactionLock in commit order — the last publish is the final value",
            )
            assertEquals(finalA, cBridge.lastPublished.get())
        } finally {
            tearProbe.dispose()
        }
    }

    /**
     * Two threads drive the bridge's captured inbound callback (the `applyFromBridge`
     * path, which bypasses transactions and middleware) while two writer threads commit
     * actions. Last whole write wins: the final value must be a value some party actually
     * wrote, the store must stay functional, and — because publish values come from the
     * committing transaction's own pending writes — the bridge must only ever have been
     * handed committed action values, never inbound-driven ones.
     *
     * Delivery/notification ordering is deliberately NOT asserted (unserialized between
     * the two paths by design — both notify outside `stateLock`).
     */
    @Test
    fun `inbound applyFromBridge storm racing an action storm stays whole and functional`() {
        val store = BrCounterStore()
        assertEquals(0, store.count.value)
        val inboundBridge = BrDrivableBridge()
        store { count bridge inboundBridge }

        val writerThreads = 2
        val driverThreads = 2
        val iterations = 1_000
        val stride = 1_000_000
        val errors = AtomicInteger(0)

        completesWithin(30, "inbound-vs-action storm") {
            runWorkers(writerThreads + driverThreads, "br-inbound-race") { t ->
                if (t < writerThreads) {
                    for (i in 1..iterations) {
                        val result = store action { count mutate (t * stride + i) }
                        if (result is TransactionResult.Error) errors.incrementAndGet()
                    }
                } else {
                    for (i in 1..iterations) {
                        inboundBridge.drive(t * stride + i)
                    }
                }
            }
        }

        assertEquals(0, errors.get(), "no action may fail while inbound applies race the commits")
        assertNull(store.activeTransaction, "no action may leak an active transaction")

        val finalValue = store.count.value
        assertTrue(
            finalValue / stride in 0 until (writerThreads + driverThreads) && finalValue % stride in 1..iterations,
            "final value $finalValue was never written by any writer or inbound driver — a torn or fabricated value",
        )

        // Publishes carry the committing transaction's own staged values, so inbound
        // drives must never appear — and never add publishes (loop prevention).
        assertEquals(
            writerThreads * iterations,
            inboundBridge.published.size,
            "exactly one publish per action commit; inbound applies must add none",
        )
        for (v in inboundBridge.published) {
            assertTrue(
                v / stride in 0 until writerThreads && v % stride in 1..iterations,
                "publish carried $v, an inbound-driven value — applyFromBridge leaked into outbound publish",
            )
        }

        // The store must still be fully functional after the storm.
        val sentinel = store action { count mutate -1 }
        assertIs<TransactionResult.Success<*>>(sentinel, "a fresh action must still commit after the storm")
        assertEquals(-1, store.count.value, "the post-storm sentinel commit must be visible")
        assertEquals(writerThreads * iterations + 1, inboundBridge.published.size, "the sentinel must publish once")
    }

    /**
     * Loop-prevention pin: commit-driven publishes are counted, then a 4-thread inbound
     * storm drives the captured callback — the publish count must not move at all, and a
     * subsequent commit must publish exactly once more.
     */
    @Test
    fun `inbound applies never republish to the bridge`() {
        val store = BrCounterStore()
        assertEquals(0, store.count.value)
        val inboundBridge = BrDrivableBridge()
        store { count bridge inboundBridge }

        val commits = 100
        for (i in 1..commits) {
            val result = store action { count mutate i }
            assertIs<TransactionResult.Success<*>>(result)
        }
        assertEquals(commits, inboundBridge.published.size, "every commit must publish exactly once")

        val driverThreads = 4
        val drivesPerThread = 500
        completesWithin(30, "inbound drive storm") {
            runWorkers(driverThreads, "br-inbound-drive") { t ->
                for (i in 1..drivesPerThread) {
                    inboundBridge.drive(-(t * drivesPerThread + i))
                }
            }
        }

        assertEquals(
            commits,
            inboundBridge.published.size,
            "applyFromBridge must never publish back to the bridge (loop prevention)",
        )
        assertTrue(
            store.count.value in -(driverThreads * drivesPerThread)..-1,
            "final value ${store.count.value} must be one of the inbound-driven values",
        )

        // Commit-driven publishing still works after the inbound storm.
        val after = store action { count mutate 777 }
        assertIs<TransactionResult.Success<*>>(after)
        assertEquals(777, store.count.value)
        assertEquals(commits + 1, inboundBridge.published.size, "a fresh commit must publish exactly once more")
        assertEquals(777, inboundBridge.published.last())
    }

    /**
     * One thread cycles the state's bridge between two recording bridges and null while
     * three writer threads commit. The setter swaps under `bridgeLock`; `publishToBridge`
     * reads the current bridge under the same lock while holding `transactionLock` —
     * churn must produce no failure, no torn publish value, and once churn quiesces a
     * freshly attached bridge must receive every subsequent commit exactly once while the
     * retired bridges receive nothing further.
     */
    @Test
    fun `bridge attach-detach churn racing writers stays consistent`() {
        val store = BrCounterStore()
        assertEquals(0, store.count.value)
        val bridgeA = BrRecordingBridge<Int>()
        val bridgeB = BrRecordingBridge<Int>()

        val writerThreads = 3
        val iterations = 1_000
        val stride = 1_000_000
        val churnCycles = 2_000
        val errors = AtomicInteger(0)

        completesWithin(60, "attach/detach churn storm") {
            runWorkers(writerThreads + 1, "br-bridge-churn") { t ->
                if (t < writerThreads) {
                    for (i in 1..iterations) {
                        val result = store action { count mutate (t * stride + i) }
                        if (result is TransactionResult.Error) errors.incrementAndGet()
                    }
                } else {
                    for (i in 1..churnCycles) {
                        val next =
                            when (i % 3) {
                                0 -> bridgeA
                                1 -> bridgeB
                                else -> null
                            }
                        store { count bridge next }
                    }
                }
            }
        }

        assertEquals(0, errors.get(), "no action may fail during bridge churn")
        assertNull(store.activeTransaction, "no action may leak an active transaction")
        val finalValue = store.count.value
        assertTrue(
            finalValue / stride in 0 until writerThreads && finalValue % stride in 1..iterations,
            "final value $finalValue was never staged by any writer",
        )
        for (v in bridgeA.published + bridgeB.published) {
            assertTrue(
                v / stride in 0 until writerThreads && v % stride in 1..iterations,
                "a churned bridge received $v, a value no writer ever staged — a torn publish",
            )
        }

        // Post-quiesce: a fresh attach routes every publish to the new bridge only.
        val aRetiredCount = bridgeA.published.size
        val bRetiredCount = bridgeB.published.size
        val finalBridge = BrRecordingBridge<Int>()
        store { count bridge finalBridge }
        val first = store action { count mutate 424_242 }
        assertIs<TransactionResult.Success<*>>(first)
        val second = store action { count mutate 424_243 }
        assertIs<TransactionResult.Success<*>>(second)

        assertEquals(424_243, store.count.value, "post-churn commits must be visible")
        assertEquals(
            listOf(424_242, 424_243),
            finalBridge.published.toList(),
            "the freshly attached bridge must receive each post-churn commit exactly once, in order",
        )
        assertEquals(aRetiredCount, bridgeA.published.size, "a replaced bridge must receive no further publishes")
        assertEquals(bRetiredCount, bridgeB.published.size, "a replaced bridge must receive no further publishes")
    }
}
