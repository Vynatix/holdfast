@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Single flight (issue #20, R8 acceptance): concurrent `hydrate()` calls —
 * from threads released together — seed once and refresh once, and on a
 * failed hydration they retry once, with the in-flight mark committed by the
 * very transaction that decides to retry (R8's guard guarantee).
 */
class HydrationSingleFlightTest {
    /** Run [calls] on [threads] threads released together by a barrier; rethrow the first failure. */
    private fun together(
        threads: Int,
        call: () -> Unit,
    ) {
        val barrier = CyclicBarrier(threads)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers =
            List(threads) {
                Thread {
                    runCatching {
                        barrier.await()
                        call()
                    }.onFailure { failures += it }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        failures.firstOrNull()?.let { throw it }
    }

    @Test fun twoConcurrentHydrateCallsSeedOnceAndRefreshOnce() =
        hydrationWatchdog(60, "two concurrent hydrate() calls, 200 rounds") {
            repeat(ROUNDS) { round ->
                val remote = FakeRemote { listOf("fresh") }
                val store = FeedStore(remote::fetch)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    together(2) { runBlocking { store.hydration.hydrate(scope) } }
                    runBlocking { assertEquals(Hydration.Hydrated, store.hydration.awaitSettled(), "round $round") }
                    assertEquals(1, remote.fetches, "round $round: exactly one refresh")
                    assertEquals(1, store.baseRuns, "round $round: exactly one seed")
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test fun fiftyCallsOnAFailedHydrationRefetchOnceMarkedInFlightByTheDecidingTransaction() =
        hydrationWatchdog(60, "fifty hydrate() calls on a failed hydration") {
            val retried = CompletableDeferred<List<String>>()
            val remote = FakeRemote { call -> if (call == 1) error("offline") else retried.await() }
            val store = FeedStore(remote::fetch)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val retries = AtomicInteger()
            val decision = ConcurrentLinkedQueue<String>()
            store.middlewares(
                object : Middleware<FeedStore>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<FeedStore>) {
                        if (context.transaction.id != RETRY_ID) return
                        retries.incrementAndGet()
                        // On the deciding transaction's own thread, before it
                        // commits: it stages the move to Seeded, and nothing
                        // has been refetched yet.
                        val hydration = context.store.hydration
                        decision += "staged=${hydration.state in context.transaction.modifiedStates}"
                        decision += "phase=${hydration.current}"
                        decision += "fetches=${remote.fetches}"
                    }
                },
            )
            try {
                runBlocking {
                    store.hydration.hydrate(scope)
                    assertIs<Hydration.Failed>(store.hydration.awaitSettled())
                }

                together(50) { runBlocking { store.hydration.hydrate(scope) } }
                assertEquals(1, retries.get(), "one deciding transaction")
                assertEquals(listOf("staged=true", "phase=Seeded", "fetches=1"), decision.toList())

                retried.complete(listOf("fresh"))
                runBlocking { assertEquals(Hydration.Hydrated, store.hydration.awaitSettled()) }
                assertEquals(2, remote.fetches, "one refetch")
                assertEquals(1, store.baseRuns, "a retry never re-runs base")
            } finally {
                scope.cancel()
            }
        }

    @Test fun fiftyConcurrentCallsOnADetachedStoreSeedOnce() =
        hydrationWatchdog(60, "fifty concurrent hydrate() calls on a detached store") {
            repeat(20) { round ->
                val remote = FakeRemote { listOf("fresh") }
                val store = FeedStore(remote::fetch)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    together(50) { runBlocking { store.hydration.hydrate(scope) } }
                    runBlocking { store.hydration.awaitSettled() }
                    assertEquals(1, store.baseRuns, "round $round")
                    assertEquals(1, remote.fetches, "round $round")
                    assertTrue(store.items.value == listOf("fresh"), "round $round")
                } finally {
                    scope.cancel()
                }
            }
        }

    private companion object {
        const val ROUNDS = 200
    }
}
