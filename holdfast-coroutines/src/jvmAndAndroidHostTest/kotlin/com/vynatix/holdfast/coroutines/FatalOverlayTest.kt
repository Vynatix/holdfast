@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #61, the persisted overlay (OverlayBinding.kt, OverlayWriter.kt,
// HydrationEngine.kt). A fatal error a codec, the key-value store, the
// snapshot capture, the store's scope or the uncaughtObserverHandler throws
// is never reported as an OverlayException and never poisons a blob: it
// propagates out of hydrate()/clearOverlay(), the commit that could not
// launch the writer, or the writer's coroutine (to the store scope's
// CoroutineExceptionHandler). The writer is never left running: the next
// change writes again.

/** A String codec that throws what [encodeFails]/[decodeFails] hold while set. */
private class FoCodec : StateCodec<String> {
    @Volatile var encodeFails: Throwable? = null

    @Volatile var decodeFails: Throwable? = null

    override fun encode(value: String): String {
        encodeFails?.let { throw it }
        return value
    }

    override fun decode(string: String): String {
        decodeFails?.let { throw it }
        return string
    }
}

/**
 * A store with a persisted overlay of one UserAuthored [note], whose `scope`
 * (where the overlay's writer runs) and `schemaVersion` (which every capture
 * reads) throw what [scopeFails]/[versionFails] hold while set.
 */
private class FoStore(
    kv: SuspendingKvStore,
    private val writerScope: CoroutineScope,
    val codec: FoCodec = FoCodec(),
    sizeLimit: Int = 8192,
    seed: FoStore.() -> Unit = { items mutate listOf("seed") },
) : Store<FoStore>(),
    SchemaVersioned {
    @Volatile var scopeFails: Throwable? = null

    @Volatile var versionFails: Throwable? = null

    val fetches = AtomicInteger()

    override val scope: CoroutineScope
        get() {
            scopeFails?.let { throw it }
            return writerScope
        }

    override val schemaVersion: Int
        get() {
            versionFails?.let { throw it }
            return 1
        }

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {}

    val note by state(codec = codec, tags = setOf(StateTag.UserAuthored)) { "" }
    val items by state(codec = ItemsCodec, tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { seed() }
            overlay(kv, OVERLAY_KEY, sizeLimit)
            refresh {
                fetches.incrementAndGet()
                listOf("fresh")
            } adopt { fetched -> items mutate fetched }
        }
}

/** The overlay blob of a [FoStore] whose note is [note]. */
private fun blobWithNote(
    note: String,
    scope: CoroutineScope,
): String {
    val donor = FoStore(RecordingKv(), scope)
    donor.action { this.note mutate note }
    return donor.snapshot(SnapshotScope.UserAuthored).encode().also { donor.dispose() }
}

/** The note [kv]'s blob holds. */
private fun FoStore.persistedNote(kv: RecordingKv): String? = persisted(kv)[note]

/** Hydrate on [scope] and wait — at most 10 s — until it settled Hydrated, the overlay LOADED. */
private suspend fun FoStore.hydratedAndLoaded(scope: CoroutineScope) {
    hydration.hydrate(scope)
    assertEquals(Hydration.Hydrated, withTimeout(10.seconds) { hydration.awaitSettled() })
    assertEquals(OverlayStatus.Loaded, hydration.overlayStatus)
}

class FatalOverlayTest {
    @Test fun aScopeStackOverflowWhenACommitLaunchesTheWriterPropagatesOutOfThatCommitAndLeavesItIdle() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv()
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                store.hydratedAndLoaded(handled.scope)
                val fatal = StackOverflowError()
                store.scopeFails = fatal

                val thrown = assertPropagates<StackOverflowError> { store action { note mutate "x" } }
                store.scopeFails = null

