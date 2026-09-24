@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SettleLeft : Store<SettleLeft>() {
    val a by state { 0 }
}

private class SettleRight : Store<SettleRight>() {
    val b by state { 0 }
}

private class SettleHost : Store<SettleHost>() {
    val y by state { 0 }
}

/** Counts the transactions a store commits: a derived state's recomputes, on its host. */
private class CommitCounter<V : Store<V>> : Middleware<V>() {
    var committed = 0

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        committed++
    }
}

/**
 * Derived states settle once per outermost entry (issue #20, R9; plan
 * decision D17): whatever one `action`, `atomic` frame — and everything
 * nested in it — changes of a `derivedState`'s sources, on however many
 * stores, it recomputes once, after that entry has exited and released every
 * store, reading a committed cut. Coalescing is not a mode (#20 open question
 * 2): `derivedState`/`merged` always settle; the legacy `derived` keeps one
 * recompute per source commit until the 0.7.0 triage.
 */
class SettleCoalescingTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private class Probe(
        left: SettleLeft,
        right: SettleRight,
        host: SettleHost,
    ) {
        var computes = 0
        var sourceHeld = false
        val seen = mutableListOf<Pair<Int, Int>>()
        val counter = CommitCounter<SettleHost>().also { host.middlewares(it) }
        val pair: DerivedState<Pair<Int, Int>> =
            host.derivedState(left.a, right.b) {
                computes++
                if (left.activeTransaction != null || right.activeTransaction != null) sourceHeld = true
                left.a.value to right.b.value
            }
        val watch = pair effect { seen += this }
    }

    private fun probe(
        left: SettleLeft,
        right: SettleRight,
        host: SettleHost,
    ): Probe = Probe(left, right, host).also { disposables += listOf(it.watch, it.pair) }

    /**
     * R9 acceptance 1: a two-store frame recomputes a cross-store derived
     * state exactly once. This flat, outermost-frame case already held before
     * R9 (each source store's queue, then the frame's drain once it had
     * released both), so it stays as a regression guard; acceptance 1 as a
     * whole is proven together with [aFrameNestedInAnActionSettlesWithIt] and
     * [sequentialActionsNestedInOneOuterActionRecomputeOnce] (once per
     * outermost entry) and `DerivedConcurrencyTest` (no torn pair).
     */
    @Test fun aTwoStoreFrameRecomputesACrossStoreDerivedStateOnce() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        val probe = probe(left, right, host)

        atomic(left, right) {
            left.action { a mutate 1 }
            right.action { b mutate 1 }
        }.getOrThrow()

        assertEquals(1 to 1, probe.pair.value)
        assertEquals(2, probe.computes, "the initial compute, then one recompute for the frame")
        assertEquals(1, probe.counter.committed, "one recompute transaction on the host")
        assertEquals(listOf(0 to 0, 1 to 1), probe.seen, "never one participant's new value with the other's old one")
        assertTrue(!probe.sourceHeld, "the recompute ran once the frame had released both stores")
    }

    /** The same frame, with the derived state hosted on one of its participants. */
    @Test fun aFrameRecomputesADerivedStateHostedOnAParticipantOnce() {
        val left = SettleLeft()
        val right = SettleRight()
        var computes = 0
        val pair =
            left.derivedState(left.a, right.b) {
                computes++
                left.a.value to right.b.value
            }
        disposables += pair

        atomic(left, right) {
            left { a mutate 2 }
            right { b mutate 2 }
        }.getOrThrow()

        assertEquals(2 to 2, pair.value)
        assertEquals(2, computes)
    }

    /** Several sequential actions nested in one outer entry recompute once, after the outer one exits. */
    @Test fun sequentialActionsNestedInOneOuterActionRecomputeOnce() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        val outer = SettleHost()
        val probe = probe(left, right, host)
        var inside: Pair<Int, Int>? = null

        outer
            .action {
                left.action { a mutate 1 }
                right.action { b mutate 1 }
                left.action { a mutate 2 }
                inside = probe.pair.value
                y mutate 1
            }.getOrThrow()

        assertEquals(0 to 0, inside, "nothing settles before the outermost entry exits")
        assertEquals(2 to 1, probe.pair.value)
        assertEquals(2, probe.computes, "one recompute for the three nested commits")
        assertEquals(listOf(0 to 0, 2 to 1), probe.seen)
        assertTrue(!probe.sourceHeld)
    }

    /** A frame nested in an action, and a write after it, still settle once: at the outer action's exit. */
    @Test fun aFrameNestedInAnActionSettlesWithIt() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        val outer = SettleHost()
        val probe = probe(left, right, host)

        outer
            .action {
                atomic(left, right) {
                    left { a mutate 1 }
                    right { b mutate 1 }
                }.getOrThrow()
                right.action { b mutate 3 }
            }.getOrThrow()

        assertEquals(1 to 3, probe.pair.value)
        assertEquals(2, probe.computes)
        assertEquals(listOf(0 to 0, 1 to 3), probe.seen)
    }

    /**
     * An outer action that rolls back settles too: the nested commits on
     * other stores stand, and the derived state recomputes from them once.
     */
    @Test fun anOuterActionThatRollsBackStillSettlesTheNestedCommits() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        val probe = probe(left, right, host)

        val rolledBack =
            host.action {
                left.action { a mutate 4 }
                right.action { b mutate 4 }
                y mutate 9
                error("abort")
            }

        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(0, host.y.value)
        assertEquals(4 to 4, probe.pair.value)
        assertEquals(2, probe.computes)
    }

    /**
     * A chain settles in rank order: a derived state that reads another one
     * recomputes after it, once, and never from its previous value — even
     * when the commit reaches it first.
     */
    @Test fun aDerivedStateReadingAnotherSettlesAfterItOnce() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        val doubled = host.derivedState(right.b) { right.b.value * 2 }
        var topComputes = 0
        // Reads left.a and the derived state: left's commit reaches it first.
        val top =
            host.derivedState(left.a, doubled) {
                topComputes++
                left.a.value to doubled.value
            }
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(top effect { seen += this }, top, doubled)

        atomic(left, right) {
            left { a mutate 1 }
            right { b mutate 1 }
        }.getOrThrow()

        assertEquals(1 to 2, top.value)
        assertEquals(2, topComputes, "the initial compute, then one recompute after `doubled` settled")
        assertEquals(listOf(0 to 0, 1 to 2), seen, "no intermediate value from `doubled`'s previous value")
    }

    /**
     * A derived state created inside an action on its host that has written a
     * state its compute reads without listing it catches up once that action
     * ends: a rollback leaves nothing its sources don't explain. (A regression
     * guard: holding the host already queued a catch-up before R9.)
     */
    @Test fun aDerivedStateCreatedFromAnUnlistedPendingWriteOnItsHostCatchesUp() {
        val left = SettleLeft()
        val host = SettleHost()
        lateinit var sum: DerivedState<Int>
        var initial = -1

        val rolledBack =
            host.action {
                y mutate 100
                sum = host.derivedState(left.a) { left.a.value + host.y.value }
                initial = sum.value
                error("abort")
            }

        assertIs<TransactionResult.Error>(rolledBack)
        disposables += sum
        assertEquals(100, initial, "the initial compute reads the action's pending write")
        assertEquals(0, sum.value, "recomputed from committed values once the action rolled back")
    }

    /**
     * The same on a third store — neither the host nor a source's store: the
     * initial compute notes that it read the action's pending write, and the
     * derived state recomputes from committed values once the action ends.
     */
    @Test fun aDerivedStateCreatedFromAnotherStoresPendingWriteCatchesUpWhenTheActionEnds() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        lateinit var sum: DerivedState<Int>
        var initial = -1

        val rolledBack =
            right.action {
                b mutate 100
                sum = host.derivedState(left.a) { left.a.value + right.b.value }
                initial = sum.value
                error("abort")
            }

        assertIs<TransactionResult.Error>(rolledBack)
        disposables += sum
        assertEquals(100, initial, "the initial compute reads the action's pending write")
        assertEquals(0, sum.value, "recomputed from committed values once the action rolled back")
    }

    /**
     * A derived state created inside an action on a source's store, whose
     * initial compute reads no uncommitted value, needs no catch-up: it
     * computes once, and its host commits no recompute transaction.
     */
    @Test fun aDerivedStateCreatedInASourceStoresActionWithoutAPendingReadComputesOnce() {
        val left = SettleLeft()
        val host = SettleHost()
        val counter = CommitCounter<SettleHost>().also { host.middlewares(it) }
        var computes = 0
        lateinit var doubled: DerivedState<Int>

        left
            .action {
                doubled =
                    host.derivedState(left.a) {
                        computes++
                        left.a.value * 2
                    }
            }.getOrThrow()
        disposables += doubled

        assertEquals(1, computes, "no catch-up recompute")
        assertEquals(0, counter.committed, "no recompute transaction on the host")
    }

    /**
     * A frame nested in an action leaves the post-commit work of the stores
     * whose roots it opened — here a legacy `derived` hosted on a participant
     * — to the outer entry's settle, instead of draining it at its own exit.
     */
    @Test fun aFrameNestedInAnActionDefersItsParticipantsPostCommitWorkToTheOuterSettle() {
        val left = SettleLeft()
        val right = SettleRight()
        val outer = SettleHost()
        val (doubled, subscription) = left.derived(left.a) { a.value * 2 }
        disposables += subscription
        var inside = -1

        outer
            .action {
                atomic(left, right) { left { a mutate 1 } }.getOrThrow()
                inside = doubled.value
                y mutate 1
            }.getOrThrow()

        assertEquals(0, inside, "the frame's drain waits for the outermost entry")
        assertEquals(2, doubled.value, "and runs when that entry settles")
    }

    /**
     * A settle runs a frame's store drains before its recomputes: a
     * derivedState reading a legacy `derived` hosted on a participant
     * recomputes once, after that derived's drained recompute, never from its
     * stale value.
     */
    @Test fun aDerivedStateOverALegacyDerivedOnAParticipantSettlesOnceAfterTheDrain() {
        val left = SettleLeft()
        val right = SettleRight()
        val host = SettleHost()
        val (legacy, subscription) = left.derived(left.a) { a.value * 10 }
        var computes = 0
        val pair =
            host.derivedState(legacy, right.b) {
                computes++
                legacy.value to right.b.value
            }
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(pair effect { seen += this }, pair, subscription)

        atomic(left, right) {
            left { a mutate 1 }
            right { b mutate 1 }
        }.getOrThrow()

        assertEquals(10 to 1, pair.value)
        assertEquals(2, computes, "the initial compute, then one recompute after the drained legacy recompute")
        assertEquals(listOf(0 to 0, 10 to 1), seen, "never the legacy derived's stale value")
    }

    /** A derived state created outside any entry reads committed values and needs no catch-up. */
    @Test fun aDerivedStateCreatedOutsideAnyEntryComputesOnce() {
        val left = SettleLeft()
        val host = SettleHost()
        val counter = CommitCounter<SettleHost>().also { host.middlewares(it) }
        var computes = 0
        val doubled =
            host.derivedState(left.a) {
                computes++
                left.a.value * 2
            }
        disposables += doubled

        assertEquals(1, computes)
        assertEquals(0, counter.committed, "no recompute transaction")
        assertNull(SettleScopes.current(), "no settle scope outlives its entry")
    }

    /**
     * The legacy `derived` keeps one recompute per source commit until the
     * 0.7.0 triage: coalescing is what `derivedState` does, not a mode.
     */
    @Test fun theLegacyDerivedStillRecomputesOncePerSourceCommit() {
        val left = SettleLeft()
        val outer = SettleHost()
        var computes = 0
        val (doubled, subscription) =
            left.derived(left.a) {
                computes++
                a.value * 2
            }
        disposables += subscription

        outer
            .action {
                left.action { a mutate 1 }
                left.action { a mutate 2 }
            }.getOrThrow()

        assertEquals(4, doubled.value)
        assertEquals(3, computes, "the initial compute, then one recompute per nested commit")
    }
}
