@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class LoopSource : Store<LoopSource>() {
    val n by state { 0 }
}

private class LoopHost : Store<LoopHost>() {
    val y by state { 0 }
}

private class TwoLoopSource : Store<TwoLoopSource>() {
    val n by state { 0 }
    val m by state { 0 }
}

private class PingStore : Store<PingStore>() {
    val s by state { 0 }
}

/**
 * A settle tolerates re-entrancy and ends (issue #20, R9): a recompute's own
 * commit joins the settling scope and queues what it changes into the same
 * settle, and a derived state whose observer keeps writing one of its sources
 * — a feedback loop — is handed to its host after a bounded number of runs
 * instead of keeping the settle going forever, and so is a frame's deferred
 * store drain that keeps coming back; either cut is reported through the
 * store's `uncaughtObserverHandler`. Watchdogged: a regression hangs.
 */
class SettleReentrancyTest {
    @Test fun aConvergingFeedbackLoopSettlesWithinTheSameEntry() {
        val source = LoopSource()
        val host = LoopHost()
        val echo = host.derivedState(source.n) { source.n.value }
        val loop = echo effect { if (this in 1..49) source.action { n update { it + 1 } }.getOrThrow() }
        try {
            completesWithin(30, "a feedback loop through a derived state's observer that converges") {
                source.action { n mutate 1 }.getOrThrow()
                assertNull(SettleScopes.current(), "no scope outlives its entry")
            }
            assertEquals(50, source.n.value)
            assertEquals(50, echo.value, "every step settled before the outer action returned")
        } finally {
            loop.dispose()
            echo.dispose()
        }
    }

    /**
     * The cut leaves the next recompute in the idle host's queue, where it
     * waits for the host's next holder: the derived state lags its source by
     * one step, and the cut is reported once — never silently.
     */
    @Test fun aFeedbackLoopThatNeverConvergesEndsTheSettle() {
        val source = LoopSource()
        val host = LoopHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        source.uncaughtObserverHandler = { reported += it }
        val echo = host.derivedState(source.n) { source.n.value }
        val writes = AtomicInteger()
        val loop =
            echo effect {
                if (this > 0) {
                    writes.incrementAndGet()
                    source.action { n update { it + 1 } }.getOrThrow()
                }
            }
        try {
            completesWithin(60, "a feedback loop through a derived state's observer that never converges") {
                source.action { n mutate 1 }.getOrThrow()
            }
            assertTrue(writes.get() in 1..2_000, "the settle stopped re-running the loop: ${writes.get()} writes")
            assertEquals(source.n.value, writes.get() + 1)
            assertEquals(source.n.value - 1, echo.value, "the recompute left in the idle host's queue has not run")
            assertEquals(1, reported.size, "the cut is reported once: $reported")
            val cut = assertIs<IllegalStateException>(reported.single())
            assertTrue(cut.message.orEmpty().startsWith("derived state LoopHost."), cut.message)
            assertTrue(cut.message.orEmpty().contains("recomputed 1000 times in one settle"), cut.message)
        } finally {
            loop.dispose()
            echo.dispose()
        }
    }

    /**
     * Two legacy `derived` states whose observers write each other's source
     * through frames: each frame defers the drain of the store whose root it
     * opened to the settle (a legacy recompute waits in that store's queue),
     * and each drain opens the next frame. No settle task is involved, so the
     * cap on a store's drains is what ends the settle: the queue left over is
     * its store's next holder's.
     */
    @Test fun aFeedbackLoopThroughFramesDeferredDrainsEndsTheSettle() {
        val c = PingStore()
        val h = PingStore()
        val reported = CopyOnWriteArrayList<Throwable>()
        c.uncaughtObserverHandler = { reported += it }
        h.uncaughtObserverHandler = { reported += it }
        val (lc, cSub) = c.derived(c.s) { s.value }
        val (lh, hSub) = h.derived(h.s) { s.value }
        val writes = AtomicInteger()
        val pings =
            listOf(
                lc effect {
                    val seen = this
                    if (seen > 0) {
                        writes.incrementAndGet()
                        atomic(h) { h { s mutate seen + 1 } }.getOrThrow()
                    }
                },
                lh effect {
                    val seen = this
                    if (seen > 0) {
                        writes.incrementAndGet()
                        atomic(c) { c { s mutate seen + 1 } }.getOrThrow()
                    }
                },
            )
        try {
            completesWithin(60, "a feedback loop through frames' deferred store drains") {
                c.action { s mutate 1 }.getOrThrow()
                assertNull(SettleScopes.current(), "no scope outlives its entry")
            }
            assertTrue(writes.get() in 2..4_000, "the settle stopped re-running the drains: ${writes.get()} writes")
            assertEquals(1, reported.size, "the cut is reported once: $reported")
            val cut = assertIs<IllegalStateException>(reported.single())
            assertTrue(cut.message.orEmpty().startsWith("a frame's post-commit drain of PingStore"), cut.message)
        } finally {
            pings.forEach { it.dispose() }
            cSub.dispose()
            hSub.dispose()
        }
    }

