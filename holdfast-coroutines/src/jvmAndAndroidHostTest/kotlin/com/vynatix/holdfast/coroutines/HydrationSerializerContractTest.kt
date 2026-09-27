@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hydration gate against the store's serializer (issue #20, R8; plan
 * decision D19), every case watchdogged: hydration interleaved with blocking
 * actions and `suspendAction`s on the store never deadlocks or spins, the
 * gate waits for a holder of the serializer without holding a thread, a
 * derived recompute handed to the gate while it holds the store runs when it
 * releases, and a `hydrate()` or `awaitSettled()` from where it would wait
 * for itself — inside an action, frame or `suspendAction`, body or commit,
 * or a state initializer — fails fast instead.
 */
class HydrationSerializerContractTest {
    @Test fun theGateBacksOffInsteadOfSpinningSoAHolderOnTheSameThreadFinishes() =
        hydrationWatchdog(20, "hydrate() behind a suspendAction on one thread") {
            runBlocking {
                // One thread: a gate that spun instead of suspending would
                // never let the holder resume, and hang here.
                val store = FeedStore { listOf("fresh") }
                val release = CompletableDeferred<Unit>()
                val holder = launch(start = CoroutineStart.UNDISPATCHED) { store.suspendAction { release.await() } }
                val hydrating = launch { store.hydration.hydrate(this@runBlocking) }
                repeat(10) { yield() }
                assertEquals(Hydration.Detached, store.hydration.current, "the gate waits for the holder")
                release.complete(Unit)
                joinAll(holder, hydrating)
                assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            }
        }

