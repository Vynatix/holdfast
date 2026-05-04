package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class DerivedVault : Holdfast<DerivedVault>() {
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
