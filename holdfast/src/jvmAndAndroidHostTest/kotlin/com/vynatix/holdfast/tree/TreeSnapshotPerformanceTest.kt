@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private class PerfLeafStore : Store<PerfLeafStore>() {
    val a by state { 0 }
    val b by state { "" }
    val c by state { 0L }
}

private class PerfApp : Store<PerfApp>() {
    val leaves by keyed<Int, PerfLeafStore> { PerfLeafStore() }
}

private const val LEAVES = 16
private const val CAPTURES = 1_000
private const val BUDGET_MS = 5_000L

/**
 * A capture of a sixteen-leaf tree is cheap enough to take per frame: 1,000
 * of them, warm, well under a generous budget that fails only on a cliff.
 * JVM and Android host only, compared in milliseconds (a whole-seconds
 * comparison would have failed at exactly the budget): a debug Kotlin/Native
 * binary on the iOS simulator has no wall-clock budget worth pinning.
 */
class TreeSnapshotPerformanceTest {
    @Test
    fun aThousandCapturesOfSixteenLeavesStayUnderBudget() {
        val root = PerfApp()
        repeat(LEAVES) { i -> root.leaves.create(i) }
        root.tree.snapshot()
        val started = TimeSource.Monotonic.markNow()
        var captured = 0
        repeat(CAPTURES) {
            captured +=
                root.tree
                    .snapshot()
                    .children.size
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        assertEquals(CAPTURES, captured, "each capture holds the keyed branch")
        assertTrue(elapsedMs < BUDGET_MS, "$CAPTURES captures of $LEAVES leaves took ${elapsedMs}ms; budget ${BUDGET_MS}ms exceeded")
    }
}
