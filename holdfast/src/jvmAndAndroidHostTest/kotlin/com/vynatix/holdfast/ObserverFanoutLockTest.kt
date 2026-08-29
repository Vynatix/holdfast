package com.vynatix.holdfast

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Counter : Store<Counter>() {
    val count by state { 0 }
}

/**
 * Commit fanout invokes observer callbacks outside `observersLock`, so an
 * observer body is not part of that lock's graph.
 *
 * Scope note: this is about `observersLock` only. `action` still holds the
 * store's `transactionLock` across the whole fanout, so writing to a second
 * store from an observer remains a deadlock hazard — see the note on
 * `MutableState.notifyObservers`.
 */
class ObserverFanoutLockTest {
    @Test
    fun `a blocked observer does not stop another thread from subscribing or disposing`() {
        val store = Counter()
        val inObserver = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)

        val blocking =
            store {
                count effect {
                    if (this == 1) {
                        inObserver.countDown()
                        // Hold the fanout open until the other thread has finished.
                        releaseObserver.await(10, TimeUnit.SECONDS)
                    }
                }
            }

        val committer = Thread { store action { count mutate 1 } }
        committer.isDaemon = true
        committer.start()

        assertTrue(inObserver.await(10, TimeUnit.SECONDS), "observer should have been entered")

        // While that observer is parked mid-fanout, another thread must still be
        // able to subscribe to and unsubscribe from the same state.
        val subscribed = CountDownLatch(1)
        val other =
            Thread {
                val sub = store { count effect { } }
                sub.dispose()
                subscribed.countDown()
            }
        other.isDaemon = true
        other.start()

        assertTrue(
            subscribed.await(10, TimeUnit.SECONDS),
            "subscribe/dispose blocked behind a slow observer — callbacks are running under observersLock",
        )

        releaseObserver.countDown()
        committer.join(10_000)
        blocking.dispose()
        assertEquals(1, store.count.value)
    }

    @Test
    fun `an observer may subscribe and dispose on the state it is observing`() {
        val store = Counter()
        val seen = mutableListOf<Int>()
        var reentrant: Disposable? = null

        val sub =
            store {
                count effect {
                    seen += this
                    // Reentrant subscribe from inside a fanout callback: with the
                    // callbacks running under observersLock this relied on the
                    // lock being reentrant; it must keep working without it.
                    if (this == 1 && reentrant == null) {
                        reentrant = store { count effect { } }
                    }
                }
            }

        store action { count mutate 1 }

        assertEquals(listOf(0, 1), seen)
        reentrant?.dispose()
        sub.dispose()
    }
}
