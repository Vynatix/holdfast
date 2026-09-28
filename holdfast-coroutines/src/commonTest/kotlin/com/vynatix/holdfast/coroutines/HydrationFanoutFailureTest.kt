@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.TransactionException
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.reset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A hydration transaction whose commit applied but whose fanout an observer
 * then ended, through a rethrowing `uncaughtObserverHandler` (issue #20, R8):
 * the hydrator's own bookkeeping still saw the phase it committed — core
 * notifies sealed states first — so a seed or retry still launches its
 * refresh (then `hydrate()` throws), an adoption still settles, and a detach
 * still cancels the refresh it abandoned.
 */
class HydrationFanoutFailureTest {
    @Test fun aSeedWhoseHydrationObserverThrowsStillLaunchesTheRefreshThenHydrateThrows() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            store.uncaughtObserverHandler = { throw it }
            store.hydration.state effect { if (this == Hydration.Seeded) error("ui") }

            val thrown = assertFailsWith<TransactionException> { store.hydration.hydrate(this) }
            assertEquals("ui", thrown.cause?.message)
            assertEquals(Hydration.Seeded, store.hydration.current, "the seed applied")
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(1, remote.fetches)
            assertEquals(1, store.baseRuns)
        }

    @Test fun aSeedWhoseObserverOfAStateBaseWroteThrowsStillLaunchesTheRefreshThenHydrateThrows() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            store.uncaughtObserverHandler = { throw it }
            // `items` is staged before the phase: without the sealed states
            // fanning out first, it would end the fanout before the hydrator's
            // own observer of the phase ran.
            store.items effect { if (this == listOf("seed")) error("ui") }

            val thrown = assertFailsWith<TransactionException> { store.hydration.hydrate(this) }
            assertEquals("ui", thrown.cause?.message)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(1, remote.fetches)
        }

    @Test fun aRetryWhoseFanoutFailsStillRefreshes() =
        runBlocking {
            val remote = FakeRemote { call -> if (call == 1) error("offline") else listOf("fresh") }
            val store = FeedStore(remote::fetch)
            store.hydration.hydrate(this)
            assertIs<Hydration.Failed>(store.hydration.awaitSettled())
            store.uncaughtObserverHandler = { throw it }
            store.hydration.state effect { if (this == Hydration.Seeded) error("ui") }

            assertFailsWith<TransactionException> { store.hydration.hydrate(this) }
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(2, remote.fetches)
            assertEquals(1, store.baseRuns, "a retry refreshes only")
        }

    @Test fun anAdoptionWhoseObserverOfARemoteStateThrowsStillSettlesHydrated() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val middleware = HydrationMiddlewareLog<FeedStore>()
            store.middlewares(middleware)
            store.uncaughtObserverHandler = { throw it }
            // Staged by adopt, so before the phase.
            store.items effect { if (this == listOf("fresh")) error("ui") }

            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(Hydration.Hydrated, store.hydration.current)
            assertEquals(listOf("fresh"), store.items.value)
            assertTrue(
                middleware.events().none { it.endsWith(FAILURE_ID) },
                "the applied adoption left nothing to record: ${middleware.events()}",
            )
            store.hydration.hydrate(this)
            assertEquals(1, remote.fetches, "Hydrated: nothing more to do")
        }

    @Test fun aFailureRecordWhoseFanoutFailsIsRecordedNotStranded() =
        runBlocking {
            val remote = FakeRemote { call -> if (call == 1) error("offline") else listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val middleware = HydrationMiddlewareLog<FeedStore>()
            store.middlewares(middleware)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = {
                reported += it
                throw it
            }
            store.hydration.state effect { if (this is Hydration.Failed) error("ui") }

            store.hydration.hydrate(this)
            assertEquals("offline", (withTimeout(5.seconds) { store.hydration.awaitSettled() } as Hydration.Failed).cause.message)
            assertIs<Hydration.Failed>(store.hydration.current, "the record applied")
            assertEquals(listOf("ui"), reported.map { it.message }, "reported once, by the fanout: nothing was stranded")

            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertTrue("started $RETRY_ID" in middleware.events(), middleware.events().toString())
        }

    @Test fun aResetWhoseObserverThrowsStillCancelsTheRefreshInFlight() =
        runBlocking {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            store.hydration.hydrate(this)
            yield() // the refresh waits in its fetch
            var armed = false
            // `items` resets first; the detach is staged after it.
            store.items effect { if (armed && isEmpty()) error("ui") }
            store.uncaughtObserverHandler = { throw it }
            armed = true

            val result = store.reset()
            val failure = assertIs<TransactionException>((result as TransactionResult.Error).exception)
            assertEquals("ui", failure.cause?.message)
            assertEquals(Hydration.Detached, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            yield()
            assertTrue(remote.cancelled, "the reset cancelled the refresh it abandoned")
            remote.release.complete(listOf("late"))
            repeat(3) { yield() }
            assertEquals(emptyList(), store.items.value, "nothing adopted")
            assertEquals(Hydration.Detached, store.hydration.current)
        }
}
