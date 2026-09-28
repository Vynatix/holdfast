@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

private class Window : Store<Window>() {
    val count by state { 0 }
}

/**
 * A serializer the test takes first, standing in for a `suspendAction` that
 * has just installed it and holds it: every blocking acquire then waits for
 * the test to let go ([letGo]).
 */
private class HeldSerializer : Store.AsyncSerializer {
    private val permit = Semaphore(1).apply { acquire() }
    val acquires = AtomicInteger()

    fun letGo() = permit.release()

    override fun blockingAcquire() {
        acquires.incrementAndGet()
        permit.acquire()
    }

    override fun blockingRelease() = permit.release()

    override fun tryBlockingAcquire(): Boolean = permit.tryAcquire()
}

/**
 * The blocking side of the serializer's install window
 * (`SerializerInstallWindow.kt`): a top-level `action` or `atomic` that read
 * the store's serializer as not installed yet, and got the transaction lock
 * only after one was installed, re-reads it under the lock and waits for it
 * — its holder may be a `suspendAction`, which installs its transaction
 * without that lock — instead of running beside that holder. And the probe
 * the suspending side waits with never blocks and never counts a hold of
 * this thread's own as released.
 */
class SerializerInstallWindowTest {
    @Test fun anActionThatReadNoSerializerWaitsForTheOneInstalledBeforeItTookTheLock() =
        completesWithin(20, "an action behind a serializer installed while it waited for the lock") {
            val store = Window()
            assertWaitsForTheLateSerializer(store) { bodyRan ->
                store.action {
                    bodyRan.set(true)
                    count update { it + 1 }
                }
            }
        }

    @Test fun anAtomicThatReadNoSerializerWaitsForTheOneInstalledBeforeItTookTheLock() =
        completesWithin(20, "an atomic behind a serializer installed while it waited for the lock") {
            val store = Window()
            assertWaitsForTheLateSerializer(store) { bodyRan ->
                atomic(store) {
                    bodyRan.set(true)
                    store { count update { it + 1 } }
                }
            }
        }

    @Test fun theLockProbeAnswersWithoutWaitingAndCountsNoHoldOfThisThreadAsReleased() =
        completesWithin(20, "probing the transaction lock") {
            val store = Window()
            assertTrue(store.internalTransactionLockFree(), "free when nobody holds it")
            store.runUnderLock {
                assertFalse(store.internalTransactionLockFree(), "held by this very thread")
            }
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                daemon("lock-holder") {
                    store.runUnderLock {
                        held.countDown()
                        release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            held.await()
            assertFalse(store.internalTransactionLockFree(), "held by another thread: answers at once")
            release.countDown()
            holder.join()
            assertTrue(store.internalTransactionLockFree(), "free again")
            assertTrue(store.internalTransactionLockFree(), "and the probe left it free")
        }

    /**
     * Hold [store]'s transaction lock on another thread, start [enter] on a
     * third, which reads no serializer and parks on the lock, then install a
     * serializer the test holds and release the lock: [enter]'s body must not
     * run until the serializer is let go.
     */
    private fun assertWaitsForTheLateSerializer(
        store: Window,
        enter: (bodyRan: AtomicBoolean) -> TransactionResult<*>,
    ) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lockHolder =
            daemon("lock-holder") {
                store.runUnderLock {
                    held.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        held.await()
        val bodyRan = AtomicBoolean(false)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val late = daemon("late-holder", failures) { enter(bodyRan).getOrThrow() }
        awaitParkedOnTheLock(late)
        val serializer = HeldSerializer()
        store.asyncSerializer = serializer
        release.countDown()
        lockHolder.join()
        awaitEither(serializer, bodyRan)
        Thread.sleep(SETTLE_MS)
        assertFalse(bodyRan.get(), "the late holder waits for the serializer installed before it took the lock")
        assertEquals(0, store.count.value)
        serializer.letGo()
        late.join()
        assertTrue(failures.isEmpty(), failures.joinToString())
        assertTrue(bodyRan.get())
        assertEquals(1, store.count.value, "its transaction committed once it held the serializer")
        assertTrue(serializer.tryBlockingAcquire(), "and it released the serializer")
    }

    private companion object {
        const val SETTLE_MS = 100L
        const val WAIT_SECONDS = 10L

        /** Wait until [thread] is parked acquiring a store's transaction lock. */
        fun awaitParkedOnTheLock(thread: Thread) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (thread.stackTrace.none { it.className.endsWith("StoreLock") && it.methodName == "acquire" } ||
                thread.state != Thread.State.WAITING
            ) {
                if (System.nanoTime() > deadline) fail("the late holder never waited for the transaction lock")
                Thread.sleep(1)
            }
        }

        /** Wait until something has asked [serializer] for the store, or a body ran without it. */
        fun awaitEither(
            serializer: HeldSerializer,
            bodyRan: AtomicBoolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (serializer.acquires.get() == 0 && !bodyRan.get()) {
                if (System.nanoTime() > deadline) fail("the late holder neither asked for the serializer nor ran")
                Thread.sleep(1)
            }
        }
    }
}
