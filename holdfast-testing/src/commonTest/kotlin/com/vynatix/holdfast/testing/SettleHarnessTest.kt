@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.testing.concurrency.transaction
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

private class HarnessSources : Store<HarnessSources>() {
    val x by state { 0 }
    val z by state { 0 }
}

private class HarnessOther : Store<HarnessOther>() {
    val w by state { 0 }
}

private class HarnessHost(
    sources: HarnessSources,
    other: HarnessOther,
) : Store<HarnessHost>() {
    var tenfoldComputes = 0
    var pairComputes = 0

    val tenfold by derivedState(sources.z) {
        tenfoldComputes++
        sources.z.value * 10
    }

    // Reads `tenfold`, which the same commits change: it must settle after it.
    val pair by derivedState(sources.x, other.w, tenfold) {
        pairComputes++
        sources.x.value + other.w.value to tenfold.value
    }
}

private class HarnessOuter : Store<HarnessOuter>() {
    val n by state { 0 }
}

/**
 * `:holdfast-testing` settles derived states like production entries do
 * (issue #20, R9): an open transaction's commit is an entry, whose derived
 * states recompute once, in rank order, after the store's lock is released;
 * and a tracked host's timeline shows one recompute for everything an outer
 * action nests.
 */
class SettleHarnessTest {
    @Test fun anOpenTransactionsCommitSettlesItsDerivedStatesOnceInRankOrder() =
        storeTest {
            val sources = HarnessSources()
            val host = HarnessHost(sources, HarnessOther())
            val seen = mutableListOf<Pair<Int, Int>>()
            val watch = host.pair effect { seen += this }
            val handle = track(sources)

            // x is written first, so the commit reaches `pair` before `tenfold`.
            val open =
                transaction(on = handle) {
                    x mutate 1
                    z mutate 2
                }
            open.commit().shouldBeSuccess()

            assertEquals(1 to 20, host.pair.value)
            assertEquals(2, host.pairComputes, "the initial compute, then one recompute after `tenfold`")
            assertEquals(listOf(0 to 0, 1 to 20), seen, "never paired with `tenfold`'s previous value")
            watch.dispose()
        }

    @Test fun anOpenTransactionsRollbackRecomputesNothing() =
        storeTest {
            val sources = HarnessSources()
            val host = HarnessHost(sources, HarnessOther())
            val handle = track(sources)

            transaction(on = handle) { x mutate 5 }.rollback()

            assertEquals(0 to 0, host.pair.value)
            assertEquals(1, host.pairComputes)
        }

    @Test fun actionsNestedInOneOuterActionRecordOneRecompute() =
        storeTest {
            val sources = HarnessSources()
            val other = HarnessOther()
            val hostHandle = track(HarnessHost(sources, other))
            val outer = track(HarnessOuter())

            outer
                .action {
                    sources.action { x mutate 1 }
                    other.action { w mutate 2 }
                    n mutate 1
                }.shouldBeSuccess()

            val recomputes = hostHandle.emissions(HarnessHost::pair)
            assertEquals(1, recomputes.size, "one recompute for the two nested commits")
            assertEquals(3 to 0, recomputes.single().newValue)
            assertEquals(2, hostHandle.read { pairComputes })
        }
}
