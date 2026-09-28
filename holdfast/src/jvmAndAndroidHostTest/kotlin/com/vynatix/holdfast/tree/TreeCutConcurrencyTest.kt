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

private class CutKeyedStore(
    id: Int,
    root: CutRoot,
) : Store<CutKeyedStore>(root.keyed.at(id)) {
    val n by state { 0 }
}

private class CutRoot : Root("cut") {
    val left = CutLeftStore()
    val right = CutRightStore()
    val pair by branch(left, right)
    val keyed by keyed<Int, CutKeyedStore>()
}

private const val FRAMES = 3_000
private const val MIN_CUTS = 1_000

/**
 * T4 (never a mix): a tree capture during another thread's two-store
 * frames — blocking `atomic` and suspending `suspendAtomic` alike — holds
 * both stores as they were before some frame or after it, never one
 * participant's new value with the other's old one; a single-store,
 * two-state commit is never captured half-applied; and captures keep
 * flowing while a `suspendAction` body is parked holding the serializer.
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
            val root = CutRoot()
            val mixes = ConcurrentLinkedQueue<String>()
            val done = AtomicBoolean(false)
            val start = CyclicBarrier(3)
            val writer =
                daemon("writer") {
                    start.await()
                    repeat(FRAMES) { k ->
                        root.left action {
                            x mutate k + 1
                            x2 mutate k + 1
                        }
                    }
                }
            val reader =
                daemon("reader") {
                    start.await()
                    while (!done.get()) {
                        val tree = root.snapshot(root.nodeOf(root.left)!!)
                        val a = tree[root.left.x]
                        val b = tree[root.left.x2]
                        if (a != b) mixes += "x=$a x2=$b"
                    }
                }
            start.await()
            writer.join(100_000)
            done.set(true)
            reader.join(10_000)
            assertEquals(emptyList(), mixes.toList().take(5))
            assertEquals(FRAMES, root.left.x.value)
        }

    @Test
    fun aCaptureWhileASuspendActionBodyIsParkedHoldingTheSerializerReturns() =
        completesWithin(30, "a capture during a parked suspendAction") {
            val root = CutRoot()
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
            val tree = root.snapshot()
            assertEquals(0, tree[root.left.x], "the parked body's write is not committed yet")
            release.countDown()
            body.join()
            assertEquals(1, root.snapshot()[root.left.x])
        }

    @Test
    fun aCaptureRacingKeyedCreateAndDisposeNeverThrows() =
        completesWithin(60, "captures racing keyed churn") {
            val root = CutRoot()
            val done = AtomicBoolean(false)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val cuts = AtomicInteger()
            val churner =
                daemon("churner", failures) {
                    repeat(500) { i ->
                        val s = root.keyed.create(i) { CutKeyedStore(it, root) }
                        s action { n mutate i }
                        s.dispose()
                    }
                }
            val reader =
                daemon("reader", failures) {
                    while (!done.get()) {
                        val tree = root.snapshot()
                        for (child in tree[root.keyed]!!.children) checkNotNull(child.leaf)
                        cuts.incrementAndGet()
                    }
                }
            churner.join()
            done.set(true)
            reader.join()
            assertTrue(failures.isEmpty(), failures.joinToString())
            assertTrue(cuts.get() > 0)
            assertTrue(root.snapshot()[root.keyed]!!.children.isEmpty())
        }

    @Test
    fun rootDisposeDuringACaptureDoesNotDeadlock() =
        completesWithin(30, "root dispose racing captures") {
            val root = CutRoot()
            val start = CyclicBarrier(2)
            val reader =
                daemon("reader") {
                    start.await()
                    repeat(200) { runCatching { root.snapshot() } }
                }
            val disposer =
                daemon("disposer") {
                    start.await()
                    Thread.sleep(2)
                    root.dispose()
                }
            reader.join()
            disposer.join()
            assertTrue(root.isDisposed)
        }

    private fun captureWhileCommitting(
        what: String,
        commit: (CutRoot, Int) -> Unit,
    ) {
        val root = CutRoot()
        val slow = AtomicBoolean(true)
        // Widens the window between the two participants' applies would
        // have been under the old commit order; the frame bracket closes it.
        val lag = root.left.x effect { if (slow.get()) Thread.sleep(0, 100_000) }
        val mixes = ConcurrentLinkedQueue<String>()
        val cuts = AtomicInteger()
        val done = AtomicBoolean(false)
        val start = CyclicBarrier(3)
        try {
            completesWithin(180, "tree captures during $what frames") {
                val writer =
                    daemon("$what-writer") {
                        start.await()
                        repeat(FRAMES) { commit(root, it + 1) }
                    }
                val reader =
                    daemon("tree-reader") {
                        start.await()
                        while (!done.get()) {
                            val tree = root.snapshot()
                            val lx = tree[root.left.x]
                            val ry = tree[root.right.y]
                            if (lx != ry) mixes += "x=$lx y=$ry"
                            cuts.incrementAndGet()
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
            assertEquals(FRAMES, root.left.x.value)
            assertEquals(FRAMES, root.right.y.value)
        } finally {
            done.set(true)
            slow.set(false)
            lag.dispose()
        }
    }
}