    /**
     * Issue #37: a legacy `derived` whose observer writes its source on
     * another store with a plain action re-queues its recompute on the host's
     * queue from inside every run, and its release drains that queue again —
     * nested in the drain running it. No settle sees this loop. The nested
     * drain hands its work to the outer one (so the stack stays flat), and the
     * outer drain cuts the task once it has run the settle cap's number of
     * times, leaving it queued for the host's next holder and reporting once.
     */
    @Test fun aLegacyDerivedFeedbackLoopThroughAnotherStoreEndsTheDrain() {
        val source = LoopSource()
        val host = LoopHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        source.uncaughtObserverHandler = { reported += it }
        val (d, sub) = host.derived(source.n) { source.n.value }
        val writes = AtomicInteger()
        val loop =
            d effect {
                if (this > 0) {
                    writes.incrementAndGet()
                    source.action { n update { it + 1 } }.getOrThrow()
                }
            }
        try {
            completesWithin(60, "a legacy derived feedback loop through another store's action") {
                host.action { source.action { n mutate 1 }.getOrThrow() }.getOrThrow()
            }
            assertTrue(writes.get() in 1..2_000, "the drain stopped re-running the loop: ${writes.get()} writes")
            assertEquals(1, reported.size, "the cut is reported once: $reported")
            val cut = assertIs<IllegalStateException>(reported.single())
            assertTrue(cut.message.orEmpty().startsWith("a post-commit task of LoopHost"), cut.message)
            assertTrue(cut.message.orEmpty().contains("1000 times in a row"), cut.message)
        } finally {
            loop.dispose()
            sub.dispose()
        }
    }

    /**
     * Issue #37: the recompute a settle's cut left in an idle host's queue is
     * run by the next drain of that queue — here one with no settle scope
     * open, as `Store.postCommit`'s lost-wakeup drain is. Its own commit opens
     * a settle that runs the loop up to the cap and leaves the recompute in
     * the queue again, after the drain running it last took anything: the
     * drain ends instead of running it once more, and the next holder gets it.
     */
    @Test fun aDrainWithNoSettleOpenRunsACutRecomputeOnceAndEnds() {
        val source = LoopSource()
        val host = LoopHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        source.uncaughtObserverHandler = { reported += it }
        val echo = host.derivedState(source.n) { source.n.value }
        val writes = AtomicInteger()
        val loop =
            echo effect {
                if (this > 0) {
                    writes.incrementAndGet()
                    source.action { n update { it + 1 } }.getOrThrow()
                }
            }
        try {
            source.action { n mutate 1 }.getOrThrow()
            assertEquals(1, reported.size, "the settle cut the loop: $reported")
            val afterSettle = writes.get()
            completesWithin(60, "a drain with no settle scope open, of a queue holding a cut recompute") {
                assertNull(SettleScopes.current())
                host.internalDrainPostCommitTasks()
            }
            assertTrue(
                writes.get() - afterSettle in 1..2_000,
                "the drain ran the loop once more, up to the settle cap: ${writes.get() - afterSettle} writes",
            )
            assertEquals(2, reported.size, "each settle reports its own cut: $reported")
            reported.forEach { assertTrue(it.message.orEmpty().contains("recomputed 1000 times in one settle"), it.message) }
        } finally {
            loop.dispose()
            echo.dispose()
        }
    }

