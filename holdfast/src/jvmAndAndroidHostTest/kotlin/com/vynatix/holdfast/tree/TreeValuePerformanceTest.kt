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
    val leaves = List(LEAVES) { PfLeafStore() }
    val all by leaves.foldIndexed(branch(*leaves.toTypedArray())) { i, declaration, leaf -> declaration.named(leaf, "leaf$i") }
}

private const val LEAVES = 16
private const val MUTATES = 10_000
private const val BUDGET_MS = 20_000L

/**
 * A generous wall-clock budget, like `PerformanceBudgetTest`'s: it fails only
 * on a cliff, such as recapturing every leaf per commit (which the capture
 * count pins exactly, budget or not). JVM and Android host only: each
 * iteration is a leaf action plus a `Root.value` settle — a host transaction,
 * a sixteen-leaf capture reusing fifteen, the distinct compare and the effect
 * fanout — several times the work of a bare mutate, and a debug Kotlin/Native
 * binary on the iOS simulator has no wall-clock budget worth pinning.
 */
class TreeValuePerformanceTest {
    @Test
    fun tenThousandSingleStateMutatesWithASixteenLeafRootValueStayUnderBudget() {
        val root = PfRoot()
        var fires = 0
        root.value effect { fires++ }
        val mark = TimeSource.Monotonic.markNow()
        repeat(MUTATES) { i ->
            val leaf = root.leaves[i % LEAVES]
            leaf action { n mutate n.value + 1 }
        }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertEquals(MUTATES + 1, fires, "one publish per commit")
        assertEquals(MUTATES + 1L, root.internalSettleCount)
        assertEquals(LEAVES + MUTATES.toLong(), root.internalCaptureCount, "every commit recaptures its one leaf")
        assertTrue(
            elapsedMs < BUDGET_MS,
            "$MUTATES mutates under a sixteen-leaf root took ${elapsedMs}ms; budget ${BUDGET_MS}ms exceeded",
        )
    }
}
