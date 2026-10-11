@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A store with no hydrator, for the lookups. */
private class Plain : Store<Plain>() {
    val n by state { 0 }
}

/**
 * A store whose state's initializer completes [answer], so what awaits it on
 * `Dispatchers.Unconfined` resumes inside that initializer.
 */
private class Completer(
    answer: CompletableDeferred<List<String>>,
) : Store<Completer>() {
    val done by state { answer.complete(listOf("inside")) }
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

            val job = Job() // unparented, so runBlocking never waits on it
            val scope = CoroutineScope(coroutineContext + job)
            val thrown = assertFailsWith<IllegalStateException> { store.hydration.hydrate(scope) }
            assertEquals("rejected $SEED_ID", thrown.message)
            // Before anything suspends: on this one-thread loop a refresh
            // launched regardless of the commit would still be queued, so
            // still listed; after a yield it would have run and finished.
            assertTrue(job.children.none(), "no refresh job was launched")
            repeat(3) { yield() }
            assertEquals(0, remote.fetches, "no refresh was launched")
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(emptyList(), store.items.value, "base's writes rolled back with the seed")
            assertEquals(1, store.baseRuns)
            assertEquals(listOf("started $SEED_ID", "completed $SEED_ID", "error $SEED_ID"), middleware.events())
            job.cancel()
        }

    @Test fun aRefreshOnAnAlreadyCancelledScopeStillRunsAndFailsWithTheCancellation() =
        runBlocking {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val dead = CoroutineScope(coroutineContext + Job()).apply { cancel() }

            store.hydration.hydrate(dead)
            assertEquals(Hydration.Seeded, store.hydration.current)
            val settled = withTimeout(5.seconds) { store.hydration.awaitSettled() }
            assertIs<CancellationException>((settled as Hydration.Failed).cause)
            assertEquals(1, remote.fetches, "the refresh ran, into its scope's cancellation")

            remote.release.complete(listOf("fresh"))
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(1, store.baseRuns, "the retry refreshes only")
            assertEquals(2, remote.fetches)
        }

    @Test fun aScopeCancelledBeforeTheRefreshStartsFailsTheHydration() = cancelledWhileRefreshing(started = false)

    @Test fun aScopeCancelledWhileTheRefreshFetchesFailsTheHydration() = cancelledWhileRefreshing(started = true)

    /** Cancel the refresh's scope once it is launched — queued, or [started] and parked in its fetch. */
    private fun cancelledWhileRefreshing(started: Boolean) =
        runBlocking {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val job = Job()
            val scope = CoroutineScope(coroutineContext + job)

            store.hydration.hydrate(scope)
            if (started) yield() // the refresh waits in its fetch
            job.cancel()
            val settled = withTimeout(5.seconds) { store.hydration.awaitSettled() }
            assertIs<CancellationException>((settled as Hydration.Failed).cause)

            remote.release.complete(listOf("fresh"))
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(1, store.baseRuns, "the retry refreshes only")
        }

    @Test fun aCallerCancelledAfterTheSeedCommittedStillLaunchesTheRefresh() =
        runBlocking {
            lateinit var caller: Job
            var bases = 0
            var fetches = 0
            val store =
                Specced {
                    base {
                        bases++
                        caller.cancel() // inside the seed, which commits regardless
                    }
                    refresh {
                        fetches++
                        7
                    } adopt { remote mutate it }
                }
            // The refresh runs on this live scope, not on the caller's.
            caller = launch(start = CoroutineStart.LAZY) { store.hydration.hydrate(this@runBlocking) }
            caller.start()
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(7, store.remote.value)
            assertEquals(1, bases)
            assertEquals(1, fetches)
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

    @Test fun aFailureTheMiddlewareRejectsTooStrandsTheRefreshUntilTheNextHydrateRetriesIt() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val reported = mutableListOf<Throwable>()
            val lockedAtReport = mutableListOf<Boolean>()
            store.uncaughtObserverHandler = {
                reported += it
                lockedAtReport += ensureSerializer(store).mutex.isLocked
            }
            var rejecting = true
            val middleware = HydrationMiddlewareLog<FeedStore> { rejecting && (it == ADOPT_ID || it == FAILURE_ID) }
            store.middlewares(middleware)

            store.hydration.hydrate(this)
            val settled = withTimeout(5.seconds) { store.hydration.awaitSettled() }
            assertEquals("rejected $ADOPT_ID", (settled as Hydration.Failed).cause.message, "what went unrecorded")
            assertEquals(Hydration.Seeded, store.hydration.current, "no transaction could move the phase")
            withTimeout(5.seconds) { while (reported.isEmpty()) yield() }
            assertEquals(listOf("rejected $FAILURE_ID"), reported.map { it.message })
            assertEquals(listOf(false), lockedAtReport, "reported once the gate released the store")
            assertEquals(listOf("seed"), store.items.value)

            rejecting = false
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(2, remote.fetches, "the stranded refresh was retried")
            assertEquals(1, store.baseRuns, "a retry refreshes only")
            assertTrue("started $RETRY_ID" in middleware.events(), middleware.events().toString())
        }

    @Test fun aHandlerThatThrowsForAnUnrecordedOutcomeIsIgnoredAndFailsNothing() =
        runBlocking {
            val remote = FakeRemote<List<String>> { error("down") }
            val store = FeedStore(remote::fetch)
            store.middlewares(HydrationMiddlewareLog { it == FAILURE_ID })
            store.uncaughtObserverHandler = { throw it }
            val escaped = mutableListOf<Throwable>()
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + supervisor + CoroutineExceptionHandler { _, e -> escaped += e })
            try {
                store.hydration.hydrate(scope)
                val refresh = supervisor.children.single()
                val settled = withTimeout(5.seconds) { store.hydration.awaitSettled() }
                assertEquals("down", (settled as Hydration.Failed).cause.message)
                refresh.join()
                assertFalse(refresh.isCancelled, "the refresh completed normally")
                assertEquals(emptyList(), escaped, "nothing reached the scope's exception handler")
                assertEquals(Hydration.Seeded, store.hydration.current, "stranded")
            } finally {
                supervisor.cancel()
            }
        }

    @Test fun aRefreshTheScopeCannotLaunchFailsTheHydrationAndTheNextHydrateRetriesIt() =
        runBlocking {
            val remote = FakeRemote { listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val rejecting =
                CoroutineScope(
                    object : CoroutineDispatcher() {
                        override fun dispatch(
                            context: CoroutineContext,
                            block: Runnable,
                        ): Unit = throw IllegalStateException("rejected")
                    },
                )
            try {
                val thrown = assertFails { store.hydration.hydrate(rejecting) }
                // kotlinx wraps a dispatcher's failure (its internal DispatchException).
                assertTrue(generateSequence(thrown) { it.cause }.any { it.message == "rejected" }, "$thrown")
                val settled = withTimeout(5.seconds) { store.hydration.awaitSettled() }
                assertSame(thrown, (settled as Hydration.Failed).cause)
                assertEquals(0, remote.fetches)

                store.hydration.hydrate(this)
                assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
                assertEquals(listOf("fresh"), store.items.value)
                assertEquals(1, store.baseRuns, "a retry refreshes only")
            } finally {
                rejecting.cancel() // the ATOMIC child kotlinx created never runs, so never completes
            }
        }

    @Test fun aSettleThatThrowsStrandsTheRefreshAndReportsWhatItThrew() =
        runBlocking {
            val answer = CompletableDeferred<List<String>>()
            val remote = FakeRemote { answer.await() }
            val store = FeedStore(remote::fetch)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            val middleware = HydrationMiddlewareLog<FeedStore>()
            store.middlewares(middleware)
            val unconfined = CoroutineScope(Dispatchers.Unconfined + Job())
            try {
                store.hydration.hydrate(unconfined) // the refresh runs at once, up to its fetch
                assertEquals(1, remote.fetches)
                // The refresh resumes inline, inside another store's state
                // initializer, where its adoption may not open a transaction.
                assertTrue(Completer(answer).done.value)
                val settled = withTimeout(5.seconds) { store.hydration.awaitSettled() }
                val cause = assertIs<IllegalStateException>((settled as Hydration.Failed).cause)
                assertTrue("initializer of Completer.done" in cause.message.orEmpty(), cause.message)
                assertEquals(listOf<Throwable>(cause), reported)
                assertEquals(Hydration.Seeded, store.hydration.current, "stranded: nothing could move the phase")

                store.hydration.hydrate(this)
                assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
                assertEquals(listOf("inside"), store.items.value)
                assertTrue("started $RETRY_ID" in middleware.events(), middleware.events().toString())
                assertEquals(1, store.baseRuns, "a retry refreshes only")
            } finally {
                unconfined.cancel()
            }
        }

    @Test fun hydrateRunsTheRefreshOnTheStoresScopeByDefault() =
        runBlocking {
            val ranOn = atomic<String?>(null)
            val store =
                FeedStore {
                    ranOn.value = currentCoroutineContext()[CoroutineName]?.name
                    listOf("a")
                }
            store.bindToScope(this + CoroutineName("store-scope"))
            store.hydration.hydrate() // no argument: the store's scope
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals("store-scope", ranOn.value, "refreshed on store.scope, never Store.defaultScope")
        }

    @Test fun aStoreHasOneHydratorFoundAgainByHydratorOrNull() {
        val store = FeedStore { emptyList() }
        assertSame(store.hydration, store.hydratorOrNull())
        assertNull(Plain().hydratorOrNull())
        // Any Store<*>: a walk over stores of different types needs no cast.
        val stores: List<Store<*>> = listOf(store, Plain())
        assertEquals(listOf<Hydrator<*>?>(store.hydration, null), stores.map { it.hydratorOrNull() })

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
            val seeded = mutableListOf<String>()

            fun specced(
                name: String,
                failure: String? = null,
            ) = Specced {
                base {
                    seeded += name
                    if (failure != null) error(failure)
                }
                refresh { 1 } adopt { remote mutate it }
            }
            val a = specced("a")
            val b = specced("b", failure = "first failure")
            val c = specced("c")
            val d = specced("d", failure = "second failure")
            listOf(a, b, c, d).forEach { it.bindToScope(this) }

            val thrown = assertFailsWith<IllegalStateException> { hydrateEach(a.hydration, b.hydration, c.hydration, d.hydration) }
            assertEquals("first failure", thrown.message)
            assertEquals(listOf("second failure"), thrown.suppressedExceptions.map { it.message })
            assertEquals(listOf("a", "b", "c", "d"), seeded, "in order, past each failure")
            assertEquals(Hydration.Hydrated, a.hydration.awaitSettled())
            assertEquals(Hydration.Hydrated, c.hydration.awaitSettled(), "a failure does not stop the rest")
            assertEquals(Hydration.Detached, b.hydration.current)
            assertEquals(Hydration.Detached, d.hydration.current)
        }

    @Test fun hydrateEachStopsAtOnceWhenItsCallerIsCancelled() =
        runBlocking {
            var caller: Job? = null
            val x =
                Specced {
                    base { error("earlier") }
                    refresh { 1 } adopt { remote mutate it }
                }
            val y =
                Specced {
                    base { checkNotNull(caller).cancel() } // inside the seed, which commits regardless
                    refresh { 2 } adopt { remote mutate it }
                }
            val z = FeedStore { listOf("z") }
            x.bindToScope(this)
            y.bindToScope(this)
            z.bindToScope(this)

            var caught: Throwable? = null
            val job =
                launch(start = CoroutineStart.LAZY) {
                    caught = runCatching { hydrateEach(x.hydration, y.hydration, z.hydration) }.exceptionOrNull()
                }
            caller = job
            job.start()
            job.join()
            assertIs<CancellationException>(caught, "the cancellation, not the earlier failure: $caught")
            assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { y.hydration.awaitSettled() }, "y's seed committed")
            assertEquals(Hydration.Detached, z.hydration.current, "nothing after the cancellation")
            assertEquals(0, z.baseRuns)
            assertEquals(Hydration.Detached, x.hydration.current)
        }

    @Test fun awaitSettledReturnsAtOnceOnADetachedHydrator() =
        runBlocking {
            val store = FeedStore { listOf("a") }
            assertEquals(Hydration.Detached, store.hydration.awaitSettled())
        }
}
