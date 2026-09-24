@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Observable
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.reset
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A store whose refresh never observes cancellation, so a stale one completes. */
private class StubbornStore(
    private val answers: List<CompletableDeferred<Int>>,
) : Store<StubbornStore>() {
    val remote by state(tags = setOf(StateTag.Remote)) { 0 }
    var fetches = 0
    val hydration =
        hydrator {
            refresh {
                val answer = answers[fetches++]
                withContext(NonCancellable) { answer.await() }
            } adopt { remote mutate it }
        }
}

/**
 * The ways back to Detached (issue #20, R8, and plan deviation 8):
 * `invalidate()`, `stageInvalidate()` inside an action, and the store's
 * `reset()` — and nothing else: the hydrator's state refuses every store
 * write, and no snapshot, restore or reset of the declared states reaches it.
 */
class HydrationInvalidateTest {
    @Test fun invalidateDetachesAHydratedStoreAndTheNextHydrateSeedsAndRefreshesAgain() =
        runBlocking {
            val remote = FakeRemote { call -> listOf("fetch $call") }
            val store = FeedStore(remote::fetch)
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())

            assertIs<TransactionResult.Success<Unit>>(store.hydration.invalidate())
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(listOf("fetch 1"), store.items.value, "invalidating keeps the store's values")

            store.hydration.hydrate(this)
            assertEquals(2, store.baseRuns, "base runs again after an invalidate")
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(listOf("fetch 2"), store.items.value)
        }

    @Test fun invalidateWhileARefreshIsInFlightCancelsItAndAdoptsNothing() =
        runBlocking {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            store.hydration.hydrate(this)
            yield() // the refresh starts, and waits for its answer
            assertEquals(1, remote.fetches)

            store.hydration.invalidate()
            assertEquals(Hydration.Detached, store.hydration.current)
            remote.release.complete(listOf("late"))
            repeat(3) { yield() }
            assertEquals(Hydration.Detached, store.hydration.current, "an invalidated refresh moves nothing")
            assertEquals(listOf("seed"), store.items.value, "an invalidated refresh adopts nothing")
            assertEquals(Hydration.Detached, store.hydration.awaitSettled())
        }

    @Test fun aStaleRefreshThatFinishesAfterTheNextHydrateIsDiscarded() =
        runBlocking {
            val answers = List(2) { CompletableDeferred<Int>() }
            val store = StubbornStore(answers)
            try {
                store.hydration.hydrate(this)
                yield()
                store.hydration.invalidate()
                store.hydration.hydrate(this)
                yield()
                assertEquals(2, store.fetches)

                answers[0].complete(1) // the stale refresh, which ignored its cancellation
                repeat(3) { yield() }
                assertEquals(Hydration.Seeded, store.hydration.current, "the stale result is not adopted")
                assertEquals(0, store.remote.value)

                answers[1].complete(2)
                assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
                assertEquals(2, store.remote.value)
            } finally {
                // The refreshes ignore cancellation: a failed assertion must
                // not leave runBlocking waiting for them.
                answers.forEach { it.complete(0) }
            }
        }

    @Test fun stageInvalidateCommitsAndRollsBackWithTheCallersAction() =
        runBlocking {
            val store = FeedStore { listOf("a") }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            val rolledBack =
                store action {
                    hydration.stageInvalidate()
                    assertEquals(Hydration.Detached, hydration.current, "the action reads its own staged detach")
                    error("abort")
                }
            assertIs<TransactionResult.Error>(rolledBack)
            assertEquals(Hydration.Hydrated, store.hydration.current)

            store action {
                pinned mutate setOf("a")
                hydration.stageInvalidate()
            }
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(setOf("a"), store.pinned.value)
        }

    @Test fun stageInvalidateOutsideAnActionFailsTeachingInvalidate() {
        val store = FeedStore { listOf("a") }
        val thrown = assertFailsWith<IllegalStateException> { store.hydration.stageInvalidate() }
        assertTrue("hydrator.invalidate()" in thrown.message.orEmpty(), thrown.message)
    }

    @Test fun invalidateInsideAnActionIsASavepointOfIt() =
        runBlocking {
            val store = FeedStore { listOf("a") }
            val middleware = HydrationMiddlewareLog<FeedStore>()
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.middlewares(middleware)

            store action {
                hydration.invalidate()
                error("abort")
            }
            assertEquals(Hydration.Hydrated, store.hydration.current, "rolled back with the enclosing action")
            assertTrue("started HydrationInvalidate" in middleware.events(), middleware.events().toString())
        }

    @Test fun resetDetachesInsideItsTransactionAndTheNextHydrateSeedsAgain() =
        runBlocking {
            val store = FeedStore { listOf("fetched") }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            assertIs<TransactionResult.Success<Unit>>(store.reset())
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(emptyList(), store.items.value)

            store.hydration.hydrate(this)
            assertEquals(2, store.baseRuns)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
        }

    @Test fun aResetThatRollsBackLeavesTheHydrationAsItWas() =
        runBlocking {
            val store = FeedStore { listOf("fetched") }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            store action {
                reset()
                assertEquals(Hydration.Detached, hydration.current)
                error("abort")
            }
            assertEquals(Hydration.Hydrated, store.hydration.current)
        }

    @Test fun aSterileRestoreDoesNotDetach() =
        runBlocking {
            val store = FeedStore { listOf("fetched") }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            val snapshot = store.snapshot()

            store.restore(snapshot, RestorePolicy.Strict, sterile = true)
            assertEquals(emptyList(), store.items.value, "the Remote state was reset")
            assertEquals(Hydration.Hydrated, store.hydration.current)
        }

    @Test fun theHydratorsStateRefusesEveryStoreWriteNamingTheHydrator() {
        val store = FeedStore { listOf("a") }
        val state = store.hydration.state
        val attempts =
            listOf<() -> Unit>(
                { store { state mutate Hydration.Hydrated } },
                { store { state update { Hydration.Hydrated } } },
                { store { state bridge null } },
                { store { state observeFrom Observable { Disposable { } } } },
            )
        for (attempt in attempts) {
            val thrown = assertFailsWith<IllegalStateException> { attempt() }
            assertTrue("hydration phase of FeedStore's hydrator" in thrown.message.orEmpty(), thrown.message)
        }
        assertEquals(Hydration.Detached, store.hydration.current)
    }

    @Test fun noSnapshotRestoreOrListingSeesTheHydratorsState() =
        runBlocking {
            val store = FeedStore { listOf("a") }
            val detachedSnapshot = store.snapshot()
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            assertFalse(HYDRATION_STATE_NAME in store.snapshot().stateNames)
            assertFalse(store.properties.values.any { it === store.hydration.state })
            assertFailsWith<IllegalArgumentException> { store.snapshot()[store.hydration.state] }
            store.restore(detachedSnapshot)
            assertEquals(Hydration.Hydrated, store.hydration.current, "a restore leaves the hydration alone")
        }
}
