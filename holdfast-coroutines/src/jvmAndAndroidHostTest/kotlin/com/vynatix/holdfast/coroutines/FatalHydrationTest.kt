@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #61, hydration (HydrationEngine.kt, HydrationRefreshRun.kt,
// HydrationGate.kt, Hydrator.kt hydrateEach, HydrateAll.kt). A fatal error
// thrown by `base { }`, the fetch, `adopt { }`, a middleware, an observer, a
// derived state settled at the gate's release or the uncaughtObserverHandler
// is never reported and never turned into a TransactionException or a
// HydrateAllReport outcome: it propagates out of hydrate()/hydrateEach()/
// hydrateAll(), or out of the refresh's coroutine to its scope's
// CoroutineExceptionHandler. Whatever the hydration marked in flight is never
// left in flight with nothing running: awaitSettled() returns, and the next
// hydrate() retries.

/** A hydrated store whose seed, fetch and adoption a test passes in. */
private class FhStore(
    seed: FhStore.() -> Unit = { items mutate listOf("seed") },
    fetch: suspend () -> List<String> = { listOf("fresh") },
    take: FhStore.(List<String>) -> Unit = { items mutate it },
) : Store<FhStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val src by state { 0 }
    val hydration =
        hydrator {
            base { seed() }
            refresh { fetch() } adopt { fetched -> take(fetched) }
        }
}

/** A receiver with two hydrated children, materialized by first read. */
private class FhParent(
    firstSeed: FhStore.() -> Unit,
    secondSeed: FhStore.() -> Unit,
) : Store<FhParent>() {
    val first by store { FhStore(seed = firstSeed) }
    val second by store { FhStore(seed = secondSeed) }
}

/** Throws [armed] from `onTransactionCompleted` of the transactions named [id] while it is set. */
private class FatalOnCompleted<V : Store<V>>(
    private val id: String,
) : Middleware<V>() {
    @Volatile var armed: Throwable? = null

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        if (context.transaction.id == id) armed?.let { throw it }
    }
}

/** A scope whose context cannot even be read: launching on it throws [fatal]. */
private class UnreadableScope(
    private val fatal: Throwable,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext get() = throw fatal
}

/** Where the hydration settles, waiting at most 10 s: it must never hang. */
private suspend fun Hydrator<*>.settled(): Hydration = withTimeout(10.seconds) { awaitSettled() }

class FatalHydrationTest {
    @Test fun aBaseStackOverflowRollsTheSeedBackAndPropagatesRaw() =
        runBlocking<Unit> {
            val fatal = StackOverflowError()
            var armed = true
            val store = FhStore(seed = { if (armed) throw fatal else items mutate listOf("seed") })
            val reported = store.recordedReports()
            val log = HydrationMiddlewareLog<FhStore>()
            store.middlewares(log)

            val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(this) }
            armed = false

