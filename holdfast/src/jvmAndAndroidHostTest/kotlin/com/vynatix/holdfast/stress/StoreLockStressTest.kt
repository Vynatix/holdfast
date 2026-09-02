package com.vynatix.holdfast.stress

import com.vynatix.holdfast.StoreLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plain, unsynchronized mutable fields. The [StoreLock] under test is their
 * only guard, so a missed happens-before edge or a mutual-exclusion breach
 * surfaces as a lost update or a torn shadow pair instead of hiding behind any
 * other synchronization.
 */
private class SlGuardedState {
    var counter = 0
    var firstShadow = 0
    var secondShadow = 0
}

/** One reentrant dive: acquire down to [depth]; optionally throw from level [throwAt]. */
private class SlDivePlan(
    val depth: Int,
    val throwAt: Int?,
)

private class SlUnwindException : RuntimeException("deliberate unwind")

/**
 * Run [body] on a named daemon worker and fail — rather than hang — if it does
 * not finish within [seconds]. A wedged StoreLock parks its waiters forever
 * (silently, at zero CPU), so without the watchdog a regression would burn the
 * module's whole 10-minute test-task cap before reporting anything.
 */
private fun slCompletesWithin(
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
    worker.name = "sl-stress-probe"
    worker.start()
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        fail("$what did not complete within ${seconds}s — the lock is wedged or a waiter never woke")
    }
    thrown.get()?.let { throw it }
}

private fun slThread(
    name: String,
    body: () -> Unit,
): Thread {
    val thread = Thread { body() }
    thread.isDaemon = true
    thread.name = name
    return thread
}

/**
 * Recursively re-acquire [lock] down to [target], alternating `withLock` and
 * bare `acquire`/`release` at each level so exception unwind exercises both
 * idioms, then run [atBottom] at full depth. Throws [SlUnwindException] from
 * level [throwAt] when set.
 */
private fun slDive(
    lock: StoreLock,
    depth: Int,
    target: Int,
    throwAt: Int?,
    atBottom: () -> Unit,
) {
    if (depth % 2 == 0) {
        lock.withLock { slDiveBody(lock, depth, target, throwAt, atBottom) }
    } else {
        lock.acquire()
        try {
            slDiveBody(lock, depth, target, throwAt, atBottom)
        } finally {
            lock.release()
        }
    }
}

private fun slDiveBody(
    lock: StoreLock,
    depth: Int,
    target: Int,
    throwAt: Int?,
    atBottom: () -> Unit,
) {
    if (depth == throwAt) throw SlUnwindException()
    if (depth < target) {
        slDive(lock, depth + 1, target, throwAt, atBottom)
    } else {
        atBottom()
    }
}

/**
 * Direct-API stress tests for [StoreLock], the reentrant parking lock every
 * `action` holds across its whole body plus commit fanout. The lock is
 * exercised bare — no Store on top — against plain unsynchronized state, so
 * mutual exclusion, reentrancy accounting, the ownership guard, and the
 * park-not-spin contract are pinned without the store's own bookkeeping in
 * the way.
 */
class StoreLockStressTest {
    /**
     * The lock is the only synchronization over three plain fields; 8 threads
     * of increments must conserve exactly, and the shadow pair — written on
     * either side of the counter update — must never be observed torn by the
     * next acquirer.
     */
    @Test
    fun `mutual exclusion hammer keeps plain fields exact and untorn across 8 threads`() {
        slCompletesWithin(15, "mutual exclusion hammer") {
            val lock = StoreLock()
            val state = SlGuardedState()
            val threads = 8
            val iterations = 2000
            val start = CyclicBarrier(threads)
            val firstFailure = AtomicReference<Throwable?>(null)

            val workers =
                List(threads) { index ->
                    slThread("sl-hammer-$index") {
                        try {
                            start.await()
                            repeat(iterations) {
                                lock.withLock {
                                    val first = state.firstShadow
                                    val second = state.secondShadow
                                    check(first == second) {
                                        "torn pair on entry: firstShadow=$first secondShadow=$second"
                                    }
                                    state.firstShadow = first + 1
                                    state.counter++
                                    state.secondShadow = second + 1
                                }
                            }
                        } catch (e: Throwable) {
                            firstFailure.compareAndSet(null, e)
                        }
                    }
                }
            workers.forEach { it.start() }
            workers.forEach { it.join() }

            firstFailure.get()?.let { throw it }
            assertEquals(threads * iterations, state.counter, "no increment may be lost")
            assertEquals(threads * iterations, state.firstShadow)
            assertEquals(threads * iterations, state.secondShadow)
        }
    }

