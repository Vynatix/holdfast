@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Plan PR 10: the attachment slot under threads — one winner per key however
// many threads race to attach it, and an attach racing dispose() either lands
// before the slot closes (and is told of the dispose) or fails: never an
// attachment the dispose does not tell.

private class RacedStore : Store<RacedStore>() {
    val n by state { 0 }
}

/** Counts how often its store told it of the dispose. */
private class CountingAttachment : StoreAttachment {
    val disposals = AtomicInteger()

    override fun onStoreDisposed() {
        disposals.incrementAndGet()
    }
}

private val racedKey = StoreAttachmentKey<CountingAttachment>("raced")

private const val THREADS = 8

class StoreAttachmentRaceTest {
    @Test fun manyThreadsAttachingOneKeyGetOneWinnerAndOneCreate() {
        completesWithin(60, "racing attaches") {
            repeat(200) { round ->
                val store = RacedStore()
                val creates = AtomicInteger()
                val results = arrayOfNulls<CountingAttachment>(THREADS)
                val start = CyclicBarrier(THREADS)
                val workers =
                    (0 until THREADS).map { i ->
                        daemon("attacher-$round-$i") {
                            start.await(10, TimeUnit.SECONDS)
                            results[i] = store.internalAttachIfAbsent(racedKey) { CountingAttachment().also { creates.incrementAndGet() } }
                        }
                    }
                workers.forEach { it.join(10_000) }

                val winner = store.internalAttachment(racedKey)
                assertEquals(1, creates.get(), "round $round: create ran once")
                assertTrue(results.all { it != null && it === winner }, "round $round: every caller got the winner")
                assertEquals(listOf<StoreAttachment>(winner!!), store.internalAttachments())
                store.dispose()
                assertEquals(1, winner.disposals.get(), "round $round: the winner was told of the dispose once")
            }
        }
    }

    @Test fun anAttachRacingDisposeIsEitherToldOfItOrRefused() {
        completesWithin(60, "attaches racing dispose") {
            repeat(200) { round ->
                val store = RacedStore()
                // Every attachment create built: attached, it must be told of the dispose exactly once.
                val built = ConcurrentLinkedQueue<CountingAttachment>()
                val returned = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
                val refusals = ConcurrentLinkedQueue<Throwable>()
                val start = CyclicBarrier(THREADS + 1)
                val workers =
                    (0 until THREADS).map { i ->
                        daemon("attacher-$round-$i") {
                            start.await(10, TimeUnit.SECONDS)
                            // A fresh key per attach, so every attach that lands builds one.
                            repeat(20) {
                                val key = StoreAttachmentKey<CountingAttachment>("k$i-$it")
                                // A create that yields keeps attaches in flight while dispose() closes the slot.
                                runCatching { store.internalAttachIfAbsent(key) { slowAttachment().also { built += it } } }
                                    .onSuccess { returned += it }
                                    .onFailure { refusals += it }
                            }
                        }
                    }
                start.await(10, TimeUnit.SECONDS)
                store.dispose()
                workers.forEach { it.join(10_000) }

                assertTrue(
                    refusals.all { it is IllegalStateException && it.message == "store disposed" },
                    "round $round: an attach is only ever refused as disposed: ${refusals.firstOrNull()}",
                )
                assertEquals(built.size, returned.size, "round $round: every attachment built was attached")
                val untold = built.filter { it.disposals.get() != 1 }
                assertTrue(untold.isEmpty(), "round $round: ${untold.size} attachment(s) not told exactly once")
                assertTrue(store.internalAttachments().isEmpty())
            }
        }
    }

    @Test fun onStoreDisposedRunsWithNoLockOfTheStoreHeld() {
        val store = RacedStore()
        val probe = AtomicReference<String>()
        // null: the callback never ran. Recorded, never thrown: a throw from
        // onStoreDisposed is isolated by dispose() and would pass unnoticed.
        val freed = AtomicReference<Boolean>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        store.internalAttachIfAbsent(
            StoreAttachmentKey<StoreAttachment>("prober"),
        ) {
            object : StoreAttachment {
                override fun onStoreDisposed() {
                    // Another thread takes this store's transaction lock, then
                    // the slot's lock (past the store's disposed check, which
                    // would refuse it before the lock): it would wait until
                    // dispose() returned if dispose() still held either while
                    // telling us.
                    val done = CountDownLatch(1)
                    daemon("lock-prober") {
                        store.runUnderLock { }
                        val attach = runCatching { store.attachmentSlot.attachIfAbsent(racedKey) { CountingAttachment() } }
                        probe.set(attach.exceptionOrNull()?.message)
                        done.countDown()
                    }
                    freed.set(done.await(5, TimeUnit.SECONDS))
                }
            }
        }

        completesWithin(30, "dispose telling an attachment that probes the store's locks") { store.dispose() }

        assertEquals(true, freed.get(), "onStoreDisposed ran while dispose() still held a lock of the store")
        assertTrue(failures.isEmpty(), "onStoreDisposed failed: ${failures.firstOrNull()}")
        // Written before done.countDown(), which the successful await makes visible here.
        assertEquals("store disposed", probe.get())
    }

    @Test fun aResetRacingDisposeNeverTellsAnAttachmentAfterItsDisposal() {
        completesWithin(60, "resets racing dispose") {
            repeat(200) { round ->
                val store = RacedStore()
                val events = ConcurrentLinkedQueue<String>()
                store.internalAttachIfAbsent(StoreAttachmentKey<StoreAttachment>("ordering")) {
                    object : StoreAttachment {
                        override fun onStoreReset() {
                            events += "reset"
                        }

                        override fun onStoreDisposed() {
                            events += "disposed"
                        }
                    }
                }
                val start = CyclicBarrier(2)
                val resetter =
                    daemon("resetter-$round") {
                        start.await(10, TimeUnit.SECONDS)
                        repeat(20) { runCatching { store.resetForRace() } }
                    }
                start.await(10, TimeUnit.SECONDS)
                store.dispose()
                resetter.join(10_000)

                assertEquals("disposed", events.last(), "round $round: $events")
                assertEquals(1, events.count { it == "disposed" }, "round $round: $events")
            }
        }
    }
}

/** A [CountingAttachment] whose construction yields the thread a few times. */
private fun slowAttachment(): CountingAttachment {
    repeat(3) { Thread.yield() }
    return CountingAttachment()
}

@OptIn(ExperimentalStoreApi::class)
private fun RacedStore.resetForRace() {
    reset()
}
