@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock stopped at [at]. */
private class FixedClock(
    private val at: Instant,
) : Clock {
    override fun now(): Instant = at
}

/** A clock that fails every read: bound on a store, it proves nothing read the store's clock. */
private object UnreadableClock : Clock {
    override fun now(): Instant = error("the store's clock was read")
}

/** Stamps when it was seeded and when it adopted, through the store's clock. */
private class Stamped : Store<Stamped>() {
    val seededAt by state { Instant.DISTANT_PAST }
    val adoptedAt by state(tags = setOf(StateTag.Remote)) { Instant.DISTANT_PAST }
    val hydration =
        hydrator {
            base { seededAt mutate clock.now() }
            refresh { it.clock.now() } adopt { fetchedAt -> adoptedAt mutate fetchedAt }
        }
}

/** Reads no time at all. */
private class Timeless : Store<Timeless>() {
    val n by state(tags = setOf(StateTag.Remote)) { 0 }
    val hydration = hydrator { refresh { 1 } adopt { n mutate it } }
}

/**
 * Time and hydration (issue #20, R8 with R10): `base`, `refresh` and `adopt`
 * read time through the store's clock, so a bound fixed clock makes them
 * deterministic; the hydrator itself never reads that clock — its gate backs
 * off in coroutine time — so a fixed (or unreadable) clock never wedges it.
 */
class HydrationClockTest {
    @Test fun baseRefreshAndAdoptStampTheStoresBoundClock() =
        runBlocking {
            val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val store = Stamped()
            store.bindClock(FixedClock(at))
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(at, store.seededAt.value)
            assertEquals(at, store.adoptedAt.value)
        }

    @Test fun theHydratorNeverReadsTheStoresClockEvenWhileItWaitsForTheStore() =
        runBlocking {
            val store = Timeless()
            store.bindClock(UnreadableClock)
            val release = CompletableDeferred<Unit>()
            // A suspendAction holds the store, so the gate backs off until it is done.
            val holder = launch(start = CoroutineStart.UNDISPATCHED) { store.suspendAction { release.await() } }
            val hydrating = launch { store.hydration.hydrate(this@runBlocking) }
            repeat(5) { yield() }
            release.complete(Unit)
            joinAll(holder, hydrating)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(1, store.n.value)
        }
}
