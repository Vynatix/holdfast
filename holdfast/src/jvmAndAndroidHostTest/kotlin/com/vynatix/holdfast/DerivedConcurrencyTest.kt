@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class PairLeft : Store<PairLeft>() {
    val a by state { 0 }
}

private class PairRight : Store<PairRight>() {
    val b by state { 0 }
}

private class PairHost : Store<PairHost>() {
    val y by state { 0 }
}

/**
 * R9 acceptance 1, "no torn pair" (issue #20): writers commit equal values to
 * two stores in `atomic` frames while other threads keep the derived state's
 * host busy and read everything — no value the derived state commits, and no
 * value anyone observes or reads of it, pairs one frame's write on one store
 * with another frame's on the other. Frames apply every participant inside one
 * write bracket, and a compute reads its sources from one committed cut.
 *
 * Every writer holds `left.a == right.b` at each commit, so any unequal pair
 * is a torn read. Stress: several thousand frames per run.
 */
class DerivedConcurrencyTest {
    private val rounds = 1_500

    @Test fun noTornPairIsEverCommittedOrObservedUnderConcurrentFrames() {
        val left = PairLeft()
        val right = PairRight()
        val host = PairHost()
        val slowLeft = AtomicBoolean(true)
        // Widens the window between the two participants in the old commit
        // order: the first participant's fanout ran before the second applied.
        val lag = left.a effect { if (slowLeft.get()) Thread.yield() }
        val pair = host.derivedState(left.a, right.b) { left.a.value to right.b.value }
        val torn = ConcurrentLinkedQueue<String>()
        val observed = AtomicInteger()
        val watch =
            pair effect {
                observed.incrementAndGet()
                if (first != second) torn += "observed $this"
            }
        val counter = AtomicInteger()
        val done = AtomicBoolean(false)
        val start = CyclicBarrier(6)
        try {
            completesWithin(120, "concurrent frames under a cross-store derived state") {
                val writers =
                    List(2) { w ->
                        daemon("frame-writer-$w") {
                            start.await()
                            repeat(rounds) {
                                val k = counter.incrementAndGet()
                                atomic(left, right) {
                                    left { a mutate k }
                                    right { b mutate k }
                                }.getOrThrow()
                            }
                        }
                    }
                val busyHost =
                    daemon("host-holder") {
                        start.await()
                        var i = 0
                        while (!done.get()) host action { y mutate i++ }
                    }
                val reader =
                    daemon("derived-reader") {
                        start.await()
                        while (!done.get()) {
                            val seen = pair.value
                            if (seen.first != seen.second) torn += "read $seen"
                        }
                    }
                val creator =
                    daemon("derived-creator") {
                        start.await()
                        while (!done.get()) {
                            // A derived state created mid-stream: its initial
                            // compute reads a committed cut too.
                            val fresh = host.derivedState(left.a, right.b) { left.a.value to right.b.value }
                            val seen = fresh.value
                            if (seen.first != seen.second) torn += "initial $seen"
                            fresh.dispose()
                        }
                    }
                start.await()
                writers.forEach { it.join(100_000) }
                done.set(true)
                listOf(busyHost, reader, creator).forEach { it.join(10_000) }
                assertTrue((writers + busyHost + reader + creator).none { it.isAlive }, "every thread finished")
            }
            assertEquals(emptyList(), torn.toList().take(5), "a torn pair was committed or observed")
            assertEquals(left.a.value to right.b.value, pair.value, "the derived state converged to the last frame")
            assertTrue(observed.get() > 1, "the derived state recomputed during the run")
        } finally {
            done.set(true)
            slowLeft.set(false)
            watch.dispose()
            lag.dispose()
            pair.dispose()
        }
    }
}
