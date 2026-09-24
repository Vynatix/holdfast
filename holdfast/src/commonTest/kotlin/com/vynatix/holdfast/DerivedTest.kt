package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class DerivedVault : Store<DerivedVault>() {
    val items by state { emptyList<Int>() }
    val tax by state { 1.0 }
    val multiplier by state { 1 }
}

private class DerivedSourceStore : Store<DerivedSourceStore>() {
    val a by state { 0 }
    val b by state { 0 }
}

private class DerivedHostStore : Store<DerivedHostStore>() {
    val unrelated by state { 0 }
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

    /**
     * A `computed` source is refused with a teaching IllegalArgumentException
     * before anything runs, registers or subscribes: no compute, no backing
     * state (in `properties` or a snapshot), no observer on the other sources.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun aComputedSourceIsRefusedBeforeAnythingRunsOrSubscribes() {
        val v = DerivedVault()
        val snapshot = v.snapshot()
        val keys = v.properties.keys.toSet()
        val observers = v.items.observerCount
        var computes = 0
        val e =
            assertFailsWith<IllegalArgumentException> {
                v.derived(v.items, v.computed { tax.value }) {
                    computes++
                    0
                }
            }
        assertContains(e.message.orEmpty(), "computed { }")
        assertEquals(keys, v.properties.keys, "no backing state was registered")
        assertEquals(snapshot, v.snapshot())
        assertEquals(observers, v.items.observerCount, "no source was subscribed")
        v action { items mutate listOf(1) }
        assertEquals(0, computes, "the compute never ran, and nothing recomputes it")
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

    /**
     * Each source's observer used to queue its own recompute lambda, so a
     * commit touching N sources recomputed (and committed, and fanned out) N
     * times. The derived now submits one stable task, which the post-commit
     * queue deduplicates by identity.
     */
    @Test fun derivedRecomputesOnceWhenSeveralSourcesChangeInOneCommit() {
        val v = DerivedVault()
        var computes = 0
        val (product, d) =
            v.derived(v.items, v.tax, v.multiplier) {
                computes++
                items.value.sum() * tax.value * multiplier.value
            }
        disposables += d
        assertEquals(1, computes, "the initial value is computed once")

        val seen = mutableListOf<Double>()
        val sub = v { product effect { seen.add(this) } }
        seen.clear()

        v action {
            items mutate listOf(1, 2)
            tax mutate 2.0
            multiplier mutate 3
        }

        assertEquals(2, computes, "three sources changed in one commit: exactly one recompute")
        assertEquals(18.0, product.value)
        assertEquals(listOf(18.0), seen, "the derived commits (and fans out) once per source commit")

        v action { tax mutate 1.0 }
        assertEquals(3, computes, "a later commit still recomputes")
        assertEquals(9.0, product.value)
        sub.dispose()
    }

    /**
     * The recompute used to run as a fire-and-forget `action` whose result the
     * post-commit drain discarded inside `runCatching`: one throwing compute
     * and the derived froze with no trace anywhere.
     */
    @Test fun derivedComputeFailureIsReportedAndLaterCommitsRecover() {
        val v = DerivedVault()
        val reported = mutableListOf<Throwable>()
        v.uncaughtObserverHandler = { reported += it }
        val (total, d) =
            v.derived(v.items) {
                check(items.value.size < 3) { "too many items" }
                items.value.sum()
            }
        disposables += d

        val r = v action { items mutate listOf(1, 2, 3) }
        assertIs<TransactionResult.Success<*>>(r, "the source commit itself is unaffected")
        assertEquals(1, reported.size, "the failing recompute must be reported, not swallowed")
        assertEquals("too many items", reported.single().message)
        assertEquals(0, total.value, "a failed recompute rolls back and keeps the previous value")

        v action { items mutate listOf(4) }
        assertEquals(4, total.value, "the next source commit recomputes normally")
    }

    /** Disposal stops recomputation, including a recompute already queued by the same commit. */
    @Test fun derivedDisposedDuringTheCommitThatQueuedItsRecomputeDoesNotRecompute() {
        val v = DerivedVault()
        val (total, d) = v.derived(v.items) { items.value.sum() }
        // Subscribed after the derived, so it fans out after the derived's own
        // source observer has queued the recompute.
        val sub = v { items effect { if (isNotEmpty()) d.dispose() } }

        v action { items mutate listOf(5) }

        assertEquals(0, total.value, "the recompute queued before dispose must not commit")
        sub.dispose()
    }

