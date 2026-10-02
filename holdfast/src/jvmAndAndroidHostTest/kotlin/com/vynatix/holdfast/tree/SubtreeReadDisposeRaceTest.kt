@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals

private class RdInsideStore : Store<RdInsideStore>() {
    val inside by state { 1 }
}

private class RdOutsideStore : Store<RdOutsideStore>() {
    val outside by state { 2 }
}

/** Created per round AFTER the capture: a store the capture never listed, so a read of it asks the live tree. */
private class RdJoinedStore : Store<RdJoinedStore>() {
    val joined by state { 3 }
}

private class RdApp : Store<RdApp>() {
    val insideStore = RdInsideStore()
    val outsideStore = RdOutsideStore()
    val captured by group { listOf(insideStore) }
    val other by group { listOf(outsideStore) }
    val later by keyed<Int, RdJoinedStore> { RdJoinedStore() }
}

private const val ROUNDS = 300
private const val READS_PER_ROUND = 4_000

/**
 * A read of a state outside the captured subtree that the capture never
 * listed — a store that joined the receiver's subtree since — consults the
 * live tree to tell "under the receiver now, so `Absent`" from "never
 * under it, so throw". The receiver's `dispose()` landing while the read
 * runs releases that store as a subtree root; the read still answers
 * `Absent` (a disposed receiver can no longer tell, so it never throws),
 * never an "is not under" exception from a read that is documented to work
 * after dispose. A store listed at capture time reads `Absent` throughout.
 */
class SubtreeReadDisposeRaceTest {
    @Test
    fun anOutsideReadRacingTheReceiversDisposeAnswersAbsentAndNeverThrows() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        repeat(ROUNDS) { round ->
            val root = RdApp()
            val outside = root.outsideStore.outside
            val subtree = root.tree.snapshot(root.captured)
            val joined = root.later.create(round).joined
            val start = CyclicBarrier(2)
            val reader =
                daemon("outside-reader-$round", failures) {
                    start.await()
                    repeat(READS_PER_ROUND) {
                        assertEquals(SnapshotEntry.Absent, subtree.entry(outside), "listed at capture time")
                        assertEquals(SnapshotEntry.Absent, subtree.entry(joined), "joined since the capture")
                    }
                }
            start.await()
            // Let the reader get going, then pull the receiver away under it.
            repeat(round % 7) { Thread.yield() }
            root.dispose()
            reader.join()
            assertEquals(SnapshotEntry.Absent, subtree.entry(outside), "after dispose: still Absent")
            assertEquals(SnapshotEntry.Absent, subtree.entry(joined), "after dispose: still Absent")
        }
        assertEquals(emptyList<Throwable>(), failures.toList())
    }
}
