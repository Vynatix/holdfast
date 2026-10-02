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

private class TreeValuePerformanceLeafStore : Store<TreeValuePerformanceLeafStore>() {
    val n by state { 0 }
    val label by state { "leaf" }
}

private class TreeValuePerformanceParent : Store<TreeValuePerformanceParent>() {
    val leaves by stores<Int, TreeValuePerformanceLeafStore> { TreeValuePerformanceLeafStore() }
}

private const val LEAVES = 16
private const val MUTATES = 10_000
private const val BUDGET_MS = 20_000L

/**
 * A generous wall-clock budget, like `PerformanceBudgetTest`'s: it fails only
 * on a cliff, such as recapturing every store per commit (which the capture
 * count pins exactly, budget or not). JVM and Android host only: each
 * iteration is a child action plus a settle of the parent's `tree` value — a
 * host transaction, a seventeen-store capture (the parent and sixteen
 * children) reusing sixteen, the distinct compare and the effect fanout —
 * several times the work of a bare mutate, and a debug Kotlin/Native binary
 * on the iOS simulator has no wall-clock budget worth pinning.
 */
class TreeValuePerformanceTest {
    @Test
    fun tenThousandSingleStateMutatesWithASixteenChildTreeValueStayUnderBudget() {
        val parent = TreeValuePerformanceParent()
        val leaves = List(LEAVES) { parent.leaves.create(it) }
        val tree = parent.tree
        var fires = 0
        tree effect { fires++ }
        val mark = TimeSource.Monotonic.markNow()
        repeat(MUTATES) { i ->
            val leaf = leaves[i % LEAVES]
            leaf action { n mutate n.value + 1 }
        }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertEquals(MUTATES + 1, fires, "one publish per commit")
        assertEquals(MUTATES + 1L, tree.internalSettleCount)
        assertEquals(
            LEAVES + 1L + MUTATES,
            tree.internalCaptureCount,
            "the first settle captures the parent and every child; every commit recaptures its one child",
        )
        assertTrue(
            elapsedMs < BUDGET_MS,
            "$MUTATES mutates under a sixteen-child tree took ${elapsedMs}ms; budget ${BUDGET_MS}ms exceeded",
        )
    }
}