    /**
     * A recompute the post-commit drain has already taken into its batch still
     * does not commit once its derived is disposed: withdrawing it from the
     * queue finds nothing by then, so only the dispose flag stops it.
     */
    @Test fun derivedDisposedByAnEarlierRecomputeInTheSameDrainDoesNotRecompute() {
        val v = DerivedVault()
        // Created first, so its recompute is queued (and drained) before the second's.
        val (first, d1) = v.derived(v.items) { items.value.sum() }
        var secondComputes = 0
        val (second, d2) =
            v.derived(v.items) {
                secondComputes++
                items.value.size
            }
        disposables += d1
        disposables += d2 // dispose is idempotent
        // Fires from the FIRST derived's recompute commit, i.e. after the drain
        // has already moved both recomputes out of the queue.
        val sub = v { first effect { if (this != 0) d2.dispose() } }

        v action { items mutate listOf(5) }

        assertEquals(5, first.value, "the first derived recomputes normally")
        assertEquals(0, second.value, "a recompute taken by the drain before dispose must not commit")
        assertEquals(1, secondComputes, "only the initial compute; the disposed recompute never runs compute")
        sub.dispose()
    }

    /**
     * Pins today's cross-store behavior. `postCommit`'s identity dedup only
     * applies while the HOST has a transaction active; with the host idle,
     * each changed source's observer runs the recompute inline from the
     * source's commit fanout, so one source commit touching two sources
     * recomputes (and commits, and fans out) the derived twice. Cross-store
     * settling (#20 PR 11, `SettleScope`) is expected to change this count to
     * one — flip the assertions deliberately when it lands.
     */
    @Test fun derivedWithSourcesOnAnIdleOtherStoreRecomputesOncePerChangedSource() {
        val src = DerivedSourceStore()
        val host = DerivedHostStore()
        var computes = 0
        val (sum, d) =
            host.derived(src.a, src.b) {
                computes++
                src.a.value + src.b.value
            }
        disposables += d
        val seen = mutableListOf<Int>()
        val sub = host { sum effect { seen.add(this) } }
        seen.clear()

        src action {
            a mutate 1
            b mutate 2
        }

        assertEquals(3, computes, "the initial compute plus one per changed source on another store")
        assertEquals(listOf(3, 3), seen, "the derived commits once per changed source")
        assertEquals(3, sum.value)
        sub.dispose()
    }

    /**
     * A derived's subscriptions live on its SOURCE store, so they outlive a
     * disposed host. A later source commit used to run the recompute's
     * blocking `action` on the disposed host, throwing "store disposed" into
     * the source's observer fanout; the recompute now sees the host disposed
     * and does nothing.
     */
    @Test fun derivedWhoseHostIsDisposedIgnoresLaterSourceCommits() {
        val src = DerivedSourceStore()
        val host = DerivedHostStore()
        val sourceErrors = mutableListOf<Throwable>()
        val hostErrors = mutableListOf<Throwable>()
        src.uncaughtObserverHandler = { sourceErrors += it }
        host.uncaughtObserverHandler = { hostErrors += it }
        val (_, d) = host.derived(src.a) { src.a.value * 2 }
        disposables += d

        host.dispose()
        val r = src action { a mutate 2 }

        assertIs<TransactionResult.Success<*>>(r, "the source commit is unaffected by the disposed host")
        assertEquals(2, src.a.value)
        assertEquals(emptyList<Throwable>(), sourceErrors, "nothing may be thrown into the source's fanout")
        assertEquals(emptyList<Throwable>(), hostErrors, "a disposed host reports nothing")
        d.dispose() // Disposing the derived afterwards is still safe.
    }

    /** A recompute that fails because its host was disposed mid-recompute is not reported. */
    @Test fun derivedRecomputeFailingBecauseItsHostWasDisposedIsNotReported() {
        val src = DerivedSourceStore()
        val host = DerivedHostStore()
        val hostErrors = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { hostErrors += it }
        val (_, d) =
            host.derived(src.a) {
                if (src.a.value == 2) {
                    host.dispose()
                    error("boom")
                }
                src.a.value
            }
        disposables += d

        val r = src action { a mutate 2 }

        assertIs<TransactionResult.Success<*>>(r)
        assertTrue(host.isDisposed)
        assertEquals(emptyList<Throwable>(), hostErrors, "a failure racing the host's dispose must not be reported")
    }

    /**
     * `atomic` drains a participant's post-commit queue only once the whole
     * frame has unwound. Draining at that participant's own unwind step ran
     * the recompute while an EARLIER participant still had the frame's
     * finished root installed, so an observer writing to it threw instead of
     * committing.
     */
    @OptIn(StoreInternalApi::class)
    @Test
    fun derivedRecomputedByAtomicCanWriteToAnEarlierParticipant() {
        // Constructed first, so it sorts first in the frame's lock order.
        val earlier = DerivedHostStore()
        val v = DerivedVault()
        assertTrue(earlier.lockOrderKey < v.lockOrderKey)
        val (total, d) = v.derived(v.items) { items.value.sum() }
        disposables += d
        val errors = mutableListOf<Throwable>()
        v.uncaughtObserverHandler = { errors += it }
        val sub =
            total effect {
                val t = this
                earlier { unrelated mutate t }
            }

        val r = atomic(earlier, v) { v { items mutate listOf(2, 3) } }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(emptyList<Throwable>(), errors, "the observer's write must not hit the frame's finished root")
        assertEquals(5, total.value)
        assertEquals(5, earlier.unrelated.value, "the observer's write to the earlier participant must commit")
        sub.dispose()
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
