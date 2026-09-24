@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Observable
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.computed
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.merged
import com.vynatix.holdfast.observerCount
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A thread the user drafts a reply in, while sync adopts the server's messages. */
private class ThreadStore : Store<ThreadStore>() {
    val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
    val server by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }

    /** How many times `shown`'s merge has run. Declared before `shown`, whose initial merge counts. */
    var merges = 0
    val shown by merged(draft, server) { d, s ->
        merges++
        if (d.isEmpty()) s else s + d
    }
}

/** The sources of a derived state that lives on another store. */
private class SourcePair : Store<SourcePair>() {
    val a by state { 0 }
    val b by state { 0 }
}

/**
 * A `merged` state (issue #20, R6) through `:holdfast-coroutines`: its flows,
 * the owning scope `asStateFlow` defaults to, `suspendDerived` over it, and an
 * adoption committed by `suspendAction`.
 */
class MergedSuspendTest {
    @Test fun asFlowOverAMergedStateEmitsEachMergedValueAndReleasesItsObserver() =
        runBlocking {
            val store = ThreadStore()
            val seen = mutableListOf<List<String>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { store.shown.asFlow().collect { seen += it } }
            yield()
            store action {
                server mutate listOf("a")
                server update { it + "b" }
            }
            yield()
            store action { draft mutate "reply" }
            yield()
            assertEquals(1, store.shown.observerCount)
            job.cancelAndJoin()

            assertEquals(listOf(emptyList(), listOf("a", "b"), listOf("a", "b", "reply")), seen)
            assertEquals(0, store.shown.observerCount, "cancelling the collector releases its observer")
        }

    @Test fun asStateFlowOverAMergedStateDefaultsToItsStoresScope() =
        runBlocking {
            val store = ThreadStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            store.bindToScope(scope)
            val flow = store.shown.asStateFlow(started = SharingStarted.Eagerly)
            store action { server mutate listOf("a") }

            assertEquals(listOf("a"), withTimeout(5_000) { flow.first { it == listOf("a") } })
            assertEquals(1, store.shown.observerCount, "the eager sharing observes the merged state")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            assertEquals(0, store.shown.observerCount, "cancelling the store's scope ends the sharing")
        }

    /** An adoption committed by `suspendAction` — writes on both sides of a suspension — recomputes once. */
    @Test fun aSuspendActionAdoptionRecomputesTheMergedValueOnce() =
        runBlocking {
            val store = ThreadStore()
            store action { draft mutate "reply" }
            val before = store.merges

            store
                .suspendAction {
                    server mutate listOf("a")
                    delay(1)
                    server update { it + "b" }
                }.getOrThrow()

            assertEquals(1, store.merges - before, "one adoption commit, one recompute")
            assertEquals("reply", store.draft.value)
            assertEquals(listOf("a", "b", "reply"), store.shown.value)
        }

    @Test fun firstAndAwaitValueResolveOnAMergedState() =
        runBlocking {
            val store = ThreadStore()
            val awaited = async(start = CoroutineStart.UNDISPATCHED) { store.shown.awaitValue(listOf("x")) }
            val firstLong = async(start = CoroutineStart.UNDISPATCHED) { store.shown.first { it.size == 2 } }
            store action { server mutate listOf("x") }
            yield() // asFlow conflates: let the collectors take this value first.
            store action { server mutate listOf("x", "y") }
            assertEquals(listOf("x"), withTimeout(5_000) { awaited.await() })
            assertEquals(listOf("x", "y"), withTimeout(5_000) { firstLong.await() })
        }

