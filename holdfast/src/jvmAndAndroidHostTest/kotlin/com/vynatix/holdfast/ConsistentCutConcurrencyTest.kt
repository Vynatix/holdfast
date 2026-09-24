@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.coroutines.suspendAtomic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CutLeft : Store<CutLeft>() {
    val x by state { 0 }
    val untouched by state { "declared, never read" }
}

private class CutRight : Store<CutRight>() {
    val y by state { 0 }
}

/**
 * R9 acceptance 2 (issue #20; the primitive #21's T4 builds on): a
 * consistent cut across stores, taken while another thread commits two-store
 * frames — blocking `atomic` and suspending `suspendAtomic` alike — holds each
 * store as it was before some frame or after it, never one participant's new
 * value with the other's old one. A frame applies every participant inside one
 * write bracket before any of them fans out, and the cut retries while a
 * bracket is open. Stress.
 */
class ConsistentCutConcurrencyTest {
    private val frames = 1_500

    @Test fun aCutDuringAnotherThreadsAtomicFramesIsNeverAMix() {
        cutWhileCommitting("atomic") { left, right, k ->
            atomic(left, right) {
                left { x mutate k }
                right { y mutate k }
            }.getOrThrow()
        }
    }

    @Test fun aCutDuringAnotherThreadsSuspendAtomicFramesIsNeverAMix() {
        cutWhileCommitting("suspendAtomic") { left, right, k ->
            runBlocking {
                suspendAtomic(left, right) {
                    left { x mutate k }
                    yield()
                    right { y mutate k }
                }.getOrThrow()
            }
        }
    }

    /** Every declared state of every store is materialized before the cut, as [snapshot] does for one store. */
    @Test fun captureConsistentMaterializesEveryStoreFirst() {
        val left = CutLeft()
        val right = CutRight()
        val (l, r) = captureConsistent(listOf(left, right))
        assertEquals(setOf("x", "untouched"), l.stateNames)
        assertEquals("declared, never read", l[left.untouched])
        assertEquals(setOf("y"), r.stateNames)
        assertEquals(l, left.snapshot(), "one store's part equals that store's own snapshot")
    }

    private fun cutWhileCommitting(
        what: String,
        commit: (CutLeft, CutRight, Int) -> Unit,
    ) {
        val left = CutLeft()
        val right = CutRight()
        val slow = AtomicBoolean(true)
        // Widens the window the old commit order left between the two
        // participants: the first fanned out before the second applied.
        val lag = left.x effect { if (slow.get()) Thread.sleep(0, 50_000) }
        val mixes = ConcurrentLinkedQueue<String>()
        val cuts = AtomicInteger()
        val done = AtomicBoolean(false)
        val start = CyclicBarrier(3)
        try {
            completesWithin(120, "consistent cuts during $what frames") {
                val writer =
                    daemon("$what-writer") {
                        start.await()
                        repeat(frames) { commit(left, right, it + 1) }
                    }
                val reader =
                    daemon("cut-reader") {
                        start.await()
                        while (!done.get()) {
                            val (l, r) = captureConsistent(listOf(left, right))
                            val lx = l[left.x]
                            val ry = r[right.y]
                            if (lx != ry) mixes += "x=$lx y=$ry"
                            cuts.incrementAndGet()
                        }
                    }
                start.await()
                writer.join(100_000)
                done.set(true)
                reader.join(10_000)
                assertTrue(!writer.isAlive && !reader.isAlive, "both threads finished")
            }
            assertEquals(emptyList(), mixes.toList().take(5), "a cut held one frame's write on one store only")
            assertTrue(cuts.get() > 0, "the reader took cuts")
            assertEquals(frames, left.x.value)
            assertEquals(frames, right.y.value)
        } finally {
            done.set(true)
            slow.set(false)
            lag.dispose()
        }
    }
}
