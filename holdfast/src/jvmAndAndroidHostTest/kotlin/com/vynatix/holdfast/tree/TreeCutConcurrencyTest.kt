@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.effect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CutLeftStore : Store<CutLeftStore>() {
    val x by state { 0 }
    val x2 by state { 0 }
}

private class CutRightStore : Store<CutRightStore>() {
    val y by state { 0 }
}

private class CutKeyedStore : Store<CutKeyedStore>() {
    val n by state { 0 }
}

private class CutApp : Store<CutApp>() {
    val left = CutLeftStore()
    val right = CutRightStore()
    val pair by group { listOf(left, right) }
    val keyed by keyed<Int, CutKeyedStore> { CutKeyedStore() }
}

private const val FRAMES = 3_000
private const val MAX_FRAMES = 30_000
private const val CHURN_ROUNDS = 500
private const val MAX_CHURN_ROUNDS = 50_000
private const val MIN_CUTS = 1_000
private const val MIN_CAPTURES_BEFORE_DISPOSE = 20

/**
 * Whether a writer runs one more round: at least [minimum] rounds, then as
 * many more — up to [bound] — as it takes for [reader] to take [MIN_CUTS]
 * cuts; never once the test is [done] or the reader has gone.
 */
private fun moreRounds(
    rounds: Int,
    minimum: Int,
    bound: Int,
    cuts: AtomicInteger,
    done: AtomicBoolean,
    reader: Thread,
): Boolean = !done.get() && (rounds < minimum || (rounds < bound && cuts.get() < MIN_CUTS && reader.isAlive))

/**
 * T4 (never a mix): a tree capture during another thread's two-store
 * frames — blocking `atomic` and suspending `suspendAtomic` alike — holds
 * both stores as they were before some frame or after it, never one
 * participant's new value with the other's old one; a single-store,
 * two-state commit is never captured half-applied; and captures keep
 * flowing while a `suspendAction` body is parked holding the serializer.
 *
 * Every racing reader has a progress minimum ([MIN_CUTS]): the writer keeps
 * committing — the churner keeps creating and disposing — until the reader
 * has taken that many cuts, so a fast machine cannot finish the writes
 * before the reader has raced any of them. Every writer loop is bounded
 * ([MAX_FRAMES], [MAX_CHURN_ROUNDS]) and stops on the test's `done` flag
 * ([moreRounds]), so a reader that hangs fails the test on its minimum
 * instead of leaving a daemon committing until the Gradle worker exits.
 */
class TreeCutConcurrencyTest {
    @Test
    fun aCaptureDuringAnotherThreadsAtomicFramesIsNeverAMix() {
        captureWhileCommitting("atomic") { root, k ->
            atomic(root.left, root.right) {
                root.left { x mutate k }
                root.right { y mutate k }
            }.getOrThrow()
        }
    }

    @Test
    fun aCaptureDuringAnotherThreadsSuspendAtomicFramesIsNeverAMix() {
        captureWhileCommitting("suspendAtomic") { root, k ->
            runBlocking {
                suspendAtomic(root.left, root.right) {
                    root.left { x mutate k }
                    yield()
                    root.right { y mutate k }
                }.getOrThrow()
            }
        }
    }

    @Test
    fun nestedSameFlavourFramesAreNeverAMixPerFrame() {
        captureWhileCommitting("nested atomic") { root, k ->
            atomic(root.left, root.right) {
                root.left { x mutate k }
                atomic(root.left, root.right) { root.right { y mutate k } }.getOrThrow()
            }.getOrThrow()
        }
    }

    @Test
    fun aTwoStateSingleStoreCommitIsNeverCapturedHalfApplied() =
        completesWithin(120, "captures during two-state commits") {
            val root = CutApp()
            val mixes = ConcurrentLinkedQueue<String>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val cuts = AtomicInteger()
            val committed = AtomicInteger()
            val done = AtomicBoolean(false)
            val start = CyclicBarrier(3)
            try {
                val reader =
                    daemon("reader", failures) {
                        start.await()
                        while (!done.get()) {
                            val tree = root.tree.snapshot(root.tree.nodeOf(root.left)!!)
                            val a = tree[root.left.x]
                            val b = tree[root.left.x2]
                            if (a != b) mixes += "x=$a x2=$b"
                            cuts.incrementAndGet()
                        }
                    }
                val writer =
                    daemon("writer", failures) {
                        start.await()
                        while (moreRounds(committed.get(), FRAMES, MAX_FRAMES, cuts, done, reader)) {
                            val k = committed.incrementAndGet()
                            root.left action {
                                x mutate k
                                x2 mutate k
                            }
                        }
                    }
                start.await()
                writer.join(100_000)
                done.set(true)
                reader.join(10_000)
                assertTrue(failures.isEmpty(), failures.joinToString())
                assertTrue(!writer.isAlive && !reader.isAlive, "both threads finished")
                assertEquals(emptyList(), mixes.toList().take(5))
                assertTrue(cuts.get() >= MIN_CUTS, "the reader took ${cuts.get()} cuts")
                assertTrue(committed.get() >= FRAMES, "the writer committed ${committed.get()} times")
                assertEquals(committed.get(), root.left.x.value)
            } finally {
                done.set(true)
            }
        }

