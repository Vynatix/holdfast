@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.effect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RcsLeftStore : Store<RcsLeftStore>() {
    val x by state { 0 }
}

private class RcsRightStore : Store<RcsRightStore>() {
    val y by state { 0 }
}

private class RcsApp : Store<RcsApp>() {
    val left = RcsLeftStore()
    val right = RcsRightStore()
    val pair by stores { listOf(left, right) }
}

private const val FRAMES = 2_000
private const val MIN_READS = 500

/** T4 for suspending writers: a `tree` handle's value during another thread's `suspendAtomic` frames is never a mix. */
class TreeConsistentCutSuspendTest {
    @Test
    fun valueIsNeverAMixDuringSuspendAtomicFrames() =
        completesWithin(120, "value reads during suspendAtomic frames") {
            val root = RcsApp()
            val mixes = ConcurrentLinkedQueue<String>()
            root.tree effect { if (this[root.left.x] != this[root.right.y]) mixes += "published" }
            val done = AtomicBoolean(false)
            val reads = AtomicInteger()
            val writer =
                daemon("writer") {
                    runBlocking {
                        repeat(FRAMES) { k ->
                            suspendAtomic(root.left, root.right) {
                                root.left { x mutate k + 1 }
                                yield()
                                root.right { y mutate k + 1 }
                            }.getOrThrow()
                        }
                    }
                    done.set(true)
                }
            val reader =
                daemon("reader") {
                    while (!done.get()) {
                        val tree = root.tree.value
                        if (tree[root.left.x] != tree[root.right.y]) mixes += "read"
                        reads.incrementAndGet()
                    }
                }
            writer.join()
            reader.join()
            assertEquals(emptyList<String>(), mixes.toList())
            assertTrue(reads.get() >= MIN_READS, "reader progress ${reads.get()}")
            assertEquals(FRAMES, root.tree.internalSettleNow()[root.left.x])
            assertTrue(root.tree.internalSettleCount <= FRAMES + 2L, "at most one settle per frame: ${root.tree.internalSettleCount}")
        }
}
