@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A store with no hydrator, for the lookups. */
private class Plain : Store<Plain>() {
    val n by state { 0 }
}

/** A store whose spec is built from what a test passes. */
private class Specced(
    spec: HydrationSpec<Specced>.() -> Unit,
) : Store<Specced>() {
    val remote by state(tags = setOf(StateTag.Remote)) { 0 }
    val hydration = hydrator(spec)
}

/**
 * The hydration lifecycle (issue #20, R8) with a fake remote: all four phases,
 * the idempotence guarantee, a retry that refreshes only, a seed that rolls
 * back launching nothing, and middleware seeing every hydration transaction.
 */
class HydrationLifecycleTest {
    @Test fun hydrateDrivesDetachedSeededHydratedAndAdoptsWhatTheRemoteSent() =
        runBlocking {
            val sent = CompletableDeferred<List<String>>()
            val remote = FakeRemote { sent.await() }
            val store = FeedStore(remote::fetch)
            val seen = mutableListOf<Hydration>()
            store.hydration.state effect { seen += this }
            assertEquals(Hydration.Detached, store.hydration.current)

            store.hydration.hydrate(this)
            // The seed committed before hydrate() returned: base ran, and the
            // phase moved to Seeded in the same transaction.
            assertEquals(Hydration.Seeded, store.hydration.current)
            assertEquals(listOf("seed"), store.items.value)
            assertEquals(1, store.baseRuns)

            sent.complete(listOf("a", "b"))
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(listOf("a", "b"), store.items.value)
            assertEquals(1, remote.fetches)
            assertEquals(listOf(Hydration.Detached, Hydration.Seeded, Hydration.Hydrated), seen)
        }

    @Test fun aFailingRefreshFailsWithItsCauseAndARetryRefreshesWithoutRunningBaseAgain() =
        runBlocking {
            val offline = IllegalStateException("offline")
            val remote = FakeRemote { call -> if (call == 1) throw offline else listOf("fresh") }
            val store = FeedStore(remote::fetch)

            store.hydration.hydrate(this)
            assertEquals(Hydration.Failed(offline), store.hydration.awaitSettled())
            assertSame(offline, (store.hydration.current as Hydration.Failed).cause)
            assertEquals(listOf("seed"), store.items.value, "a failed refresh keeps what base seeded")

            store.hydration.hydrate(this)
            assertEquals(Hydration.Seeded, store.hydration.current, "the retry moves back to Seeded")
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(1, store.baseRuns, "a retry refreshes only")
            assertEquals(2, remote.fetches)
        }

    @Test fun hydrateWhileSeededOrHydratedDoesNothing() =
        runBlocking {
            val sent = CompletableDeferred<List<String>>()
            val remote = FakeRemote { sent.await() }
            val store = FeedStore(remote::fetch)

            store.hydration.hydrate(this)
            yield()
            store.hydration.hydrate(this) // Seeded: in flight
            sent.complete(listOf("a"))
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())