    @Test fun hydrateWaitsForABlockingActionOnAnotherThreadAndThenSeeds() =
        hydrationWatchdog(20, "hydrate() behind a blocking action") {
            val store = FeedStore { listOf("fresh") }
            // Installed first, so the holder takes it: the gate then backs off
            // politely behind it, holding no thread, rather than spinning on
            // the transaction lock of an action that predates the install.
            val serializer = ensureSerializer(store)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    store action {
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        pinned mutate setOf("x")
                    }
                }
            holder.start()
            entered.await()
            assertTrue(serializer.mutex.isLocked, "the holder took the store's serializer")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                runBlocking {
                    val hydrating = launch(Dispatchers.Default) { store.hydration.hydrate(scope) }
                    repeat(POLLS) {
                        Thread.sleep(POLL_MS)
                        assertTrue(hydrating.isActive, "the gate waits for the holder")
                        assertTrue(noThreadWaitsOnATransactionLock(), "the gate waits without holding a thread")
                    }
                    assertEquals(Hydration.Detached, store.hydration.current)
                    release.countDown()
                    hydrating.join()
                    assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
                }
                holder.join()
                assertEquals(setOf("x"), store.pinned.value)
            } finally {
                scope.cancel()
            }
        }

    @Test fun hydrateAndAwaitSettledInsideAStateInitializerFailFast() =
        hydrationWatchdog(20, "hydrate() and awaitSettled() from an initializer") {
            val remote = GatedRemote<List<String>>()
            val store = ProbedFeed(remote::fetch)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                runBlocking { store.hydration.hydrate(scope) } // Seeded: the refresh waits for its answer
                // First delegate reads, outside any action: no settle scope is
                // open, so only the no-write region can refuse them.
                val awaitRefusal = store.awaitProbe.value
                assertTrue(awaitRefusal.startsWith("IllegalStateException: "), awaitRefusal)
                assertTrue("run awaitSettled() on ProbedFeed" in awaitRefusal, awaitRefusal)
                assertTrue("initializer of ProbedFeed.awaitProbe" in awaitRefusal, awaitRefusal)
                val hydrateRefusal = store.hydrateProbe.value
                assertTrue(hydrateRefusal.startsWith("IllegalStateException: "), hydrateRefusal)
                assertTrue("run hydrate() on ProbedFeed" in hydrateRefusal, hydrateRefusal)
                assertTrue("initializer of ProbedFeed.hydrateProbe" in hydrateRefusal, hydrateRefusal)

                assertEquals(Hydration.Seeded, store.hydration.current, "nothing wedged or moved")
                assertEquals(1, store.baseRuns)
                remote.release.complete(listOf("fresh"))
                runBlocking { assertEquals(Hydration.Hydrated, store.hydration.awaitSettled()) }
            } finally {
                scope.cancel()
            }
        }

    @Test fun aDerivedRecomputeHandedToTheGateRunsWhenItReleasesTheStore() =
        hydrationWatchdog(20, "a derived recompute during the seed") {
            runBlocking {
                val store = FeedStore { listOf("a", "b") }
                // The legacy derived recomputes through the store's
                // post-commit queue: the seed's commit hands it to the gate,
                // which drains it once it has released the store (nothing
                // else here would: no derivedState settles on this store).
                val (count, disposeCount) = store.derived(store.items) { items.value.size }
                store.hydration.hydrate(this)
                assertEquals(1, count.value, "recomputed after the seed, before hydrate() returned")
                store.hydration.awaitSettled()
                assertEquals(2, count.value, "recomputed after the adoption")
                disposeCount.dispose()
            }
        }

    @Test fun aDerivedStateSettlesOnceTheGateHasReleasedTheStore() =
        hydrationWatchdog(20, "a derivedState over the seed") {
            runBlocking {
                val store = FeedStore { listOf("a", "b") }
                val settled = store.derivedState(store.items) { items.value.size }
                store.hydration.hydrate(this)
                assertEquals(1, settled.value, "settled after the seed, before hydrate() returned")
                store.hydration.awaitSettled()
                assertEquals(2, settled.value)
                settled.dispose()
            }
        }

    @Test fun hydrateInsideAnActionAFrameOrASuspendingEntryFailsFast() =
        hydrationWatchdog(20, "hydrate() from inside an entry") {
            val store = FeedStore { listOf("fresh") }
            val other = FeedStore { listOf("fresh") }
            val refusals = ConcurrentLinkedQueue<Throwable?>()

            fun attempt(on: FeedStore = store) {
                refusals += runCatching { runBlocking { on.hydration.hydrate(this) } }.exceptionOrNull()
            }
            store action { attempt() }
            other action { attempt() } // another store's action: its seed would escape it
            atomic(store) { attempt() }
            atomic(other) { attempt() }
            runBlocking {
                store.suspendAction { refusals += runCatching { hydration.hydrate(this@runBlocking) }.exceptionOrNull() }
                other.suspendAction { refusals += runCatching { store.hydration.hydrate(this@runBlocking) }.exceptionOrNull() }
                suspendAtomic(store) { refusals += runCatching { store.hydration.hydrate(this@runBlocking) }.exceptionOrNull() }
                // A child coroutine of the body inherits its entry, and would deadlock the body.
                store.suspendAction {
                    coroutineScope {
                        launch { refusals += runCatching { hydration.hydrate(this@runBlocking) }.exceptionOrNull() }
                    }
                }
            }
            assertEquals(8, refusals.size)
            for (refusal in refusals) {
                assertIs<IllegalStateException>(refusal)
                assertTrue("may not run inside an action" in refusal.message.orEmpty(), refusal.message)
            }
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(0, store.baseRuns)
        }

    @Test fun awaitSettledInsideAnActionAFrameOrASuspendingEntryFailsFastInsteadOfWaitingForTheRefresh() =
        hydrationWatchdog(20, "awaitSettled() from inside an entry") {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val refusals = ConcurrentLinkedQueue<Throwable?>()

            fun attempt() {
                refusals += runCatching { runBlocking { store.hydration.awaitSettled() } }.exceptionOrNull()
            }
            try {
                runBlocking { store.hydration.hydrate(scope) } // Seeded: the refresh waits for its answer
                store action { attempt() }
                atomic(store) { attempt() }
                runBlocking {
                    store.suspendAction { refusals += runCatching { hydration.awaitSettled() }.exceptionOrNull() }
                    suspendAtomic(store) { refusals += runCatching { store.hydration.awaitSettled() }.exceptionOrNull() }
                    store.suspendAction {
                        coroutineScope {
                            launch { refusals += runCatching { hydration.awaitSettled() }.exceptionOrNull() }
                        }
                    }
                }
                assertEquals(5, refusals.size)
                for (refusal in refusals) {
                    assertIs<IllegalStateException>(refusal)
                    assertTrue("may not run inside an action" in refusal.message.orEmpty(), refusal.message)
                    assertTrue("awaitSettled()" in refusal.message.orEmpty(), refusal.message)
                }
                remote.release.complete(listOf("fresh"))
                runBlocking { assertEquals(Hydration.Hydrated, store.hydration.awaitSettled(), "the store was not wedged") }
            } finally {
                scope.cancel()
            }
        }

    @Test fun hydrateFromAnObserverOfTheStoresCommitFailsFastInsteadOfWaitingForIt() =
        hydrationWatchdog(20, "hydrate() from a commit's observer") {
            val store = FeedStore { listOf("fresh") }
            // Installed first, so the blocking action holds it too: without
            // the fail-fast, its observer's gate would wait for it forever.
            ensureSerializer(store)
            val refusals = ConcurrentLinkedQueue<Throwable?>()
            val subscription =
                store.pinned effect {
                    if (isNotEmpty()) refusals += runCatching { runBlocking { store.hydration.hydrate(this) } }.exceptionOrNull()
                }
            store action { pinned mutate setOf("blocking") }
            runBlocking { store.suspendAction { pinned mutate setOf("suspending") } }
            subscription.dispose()
            assertEquals(2, refusals.size)
            for (refusal in refusals) {
                assertIs<IllegalStateException>(refusal)
                assertTrue("may not run inside an action" in refusal.message.orEmpty(), refusal.message)
            }
        }

    @Test fun hydrationInterleavedWithBlockingActionsAndSuspendActionsNeverDeadlocks() =
        hydrationWatchdog(90, "hydration under load") {
            val store = FeedStore { listOf("fresh") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val failures = ConcurrentLinkedQueue<Throwable>()
            store.uncaughtObserverHandler = { failures += it }
            // Install the store's serializer before any blocking action runs:
            // an action that read it as not installed yet runs under the
            // transaction lock alone, and a suspendAction starting meanwhile
            // can lose its transaction slot to it (a pre-existing install
            // window; the gate's transactions take the transaction lock and
            // read the phase again under it — HydrationCrossThreadTest).
            runBlocking { store.suspendAction { } }
            try {
                val blocking =
                    Thread {
                        repeat(BLOCKING_ACTIONS) { i ->
                            val result = store action { pinned mutate setOf("b$i") }
                            if (result is TransactionResult.Error) failures += result.exception
                        }
                    }
                blocking.start()
                // On IO: invalidate() is a blocking action, which spins while a
                // queued suspendAction owns the store; spinning Default threads
                // could starve that suspendAction of the thread it resumes on.
                runBlocking(Dispatchers.IO) {
                    val suspending =
                        async {
                            repeat(SUSPEND_ACTIONS) { i ->
                                val result = store.suspendAction { pinned mutate setOf("s$i") }
                                if (result is TransactionResult.Error) failures += result.exception
                            }
                        }
                    val hydrating =
                        List(2) {
                            async {
                                repeat(HYDRATIONS) {
                                    store.hydration.hydrate(scope)
                                    store.hydration.awaitSettled()
                                    val result = store.hydration.invalidate()
                                    if (result is TransactionResult.Error) failures += result.exception
                                }
                            }
                        }
                    (hydrating + suspending).awaitAll()
                }
                blocking.join()
                assertTrue(failures.isEmpty(), failures.joinToString())
                assertTrue(store.baseRuns > 0, "the cycles seeded")
                // Every cycle ends with invalidate(), and nothing adopts after one.
                assertEquals(Hydration.Detached, store.hydration.current)
                runBlocking { assertEquals(Hydration.Detached, store.hydration.awaitSettled(), "not wedged") }
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        const val BLOCKING_ACTIONS = 2_000
        const val SUSPEND_ACTIONS = 1_000
        const val HYDRATIONS = 150

        /** How many times, and how often, a test checks that the gate still waits politely. */
        const val POLLS = 10
        const val POLL_MS = 5L

        /**
         * Whether no live thread is inside a store's transaction-lock acquire:
         * a gate transaction spinning on the lock of an action that holds it
         * alone would be.
         */
        fun noThreadWaitsOnATransactionLock(): Boolean =
            Thread.getAllStackTraces().values.none { frames ->
                frames.any { it.className.endsWith("StoreLock") && it.methodName == "acquire" }
            }
    }
}

/** A feed whose probe states run `hydrate()` or `awaitSettled()` from their initializers, recording what they threw. */
private class ProbedFeed(
    remote: suspend () -> List<String>,
) : Store<ProbedFeed>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }

    /** How many times `base { }` has run. */
    var baseRuns = 0

    val hydration =
        hydrator {
            base {
                baseRuns++
                items mutate listOf("seed")
            }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }

    val awaitProbe by state { refusal { hydration.awaitSettled() } }
    val hydrateProbe by state { refusal { hydration.hydrate(this) } }

    /** What [attempt], run to completion, threw — its type and message — or "nothing". */
    private fun refusal(attempt: suspend CoroutineScope.() -> Unit): String {
        val thrown = runCatching { runBlocking { attempt() } }.exceptionOrNull() ?: return "nothing"
        return "${thrown::class.simpleName}: ${thrown.message}"
    }
}
