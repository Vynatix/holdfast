package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class WakeupCounter : Store<WakeupCounter>() {
    val count by state { 0 }
}

private fun busyFor(nanos: Long) {
    val end = System.nanoTime() + nanos
    while (System.nanoTime() < end) Thread.onSpinWait()
}

/**
 * `postCommit` decides between "queue it" and "run it now" by reading the
 * store's active-transaction slot. A caller on another thread could read a
 * transaction that was just ending, lose the CPU, and enqueue after the owner
 * had already cleared the slot and drained an empty queue — stranding the
 * task until some unrelated later transaction happened to drain. `postCommit`
 * now re-reads the slot after enqueueing and drains itself if it emptied.
 *
 * Probabilistic by nature: with the re-check removed, a run stranded roughly
 * 10 to 50 of its 20,000 posts in development; with it, none. The last
 * assertion guards the stress itself, so a timing change that stops posts
 * from landing behind a live transaction fails instead of passing vacuously;
 * the two completion counts guard against a worker stopping early.
 */
@OptIn(StoreInternalApi::class)
class PostCommitWakeupTest {
    @Test
    fun `a postCommit racing the owner's action exit is never stranded`() {
        val store = WakeupCounter()
        val iterations = 20_000
        val ownerNanos = 2_000L
        val start = CyclicBarrier(2)
        val end = CyclicBarrier(2)
        val stranded = AtomicInteger()
        val queuedBehindTransaction = AtomicInteger()
        val ownerActionsDone = AtomicInteger()
        val postsDone = AtomicInteger()
        // A worker that throws would otherwise die silently (daemon threads do
        // not report), cutting the stress short while the assertions below
        // still pass on the iterations that did run.
        val failures = ConcurrentLinkedQueue<Throwable>()

        completesWithin(120, "postCommit wake-up stress") {
            val owner =
                daemon("postcommit-owner") {
                    try {
                        repeat(iterations) {
                            start.await(10, TimeUnit.SECONDS)
                            store action { busyFor(ownerNanos) }
                            ownerActionsDone.incrementAndGet()
                            end.await(10, TimeUnit.SECONDS)
                        }
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            val poster =
                daemon("postcommit-poster") {
                    try {
                        val random = ThreadLocalRandom.current()
                        repeat(iterations) { i ->
                            val ran = AtomicBoolean(false)
                            start.await(10, TimeUnit.SECONDS)
                            // Wait for the owner to be inside its action (or already past
                            // it), then land uniformly across the rest of it and its exit.
                            while (store.activeTransaction == null && ownerActionsDone.get() <= i) Thread.onSpinWait()
                            busyFor(random.nextLong(2 * ownerNanos))
                            if (store.activeTransaction != null) queuedBehindTransaction.incrementAndGet()
                            store.postCommit { ran.set(true) }
                            end.await(10, TimeUnit.SECONDS)
                            // Both the owner's action (and its drain) and this postCommit
                            // have returned: the task must have run by now.
                            if (!ran.get()) stranded.incrementAndGet()
                            postsDone.incrementAndGet()
                        }
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            owner.join()
            poster.join()
        }

        // The first failure queued is the root cause: the other worker's barrier
        // wait only times out about ten seconds later.
        failures.firstOrNull()?.let { throw AssertionError("a stress worker failed: $it", it) }
        assertEquals(iterations, ownerActionsDone.get(), "the owner did not finish the stress")
        assertEquals(iterations, postsDone.get(), "the poster did not finish the stress")
        assertEquals(0, stranded.get(), "postCommit tasks stranded in the queue after the owner exited")
        assertTrue(
            queuedBehindTransaction.get() > iterations / 100,
            "the stress never exercised the queued path (${queuedBehindTransaction.get()} of $iterations)",
        )
    }
}