            store.hydration.hydrate(this) // Hydrated
            yield()
            assertEquals(Hydration.Hydrated, store.hydration.current)
            assertEquals(1, store.baseRuns, "no base re-run")
            assertEquals(1, remote.fetches, "no refresh")
        }

    @Test fun aSeedTheMiddlewareRollsBackLaunchesNothingAndHydrateThrows() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val middleware = HydrationMiddlewareLog<FeedStore> { it == SEED_ID }
            store.middlewares(middleware)

            val thrown = assertFailsWith<IllegalStateException> { store.hydration.hydrate(this) }
            assertEquals("rejected $SEED_ID", thrown.message)
            repeat(3) { yield() }
            assertEquals(0, remote.fetches, "no refresh was launched")
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(emptyList(), store.items.value, "base's writes rolled back with the seed")
            assertEquals(1, store.baseRuns)
            assertEquals(listOf("started $SEED_ID", "completed $SEED_ID", "error $SEED_ID"), middleware.events())
        }

    @Test fun aThrowingBaseRollsTheSeedBackAndTheNextHydrateSeedsAgain() =
        runBlocking {
            var failBase = true
            val store =
                Specced {
                    base {
                        remote mutate 1
                        check(!failBase) { "no seed data" }
                    }
                    refresh { 2 } adopt { remote mutate it }
                }
            assertEquals("no seed data", assertFailsWith<IllegalStateException> { store.hydration.hydrate(this) }.message)
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(0, store.remote.value)

            failBase = false
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(2, store.remote.value)
        }

    @Test fun middlewareSeesTheSeedTheAdoptionTheRetryAndTheFailure() =
        runBlocking {
            val remote = FakeRemote { call -> if (call == 1) error("offline") else listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val middleware = HydrationMiddlewareLog<FeedStore>()
            store.middlewares(middleware)

            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            assertEquals(
                listOf(
                    "started $SEED_ID",
                    "completed $SEED_ID",
                    "started $FAILURE_ID",
                    "completed $FAILURE_ID",
                    "started $RETRY_ID",
                    "completed $RETRY_ID",
                    "started $ADOPT_ID",
                    "started Adopt",
                    "completed Adopt",
                    "completed $ADOPT_ID",
                ),
                middleware.events(),
            )
        }

    @Test fun anAdoptionTheMiddlewareRejectsIsRecordedAsAFailureOfItsOwn() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            store.middlewares(HydrationMiddlewareLog { it == ADOPT_ID })

            store.hydration.hydrate(this)
            val failed = store.hydration.awaitSettled()
            assertEquals("rejected $ADOPT_ID", (failed as Hydration.Failed).cause.message)
            assertEquals(listOf("seed"), store.items.value, "the adoption rolled back")
        }

    @Test fun aStoreHasOneHydratorFoundAgainByHydratorOrNull() {
        val store = FeedStore { emptyList() }
        assertSame(store.hydration, store.hydratorOrNull())
        assertNull(Plain().hydratorOrNull())

        val second = assertFailsWith<IllegalStateException> { store.hydrator { refresh { 0 } adopt { } } }
        assertTrue("already has a hydrator" in second.message.orEmpty(), second.message)
        assertSame(store.hydration, store.hydratorOrNull(), "the failed second hydrator attached nothing")
    }

    @Test fun anIncompleteOrRepeatedSpecFailsWhereTheHydratorIsDeclared() {
        val noRefresh = assertFailsWith<IllegalStateException> { Specced { base { } } }
        assertTrue("needs refresh" in noRefresh.message.orEmpty(), noRefresh.message)
        val noAdopt = assertFailsWith<IllegalStateException> { Specced { refresh { 1 } } }
        assertTrue("without adopt" in noAdopt.message.orEmpty(), noAdopt.message)
        val twoBases =
            assertFailsWith<IllegalStateException> {
                Specced {
                    base { }
                    base { }
                    refresh { 1 } adopt { }
                }
            }
        assertTrue("base { } twice" in twoBases.message.orEmpty(), twoBases.message)
        val twoAdopts =
            assertFailsWith<IllegalStateException> {
                Specced {
                    val fetching = refresh { 1 }
                    fetching adopt { }
                    fetching adopt { }
                }
            }
        assertTrue("adopt { } twice" in twoAdopts.message.orEmpty(), twoAdopts.message)
    }

    @Test fun hydrateEachSeedsEveryStoreInOrderAndThrowsTheFirstFailureAfterAll() =
        runBlocking {
            val first = FeedStore { listOf("a") }
            val failing =
                Specced {
                    base { error("no seed data") }
                    refresh { 1 } adopt { remote mutate it }
                }
            val last = FeedStore { listOf("c") }
            first.bindToScope(this)
            failing.bindToScope(this)
            last.bindToScope(this)

            val thrown =
                assertFailsWith<IllegalStateException> { hydrateEach(first.hydration, failing.hydration, last.hydration) }
            assertEquals("no seed data", thrown.message)
            assertEquals(Hydration.Hydrated, first.hydration.awaitSettled())
            assertEquals(Hydration.Detached, failing.hydration.current)
            assertEquals(Hydration.Hydrated, last.hydration.awaitSettled(), "a failure does not stop the rest")
            assertEquals(listOf("c"), last.items.value)
        }

    @Test fun awaitSettledReturnsAtOnceOnADetachedHydrator() =
        runBlocking {
            val store = FeedStore { listOf("a") }
            assertEquals(Hydration.Detached, store.hydration.awaitSettled())
        }
}
