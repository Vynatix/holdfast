@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Observable
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Run [block] on a thread of its own and wait for it — a bridge delivering from elsewhere; rethrow its failure. */
private fun onAnotherThread(block: () -> Unit) {
    var failure: Throwable? = null
    thread(name = "bridge") { runCatching(block).onFailure { failure = it } }.join()
    failure?.let { throw it }
}

/** Where a test's bridge delivers during a seed: inside `base { }`, or inside the seed's commit fanout. */
private enum class Delivery { InBase, InSeedFanout }

/**
 * The persisted overlay under concurrency (issue #20, R8/R3; plan PR 14):
 * a writer on a multi-threaded scope, fed by commits from several threads,
 * always ends having written the store's latest UserAuthored values; a
 * `hydrate()` racing a `clearOverlay()` of a kept blob always ends with the
 * blob gone and the overlay writing again, whichever takes the store first;
 * a writer run inline never reports under the hydration gate; and a bridge's
 * inbound value from another thread while a seed runs is kept and written.
 */
class HydrationOverlayConcurrencyTest {
    /** Run [call] on [threads] threads released together; rethrow the first failure. */
    private fun together(
        threads: Int,
        call: (thread: Int) -> Unit,
    ) {
        val barrier = CyclicBarrier(threads)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers =
            List(threads) { thread ->
                Thread {
                    runCatching {
                        barrier.await()
                        call(thread)
                    }.onFailure { failures += it }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        failures.firstOrNull()?.let { throw it }
    }

    @Test fun commitsFromManyThreadsEndWithTheLatestValuesWritten() =
        hydrationWatchdog(60, "concurrent commits feeding the overlay's writer, 20 rounds") {
            repeat(ROUNDS) { round ->
                val kv = RecordingKv()
                val store = Reader(kv)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                store.bindToScope(scope)
                val reported = ConcurrentLinkedQueue<Throwable>()
                store.uncaughtObserverHandler = { reported += it }
                try {
                    runBlocking {
                        store.hydration.hydrate(scope)
                        store.hydration.awaitSettled()
                    }
                    together(THREADS) { thread ->
                        repeat(COMMITS) { i ->
                            store.action {
                                note mutate "thread $thread commit $i"
                                drafts["t$thread"] mutate "$i"
                            }
                        }
                    }
                    runBlocking { store.hydration.overlayWritten() }

                    assertEquals(
                        store.snapshot(SnapshotScope.UserAuthored).encode(),
                        kv.value(),
                        "round $round: the last write holds the latest values",
                    )
                    assertTrue(kv.puts <= THREADS * COMMITS, "round $round: ${kv.puts} puts")
                    assertEquals(emptyList(), reported.toList(), "round $round")
                } finally {
                    store.dispose()
                    scope.cancel()
                }
            }
        }

    @Test fun hydrateRacingClearOverlayOfAKeptBlobAlwaysEndsWritingWithTheBlobGone() =
        hydrationWatchdog(60, "hydrate() racing clearOverlay(), 100 rounds") {
            repeat(RACES) { round ->
                val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
                val store = Reader(kv)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                store.bindToScope(scope)
                val reported = ConcurrentLinkedQueue<Throwable>()
                store.uncaughtObserverHandler = { reported += it }
                try {
                    together(2) { thread ->
                        runBlocking {
                            if (thread == 0) store.hydration.hydrate(scope) else store.hydration.clearOverlay()
                        }
                    }
                    runBlocking { store.hydration.awaitSettled() }

                    // Whichever took the store first: the blob is gone, and
                    // there is none left to protect, so the overlay writes.
                    assertNull(kv.value(), "round $round")
                    assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus, "round $round")
                    assertTrue(reported.size <= 1, "round $round: $reported")
                    store.action { note mutate "written" }
                    runBlocking { store.hydration.overlayWritten() }
                    assertEquals("written", persisted(kv)[store.note], "round $round")
                } finally {
                    store.dispose()
                    scope.cancel()
                }
            }
        }

    @Test fun aWriterRunInlineAfterClearOverlayReportsOnceTheGateHasReleasedTheStore() =
        hydrationWatchdog(30, "clearOverlay() whose inline writer reports to a handler that opens an action") {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv, sizeLimit = 200)
            // A launch on it runs at once, on the thread that signalled the writer.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            store.bindToScope(scope)
            val reported = ConcurrentLinkedQueue<Throwable>()
            val handled = ConcurrentLinkedQueue<TransactionResult<Unit>>()
            store.uncaughtObserverHandler = { failure ->
                reported += failure
                handled += store.action { token mutate "handled" }
            }
            try {
                runBlocking {
                    store.hydration.hydrate(this)
                    store.hydration.awaitSettled()
                }
                assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)

                // Between the removal and the gate, a holder of the store commits
                // an oversize change: counted, and not written while the blob is
                // kept. It is released by the removal and runs while the clear
                // backs off at the gate, on this thread.
                runBlocking {
                    val release = CompletableDeferred<Unit>()
                    val holder =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            store.suspendAction {
                                release.await()
                                note mutate "x".repeat(500)
                            }
                        }
                    kv.afterRemove = { release.complete(Unit) }
                    store.hydration.clearOverlay()
                    holder.join()
                }

                assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
                assertEquals(2, reported.size, "the kept blob's report, then the oversize write's: $reported")
                assertTrue("200" in reported.last().message.orEmpty(), reported.last().message)
                assertTrue(handled.all { it is TransactionResult.Success }, "$handled")
                assertEquals("handled", store.token.value)
                assertNull(kv.value())
            } finally {
                store.dispose()
                scope.cancel()
            }
        }

    @Test fun aWriterRunInlineAfterASeedReportsOnceTheGateHasReleasedTheStore() =
        hydrationWatchdog(30, "a seed whose inline writer reports to a handler that opens an action") {
            val kv = RecordingKv()
            var deliver: (String) -> Unit = { }
            // base { } lets another thread's bridge deliver an oversize note.
            val store = Reader(kv, sizeLimit = 200, seed = { onAnotherThread { deliver("x".repeat(500)) } })
            store.run {
                note observeFrom
                    Observable { observer ->
                        deliver = observer
                        Disposable { }
                    }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            store.bindToScope(scope)
            val reported = ConcurrentLinkedQueue<Throwable>()
            val handled = ConcurrentLinkedQueue<TransactionResult<Unit>>()
            store.uncaughtObserverHandler = { failure ->
                reported += failure
                handled += store.action { token mutate "handled" }
            }
            try {
                runBlocking {
                    store.hydration.hydrate(this)
                    store.hydration.awaitSettled()
                }

                assertEquals("x".repeat(500), store.note.value)
                val report = assertIs<OverlayException>(reported.single())
                assertTrue("200" in report.message.orEmpty(), report.message)
                assertTrue(handled.single() is TransactionResult.Success, "$handled")
                assertNull(kv.value())
            } finally {
                store.dispose()
                scope.cancel()
            }
        }

    @Test fun aBridgesValueFromAnotherThreadDuringAFirstSeedIsKeptAndWritten() {
        for (delivery in Delivery.entries) {
            hydrationWatchdog(30, "a bridge delivering $delivery of a first seed") { bridgeDuringASeed(false, delivery) }
        }
    }

    @Test fun aBridgesValueFromAnotherThreadDuringALaterSeedIsKeptAndWritten() {
        for (delivery in Delivery.entries) {
            hydrationWatchdog(30, "a bridge delivering $delivery of a later seed") { bridgeDuringASeed(true, delivery) }
        }
    }

    /**
     * A [Reader] whose note follows a bridge that delivers from another thread
     * where [delivery] says, during a first seed or — when [reseed] — a later
     * one of a loaded overlay; `base { }` never writes the note. The store and
     * the key both end with the bridge's value.
     */
    private fun bridgeDuringASeed(
        reseed: Boolean,
        delivery: Delivery,
    ) {
        val kv = RecordingKv()
        var deliver: (String) -> Unit = { }
        var inBase: String? = null
        var inFanout: String? = null
        val store =
            Reader(kv, seed = {
                items mutate listOf("seed $baseRuns")
                inBase?.let { value -> onAnotherThread { deliver(value) } }
            })
        store.run {
            note observeFrom
                Observable { observer ->
                    deliver = observer
                    Disposable { }
                }
        }
        // Inside the seed's fanout: base { } changed the items.
        store.items effect { inFanout?.let { value -> onAnotherThread { deliver(value) } }.also { inFanout = null } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store.bindToScope(scope)
        val reported = ConcurrentLinkedQueue<Throwable>()
        store.uncaughtObserverHandler = { reported += it }
        try {
            runBlocking {
                if (reseed) {
                    store.hydration.hydrate(this)
                    store.hydration.awaitSettled()
                    store.action { note mutate "held" }
                    store.hydration.overlayWritten()
                    store.hydration.invalidate()
                }
                when (delivery) {
                    Delivery.InBase -> inBase = "from the bridge"
                    Delivery.InSeedFanout -> inFanout = "from the bridge"
                }
                store.hydration.hydrate(this)
                store.hydration.awaitSettled()
                store.hydration.overlayWritten()
            }

            val what = "delivered ${delivery.name} of a ${if (reseed) "later" else "first"} seed"
            assertEquals("from the bridge", store.note.value, "$what: the seed kept it")
            assertEquals("from the bridge", persisted(kv)[store.note], "$what: the writer wrote it")
            assertEquals(store.snapshot(SnapshotScope.UserAuthored).encode(), kv.value(), what)
            assertEquals(emptyList(), reported.toList(), what)
        } finally {
            store.dispose()
            scope.cancel()
        }
    }

    @Test fun aLaterSeedThatPutsTheHeldValueOverABridgesValueWritesItAgain() =
        hydrationWatchdog(30, "a later seed putting its held value over a bridge's written one") {
            val kv = RecordingKv()
            var deliver: (String) -> Unit = { }
            var armed = false
            val store =
                Reader(kv, seed = {
                    if (armed) {
                        note mutate "from base"
                        val puts = kv.puts
                        onAnotherThread { deliver("from the bridge") }
                        // The writer writes the bridge's value while the seed is open.
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (kv.puts == puts) {
                            check(System.nanoTime() < deadline) { "the writer never wrote the bridge's value" }
                            Thread.sleep(1)
                        }
                    }
                })
            store.run {
                note observeFrom
                    Observable { observer ->
                        deliver = observer
                        Disposable { }
                    }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            store.bindToScope(scope)
            try {
                runBlocking {
                    store.hydration.hydrate(this)
                    store.hydration.awaitSettled()
                    store.action { note mutate "held" }
                    store.hydration.overlayWritten()
                    store.hydration.invalidate()
                    armed = true
                    store.hydration.hydrate(this)
                    store.hydration.awaitSettled()
                    store.hydration.overlayWritten()
                }

                // base { } wrote the note, so the held value went back over it
                // (and over the bridge's, as any transaction's write does).
                assertEquals("held", store.note.value)
                assertEquals(store.snapshot(SnapshotScope.UserAuthored).encode(), kv.value(), "the blob follows")
            } finally {
                store.dispose()
                scope.cancel()
            }
        }

    private companion object {
        const val ROUNDS = 20
        const val THREADS = 4
        const val COMMITS = 200
        const val RACES = 100
    }
}