    @Test fun suspendDerivedFollowsAMergedSource() =
        runBlocking {
            val store = ThreadStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            store.bindToScope(scope)
            val (count, handle) = store.suspendDerived(store.shown) { shown.value.size }
            try {
                store action { server mutate listOf("a", "b") }
                assertEquals(2, withTimeout(5_000) { count.asFlow().first { it == 2 } })
            } finally {
                handle.dispose()
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }

    @Test fun collectingAMergedStateOfADisposedStoreFails() =
        runBlocking {
            val store = ThreadStore()
            val shown = store.shown
            store.dispose()
            val e = assertFailsWith<IllegalStateException> { shown.asFlow().first() }
            assertContains(e.message.orEmpty(), "disposed")
        }

    /**
     * Sources on another store committed by `suspendAction` (writes on both
     * sides of a suspension) and by `suspendAtomic`: each commit recomputes the
     * derived state once, before the suspending call returns.
     */
    @Test fun aSuspendingCommitOnASourceStoreRecomputesACrossStoreDerivedStateOnce() =
        runBlocking {
            val sources = SourcePair()
            val host = ThreadStore()
            var computes = 0
            val sum =
                host.derivedState(sources.a, sources.b) {
                    computes++
                    sources.a.value + sources.b.value
                }
            try {
                sources
                    .suspendAction {
                        a mutate 1
                        delay(1)
                        b mutate 2
                    }.getOrThrow()
                assertEquals(3, sum.value)
                assertEquals(2, computes, "the initial compute, then one recompute")

                suspendAtomic(sources) {
                    sources { a mutate 10 }
                    delay(1)
                    sources { b mutate 20 }
                }.getOrThrow()
                assertEquals(30, sum.value)
                assertEquals(3, computes, "one more recompute for the frame")
            } finally {
                sum.dispose()
            }
        }

    /**
     * A `suspendAction` parked on the thread a recompute runs on — `runBlocking`
     * here, as on Android's main thread, or any thread on wasmJs — neither holds
     * the recompute back nor leaks into it. A commit on another source's store,
     * or a value pushed into one through `observeFrom`, recomputes at once from
     * committed values, so the parked action's rollback leaves nothing to
     * correct, and its commit recomputes once more.
     */
    @Test fun aSuspendActionParkedOnTheRecomputingThreadNeitherDelaysNorLeaksIntoARecompute() =
        runBlocking {
            for (commits in listOf(false, true)) {
                val left = SourcePair()
                val right = SourcePair()
                val host = SourcePair()
                val pair = host.derivedState(left.a, right.a) { left.a.value to right.a.value }
                var push: ((Int) -> Unit)? = null
                val inbound =
                    left {
                        a observeFrom
                            Observable { observer ->
                                push = observer
                                Disposable { push = null }
                            }
                    }
                val gate = CompletableDeferred<Unit>()
                val parked =
                    async {
                        right.suspendAction {
                            a mutate 5
                            gate.await()
                        }
                    }
                yield() // right's suspendAction is now parked on this thread, its write pending.

                left action { a mutate 1 }
                assertEquals(1 to 0, pair.value, "recomputed at once, without right's pending write")
                push!!(2)
                assertEquals(2 to 0, pair.value, "an observeFrom push recomputes at once too")

                if (commits) gate.complete(Unit) else gate.completeExceptionally(IllegalStateException("abort"))
                val result = parked.await()
                if (commits) {
                    result.getOrThrow()
                    assertEquals(2 to 5, pair.value, "right's commit recomputes it")
                } else {
                    assertIs<TransactionResult.Error>(result)
                    assertEquals(2 to 0, pair.value, "right's rollback leaves nothing to correct")
                }
                inbound.dispose()
                pair.dispose()
            }
        }

    /**
     * Disposing a store from its own commit's fanout drains its post-commit
     * queue: a `suspendDerived` hosted there launches nothing, and a derived
     * state hosted there (`shown`) does not recompute. Neither reports anything.
     */
    @Test fun disposingAStoreFromItsOwnCommitLaunchesNoSuspendDerivedAndRecomputesNothingHostedThere() {
        val store = ThreadStore()
        val scopeJob = SupervisorJob()
        // Unconfined: without the isDisposed guard, the launch and its compute would run inline in the drain.
        store.bindToScope(CoroutineScope(scopeJob + Dispatchers.Unconfined))
        val errors = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { errors += it }
        var computes = 0
        val (_, handle) =
            store.suspendDerived(store.server) {
                computes++
                0
            }
        val mergesBefore = store.merges
        // Subscribed after the suspendDerived and `shown` subscriptions, so their tasks are queued before
        // dispose drains the queue.
        store.server effect { if (isNotEmpty()) store.dispose() }

        store action { server mutate listOf("a") }

        assertTrue(store.isDisposed)
        assertEquals(1, computes, "only the initial seed: nothing launched after dispose")
        assertEquals(mergesBefore, store.merges, "shown's queued recompute met a disposed host and did nothing")
        assertEquals(emptyList(), errors, "a disposed host reports nothing")
        handle.dispose()
        scopeJob.cancel()
    }

    /**
     * A `computed` source is refused before the initial compute runs, and
     * leaves no backing state and no subscription behind.
     */
    @Test fun suspendDerivedRefusesAComputedSourceBeforeAnythingRunsOrSubscribes() {
        val store = ThreadStore()
        val snapshot = store.snapshot()
        val keys = store.properties.keys.toSet()
        val observers = store.server.observerCount
        var computes = 0
        val e =
            assertFailsWith<IllegalArgumentException> {
                store.suspendDerived(store.server, store.computed { draft.value }) {
                    computes++
                    0
                }
            }
        assertContains(e.message.orEmpty(), "computed { }")
        assertEquals(keys, store.properties.keys, "no backing state was registered")
        assertEquals(snapshot, store.snapshot())
        assertEquals(observers, store.server.observerCount, "no source was subscribed")
        store action { server mutate listOf("a") }
        assertEquals(0, computes)
    }
}
