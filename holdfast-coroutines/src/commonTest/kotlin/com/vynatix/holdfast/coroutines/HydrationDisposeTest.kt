@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A store whose refresh ignores cancellation, so it completes after a dispose. */
private class Unstoppable(
    private val answer: CompletableDeferred<Int>,
) : Store<Unstoppable>() {
    val remote by state(tags = setOf(StateTag.Remote)) { 0 }
    val hydration = hydrator { refresh { withContext(NonCancellable) { answer.await() } } adopt { remote mutate it } }
}

/**
 * Disposing a store mid-hydration (issue #20, R8): the refresh in flight is
 * cancelled, and one that finishes anyway is dropped — nothing adopted,
 * nothing reported; a waiter in `awaitSettled()` is released with the
 * disposed-store error, and the hydrator's state keeps its last value.
 */
class HydrationDisposeTest {
    @Test fun disposeCancelsTheRefreshInFlightAndReleasesAwaitSettled() =
        runBlocking {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.hydration.hydrate(this)
            yield()
            assertEquals(1, remote.fetches)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { runCatching { store.hydration.awaitSettled() } }

            store.dispose()
            val released = waiter.await().exceptionOrNull()
            assertTrue(released is IllegalStateException && "disposed" in released.message.orEmpty(), "$released")
            yield() // the cancelled fetch resumes
            assertTrue(remote.cancelled, "dispose() cancels the refresh in flight")
            remote.release.complete(listOf("late"))
            repeat(3) { yield() }
            assertEquals(Hydration.Seeded, store.hydration.current, "the state keeps its last value")
            assertEquals(emptyList(), reported)
        }

    @Test fun aDisposeBeforeTheRefreshIsTrackedStillCancelsIt() =
        runBlocking {
            // Unconfined: the ATOMIC launch runs the fetch inline, so its
            // dispose() lands before hydrate() has recorded the job.
            val parked = CompletableDeferred<List<String>>()
            var cancelled = false
            lateinit var store: FeedStore
            store =
                FeedStore {
                    store.dispose()
                    try {
                        parked.await()
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            val scope = CoroutineScope(Dispatchers.Unconfined + Job())
            try {
                store.hydration.hydrate(scope)
                withTimeout(5.seconds) { while (!cancelled) yield() }
                assertEquals(Hydration.Seeded, store.hydration.current, "the state keeps its last value")
                assertEquals(emptyList(), reported)
            } finally {
                scope.cancel()
            }
        }

    @Test fun aRefreshThatFinishesAfterTheDisposeIsDroppedSilently() =
        runBlocking {
            val answer = CompletableDeferred<Int>()
            val store = Unstoppable(answer)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            val remote = store.remote // read before dispose, which refuses delegate reads
            store.hydration.hydrate(this)
            yield()
            store.dispose()
            answer.complete(1)
            repeat(3) { yield() }
            assertEquals(Hydration.Seeded, store.hydration.current)
            assertEquals(0, remote.value)
            assertEquals(emptyList(), reported)
        }

    @Test fun afterDisposeEveryHydrationEntrypointThrowsAndTheStateStaysReadable() =
        runBlocking {
            val store = FeedStore { listOf("a") }
            val hydration = store.hydration
            store.dispose()
            assertFailsWith<IllegalStateException> { hydration.hydrate(this) }
            assertFailsWith<IllegalStateException> { hydration.invalidate() }
            assertFailsWith<IllegalStateException> { hydration.stageInvalidate() }
            assertFailsWith<IllegalStateException> { hydration.awaitSettled() }
            assertEquals(Hydration.Detached, hydration.current)
            assertEquals(Hydration.Detached, hydration.state.value)
        }
}
