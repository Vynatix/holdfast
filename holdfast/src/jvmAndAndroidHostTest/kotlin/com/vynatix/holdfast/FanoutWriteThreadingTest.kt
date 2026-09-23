package com.vynatix.holdfast

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ThreadedFanoutStore : Store<ThreadedFanoutStore>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

/**
 * The refusal of writes into an applied transaction ([FanoutWriteTest]) is
 * about NESTING: it applies to the thread running the commit's fanout, never
 * to other threads that merely find the store busy. Under a blocking commit
 * those wait for the store and then commit on their own, exactly as before —
 * whether they `mutate` or open an `action`. (Under a `suspendAction` commit a
 * bare `mutate` from another thread is refused instead; `:holdfast-coroutines`'
 * `SuspendFanoutNestingTest` pins that.)
 */
class FanoutWriteThreadingTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    /**
     * Commit `trigger` while another thread writes `echo` through [write]
     * during the fanout, and return that thread's failure, if any.
     */
    private fun overlapFanoutWith(
        s: ThreadedFanoutStore,
        write: () -> Unit,
    ): Throwable? {
        val inFanout = CountDownLatch(1)
        val otherCalling = CountDownLatch(1)
        var initial = true
        disposables +=
            s.trigger.effect {
                if (initial) {
                    initial = false
                } else {
                    inFanout.countDown()
                    otherCalling.await(5, TimeUnit.SECONDS)
                    // Hold the fanout open while the other thread's write is in flight.
                    Thread.sleep(150)
                }
            }
        val failure = AtomicReference<Throwable?>(null)
        val other =
            daemon("fanout-overlap-writer") {
                inFanout.await(5, TimeUnit.SECONDS)
                otherCalling.countDown()
                runCatching(write).onFailure { failure.set(it) }
            }
        completesWithin(10, "a commit whose fanout overlaps another thread's write") {
            s action { trigger mutate 1 }
            other.join(5_000)
        }
        return failure.get()
    }

    @Test fun anotherThreadsMutateDuringFanoutWaitsAndCommits() {
        val s = ThreadedFanoutStore()

        val failure = overlapFanoutWith(s) { s { echo mutate 7 } }

        assertNull(failure, "another thread's mutate is not nested and must not be refused")
        assertEquals(7, s.echo.value)
        assertEquals(1, s.trigger.value)
    }

    /**
     * The refusal message must not wait for `propertiesLock`: a write refused
     * inside `Bridge.publish` runs under that state's bridge lock, while
     * `clearStates`/`removeState` take `propertiesLock` and then bridge locks.
     * Here the other thread takes `propertiesLock` and blocks on the bridge
     * lock the publish holds; a refusal that waited for `propertiesLock` to
     * name the state would deadlock both threads (and every later action).
     */
    private fun assertBridgeWriteBackSurvivesConcurrentRemoval(remove: (ThreadedFanoutStore) -> Unit) {
        val s = ThreadedFanoutStore()
        val failures = CopyOnWriteArrayList<Throwable>()
        s.uncaughtObserverHandler = { failures += it }
        // Captured up front: a delegate read inside the publish would itself
        // take propertiesLock, which is not what this test is about.
        val echoRef = s.echo
        val inPublish = CountDownLatch(1)
        s {
            trigger bridge
                object : Bridge<Int> {
                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

                    override fun publish(value: Int): Boolean {
                        inPublish.countDown()
                        // Let the other thread take propertiesLock and block on
                        // this state's bridge lock, held around this call.
                        Thread.sleep(150)
                        s { echoRef mutate value }
                        return true
                    }
                }
        }
        val remover =
            daemon("state-remover") {
                inPublish.await(5, TimeUnit.SECONDS)
                remove(s)
            }

        completesWithin(10, "a bridge write-back refused while another thread removes states") {
            s action { trigger mutate 1 }
            remover.join(5_000)
        }

        assertFalse(remover.isAlive, "the removing thread must not stay blocked")
        val refusal = assertIs<IllegalStateException>(failures.single())
        assertTrue("has already applied its writes" in refusal.message.orEmpty(), "unexpected: $refusal")
    }

    @Test fun bridgeWriteBackRefusalDuringClearStatesDoesNotDeadlock() {
        assertBridgeWriteBackSurvivesConcurrentRemoval { it.clearStates() }
    }

    @Test fun bridgeWriteBackRefusalDuringRemoveStateDoesNotDeadlock() {
        assertBridgeWriteBackSurvivesConcurrentRemoval { it.removeState("trigger") }
    }

    @Test fun anotherThreadsActionDuringFanoutWaitsAndCommits() {
        val s = ThreadedFanoutStore()
        val result = AtomicReference<TransactionResult<*>?>(null)

        val failure = overlapFanoutWith(s) { result.set(s action { echo mutate 8 }) }

        assertNull(failure)
        assertIs<TransactionResult.Success<*>>(result.get(), "another thread's action waits its turn")
        assertEquals(8, s.echo.value)
    }
}