    /**
     * Reentrancy accounting under fire: one thread repeatedly dives to depths
     * up to 30 (seeded plan), throwing from a random level on roughly 30% of
     * dives, while four threads contend for the same lock. Every unwind —
     * normal or exceptional — must leave the diver holding nothing (its own
     * `release()` must throw) and the lock immediately usable by everyone else.
     */
    @Test
    fun `deep reentrant dives with seeded exception unwind always release fully under contention`() {
        slCompletesWithin(15, "reentrancy unwind hammer") {
            val lock = StoreLock()
            val state = SlGuardedState()
            val random = Random(42)
            val plans =
                List(200) {
                    val depth = random.nextInt(1, 31)
                    val throwAt = if (random.nextInt(10) < 3) random.nextInt(1, depth + 1) else null
                    SlDivePlan(depth, throwAt)
                }
            val expectedDiveSuccesses = plans.count { it.throwAt == null }
            val firstFailure = AtomicReference<Throwable?>(null)
            val diveSuccesses = AtomicInteger(0)

            val contenders = 4
            val contenderIterations = 2000
            val contenderThreads =
                List(contenders) { index ->
                    slThread("sl-contender-$index") {
                        try {
                            repeat(contenderIterations) {
                                lock.withLock { state.counter++ }
                            }
                        } catch (e: Throwable) {
                            firstFailure.compareAndSet(null, e)
                        }
                    }
                }
            val diver =
                slThread("sl-diver") {
                    try {
                        for (plan in plans) {
                            try {
                                slDive(lock, 1, plan.depth, plan.throwAt) { state.firstShadow++ }
                                diveSuccesses.incrementAndGet()
                            } catch (expected: SlUnwindException) {
                                // Planned unwind — every level's withLock/finally released.
                            }
                            var stillHeld = false
                            try {
                                lock.release()
                                stillHeld = true
                            } catch (expected: IllegalStateException) {
                                // Correct: the dive fully unwound, this thread holds nothing.
                            }
                            if (stillHeld) {
                                // Free the mutex so the contenders can finish, then report.
                                try {
                                    while (true) {
                                        lock.release()
                                    }
                                } catch (drained: IllegalStateException) {
                                    // Fully drained.
                                }
                                throw AssertionError(
                                    "lock still held after unwind (depth=${plan.depth}, throwAt=${plan.throwAt})",
                                )
                            }
                        }
                    } catch (e: Throwable) {
                        firstFailure.compareAndSet(null, e)
                    }
                }

            (contenderThreads + diver).forEach { it.start() }
            (contenderThreads + diver).forEach { it.join() }

            firstFailure.get()?.let { throw it }
            assertEquals(expectedDiveSuccesses, diveSuccesses.get(), "every non-throwing dive must reach bottom")
            assertEquals(expectedDiveSuccesses, state.firstShadow, "all bottom writes must be visible")
            assertEquals(contenders * contenderIterations, state.counter, "contender increments must be exact")
            // Freed for everyone: a fresh caller acquires without waiting.
            lock.withLock { state.secondShadow = 1 }
            assertEquals(1, state.secondShadow)
        }
    }

    /**
     * The release guard reads `locked` and `ownerThreadId` before mutating
     * anything: a foreign `release()` must throw and leave the owner's hold —
     * including its reentrancy count — fully intact.
     */
    @Test
    fun `release from a non-owner thread throws IllegalStateException and leaves the owner intact`() {
        slCompletesWithin(10, "wrong-thread release") {
            val lock = StoreLock()
            val state = SlGuardedState()
            val ownerHolds = CountDownLatch(1)
            val intruderDone = CountDownLatch(1)
            val ownerFailure = AtomicReference<Throwable?>(null)
            val intruderThrew = AtomicReference<Throwable?>(null)

            val owner =
                slThread("sl-owner") {
                    try {
                        lock.acquire()
                        ownerHolds.countDown()
                        check(intruderDone.await(8, TimeUnit.SECONDS)) { "intruder never finished" }
                        // Still the owner: reentrant acquire and the final release must work.
                        lock.withLock { state.counter++ }
                        lock.release()
                    } catch (e: Throwable) {
                        ownerFailure.compareAndSet(null, e)
                    }
                }
            owner.start()
            assertTrue(ownerHolds.await(5, TimeUnit.SECONDS), "owner should have acquired")

            val intruder =
                slThread("sl-intruder") {
                    try {
                        lock.release()
                    } catch (e: Throwable) {
                        intruderThrew.set(e)
                    } finally {
                        intruderDone.countDown()
                    }
                }
            intruder.start()
            intruder.join()
            owner.join()

            ownerFailure.get()?.let { throw it }
            val thrown = intruderThrew.get()
            assertTrue(
                thrown is IllegalStateException,
                "release from a non-owner thread must throw IllegalStateException, got $thrown",
            )
            assertEquals(1, state.counter, "owner must keep working after the failed foreign release")
            // The owner fully released on exit; a fresh caller can take the lock.
            lock.withLock { state.counter++ }
            assertEquals(2, state.counter)
        }
    }