                assertSame(fatal, thrown, "out of the commit whose observer could not launch the writer")
                assertEquals("x", store.note.value, "the commit stands")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                assertIs<TransactionResult.Success<*>>(store action { note mutate "y" })
                store.hydration.overlayWritten()
                assertEquals("y", store.persistedNote(kv), "the writer was left idle: the next change wrote")
            }
        }

    @Test fun aPutStackOverflowFailsTheWritersCoroutineUnreportedAndTheNextChangeWritesAgain() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv()
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                store.hydratedAndLoaded(handled.scope)
                val fatal = StackOverflowError()
                kv.failPut = fatal

                assertIs<TransactionResult.Success<*>>(store action { note mutate "x" })

                assertSame(fatal, handled.first(), "the writer's coroutine failed with it, unwrapped")
                kv.failPut = null
                store.hydration.overlayWritten()
                assertTrue(reported.isEmpty(), "no OverlayException: $reported")
                assertNull(kv.value(), "nothing was written")
                assertIs<TransactionResult.Success<*>>(store action { note mutate "y" })
                store.hydration.overlayWritten()
                assertEquals("y", store.persistedNote(kv), "the next change wrote the latest values")
                assertEquals(1, handled.caught.size, "one failed coroutine: ${handled.caught}")
            }
        }

    @Test fun aCodecEncodeStackOverflowFailsTheWritersCoroutineAndKeepsTheLastBlob() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv()
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                store.hydratedAndLoaded(handled.scope)
                store.action { note mutate "a" }
                store.hydration.overlayWritten()
                val fatal = StackOverflowError()
                store.codec.encodeFails = fatal

                assertIs<TransactionResult.Success<*>>(store action { note mutate "b" })

                assertSame(fatal, handled.first(), "the writer's coroutine failed with it, unwrapped")
                store.codec.encodeFails = null
                store.hydration.overlayWritten()
                assertTrue(reported.isEmpty(), "no OverlayException: $reported")
                assertEquals("a", store.persistedNote(kv), "the key keeps the last blob")
                store.action { note mutate "c" }
                store.hydration.overlayWritten()
                assertEquals("c", store.persistedNote(kv))
            }
        }

    @Test fun aCaptureStackOverflowFailsTheWritersCoroutineAndKeepsTheLastBlob() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv()
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                store.hydratedAndLoaded(handled.scope)
                store.action { note mutate "a" }
                store.hydration.overlayWritten()
                val fatal = StackOverflowError()
                // Read by the writer's snapshot(SnapshotScope.UserAuthored).
                store.versionFails = fatal

                assertIs<TransactionResult.Success<*>>(store action { note mutate "b" })

                assertSame(fatal, handled.first(), "the writer's coroutine failed with it, unwrapped")
                store.versionFails = null
                store.hydration.overlayWritten()
                assertTrue(reported.isEmpty(), "no OverlayException: $reported")
                assertEquals("a", store.persistedNote(kv), "the key keeps the last blob")
                store.action { note mutate "c" }
                store.hydration.overlayWritten()
                assertEquals("c", store.persistedNote(kv))
            }
        }

    @Test fun aHandlerStackOverflowForAnOverlayReportFailsTheWritersCoroutineOnce() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv()
                val store = FoStore(kv, handled.scope, sizeLimit = 10)
                val fatal = StackOverflowError()
                val handlerSaw = CopyOnWriteArrayList<Throwable>()
                store.uncaughtObserverHandler = {
                    handlerSaw += it
                    if (it is OverlayException) throw fatal
                }
                store.hydratedAndLoaded(handled.scope)

                assertIs<TransactionResult.Success<*>>(store action { note mutate "longer than ten characters" })

                assertSame(fatal, handled.first(), "the writer's coroutine failed with the handler's")
                store.hydration.overlayWritten()
                assertIs<OverlayException>(handlerSaw.single(), "handed the too-large report once: $handlerSaw")
                assertNull(kv.value(), "nothing was written")
            }
        }

    @Test fun aHandlerStackOverflowForThePoisonedReportPropagatesOutOfHydrateAfterTheRefreshLaunched() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
                val store = FoStore(kv, handled.scope)
                val fatal = StackOverflowError()
                val handlerSaw = CopyOnWriteArrayList<Throwable>()
                store.uncaughtObserverHandler = {
                    handlerSaw += it
                    if (it is OverlayException) throw fatal
                }

                val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(handled.scope) }
                store.uncaughtObserverHandler = null

                assertSame(fatal, thrown)
                assertEquals(Hydration.Hydrated, withTimeout(10.seconds) { store.hydration.awaitSettled() })
                assertEquals(listOf("fresh"), store.items.value, "the refresh launched despite the fatal error")
                store.hydration.hydrate(handled.scope)
                assertEquals(1, store.fetches.get(), "Hydrated: a second hydrate does nothing")
                assertEquals("not a snapshot", kv.value(), "the blob is kept")
                assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)
                assertIs<OverlayException>(handlerSaw.single())
            }
        }

    @Test fun aBaseStackOverflowLeavesTheOverlayUnpoisonedForTheNextSeed() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv(mapOf(OVERLAY_KEY to blobWithNote("kept", handled.scope)))
                val fatal = StackOverflowError()
                var armed = true
                val store =
                    FoStore(kv, handled.scope, seed = {
                        if (armed) throw fatal
                        items mutate listOf("seed")
                    })
                val reported = store.recordedReports()

                val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(handled.scope) }
                armed = false

                assertSame(fatal, thrown)
                assertEquals(Hydration.Detached, store.hydration.current)
                assertEquals(OverlayStatus.Unloaded, store.hydration.overlayStatus, "not poisoned")
                assertTrue(reported.isEmpty(), "never reported: $reported")
                store.hydratedAndLoaded(handled.scope)
                assertEquals("kept", store.note.value, "the next seed applied the blob")
            }
        }

    @Test fun aSeedObserverStackOverflowStillLeavesTheOverlayLoaded() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv()
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                val fatal = StackOverflowError()
                store.items effect { if (this == listOf("seed")) throw fatal }

                val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(handled.scope) }

                assertSame(fatal, thrown)
                assertEquals(Hydration.Hydrated, withTimeout(10.seconds) { store.hydration.awaitSettled() })
                assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus, "the seed applied: LOADED")
                store.action { note mutate "x" }
                store.hydration.overlayWritten()
                assertEquals("x", store.persistedNote(kv), "the next UserAuthored change is written")
                assertTrue(reported.isEmpty(), "never reported: $reported")
            }
        }

    @Test fun aRestoreDecodeStackOverflowRollsTheSeedBackWithoutPoisoningTheBlob() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val blob = blobWithNote("kept", handled.scope)
                val kv = RecordingKv(mapOf(OVERLAY_KEY to blob))
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                val fatal = StackOverflowError()
                store.codec.decodeFails = fatal

                val thrown = assertPropagates<StackOverflowError> { store.hydration.hydrate(handled.scope) }
                store.codec.decodeFails = null

                assertSame(fatal, thrown)
                assertEquals(Hydration.Detached, store.hydration.current, "the seed rolled back")
                assertEquals(OverlayStatus.Unloaded, store.hydration.overlayStatus, "not poisoned")
                assertTrue(reported.isEmpty(), "no poisoned report: $reported")
                assertEquals(blob, kv.value())
                store.hydratedAndLoaded(handled.scope)
                assertEquals("kept", store.note.value, "the next seed applied the blob")
            }
        }

    @Test fun aScopeStackOverflowWhenClearOverlaySignalsTheWriterPropagatesOnceTheOverlayLoaded() =
        runBlocking<Unit> {
            FatalCatchingScope().use { handled ->
                val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
                val store = FoStore(kv, handled.scope)
                val reported = store.recordedReports()
                store.hydration.hydrate(handled.scope)
                assertEquals(Hydration.Hydrated, withTimeout(10.seconds) { store.hydration.awaitSettled() })
                assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)
                val fatal = StackOverflowError()
                // A change counted after clearOverlay() removed the blob, while it
                // waits for the store: a suspendAction holding it commits one.
                val removed = CompletableDeferred<Unit>()
                kv.afterRemove = { removed.complete(Unit) }
                val holding = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder =
                    launch {
                        store.suspendAction {
                            holding.complete(Unit)
                            release.await()
                            note mutate "late"
                        }
                    }
                withTimeout(10.seconds) { holding.await() } // the holder holds the store, parked
                store.scopeFails = fatal

                val clearing = async { runCatching { store.hydration.clearOverlay() } }
                withTimeout(10.seconds) { removed.await() }
                release.complete(Unit)
                holder.join()
                val outcome = withTimeout(10.seconds) { clearing.await() }
                store.scopeFails = null

                assertSame(fatal, outcome.exceptionOrNull()?.unrecovered(), "clearOverlay threw it: $outcome")
                assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus, "the overlay stood anew first")
                assertNull(kv.value(), "the blob was removed")
                store.action { note mutate "later" }
                store.hydration.overlayWritten()
                assertEquals("later", store.persistedNote(kv), "a later change writes")
                assertTrue(reported.all { it is OverlayException }, "only the poisoned report: $reported")
            }
        }
}
