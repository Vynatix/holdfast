package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DrainDepthStore : Store<DrainDepthStore>() {
    val x by state { 0 }
}

/**
 * Issue #37. A task the post-commit drain runs that takes the store and
 * releases it (a derived state's recompute through `tryTopLevelAction`)
 * drains the store again on release. While another thread kept handing the
 * task back to this thread's hold — a reader calling `settleNow` in a loop
 * beside a writer whose every settle recomputes the same derived state, as in
 * TreeConsistentCutTest.aCaptureRacingAKeyedDisposeNeverThrows — that drain
 * ran in place, one stack level deeper per hand-back, until a
 * StackOverflowError: swallowed by the drain's `runCatching`, after it had
 * struck inside NoWriteRegion's slot write and left a stale compute frame that
 * refused the thread's next `action`. The nested drain now hands its work to
 * the outer one, so the stack stays flat; and a task that this thread itself
 * keeps queuing again is cut after the settle cap, so the flat loop still ends.
 */
@OptIn(StoreInternalApi::class)
class PostCommitDrainDepthTest {
    /**
     * Deterministic: another thread hands the task back on every hold, then
     * the holder takes and releases the store. Each run also queues, from
     * this thread, a downstream task (as a derived state's commit queues the
     * recompute of a derived state over it), and first hands itself back on
     * this thread and withdraws that copy (as a recompute's busy attempt and
     * its successful retry do). None of that is the drain feeding itself.
     */
    @Test
    fun aTaskAnotherThreadHandsBackOnEveryHoldDrainsWithoutNesting() =
        completesWithin(120, "a task another thread hands back on every hold") {
            val store = DrainDepthStore()
            val reported = CopyOnWriteArrayList<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            val handBacks = LinkedBlockingQueue<CountDownLatch>()
            var runs = 0
            var downstreamRuns = 0
            var deepest = 0
            val downstream: () -> Unit = {
                downstreamRuns++
                store.tryTopLevelAction("downstream") { x mutate x.value }
            }
            lateinit var task: () -> Unit
            val other =
                Thread {
                    while (true) {
                        val done = handBacks.take()
                        if (runs >= ROUNDS) return@Thread
                        store.handOffPostCommit(task)
                        done.countDown()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            task = {
                runs++
                if (runs % SAMPLE_EVERY == 0) deepest = maxOf(deepest, Throwable().stackTrace.size)
                if (runs < ROUNDS) {
                    // A busy attempt's own hand-off, withdrawn by its successful retry.
                    store.handOffPostCommit(task)
                    store.withdrawPostCommit(task)
                    // Another thread finds the store held and hands the task to its holder...
                    CountDownLatch(1).also { handBacks.put(it) }.await()
                    store.handOffPostCommit(downstream)
                    // ...and the holder takes the store and releases it, which drains.
                    store.tryTopLevelAction("hold") { x mutate runs }
                }
            }
            store.postCommit(task)
            handBacks.put(CountDownLatch(1))
            other.join(10_000)
            assertEquals(ROUNDS, runs, "every hand-back ran: the drain neither overflowed nor stranded the task")
            assertTrue(deepest in 1 until MAX_FRAMES, "the drain stayed flat: $deepest frames deep")
            assertEquals(ROUNDS - 1, downstreamRuns, "the downstream task ran once per round")
            assertEquals(emptyList(), reported.toList(), "a loop another thread feeds is never cut")
            assertNull(NoWriteRegion.current())
            // The thread can still write: nothing was left behind on it.
            assertTrue(store.action { x mutate 0 } is TransactionResult.Success)
        }

    /**
     * The same loop fed by this thread itself — the task queues itself again on
     * every run, as a legacy `derived` whose observer writes one of its sources
     * on another store does — would keep the flat drain going forever: the
     * drain cuts it after the settle cap, leaves it queued for the store's
     * next holder, and reports the cut once.
     */
    @Test
    fun aTaskThisThreadKeepsQueuingAgainIsCutAndReported() =
        completesWithin(60, "a task that keeps queuing itself again") {
            val store = DrainDepthStore()
            val reported = CopyOnWriteArrayList<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            var runs = 0
            var deepest = 0
            var stop = false
            lateinit var task: () -> Unit
            task = {
                runs++
                deepest = maxOf(deepest, Throwable().stackTrace.size)
                if (!stop) {
                    store.handOffPostCommit(task)
                    store.tryTopLevelAction("hold") { x mutate runs }
                }
            }
            store.postCommit(task)
            assertTrue(runs in MAX_SETTLE_RUNS..MAX_SETTLE_RUNS + 3, "the drain cut the loop: $runs runs")
            assertTrue(deepest < MAX_FRAMES, "the drain stayed flat: $deepest frames deep")
            assertEquals(1, reported.size, "the cut is reported once: $reported")
            val cut = assertIs<IllegalStateException>(reported.single())
            assertTrue(cut.message.orEmpty().startsWith("a post-commit task of DrainDepthStore"), cut.message)
            assertTrue(cut.message.orEmpty().contains("$MAX_SETTLE_RUNS times in a row"), cut.message)
            assertNull(NoWriteRegion.current())

            // Left queued for the store's next holder, which runs it on release.
            stop = true
            val before = runs
            assertTrue(store.action { x mutate 0 } is TransactionResult.Success)
            assertEquals(before + 1, runs, "the store's next holder ran the task it was left")
            assertEquals(1, reported.size)
        }

    private companion object {
        const val ROUNDS = 20_000
        const val SAMPLE_EVERY = 1_000
        const val MAX_FRAMES = 200
    }
}
