@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.effect
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RcLeftStore : Store<RcLeftStore>() {
    val x by state { 0 }
    val x2 by state { 0 }
}

private class RcRightStore : Store<RcRightStore>() {
    val y by state { 0 }
}

private class RcKeyedStore : Store<RcKeyedStore>() {
    val n by state { 0 }
}

private class RcApp : Store<RcApp>() {
    val left = RcLeftStore()
    val right = RcRightStore()
    val pair by group { listOf(left, right) }
    val keyed by keyed<Int, RcKeyedStore> { RcKeyedStore() }
}

private const val FRAMES = 3_000
private const val MIN_CHURN = 500
private const val MIN_READS = 1_000

/**
 * T4 (never a mix) for a `tree` handle's value: every value it publishes, and every
 * read of it, during another thread's two-store frames holds both stores as
 * they were before some frame or after it; overlapping frames settle at most
 * once per frame; a leaf's action completes while a `value` observer is
 * parked; and a capture racing a keyed dispose never throws.
 *
 * Every racing reader has a progress minimum ([MIN_READS]): the writer keeps
 * committing until the reader has taken that many reads, so a fast machine
 * cannot finish the writes before the reader has raced any of them, and a
 * regression that tears a commit has reads to show up in.
 */
class TreeConsistentCutTest {
    @Test
    fun valuePublishesAndReadsAreNeverAMixDuringAtomicFrames() =
        completesWithin(120, "value reads during atomic frames") {
            val root = RcApp()
            val mixes = ConcurrentLinkedQueue<String>()
            root.tree effect
                { if (this[root.left.x] != this[root.right.y]) mixes += "published ${this[root.left.x]}/${this[root.right.y]}" }
            val done = AtomicBoolean(false)
            val reads = AtomicInteger()
            val start = CyclicBarrier(3)
            val writer =
                daemon("writer") {
                    start.await()
                    repeat(FRAMES) { k ->
                        atomic(root.left, root.right) {
                            root.left { x mutate k + 1 }
                            root.right { y mutate k + 1 }
                        }.getOrThrow()
                    }
                    done.set(true)
                }
            val reader =
                daemon("reader") {
                    start.await()
                    while (!done.get()) {
                        val tree = root.tree.value
                        if (tree[root.left.x] != tree[root.right.y]) mixes += "read ${tree[root.left.x]}/${tree[root.right.y]}"
                        reads.incrementAndGet()
                    }
                }
            start.await()
            writer.join()
            reader.join()
            assertEquals(emptyList<String>(), mixes.toList(), "a frame is seen whole or not at all")
            assertTrue(reads.get() >= MIN_READS, "reader progress ${reads.get()}")
            assertEquals(FRAMES, root.tree.value[root.left.x], "the final tree reflects every commit")
            assertTrue(root.tree.internalSettleCount <= FRAMES + 1L, "at most one settle per frame: ${root.tree.internalSettleCount}")
        }

    @Test
    fun aSingleStoreMultiStateCommitIsNeverTorn() =
        completesWithin(120, "value during two-state commits") {
            val root = RcApp()
            val torn = ConcurrentLinkedQueue<String>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            root.tree effect { if (this[root.left.x] != this[root.left.x2]) torn += "${this[root.left.x]}/${this[root.left.x2]}" }
            val done = AtomicBoolean(false)
            val reads = AtomicInteger()
            val reader =
                daemon("reader", failures) {
                    while (!done.get()) {
                        val tree = root.tree.value
                        if (tree[root.left.x] != tree[root.left.x2]) torn += "read ${tree[root.left.x]}/${tree[root.left.x2]}"
                        reads.incrementAndGet()
                    }
                }
            // At least FRAMES commits, and as many more as it takes for the reader to race MIN_READS of them.
            var committed = 0
            while (committed < FRAMES || (reads.get() < MIN_READS && reader.isAlive)) {
                val k = ++committed
                root.left action {
                    x mutate k
                    x2 mutate k
                }
            }
            done.set(true)
            reader.join()
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertEquals(emptyList<String>(), torn.toList())
            assertTrue(reads.get() >= MIN_READS, "reader progress ${reads.get()}")
            assertEquals(committed, root.tree.value[root.left.x], "the final tree reflects every commit")
        }

    @Test
    fun aLeafActionOnAnotherThreadCompletesWhileAValueObserverIsParked() =
        completesWithin(30, "a leaf action beside a parked value observer") {
            val root = RcApp()
            val parked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val subscription: Disposable =
                root.tree effect {
                    if (this[root.left.x] == 1) {
                        parked.countDown()
                        release.await()
                    }
                }
            val trigger = daemon("trigger") { root.left action { x mutate 1 } }
            assertTrue(parked.await(10, TimeUnit.SECONDS))
            // The settle holds the host lock and no leaf lock: a leaf action on another thread runs.
            val other = daemon("other") { root.right action { y mutate 7 } }
            other.join(10_000)
            assertTrue(!other.isAlive, "the leaf action completed while the observer was parked")
            assertEquals(7, root.right.y.value)
            release.countDown()
            trigger.join()
            subscription.dispose()
            assertEquals(7, root.tree.internalSettleNow()[root.right.y])
        }

    @Test
    fun aCaptureRacingAKeyedDisposeNeverThrows() =
        completesWithin(120, "value against keyed churn") {
            val root = RcApp()
            root.tree.value
            val failures = ConcurrentLinkedQueue<Throwable>()
            val done = AtomicBoolean(false)
            val reads = AtomicInteger()
            val reader =
                daemon("reader") {
                    while (!done.get()) {
                        runCatching { root.tree.value }.onFailure { failures += it }
                        runCatching { root.tree.internalSettleNow() }.onFailure { failures += it }
                        reads.incrementAndGet()
                    }
                }
            // At least MIN_CHURN create/commit/dispose rounds, and as many more as it takes for the
            // reader to race MIN_READS of them.
            var churned = 0
            while (churned < MIN_CHURN || reads.get() < MIN_READS) {
                val i = churned++
                val k = root.keyed.create(i)
                k action { n mutate i }
                k.dispose()
            }
            done.set(true)
            reader.join()
            assertEquals(emptyList<Throwable>(), failures.toList())
            assertTrue(reads.get() >= MIN_READS, "reader progress ${reads.get()}")
            assertEquals(0, root.keyed.entries().size)
            assertEquals(
                0,
                root.tree.value[root.keyed]!!
                    .children.size,
            )
        }
}
