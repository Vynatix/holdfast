@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.effect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private class PfLeafStore : Store<PfLeafStore>() {
    val n by state { 0 }
    val label by state { "leaf" }
}

private class PfRoot : Root("pf") {
    val leaves = List(16) { PfLeafStore() }
    val all by leaves.foldIndexed(branch(*leaves.toTypedArray())) { i, declaration, leaf -> declaration.named(leaf, "leaf$i") }
}

/** A generous budget, like `PerformanceBudgetTest`'s: it fails only on a cliff, such as recapturing every leaf per commit. */
class TreeValuePerformanceTest {
    @Test
    fun tenThousandSingleStateMutatesWithASixteenLeafRootValueCompleteUnderEightSeconds() {
        val root = PfRoot()
        var fires = 0
        root.value effect { fires++ }
        val mark = TimeSource.Monotonic.markNow()
        repeat(10_000) { i ->
            val leaf = root.leaves[i % 16]
            leaf action { n mutate n.value + 1 }
        }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertEquals(10_001, fires, "one publish per commit")
        assertEquals(10_001, root.internalSettleCount)
        assertEquals(16 + 10_000L, root.internalCaptureCount, "every commit recaptures its one leaf")
        assertTrue(elapsedMs < 8_000, "10 000 mutates under a sixteen-leaf root took ${elapsedMs}ms; budget 8 000ms exceeded")
    }
}
