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

private class RdRoot : Root("rd") {
    val insideStore = RdInsideStore()
    val outsideStore = RdOutsideStore()
    val captured by branch(insideStore)
    val other by branch(outsideStore)
}

private const val ROUNDS = 300
private const val READS_PER_ROUND = 4_000

/**
 * A read of a state outside the captured subtree consults the root's
 * registry to tell "a member, so `Absent`" from "never a member, so throw".
 * That answer is ONE read under the registry lock: a `Root.dispose()`
 * landing while the read runs makes it `Absent`, never an
 * "root … disposed" exception from a read that is documented to work after
 * dispose.
 */
class SubtreeReadDisposeRaceTest {
    @Test
    fun anOutsideReadRacingTheRootsDisposeAnswersAbsentAndNeverThrows() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        repeat(ROUNDS) { round ->
            val root = RdRoot()
            val outside = root.outsideStore.outside
            val subtree = root.snapshot(root.captured)
            val start = CyclicBarrier(2)
            val reader =
                daemon("outside-reader-$round", failures) {
                    start.await()
                    repeat(READS_PER_ROUND) {
                        assertEquals(SnapshotEntry.Absent, subtree.entry(outside))
                    }
                }
            start.await()
            // Let the reader get going, then pull the root away under it.
            repeat(round % 7) { Thread.yield() }
            root.dispose()
            reader.join()
            assertEquals(SnapshotEntry.Absent, subtree.entry(outside), "after dispose: still Absent")
        }
        assertEquals(emptyList<Throwable>(), failures.toList())
    }
}
