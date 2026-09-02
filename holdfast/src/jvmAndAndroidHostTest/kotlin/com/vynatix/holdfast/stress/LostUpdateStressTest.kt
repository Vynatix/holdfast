package com.vynatix.holdfast.stress

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private class LuCounterStore : Store<LuCounterStore>() {
    val count by state { 0 }
}

/**
 * Lost-update behavior of the three write idioms, from strongest to weakest:
 *
 *  1. `action { count update { it + 1 } }` — the whole read-modify-write runs under the
 *     store's `transactionLock` (Store.kt `runBlockingActionUnderLock`), so concurrent
 *     increments are exact. Pinned under an 8-thread storm.
 *  2. Bare `count mutate value` outside any action — synthesizes a one-shot action
 *     (Store.kt `mutate`, the no-active-transaction branch), so each write commits whole:
 *     last-writer-wins is allowed, torn or fabricated values are not, and the default
 *     `distinct = false` fanout fires the observer exactly once per commit.
 *  3. Bare `count update { ... }` outside any action — `update` is
 *     `this mutate block(this.value)` (Store.kt), so the read happens BEFORE the one-shot
 *     transaction is synthesized: a classic unlocked read-modify-write that loses updates.
 *     Characterized deterministically below (ROADMAP 0.2.0 open defect).
 */
class LostUpdateStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not finish
     * within [seconds]. A lost-lock or deadlock regression in the paths under test would
     * otherwise burn the 10-minute test-task cap before reporting anything.
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
        worker.name = "lost-update-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — a writer is deadlocked or spinning")
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

    @Test
    fun `update inside an action is an exact read-modify-write under contention`() {
        val store = LuCounterStore()
        // First delegate read registers the state before any cross-thread race.
        assertEquals(0, store.count.value)

        val threadCount = 8
        val iterations = 2_000
        val errorCount = AtomicInteger(0)

        completesWithin(30, "in-action update storm") {
            runWorkers(threadCount, "lu-in-action") { _ ->
                for (i in 1..iterations) {
                    val result = store action { count update { it + 1 } }
                    if (result is TransactionResult.Error) errorCount.incrementAndGet()
                }
            }
        }

        assertEquals(0, errorCount.get(), "every in-action increment must commit")
        assertEquals(
            threadCount * iterations,
            store.count.value,
            "update inside an action must never lose increments — the whole RMW runs under transactionLock",
        )
    }

    @Test
    fun `mutate outside an action commits whole values and fires the observer once per commit`() {
        val store = LuCounterStore()
        assertEquals(0, store.count.value)

        val threadCount = 8
        val iterations = 1_000
        val stride = 1_000_000

        val fires = AtomicInteger(0)
        val tornObservations = AtomicInteger(0)
        val disposable =
            store.count effect {
                fires.incrementAndGet()
                // Every commit-fire value must be a whole value some thread staged:
                // writer index in range and iteration in range. 0 is only the
                // initial-subscription fire (counted in `fires` but skipped here).
                if (this != 0) {
                    val writer = this / stride
                    val iteration = this % stride
                    if (writer !in 0 until threadCount || iteration !in 1..iterations) {
                        tornObservations.incrementAndGet()
                    }
                }
            }

        try {
            completesWithin(30, "outside-action mutate storm") {
                runWorkers(threadCount, "lu-one-shot-mutate") { t ->
                    for (i in 1..iterations) {
                        store { count mutate (t * stride + i) }
                    }
                }
            }

            val finalValue = store.count.value
            val writer = finalValue / stride
            val iteration = finalValue % stride
            assertTrue(
                writer in 0 until threadCount && iteration in 1..iterations,
                "final value $finalValue was never written by any thread — a one-shot commit tore or fabricated a value",
            )
            assertEquals(0, tornObservations.get(), "an observer saw a value no thread ever staged")
            assertEquals(
                threadCount * iterations + 1,
                fires.get(),
                "distinct=false must fire the observer once at subscription plus exactly once per one-shot commit",
            )
        } finally {
            disposable.dispose()
        }
    }

    /**
     * OPEN DEFECT (ROADMAP 0.2.0): standalone `update { }` OUTSIDE any action is a
     * non-atomic read-modify-write. `State.update` is `this mutate block(this.value)`
     * (Store.kt): the read of `this.value` and the `block` invocation happen with no lock
     * held, and only the resulting write is wrapped in the synthesized one-shot
     * transaction. Its own KDoc claims "an implicit single-shot transaction wraps the
     * operation" — it wraps only the write.
     *
     * Deterministic repro: a [CyclicBarrier] INSIDE the update lambda synchronizes both
     * threads after each has read the base value (each thread reads `this.value` strictly
     * before arriving at the barrier, and no mutate can run until both have passed it),
     * so both compute from base 0 and both commit 1. The barrier cannot deadlock: the
     * lambda runs before `mutate` synthesizes the action, so neither thread holds the
     * store's transactionLock while parked at the barrier.
     *
     * Expected once fixed (read moved inside the synthesized action): final == 2.
     * Actual today: final == 1 — one increment is lost. Flip the final assertion to
     * `assertEquals(2, ...)` when the fix lands.
     */
    @Test
    fun `standalone update outside an action deterministically loses a concurrent increment`() {
        val store = LuCounterStore()
        assertEquals(0, store.count.value)

        val barrier = CyclicBarrier(2)
        completesWithin(30, "standalone-update barrier race") {
            runWorkers(2, "lu-standalone-update") { _ ->
                store {
                    count update { base ->
                        // Both threads have read `base` (the committed value, 0) by the
                        // time this trips; both then synthesize one-shot commits of base+1.
                        barrier.await(10, TimeUnit.SECONDS)
                        base + 1
                    }
                }
            }
        }

        // BUG: two increments ran to completion but only one survives, because both read
        // base 0 before either synthesized its one-shot transaction. Expected 2 once
        // standalone update reads inside the synthesized action.
        assertEquals(
            1,
            store.count.value,
            "standalone update's unlocked read-before-transaction should lose exactly one of the " +
                "two barrier-synchronized increments; a different value means the defect's shape changed",
        )
    }
}
