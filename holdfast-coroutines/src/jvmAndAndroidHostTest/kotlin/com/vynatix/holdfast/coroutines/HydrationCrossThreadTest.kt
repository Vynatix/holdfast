@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Hydration against transactions of other threads (issue #20, R8), every case
 * watchdogged: a detach committed by a holder the gate's serializer does not
 * keep out is not overtaken by a gate decision that read the phase first — an
 * adoption, a failure record or a retry — `stageInvalidate()` stages only into
 * a transaction this thread writes into, and a stranded refresh releases its
 * waiters only once the gate has released the store.
 */
class HydrationCrossThreadTest {
    @Test fun aDetachCommittedUnderTheTransactionLockAloneIsNotOvertakenByTheAdoption() =
        hydrationWatchdog(20, "an adoption racing a lock-only detach") {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                runBlocking { store.hydration.hydrate(scope) }
                val serializer = ensureSerializer(store)
                // A blocking action that reads the serializer as not installed
                // yet (its install window) holds the transaction lock alone.
                store.asyncSerializer = null
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val holder =
                    Thread {
                        store action {
                            hydration.stageInvalidate()
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                holder.start()
                entered.await()
                store.asyncSerializer = serializer
                remote.release.complete(listOf("late"))
                // The refresh's settle takes the serializer, past its check of
                // the phase, and waits for the transaction lock the holder has.
                awaitLocked(serializer)
                Thread.sleep(SETTLE_MS)
                release.countDown()
                holder.join()
                runBlocking { assertEquals(Hydration.Detached, store.hydration.awaitSettled()) }
                assertEquals(listOf("seed"), store.items.value, "the overtaken refresh adopted nothing")
            } finally {
                scope.cancel()
            }
        }

    @Test fun aDetachCommittedUnderTheTransactionLockAloneIsNotOvertakenByTheFailureRecord() =
        hydrationWatchdog(20, "a failure record racing a lock-only detach") {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                runBlocking { store.hydration.hydrate(scope) }
                val holder = LockOnlyDetach(store)
                remote.release.completeExceptionally(IllegalStateException("down"))
                // The refresh's settle takes the serializer, past its check of
                // the phase, and its HydrationFailure record waits for the
                // transaction lock the holder has.
                holder.finishOnceTheGateWaits()
                runBlocking { assertEquals(Hydration.Detached, store.hydration.awaitSettled(), "not Failed") }
                assertEquals(listOf("seed"), store.items.value)
                runBlocking {
                    store.hydration.hydrate(scope)
                    assertEquals(2, store.baseRuns, "the detach stood, so the next hydrate seeds")
                }
            } finally {
                scope.cancel()
            }
        }

    @Test fun aDetachCommittedUnderTheTransactionLockAloneIsNotOvertakenByTheRetry() =
        hydrationWatchdog(20, "a retry racing a lock-only detach") {
            val remote = FakeRemote { call -> if (call == 1) error("down") else listOf("fresh") }
            val store = FeedStore(remote::fetch)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                runBlocking {
                    store.hydration.hydrate(scope)
                    assertIs<Hydration.Failed>(store.hydration.awaitSettled())
                }
                assertEquals(1, store.baseRuns)
                assertEquals(1, remote.fetches)
                val holder = LockOnlyDetach(store)
                // On Default: the gate's transaction blocks on the transaction lock.
                val retry = scope.launch { store.hydration.hydrate(scope) }
                holder.finishOnceTheGateWaits()
                runBlocking { retry.join() }
                assertEquals(Hydration.Detached, store.hydration.current, "the retry decided nothing")
                assertEquals(1, store.baseRuns)
                assertEquals(1, remote.fetches, "no retry fetch")
                runBlocking {
                    store.hydration.hydrate(scope)
                    assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
                }
                assertEquals(2, store.baseRuns, "the detach stood, so the next hydrate seeded")
            } finally {
                scope.cancel()
            }
        }

    @Test fun aStrandedRefreshReleasesItsWaitersOnlyOnceTheGateHasReleasedTheStore() =
        hydrationWatchdog(20, "a waiter resumed inline by a strand") {
            val remote = GatedRemote<List<String>>()
            val store = FeedStore(remote::fetch)
            val reported = ConcurrentLinkedQueue<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.middlewares(HydrationMiddlewareLog { it == ADOPT_ID || it == FAILURE_ID })
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val waiters = CoroutineScope(Dispatchers.Unconfined + Job())
            try {
                runBlocking { store.hydration.hydrate(scope) }
                // Resumed inline by the strand, on the refresh's thread: its
                // blocking action would spin forever on a serializer the gate
                // still held.
                val waiter =
                    waiters.launch {
                        if (store.hydration.awaitSettled() is Hydration.Failed) store.hydration.invalidate()
                    }
                remote.release.complete(listOf("fresh"))
                runBlocking {
                    withTimeout(5.seconds) { waiter.join() }
                    // Reported after the strand that resumed the waiter.
                    withTimeout(5.seconds) { while (reported.isEmpty()) delay(1) }
                }
                assertEquals(Hydration.Detached, store.hydration.current)
                assertEquals(listOf("rejected $FAILURE_ID"), reported.map { it.message })
            } finally {
                scope.cancel()
                waiters.cancel()
            }
        }

    @Test fun stageInvalidateWhileOnlyAnotherThreadsActionIsOpenFailsTeachingInvalidate() =
        hydrationWatchdog(20, "stageInvalidate() beside another thread's action") {
            val store = FeedStore { listOf("fresh") }
            for (settled in listOf(Hydration.Detached, Hydration.Hydrated)) {
                if (settled == Hydration.Hydrated) {
                    runBlocking {
                        store.hydration.hydrate(this)
                        store.hydration.awaitSettled()
                    }
                }
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val holder =
                    Thread {
                        store action {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                holder.start()
                entered.await()
                try {
                    val thrown = assertFailsWith<IllegalStateException> { store.hydration.stageInvalidate() }
                    assertTrue("hydrator.invalidate()" in thrown.message.orEmpty(), thrown.message)
                } finally {
                    release.countDown()
                    holder.join()
                }
                assertEquals(settled, store.hydration.current, "nothing staged into the other thread's action")
            }
        }

    /**
     * A blocking action on another thread that stages a detach while holding
     * [store]'s transaction lock alone — it read the serializer as not
     * installed yet (its install window) — and waits to commit it.
     */
    private class LockOnlyDetach(
        private val store: FeedStore,
    ) {
        private val serializer = ensureSerializer(store)
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val thread =
            Thread {
                store action {
                    hydration.stageInvalidate()
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }

        init {
            store.asyncSerializer = null
            thread.start()
            entered.await()
            store.asyncSerializer = serializer
        }

        /**
         * Once a gate holds the serializer, give it [SETTLE_MS] to reach the
         * transaction lock, then commit the detach.
         */
        fun finishOnceTheGateWaits() {
            awaitLocked(serializer)
            Thread.sleep(SETTLE_MS)
            release.countDown()
            thread.join()
        }
    }

    private companion object {
        /** How long the settle gets to reach the transaction lock once it holds the serializer. */
        const val SETTLE_MS = 100L

        /** Wait until [serializer]'s mutex is held. */
        fun awaitLocked(serializer: MutexSerializer) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!serializer.mutex.isLocked) {
                if (System.nanoTime() > deadline) fail("the gate never took the store's serializer")
                Thread.sleep(1)
            }
        }
    }
}