    /**
     * Pins the 0.2.0 parking contract documented on [StoreLock]: a blocked
     * `acquire()` delegates to `SynchronousMutex`, which parks the thread
     * (WAITING/TIMED_WAITING — or BLOCKED for a monitor-based implementation).
     * The old spin-yield lock kept waiters RUNNABLE, burning a core per waiter
     * and hiding them from thread dumps and deadlock detectors; a
     * majority-RUNNABLE sample profile here means that pathology is back.
     */
    @Test
    fun `a blocked acquirer parks instead of spinning`() {
        slCompletesWithin(15, "park-not-spin audit") {
            val lock = StoreLock()
            val holderHolds = CountDownLatch(1)
            val releaseHolder = CountDownLatch(1)
            val waiterRunning = CountDownLatch(1)
            val waiterAcquired = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)

            val holder =
                slThread("sl-park-holder") {
                    try {
                        lock.acquire()
                        holderHolds.countDown()
                        check(releaseHolder.await(10, TimeUnit.SECONDS)) { "release signal never arrived" }
                        lock.release()
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }
            holder.start()
            assertTrue(holderHolds.await(5, TimeUnit.SECONDS), "holder should have acquired")

            val waiter =
                slThread("sl-park-waiter") {
                    try {
                        waiterRunning.countDown()
                        lock.withLock { }
                        waiterAcquired.countDown()
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }
            waiter.start()
            assertTrue(waiterRunning.await(5, TimeUnit.SECONDS), "waiter should have started")

            // Observation grace: let the waiter travel from its latch signal
            // into the mutex's park before sampling its thread state.
            Thread.sleep(500)
            val samples = mutableListOf<Thread.State>()
            repeat(10) {
                samples.add(waiter.state)
                Thread.sleep(100)
            }
            val stillWaiting = waiterAcquired.count

            releaseHolder.countDown()
            assertTrue(waiterAcquired.await(5, TimeUnit.SECONDS), "waiter must acquire after the release")
            holder.join()
            waiter.join()
            failure.get()?.let { throw it }

            assertEquals(1L, stillWaiting, "waiter must not have acquired while the holder held the lock")
            val parkedStates = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED)
            val parked = samples.count { it in parkedStates }
            val runnable = samples.count { it == Thread.State.RUNNABLE }
            assertTrue(
                parked >= 1,
                "waiter was never observed parked (samples=$samples) — acquire() is spinning again",
            )
            assertTrue(
                runnable <= samples.size / 2,
                "waiter was mostly RUNNABLE while blocked (samples=$samples) — acquire() is spinning again",
            )
        }
    }

    @Test
    fun `release without acquire and double release both throw without wedging the lock`() {
        slCompletesWithin(5, "release guard checks") {
            val fresh = StoreLock()
            assertFailsWith<IllegalStateException> { fresh.release() }

            val lock = StoreLock()
            lock.acquire()
            lock.release()
            assertFailsWith<IllegalStateException> { lock.release() }

            // Neither rejected release may have corrupted anything.
            var ran = false
            lock.withLock { ran = true }
            assertTrue(ran, "lock must still be acquirable after rejected releases")
        }
    }

    /**
     * Ownership churn: 200 short-lived threads (10 sequential batches of 20)
     * hand the lock across brand-new thread identities. JVM thread ids are
     * never reused, so a forged-ownership hit on the reentrant fast path or a
     * lost wakeup shows up as a lost increment or a wedge (watchdog).
     */
    @Test
    fun `ownership churn across 200 short-lived threads stays exact and never wedges`() {
        slCompletesWithin(25, "ownership churn") {
            val lock = StoreLock()
            val state = SlGuardedState()
            val firstFailure = AtomicReference<Throwable?>(null)
            val batches = 10
            val threadsPerBatch = 20
            val cyclesPerThread = 50

            repeat(batches) { batch ->
                val start = CyclicBarrier(threadsPerBatch)
                val batchThreads =
                    List(threadsPerBatch) { index ->
                        slThread("sl-churn-b$batch-t$index") {
                            try {
                                start.await()
                                repeat(cyclesPerThread) {
                                    lock.acquire()
                                    try {
                                        state.counter++
                                    } finally {
                                        lock.release()
                                    }
                                }
                            } catch (e: Throwable) {
                                firstFailure.compareAndSet(null, e)
                            }
                        }
                    }
                batchThreads.forEach { it.start() }
                batchThreads.forEach { it.join() }
            }

            firstFailure.get()?.let { throw it }
            assertEquals(batches * threadsPerBatch * cyclesPerThread, state.counter)
            // Post-churn liveness: this probe thread can still take the lock.
            lock.withLock { state.firstShadow = 1 }
            assertEquals(1, state.firstShadow)
        }
    }
}
