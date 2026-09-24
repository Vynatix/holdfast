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
}