    @Test
    fun aCaptureWhileASuspendActionBodyIsParkedHoldingTheSerializerReturns() =
        completesWithin(30, "a capture during a parked suspendAction") {
            val root = CutApp()
            val parked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val body =
                daemon("suspend-action") {
                    runBlocking {
                        root.left.suspendAction {
                            x mutate 1
                            parked.countDown()
                            release.await()
                        }
                    }
                }
            parked.await()
            val tree = root.tree.snapshot()
            assertEquals(0, tree[root.left.x], "the parked body's write is not committed yet")
            release.countDown()
            body.join()
            assertEquals(1, root.tree.snapshot()[root.left.x])
        }

    @Test
    fun aCaptureRacingKeyedCreateAndDisposeNeverThrows() =
        completesWithin(60, "captures racing keyed churn") {
            val root = CutApp()
            val done = AtomicBoolean(false)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val cuts = AtomicInteger()
            val rounds = AtomicInteger()
            val start = CyclicBarrier(3)
            try {
                val reader =
                    daemon("reader", failures) {
                        start.await()
                        while (!done.get()) {
                            val tree = root.tree.snapshot()
                            for (child in tree[root.keyed]!!.children) checkNotNull(child.leaf)
                            cuts.incrementAndGet()
                        }
                    }
                val churner =
                    daemon("churner", failures) {
                        start.await()
                        while (moreRounds(rounds.get(), CHURN_ROUNDS, MAX_CHURN_ROUNDS, cuts, done, reader)) {
                            val i = rounds.getAndIncrement()
                            val s = root.keyed.create(i)
                            s action { n mutate i }
                            s.dispose()
                        }
                    }
                start.await()
                churner.join(50_000)
                done.set(true)
                reader.join(5_000)
                assertTrue(failures.isEmpty(), failures.joinToString())
                assertTrue(!churner.isAlive && !reader.isAlive, "both threads finished")
                assertTrue(cuts.get() >= MIN_CUTS, "the reader took ${cuts.get()} cuts")
                assertTrue(rounds.get() >= CHURN_ROUNDS, "the churner ran ${rounds.get()} rounds")
                assertTrue(
                    root.tree
                        .snapshot()[root.keyed]!!
                        .children
                        .isEmpty(),
                )
            } finally {
                done.set(true)
            }
        }

    @Test
    fun receiverDisposeDuringACaptureDoesNotDeadlockAndFailsOnlyAsDisposed() =
        completesWithin(30, "receiver dispose racing captures") {
            val root = CutApp()
            // Taken before the race: the accessor itself refuses a disposed store.
            val tree = root.tree
            val start = CyclicBarrier(2)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val succeeded = AtomicInteger()
            val reader =
                daemon("reader", failures) {
                    start.await()
                    // Captures until the first one that finds the receiver disposed: every capture before
                    // it returned, and that one — and nothing else — threw the documented exception.
                    while (true) {
                        try {
                            tree.snapshot()
                            succeeded.incrementAndGet()
                        } catch (e: IllegalStateException) {
                            check("disposed" in e.message.orEmpty()) { "a capture racing dispose threw $e" }
                            break
                        }
                    }
                }
            val disposer =
                daemon("disposer", failures) {
                    start.await()
                    // Dispose only once the reader has been capturing, so the race is against live captures.
                    while (succeeded.get() < MIN_CAPTURES_BEFORE_DISPOSE && reader.isAlive) Thread.onSpinWait()
                    root.dispose()
                }
            reader.join()
            disposer.join()
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertTrue(succeeded.get() >= MIN_CAPTURES_BEFORE_DISPOSE, "captures before dispose: ${succeeded.get()}")
            assertTrue(root.isDisposed)
        }

    private fun captureWhileCommitting(
        what: String,
        commit: (CutApp, Int) -> Unit,
    ) {
        val root = CutApp()
        val slow = AtomicBoolean(true)
        // Widens the window between the two participants' applies would
        // have been under the old commit order; the frame bracket closes it.
        val lag = root.left.x effect { if (slow.get()) Thread.sleep(0, 100_000) }
        val mixes = ConcurrentLinkedQueue<String>()
        val cuts = AtomicInteger()
        val committed = AtomicInteger()
        val done = AtomicBoolean(false)
        val start = CyclicBarrier(3)
        try {
            completesWithin(180, "tree captures during $what frames") {
                val reader =
                    daemon("tree-reader") {
                        start.await()
                        while (!done.get()) {
                            val tree = root.tree.snapshot()
                            val lx = tree[root.left.x]
                            val ry = tree[root.right.y]
                            if (lx != ry) mixes += "x=$lx y=$ry"
                            cuts.incrementAndGet()
                        }
                    }
                val writer =
                    daemon("$what-writer") {
                        start.await()
                        while (moreRounds(committed.get(), FRAMES, MAX_FRAMES, cuts, done, reader)) {
                            commit(root, committed.incrementAndGet())
                        }
                    }
                start.await()
                writer.join(150_000)
                done.set(true)
                reader.join(10_000)
                assertTrue(!writer.isAlive && !reader.isAlive, "both threads finished")
            }
            assertEquals(emptyList(), mixes.toList().take(5), "a capture held one frame's write on one store only")
            assertTrue(cuts.get() >= MIN_CUTS, "the reader took ${cuts.get()} cuts")
            assertTrue(committed.get() >= FRAMES, "the writer committed ${committed.get()} frames")
            assertEquals(committed.get(), root.left.x.value)
            assertEquals(committed.get(), root.right.y.value)
        } finally {
            done.set(true)
            slow.set(false)
            lag.dispose()
        }
    }
}
