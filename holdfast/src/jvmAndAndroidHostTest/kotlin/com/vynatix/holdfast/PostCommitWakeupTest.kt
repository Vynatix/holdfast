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
 * 10 to 50 of its first 20,000 posts in development; with it, none. The stress
 * guards itself: it runs at least [MIN_ITERATIONS] rounds and keeps going until
 * at least [MIN_QUEUED] posts landed behind a live transaction — the path the
 * race lives on — so a timing change that stops posts from landing there fails
 * instead of passing vacuously. Under load fewer posts land there per round
 * (one full-check run saw 156 of 20,000), so a fixed round count made that
 * guard flaky; the guard fails only once [MAX_ITERATIONS] rounds could not
 * reach [MIN_QUEUED]. The two completion counts guard against a worker
 * stopping early.
 */
@OptIn(StoreInternalApi::class)
class PostCommitWakeupTest {
    @Test
    fun `a postCommit racing the owner's action exit is never stranded`() {
        val store = WakeupCounter()
        val ownerNanos = 2_000L
        val start = CyclicBarrier(2)
        val end = CyclicBarrier(2)
        // Set by the poster before the round's end barrier, which publishes it
        // to the owner: both workers stop after the same round.
        val stop = AtomicBoolean(false)
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
                        do {
                            start.await(10, TimeUnit.SECONDS)
                            store action { busyFor(ownerNanos) }
                            ownerActionsDone.incrementAndGet()
                            end.await(10, TimeUnit.SECONDS)
                        } while (!stop.get())
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            val poster =
                daemon("postcommit-poster") {
                    try {
                        val random = ThreadLocalRandom.current()
                        var i = 0
                        do {
                            val ran = AtomicBoolean(false)
                            start.await(10, TimeUnit.SECONDS)
                            // Wait for the owner to be inside its action (or already past
                            // it), then land uniformly across the rest of it and its exit.
                            while (store.activeTransaction == null && ownerActionsDone.get() <= i) Thread.onSpinWait()
                            busyFor(random.nextLong(2 * ownerNanos))
                            if (store.activeTransaction != null) queuedBehindTransaction.incrementAndGet()
                            store.postCommit { ran.set(true) }
                            i++
                            val covered = i >= MIN_ITERATIONS && queuedBehindTransaction.get() >= MIN_QUEUED
                            if (covered || i >= MAX_ITERATIONS) stop.set(true)
                            end.await(10, TimeUnit.SECONDS)
                            // Both the owner's action (and its drain) and this postCommit
                            // have returned: the task must have run by now.
                            if (!ran.get()) stranded.incrementAndGet()
                            postsDone.incrementAndGet()
                        } while (!stop.get())
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
        val rounds = postsDone.get()
        assertEquals(rounds, ownerActionsDone.get(), "the owner and the poster ran different numbers of rounds")
        assertTrue(rounds >= MIN_ITERATIONS, "the stress stopped after $rounds of at least $MIN_ITERATIONS rounds")
        assertEquals(0, stranded.get(), "postCommit tasks stranded in the queue after the owner exited")
        assertTrue(
            queuedBehindTransaction.get() >= MIN_QUEUED,
            "the stress never exercised the queued path: ${queuedBehindTransaction.get()} of $rounds posts " +
                "landed behind a live transaction, fewer than $MIN_QUEUED even after the $MAX_ITERATIONS-round cap",
        )
    }

    private companion object {
        /** Rounds every run makes, whatever the coverage. */
        const val MIN_ITERATIONS = 20_000

        /** Posts that must land behind a live transaction before the stress may stop. */
        const val MIN_QUEUED = MIN_ITERATIONS / 100

        /** The cap: about 8s unloaded, well inside the watchdog. */
        const val MAX_ITERATIONS = 10 * MIN_ITERATIONS
    }
}