            assertSame(fatal, thrown, "raw, not a TransactionException")
            assertEquals(Hydration.Detached, store.hydration.current, "nothing was decided")
            assertTrue("error $SEED_ID" in log.events(), "the seed's middleware saw the error: ${log.events()}")
            assertEquals(emptyList(), store.items.value)
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertNothingInstalledOn(store)
            assertNothingLeftOnThisThread()
            // The gate released the store: a later hydrate takes it.
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.settled())
            assertEquals(listOf("fresh"), store.items.value)
        }

    @Test fun aNonFatalBaseFailureStillPropagatesAsTheSeedsFailure() =
        runBlocking<Unit> {
            val boom = IllegalStateException("base")
            val store = FhStore(seed = { throw boom })

            val thrown = assertPropagates<IllegalStateException> { store.hydration.hydrate(this) }

            assertSame(boom, thrown, "as before")
            assertEquals(Hydration.Detached, store.hydration.current)
        }

    @Test fun aSeedObserverStackOverflowStillLaunchesTheRefreshThenHydrateThrows() =
        runBlocking<Unit> {
            val fatal = StackOverflowError()
            val fetches = AtomicInteger()
            val store =
                FhStore(fetch = {
                    fetches.incrementAndGet()
                    listOf("fresh")
                })
            val reported = store.recordedReports()
            store.items effect { if (this == listOf("seed")) throw fatal }

            val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(this) }

            assertSame(fatal, thrown, "never a TransactionException")
            assertEquals(Hydration.Hydrated, store.hydration.settled(), "the seed applied and its refresh ran")
            assertEquals(listOf("fresh"), store.items.value)
            store.hydration.hydrate(this)
            assertEquals(1, fetches.get(), "Hydrated: nothing more to do")
            assertTrue(reported.isEmpty(), "never reported: $reported")
        }

    @Test fun aStackOverflowSettledAtTheGatesReleaseStillLaunchesTheDecidedRefresh() =
        runBlocking<Unit> {
            val fatal = StackOverflowError()
            val store =
                FhStore(seed = {
                    src mutate 1
                    items mutate listOf("seed")
                })
            val reported = store.recordedReports()
            var armed: Throwable? = fatal
            val mirror =
                store.derivedState(store.src) {
                    if (src.value == 1) armed?.let { throw it }
                    src.value
                }

            val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(this) }
            armed = null

            assertSame(fatal, thrown, "the gate's settle threw it, after the seed committed")
            assertTrue(store.hydration.current != Hydration.Detached, "the seed committed: ${store.hydration.current}")
            assertEquals(Hydration.Hydrated, store.hydration.settled(), "the refresh it decided on was launched")
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(0, mirror.value, "the recompute rolled back")
            assertTrue(reported.isEmpty(), "never reported: $reported")
            assertNothingInstalledOn(store)
            assertNothingLeftOnThisThread()
            mirror.dispose()
        }

    @Test fun aFetchStackOverflowIsSettledAsTheRefreshsFailureThenGoesToTheScopesHandler() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val fatal = StackOverflowError()
                val fetches = AtomicInteger()
                val store =
                    FhStore(fetch = {
                        if (fetches.incrementAndGet() == 1) throw fatal
                        listOf("fresh")
                    })
                val reported = store.recordedReports()

                store.hydration.hydrate(handled.scope)

                val settled = assertIs<Hydration.Failed>(store.hydration.settled(), "settled, not hung")
                assertSame(fatal, settled.cause.unrecovered())
                assertSame(fatal, handled.first(), "the refresh's coroutine failed with it")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                store.hydration.hydrate(handled.scope)
                assertEquals(Hydration.Hydrated, store.hydration.settled(), "the next hydrate retried")
                assertEquals(2, fetches.get())
            }
        }

    @Test fun anAdoptStackOverflowStrandsTheRefreshThenGoesToTheScopesHandler() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val fatal = StackOverflowError()
                var armed = true
                val store =
                    FhStore(take = { fetched ->
                        items mutate fetched
                        if (armed) throw fatal
                    })
                val reported = store.recordedReports()
                val log = HydrationMiddlewareLog<FhStore>()
                store.middlewares(log)

                store.hydration.hydrate(handled.scope)

                assertSame(fatal, handled.first(), "the refresh's coroutine failed with it")
                val settled = assertIs<Hydration.Failed>(store.hydration.settled(), "stranded, not hung")
                assertSame(fatal, settled.cause.unrecovered())
                assertEquals(Hydration.Seeded, store.hydration.current, "stranded: no Failed phase was committed")
                assertEquals(listOf("seed"), store.items.value, "the adoption rolled back")
                assertFalse("started $FAILURE_ID" in log.events(), "nothing recorded the failure: ${log.events()}")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                armed = false
                // The gate was released: the next hydrate takes it and retries.
                store.hydration.hydrate(handled.scope)
                assertEquals(Hydration.Hydrated, store.hydration.settled())
                assertTrue("started $RETRY_ID" in log.events(), log.events().toString())
                assertEquals(listOf("fresh"), store.items.value)
            }
        }

    @Test fun anAdoptMiddlewareStackOverflowStrandsTheRefreshThenGoesToTheScopesHandler() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val fatal = StackOverflowError()
                val store = FhStore()
                val reported = store.recordedReports()
                val rejecting = FatalOnCompleted<FhStore>(ADOPT_ID)
                rejecting.armed = fatal
                store.middlewares(rejecting)

                store.hydration.hydrate(handled.scope)

                assertSame(fatal, handled.first())
                assertIs<Hydration.Failed>(store.hydration.settled(), "stranded, not hung")
                assertEquals(Hydration.Seeded, store.hydration.current)
                assertEquals(listOf("seed"), store.items.value, "the adoption rolled back")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                rejecting.armed = null
                store.hydration.hydrate(handled.scope)
                assertEquals(Hydration.Hydrated, store.hydration.settled())
            }
        }

    @Test fun aHandlerStackOverflowForAStrandedRefreshGoesToTheScopesHandlerOnceStranded() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val fatal = StackOverflowError()
                val boom = IllegalStateException("offline")
                val fetches = AtomicInteger()
                val store =
                    FhStore(fetch = {
                        if (fetches.incrementAndGet() == 1) throw boom
                        listOf("fresh")
                    })
                var rejecting = true
                store.middlewares(HydrationMiddlewareLog { rejecting && it == FAILURE_ID })
                val handlerSaw = CopyOnWriteArrayList<Throwable>()
                store.uncaughtObserverHandler = {
                    handlerSaw += it
                    throw fatal
                }

                store.hydration.hydrate(handled.scope)

                assertSame(fatal, handled.first(), "the handler's StackOverflowError failed the refresh's coroutine")
                val settled = assertIs<Hydration.Failed>(store.hydration.settled(), "stranded before the throw")
                assertSame(boom, settled.cause.unrecovered())
                assertTrue(
                    handlerSaw.single() is IllegalStateException,
                    "the handler was handed the refused record: $handlerSaw",
                )
                store.uncaughtObserverHandler = null
                rejecting = false
                store.hydration.hydrate(handled.scope)
                assertEquals(Hydration.Hydrated, store.hydration.settled(), "the next hydrate retried")
                assertEquals(2, fetches.get())
            }
        }

    @Test fun aLaunchStackOverflowIsSettledAsTheRefreshsFailureThenHydrateThrows() =
        runBlocking<Unit> {
            val fatal = StackOverflowError()
            val store = FhStore()
            val reported = store.recordedReports()

            val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(UnreadableScope(fatal)) }

            assertSame(fatal, thrown)
            val settled = assertIs<Hydration.Failed>(store.hydration.settled(), "settled, not hung")
            assertSame(fatal, settled.cause.unrecovered())
            assertTrue(reported.isEmpty(), "never reported: $reported")
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.settled())
        }

    @Test fun hydrateEachStopsAtAStackOverflowCarryingTheFailureCollectedSoFar() =
        runBlocking<Unit> {
            val boom = IllegalStateException("base 1")
            val fatal = StackOverflowError()
            var thirdRan = false
            val first = FhStore(seed = { throw boom })
            val second = FhStore(seed = { throw fatal })
            val third = FhStore(seed = { thirdRan = true })
            val reported = listOf(first, second, third).map { it.recordedReports() }

            val thrown =
                assertPropagates<StackOverflowError> {
                    hydrateEach(first.hydration, second.hydration, third.hydration)
                }

            assertSame(fatal, thrown, "the fatal error is primary")
            assertTrue(
                thrown.suppressedExceptions.any { it.unrecovered() === boom },
                "the failure collected so far rides along as suppressed: ${thrown.suppressedExceptions}",
            )
            assertFalse(thirdRan, "the third hydrator never ran")
            assertEquals(Hydration.Detached, third.hydration.current)
            assertTrue(reported.all { it.isEmpty() }, "never reported: $reported")
        }

    @Test fun hydrateAllPropagatesAStackOverflowInsteadOfReportingIt() =
        runBlocking<Unit> {
            val fatal = StackOverflowError()
            val parent =
                FhParent(
                    firstSeed = { throw fatal },
                    secondSeed = { items mutate listOf("seed") },
                )
            // First read in this order: `first` is constructed, and listed, first.
            val first = parent.first
            val second = parent.second
            val reported = listOf(parent, first, second).map { it.recordedReports() }

            val thrown = assertPropagates<StackOverflowError> { parent.tree.hydrateAll() }

            assertSame(fatal, thrown, "never a HydrateAllReport outcome")
            assertEquals(Hydration.Detached, first.hydration.current)
            assertEquals(Hydration.Detached, second.hydration.current, "the later child was never hydrated")
            assertTrue(reported.all { it.isEmpty() }, "never reported: $reported")
        }

    @Test fun hydrateAllStillReportsANonFatalFailure() =
        runBlocking<Unit> {
            val boom = IllegalStateException("base")
            val parent =
                FhParent(
                    firstSeed = { throw boom },
                    secondSeed = { items mutate listOf("seed") },
                )
            val first = parent.first
            val second = parent.second

            val report = parent.tree.hydrateAll(scope = this)

            val failed = report.failed.single()
            assertSame(first, failed.store)
            assertEquals(Hydration.Hydrated, second.hydration.current, "the failure did not stop the rest")
        }
}
