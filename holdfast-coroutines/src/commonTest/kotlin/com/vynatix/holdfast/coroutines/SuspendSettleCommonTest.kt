@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.DerivedState
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CommonSettleSourceA : Store<CommonSettleSourceA>() {
    val a by state { 0 }
}

private class CommonSettleSourceB : Store<CommonSettleSourceB>() {
    val b by state { 0 }
}

private class CommonSettleHost : Store<CommonSettleHost>() {
    val y by state { 0 }
}

/**
 * `suspendAction` and `suspendAtomic` are entries like `action` and `atomic`
 * (issue #20, R9): the derived states their commits — and every commit nested
 * in them — change a source of settle once, when the outermost entry has
 * released every store. The single-threaded cases, in common code so that
 * they also run against the iOS carrier of a suspending entry's settle scope
 * (`withSlotIntercepted`, SettleAmbientContext.ios.kt): the `yield()`s make
 * the nested commits run on a resumption, where only that carrier installs
 * the scope. The cases that hop threads are in the JVM/Android
 * `SuspendSettleTest`.
 */
class SuspendSettleCommonTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private inner class Probe(
        left: CommonSettleSourceA,
        right: CommonSettleSourceB,
        host: CommonSettleHost,
    ) {
        var computes = 0
        var sourceHeld = false
        val seen = mutableListOf<Pair<Int, Int>>()
        val pair: DerivedState<Pair<Int, Int>> =
            host.derivedState(left.a, right.b) {
                computes++
                if (left.activeTransaction != null || right.activeTransaction != null) sourceHeld = true
                left.a.value to right.b.value
            }

        init {
            disposables += listOf(pair effect { seen += this }, pair)
        }

        fun assertSettledOnce(expected: Pair<Int, Int>) {
            assertEquals(expected, pair.value)
            assertEquals(2, computes, "the initial compute, then one recompute for the whole entry")
            assertEquals(listOf(0 to 0, expected), seen)
            assertTrue(!sourceHeld, "the recompute ran once the entry had released every store")
        }
    }

    /** R9 acceptance 1 for the suspending frame. */
    @Test fun aTwoStoreSuspendAtomicRecomputesACrossStoreDerivedStateOnce() {
        val left = CommonSettleSourceA()
        val right = CommonSettleSourceB()
        val probe = Probe(left, right, CommonSettleHost())

        runBlocking {
            suspendAtomic(left, right) {
                left { a mutate 1 }
                yield()
                right { b mutate 1 }
            }.getOrThrow()
        }

        probe.assertSettledOnce(1 to 1)
    }

    @Test fun suspendActionsNestedInOneOuterSuspendActionRecomputeOnce() {
        val left = CommonSettleSourceA()
        val right = CommonSettleSourceB()
        val outer = CommonSettleHost()
        val probe = Probe(left, right, CommonSettleHost())
        var inside: Pair<Int, Int>? = null

        runBlocking {
            outer
                .suspendAction {
                    yield()
                    left.suspendAction { a mutate 1 }.getOrThrow()
                    right.suspendAction { b mutate 1 }.getOrThrow()
                    inside = probe.pair.value
                    y mutate 1
                }.getOrThrow()
        }

        assertEquals(0 to 0, inside, "nothing settles before the outermost entry exits")
        probe.assertSettledOnce(1 to 1)
    }

    @Test fun blockingActionsInASuspendActionBodySettleWithIt() {
        val left = CommonSettleSourceA()
        val right = CommonSettleSourceB()
        val outer = CommonSettleHost()
        val probe = Probe(left, right, CommonSettleHost())

        runBlocking {
            outer
                .suspendAction {
                    yield()
                    left.action { a mutate 2 }.getOrThrow()
                    yield()
                    right.action { b mutate 2 }.getOrThrow()
                }.getOrThrow()
        }

        probe.assertSettledOnce(2 to 2)
    }

    /** A suspending frame run through `runBlocking` inside a blocking action joins that action's scope. */
    @Test fun aSuspendAtomicInsideABlockingActionSettlesWithTheAction() {
        val left = CommonSettleSourceA()
        val right = CommonSettleSourceB()
        val outer = CommonSettleHost()
        val probe = Probe(left, right, CommonSettleHost())

        outer
            .action {
                runBlocking {
                    suspendAtomic(left, right) {
                        left { a mutate 4 }
                        right { b mutate 4 }
                    }.getOrThrow()
                }
                y mutate 1
            }.getOrThrow()

        probe.assertSettledOnce(4 to 4)
        assertNull(SettleScopes.current(), "no scope outlives its entry")
    }

    /**
     * A `suspendAtomic` nested in a `suspendAction` leaves the post-commit
     * work of the stores whose roots it opened — here a legacy `derived`
     * hosted on a participant — to the outer entry's settle.
     */
    @Test fun aSuspendAtomicNestedInASuspendActionDefersItsParticipantsPostCommitWork() {
        val left = CommonSettleSourceA()
        val right = CommonSettleSourceB()
        val outer = CommonSettleHost()
        val (doubled, subscription) = left.derived(left.a) { a.value * 2 }
        disposables += subscription
        var inside = -1

        runBlocking {
            outer
                .suspendAction {
                    suspendAtomic(left, right) { left { a mutate 1 } }.getOrThrow()
                    inside = doubled.value
                }.getOrThrow()
        }

        assertEquals(0, inside, "the frame's drain waits for the outermost entry")
        assertEquals(2, doubled.value, "and runs when that entry settles")
    }
}
