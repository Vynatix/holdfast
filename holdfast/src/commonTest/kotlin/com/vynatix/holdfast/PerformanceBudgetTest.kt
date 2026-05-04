package com.vynatix.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Wall-clock budgets are intentionally generous so they fail only on real regressions,
 * not on slow CI. They protect against pathological perf cliffs (e.g., a lock that
 * starves under contention or a notification path that becomes O(n²)).
 */
private class BudgetVault : Vault<BudgetVault>() {
    val n by state { 0 }
}

class PerformanceBudgetTest {

    @Test
    fun tenThousandSingleStateMutatesCompleteUnderFiveSeconds() {
        val v = BudgetVault()
        val mark = TimeSource.Monotonic.markNow()

        repeat(10_000) {
            v action { n mutate n.value + 1 }
        }

        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertEquals(10_000, v.n.value)
        assertTrue(
            elapsedMs < 5_000,
            "10 000 single-state mutates took ${elapsedMs}ms; budget 5 000ms exceeded",
        )
    }

    @Test
    fun oneThousandActionsWithFanOutToFiveSubscribersCompleteUnderTwoSeconds() {
        val v = BudgetVault()
        val disposables = (1..5).map { id ->
            v { n effect { /* noop subscriber #$id */ } }
        }
        val mark = TimeSource.Monotonic.markNow()

        repeat(1_000) {
            v action { n mutate n.value + 1 }
        }

        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        disposables.forEach { it.dispose() }
        assertEquals(1_000, v.n.value)
        assertTrue(
            elapsedMs < 2_000,
            "1 000 commits with 5 subscribers each took ${elapsedMs}ms; budget 2 000ms exceeded",
        )
    }

    @Test
    fun tenThousandValueGetterReadsOnHotPathCompleteUnderOneSecond() {
        val v = BudgetVault()
        v action { n mutate 42 }
        val mark = TimeSource.Monotonic.markNow()

        var sink = 0
        repeat(10_000) {
            sink = sink xor v.n.value
        }

        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertTrue(sink == 0 || sink == 42, "side-effect to keep the optimizer honest")
        assertTrue(
            elapsedMs < 1_000,
            "10 000 hot-path reads took ${elapsedMs}ms; budget 1 000ms exceeded",
        )
    }

    @Test
    fun oneHundredConcurrentActionsAcrossFourWorkersCompleteUnderThreeSeconds() = runBlocking {
        val v = BudgetVault()
        val mark = TimeSource.Monotonic.markNow()

        val jobs = List(4) {
            async(Dispatchers.Default) {
                repeat(25) {
                    v action { n mutate n.value + 1 }
                }
            }
        }
        jobs.awaitAll()

        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertEquals(100, v.n.value)
        assertTrue(
            elapsedMs < 3_000,
            "100 concurrent actions across 4 workers took ${elapsedMs}ms; budget 3 000ms exceeded",
        )
    }
}