    /**
     * Issue #37: two settle-cut recomputes left in an idle host's queue, run
     * by a drain with no settle scope open. Each one's run settles its loop up
     * to the cap and hands it back for the host's next holder; the drain that
     * ran the settle leaves it there instead of starting the loop again, even
     * when the other one's run takes and releases the host.
     */
    @Test fun aDrainWithNoSettleOpenRunsTwoCutRecomputesOnceEach() {
        val source = TwoLoopSource()
        val host = LoopHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        source.uncaughtObserverHandler = { reported += it }
        val first = host.derivedState(source.n) { source.n.value }
        val second = host.derivedState(source.m) { source.m.value }
        val writes = AtomicInteger()
        val loops =
            listOf(
                first effect {
                    if (this > 0) {
                        writes.incrementAndGet()
                        source.action { n update { it + 1 } }.getOrThrow()
                    }
                },
                second effect {
                    if (this > 0) {
                        writes.incrementAndGet()
                        source.action { m update { it + 1 } }.getOrThrow()
                    }
                },
            )
        try {
            // Each entry's settle cuts the loop it started (the second entry's
            // drain of the host runs the first loop's cut recompute once more).
            source.action { n mutate 1 }.getOrThrow()
            source.action { m mutate 1 }.getOrThrow()
            val afterSettles = writes.get()
            val reportsAfterSettles = reported.size
            assertTrue(reportsAfterSettles >= 2, "each settle cut its loop: $reported")
            completesWithin(60, "a drain with no settle scope open, of a queue holding two cut recomputes") {
                host.internalDrainPostCommitTasks()
            }
            assertTrue(
                writes.get() - afterSettles in 1..4_000,
                "the drain ran each loop at most once more, up to the settle cap: ${writes.get() - afterSettles} writes",
            )
            assertTrue(
                reported.size - reportsAfterSettles in 1..2,
                "each of the drain's settles reports its own cut, once: ${reported.size - reportsAfterSettles} reports",
            )
            reported.forEach { assertTrue(it.message.orEmpty().contains("recomputed 1000 times in one settle"), it.message) }
        } finally {
            loops.forEach { it.dispose() }
            first.dispose()
            second.dispose()
        }
    }

    /**
     * Issue #37: the same, with a legacy `derived` on the host over the
     * looping derived state — its recompute takes and releases the host after
     * the settle's cut, which hands the drain everything queued then. The cut
     * recompute among it stays queued for the host's next holder.
     */
    @Test fun aDrainWithNoSettleOpenLeavesACutRecomputeQueuedPastAnotherHold() {
        val source = LoopSource()
        val host = LoopHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        source.uncaughtObserverHandler = { reported += it }
        val echo = host.derivedState(source.n) { source.n.value }
        val (doubled, doubledSub) = host.derived(echo) { echo.value * 2 }
        val writes = AtomicInteger()
        val loop =
            echo effect {
                if (this > 0) {
                    writes.incrementAndGet()
                    source.action { n update { it + 1 } }.getOrThrow()
                }
            }
        try {
            source.action { n mutate 1 }.getOrThrow()
            val afterSettle = writes.get()
            val reportsAfterSettle = reported.size
            completesWithin(60, "a drain with no settle scope open, past another hold of the host") {
                host.internalDrainPostCommitTasks()
            }
            assertTrue(
                writes.get() - afterSettle in 1..2_000,
                "the drain ran the loop once more, up to the settle cap: ${writes.get() - afterSettle} writes",
            )
            assertEquals(reportsAfterSettle + 1, reported.size, "one more settle, one more cut: $reported")
            assertTrue(doubled.value >= 2, "the legacy derived over the loop kept up: ${doubled.value}")
        } finally {
            loop.dispose()
            doubledSub.dispose()
            echo.dispose()
        }
    }

    /**
     * Issue #37: a handler that records a drain's cut by writing the host
     * takes the host from inside the report. The drain reports while it is
     * still draining, so that write's drain hands it its work and the cut
     * recompute stays where the cut left it: the handler does not restart the
     * loop it is told about.
     */
    @Test fun aHandlerThatWritesTheHostDoesNotRestartTheCutLoop() {
        val source = LoopSource()
        val host = LoopHost()
        val reported = CopyOnWriteArrayList<Throwable>()
        host.uncaughtObserverHandler = {
            reported += it
            host.action { y update { it + 1 } }
        }
        source.uncaughtObserverHandler = { reported += it }
        val (d, sub) = host.derived(source.n) { source.n.value }
        val writes = AtomicInteger()
        val loop =
            d effect {
                if (this > 0) {
                    writes.incrementAndGet()
                    source.action { n update { it + 1 } }.getOrThrow()
                }
            }
        try {
            completesWithin(60, "a feedback loop whose cut a store-writing handler records") {
                host.action { source.action { n mutate 1 }.getOrThrow() }.getOrThrow()
            }
            assertTrue(writes.get() in 1..2_000, "the handler did not restart the loop: ${writes.get()} writes")
            assertEquals(1, reported.size, "the cut is reported once: $reported")
            assertEquals(1, host.y.value, "the handler's write committed")
        } finally {
            loop.dispose()
            sub.dispose()
        }
    }
}
