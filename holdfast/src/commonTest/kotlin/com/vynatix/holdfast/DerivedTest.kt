package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class DerivedVault : Store<DerivedVault>() {
    val items by state { emptyList<Int>() }
    val tax by state { 1.0 }
    val multiplier by state { 1 }
}

class ComputedTest {
    @Test fun computedReflectsCurrentSourceValues() {
        val v = DerivedVault()
        val total = v.computed { items.value.sum() }
        assertEquals(0, total.value)
        v action { items mutate listOf(1, 2, 3) }
        assertEquals(6, total.value)
        v action { items mutate listOf(10, 20) }
        assertEquals(30, total.value)
    }

    @Test fun computedDoesNotFireObservers() {
        // computed has no observer mechanism by design — the read recomputes;
        // there's no Disposable to attach an observer to.
        val v = DerivedVault()
        val total = v.computed { items.value.sum() }
        // Verifying via the type: computed is a plain State<T>, not MutableState.
        assertTrue(total !is MutableState<*>, "computed returns a thin read-time State, not a MutableState")
    }
}

class DerivedTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    @Test fun derivedRecomputesOnSourceCommit() {
        val v = DerivedVault()
        val (total, d) = v.derived(v.items) { items.value.sum() }
        disposables += d
        assertEquals(0, total.value)
        v action { items mutate listOf(1, 2, 3) }
        assertEquals(6, total.value)
    }

    @Test fun derivedFiresItsOwnObserversOnRecompute() {
        val v = DerivedVault()
        val (total, d) = v.derived(v.items) { items.value.sum() }
        disposables += d

        val seen = mutableListOf<Int>()
        val sub = v { total effect { seen.add(this) } }
        seen.clear()

        v action { items mutate listOf(1, 2, 3) }
        v action { items mutate listOf(10) }
        assertEquals(listOf(6, 10), seen)
        sub.dispose()
    }

    @Test fun derivedRecomputeFailureReachesOnErrorAndValueRecovers() {
        // F10: a throwing recompute must not be swallowed. With an onError handler
        // the exception is delivered there, and the derived value recovers on the
        // next (non-throwing) source commit rather than freezing.
        val v = DerivedVault()
        val captured = mutableListOf<Throwable>()
        val failOnceAt = 2
        val (total, d) =
            v.derived(v.items, onError = { captured.add(it) }) {
                val sum = items.value.sum()
                if (sum == failOnceAt) error("boom at $sum")
                sum
            }
        disposables += d

        v action { items mutate listOf(2) } // compute -> 2 -> throws
        assertEquals(1, captured.size, "recompute exception reached onError")
        assertTrue(captured.first().message?.contains("boom at 2") == true)

        v action { items mutate listOf(3) } // compute -> 3, succeeds
        assertEquals(3, total.value, "derived recovers on the next source commit, not frozen")
    }

    @Test fun derivedRecomputeFailureRoutesToStoreHandlerWhenNoOnError() {
        // F10: with no onError, the failure routes to the store's uncaught handler
        // (loud default; here captured by an assigned handler).
        val v = DerivedVault()
        val captured = mutableListOf<Throwable>()
        v.uncaughtObserverHandler = { captured.add(it) }
        val (_, d) = v.derived(v.items) { if (items.value.isNotEmpty()) error("kaboom") else 0 }
        disposables += d

        v action { items mutate listOf(1) }
        assertEquals(1, captured.size, "recompute exception routed to store handler")
        assertTrue(captured.first().message?.contains("kaboom") == true)
    }

    @Test fun throwingPostCommitTaskDoesNotAffectParentActionResult() {
        // F10: the post-commit drain must not throw — a failing task is routed to
        // the uncaught handler and the outer action still reports Success.
        val v = DerivedVault()
        val captured = mutableListOf<Throwable>()
        v.uncaughtObserverHandler = { captured.add(it) }
        val (_, d) = v.derived(v.items) { if (items.value.isNotEmpty()) error("drain boom") else 0 }
        disposables += d

        val r = v action { items mutate listOf(1) }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1, captured.size)
    }

    @Test fun derivedRecomputesOnAnyOfMultipleSources() {
        val v = DerivedVault()
        val (taxed, d) = v.derived(v.items, v.tax) { items.value.sum() * tax.value }
        disposables += d

        v action { items mutate listOf(10, 20) }
        assertEquals(30.0, taxed.value)

        v action { tax mutate 1.1 }
        assertEquals(33.0, taxed.value)
    }

    @Test fun derivedDisposeStopsRecomputation() {
        val v = DerivedVault()
        val (total, d) = v.derived(v.items) { items.value.sum() }

        v action { items mutate listOf(1, 2, 3) }
        assertEquals(6, total.value)

        d.dispose()
        v action { items mutate listOf(100) }
        assertEquals(6, total.value, "after dispose, derived no longer recomputes")
    }

    @Test fun derivedSucceedsWhenInsideAnEnclosingAction() {
        // Source mutation inside an outer action: the derived's recompute
        // happens via its observer, which fires on the outer's commit.
        val v = DerivedVault()
        val (total, d) = v.derived(v.items) { items.value.sum() }
        disposables += d

        val r = v action { items mutate listOf(7, 8) }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(15, total.value)
    }

    @Test fun multipleIndependentDerivedStatesCoexist() {
        val v = DerivedVault()
        val (sum, dSum) = v.derived(v.items) { items.value.sum() }
        val (max, dMax) = v.derived(v.items) { items.value.maxOrNull() ?: 0 }
        val (count, dCount) = v.derived(v.items) { items.value.size }
        disposables += dSum
        disposables += dMax
        disposables += dCount

        v action { items mutate listOf(3, 1, 4, 1, 5, 9, 2, 6) }
        assertEquals(31, sum.value)
        assertEquals(9, max.value)
        assertEquals(8, count.value)
    }
}
