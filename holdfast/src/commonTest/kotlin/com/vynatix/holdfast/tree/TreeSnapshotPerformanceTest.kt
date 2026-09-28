@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private class PerfLeafStore(
    id: Int,
    root: PerfRoot,
) : Store<PerfLeafStore>(root.leaves.at(id)) {
    val a by state { 0 }
    val b by state { "" }
    val c by state { 0L }
}

private class PerfRoot : Root() {
    val leaves by keyed<Int, PerfLeafStore>()
}

private const val LEAVES = 16
private const val CAPTURES = 1_000
private const val BUDGET_SECONDS = 2L

/** A capture of a sixteen-leaf tree is cheap enough to take per frame: 1,000 of them under two seconds, warm. */
class TreeSnapshotPerformanceTest {
    @Test
    fun aThousandCapturesOfSixteenLeavesStayUnderBudget() {
        val root = PerfRoot()
        repeat(LEAVES) { i -> root.leaves.create(i) { PerfLeafStore(it, root) } }
        root.snapshot()
        val started = TimeSource.Monotonic.markNow()
        var captured = 0
        repeat(CAPTURES) { captured += root.snapshot().children.size }
        val elapsed = started.elapsedNow()
        assertEquals(CAPTURES, captured, "each capture holds the keyed branch")
        assertTrue(elapsed.inWholeSeconds < BUDGET_SECONDS, "$CAPTURES captures took $elapsed")
    }
}
