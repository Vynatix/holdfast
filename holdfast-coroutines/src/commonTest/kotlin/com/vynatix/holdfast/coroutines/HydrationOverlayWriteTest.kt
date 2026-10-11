@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Observable
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transformer
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import com.vynatix.holdfast.reset
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A store whose one UserAuthored state is encrypted at rest. */
private class Journal(
    kv: SuspendingKvStore,
) : Store<Journal>() {
    val entry by state(
        transformer = EncryptingTransformer(XorCipher("journal-key".encodeToByteArray())),
        codec = StringCodec,
        tags = setOf(StateTag.UserAuthored),
    ) { "" }
    val hydration =
        hydrator {
            overlay(kv, OVERLAY_KEY)
            refresh { } adopt { }
        }
}

/** A codec whose `encode` fails for `"boom"`, quoting it, as a careless codec might. */
private object FussyCodec : StateCodec<String> {
    override fun encode(value: String): String {
        check(value != "boom") { "cannot encode the value: secret boom" }
        return value
    }

    override fun decode(string: String): String = string
}

/** A transformer whose `get` fails for `"unreadable"`: a commit of it notifies no observer of the state. */
private object FailingGet : Transformer<String> {
    override fun set(value: String): String = value

    override fun get(value: String): String {
        check(value != "unreadable") { "cannot read it back" }
        return value
    }
}

/** UserAuthored states that fail on the way out: in their codec, or in their transformer's `get`. */
private class Fussy(
    kv: SuspendingKvStore,
) : Store<Fussy>() {
    val text by state(codec = FussyCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val shown by state(transformer = FailingGet, codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val hydration =
        hydrator {
            overlay(kv, OVERLAY_KEY)
            refresh { } adopt { }
        }
}

/**
 * The persisted UserAuthored overlay's outbound half (issue #20, R8 and R3;
 * plan PR 14): once the seed has loaded the overlay, a conflated writer on
 * the store's scope writes `snapshot(SnapshotScope.UserAuthored).encode()`
 * after every commit that changes a UserAuthored state — never a Remote or
 * Secret one, never a blob over the size limit, never on the scope that
 * called `hydrate()` — and `clearOverlay()` removes what it wrote.
 */
class HydrationOverlayWriteTest {
    @Test fun theOverlayIsTheStoreEnvelopeOfTheUserAuthoredStatesAndNeverPersistsRemoteOrSecret() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())

            store.action {
                pinned mutate setOf("a", "b")
                token mutate "session-token-value"
                items mutate listOf("remote-only-value")
            }
            store.hydration.overlayWritten()

            val blob = checkNotNull(kv.value())
            assertEquals(store.snapshot(SnapshotScope.UserAuthored).encode(), blob, "exactly the R1 store envelope")
            assertEquals(setOf("drafts", "note", "pinned"), persisted(kv).stateNames)
            assertFalse("session-token-value" in blob, blob)
            assertFalse("remote-only-value" in blob || "fresh" in blob, blob)
            assertEquals(setOf("a", "b"), persisted(kv)[store.pinned])
            assertEquals(1, kv.puts)
            store.dispose()
        }

    @Test fun aBurstOfCommitsIsWrittenOnceWithTheLatestValues() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            // No suspension between them: the writer this scope runs starts after the last.
            repeat(10) { i -> store.action { note mutate "draft $i" } }
            store.hydration.overlayWritten()

            assertEquals(1, kv.puts)
            assertEquals("draft 9", persisted(kv)[store.note])
            store.dispose()
        }

    @Test fun aKeyedUserAuthoredFamilyPersistsItsEntriesAndEvictions() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            store.action {
                drafts["a"] mutate "alpha"
                drafts["b"] mutate "beta"
            }
            store.hydration.overlayWritten()
            assertEquals(setOf("a", "b"), persisted(kv).keysOf(store.drafts))

            store.action { drafts.evict("a") }
            store.hydration.overlayWritten()
            assertEquals(setOf("b"), persisted(kv).keysOf(store.drafts))

            // A new process puts the entry back.
            val next = Reader(kv)
            next.bindToScope(this)
            next.hydration.hydrate(this)
            assertEquals("beta", next.drafts["b"].value)
            next.hydration.awaitSettled()
            next.dispose()
            store.dispose()
        }

    @Test fun anEncryptedUserAuthoredStateRoundTripsAsCiphertext() =
        runBlocking {
            val kv = RecordingKv()
            val store = Journal(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            store.action { entry mutate "dear diary" }
            store.hydration.overlayWritten()
            val blob = checkNotNull(kv.value())
            assertFalse("dear diary" in blob, "the blob holds ciphertext: $blob")

            // The next process decrypts what it restores, without encrypting it twice.
            val next = Journal(kv)
            next.bindToScope(this)
            next.hydration.hydrate(this)
            assertEquals("dear diary", next.entry.value)
            next.hydration.awaitSettled()
            next.dispose()
            store.dispose()
        }

    @Test fun theWriterRunsOnTheStoresScopeAndSurvivesCancellationOfTheScopeThatCalledHydrate() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            val caller = CoroutineScope(Job())

            store.hydration.hydrate(caller)
            caller.cancel()
            store.hydration.awaitSettled()

            store.action { pinned mutate setOf("after the caller's scope was cancelled") }
            store.hydration.overlayWritten()

            assertEquals(setOf("after the caller's scope was cancelled"), persisted(kv)[store.pinned])
            store.dispose()
        }

    @Test fun aBlobOverTheSizeLimitIsReportedAndNotWritten() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv, sizeLimit = 200)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.action { note mutate "short" }
            store.hydration.overlayWritten()
            val written = checkNotNull(kv.value())

            store.action { note mutate "x".repeat(500) }
            store.hydration.overlayWritten()

            val report = assertIs<OverlayException>(reported.single())
            assertTrue("200" in report.message.orEmpty() && OVERLAY_KEY in report.message.orEmpty(), report.message)
            assertFalse("xxxxx" in report.message.orEmpty())
            assertEquals(written, kv.value(), "the key keeps the last blob written")
            assertEquals(1, kv.puts)

            store.action { note mutate "short again" }
            store.hydration.overlayWritten()
            assertEquals("short again", persisted(kv)[store.note])
            store.dispose()
        }

    @Test fun aFailingPutIsReportedByClassOnlyAndTheNextChangeWritesAgain() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            kv.failPut = IllegalArgumentException("Value too long: note=private words")
            store.action { note mutate "private words" }
            store.hydration.overlayWritten()
            val report = assertIs<OverlayException>(reported.single())
            assertNull(report.cause)
            assertFalse("private words" in report.message.orEmpty(), report.message)
            assertTrue("IllegalArgumentException" in report.message.orEmpty(), report.message)

            kv.failPut = null
            store.action { note mutate "later" }
            store.hydration.overlayWritten()
            assertEquals("later", persisted(kv)[store.note])
            store.dispose()
        }

    @Test fun clearOverlayRemovesTheBlobAndDropsPendingWritesButStopsNothingElse() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.action { pinned mutate setOf("mine") }
            store.hydration.overlayWritten()

            // A change whose write has not run yet (this scope runs it at the
            // next suspension), then the clear: the pending write is dropped.
            store.action { note mutate "unsaved" }
            store.hydration.clearOverlay()
            store.hydration.overlayWritten()

            assertNull(kv.value())
            assertEquals(1, kv.removes)
            assertEquals(setOf("mine"), store.pinned.value, "clearOverlay() changes no state")
            assertEquals(Hydration.Hydrated, store.hydration.current, "nor the phase")

            // Nothing else stopped: the next change writes again.
            store.action { note mutate "saved" }
            store.hydration.overlayWritten()
            assertEquals("saved", persisted(kv)[store.note])
            store.dispose()
        }

    @Test fun clearOverlayOfAKeptBlobLetsTheOverlayWriteAgain() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv)
            store.bindToScope(this)
            store.uncaughtObserverHandler = { }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)

            store.hydration.clearOverlay()
            assertNull(kv.value())
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)

            store.action { pinned mutate setOf("written at last") }
            store.hydration.overlayWritten()
            assertEquals(setOf("written at last"), persisted(kv)[store.pinned])
            store.dispose()
        }

    @Test fun clearOverlayBeforeTheFirstSeedLeavesNothingToPutBack() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to readerBlob { pinned mutate setOf("old") }))
            val store = Reader(kv)
            store.bindToScope(this)

            store.hydration.clearOverlay()
            store.hydration.hydrate(this)

            assertEquals(emptySet(), store.pinned.value)
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aFailingRemoveMakesClearOverlayThrowAndChangesNothing() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.action { pinned mutate setOf("mine") }
            store.hydration.overlayWritten()
            val blob = kv.value()

            // A change whose write has not run yet: a failed removal must not drop it.
            store.action { note mutate "unsaved" }
            val offline = IllegalStateException("offline")
            kv.failRemove = offline
            assertSame(offline, assertFailsWith<IllegalStateException> { store.hydration.clearOverlay() })
            assertEquals(blob, kv.value())
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            store.hydration.overlayWritten()
            assertEquals("unsaved", persisted(kv)[store.note], "the pending write was not dropped")
            store.dispose()

            // A kept blob stays kept, and is still never written over.
            val kept = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val poisoned = Reader(kept)
            poisoned.bindToScope(this)
            poisoned.uncaughtObserverHandler = { }
            poisoned.hydration.hydrate(this)
            poisoned.hydration.awaitSettled()
            kept.failRemove = offline
            assertSame(offline, assertFailsWith<IllegalStateException> { poisoned.hydration.clearOverlay() })
            assertEquals(OverlayStatus.Poisoned, poisoned.hydration.overlayStatus)
            poisoned.action { note mutate "in memory only" }
            poisoned.hydration.overlayWritten()
            assertEquals(0, kept.puts)
            assertEquals("not a snapshot", kept.value())
            poisoned.dispose()
        }

    @Test fun aCancelledStoreScopeIsReportedAndTheNextChangeOnALiveOneWrites() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            // On runBlocking's event loop, with a job of its own: the writer's order is deterministic.
            val storeScope = CoroutineScope(coroutineContext + Job())
            store.bindToScope(storeScope)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            storeScope.cancel()
            store.action { pinned mutate setOf("zebra-7") }
            store.hydration.overlayWritten()

            val report = assertIs<OverlayException>(reported.single())
            assertEquals(OVERLAY_KEY, report.key)
            assertNull(report.cause)
            assertTrue("CancellationException" in report.message.orEmpty(), report.message)
            assertFalse("zebra" in report.message.orEmpty(), report.message)
            assertEquals(0, kv.puts)

            store.bindToScope(this)
            store.action { note mutate "on a live scope" }
            store.hydration.overlayWritten()
            assertEquals(setOf("zebra-7"), persisted(kv)[store.pinned], "the latest values, the missed change's too")
            assertEquals("on a live scope", persisted(kv)[store.note])
            store.dispose()
        }

    @Test fun aCodecFailingInEncodeIsReportedWithoutTheValueAndTheKeyKeepsItsBlob() =
        runBlocking {
            val kv = RecordingKv()
            val store = Fussy(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.action { text mutate "ok" }
            store.hydration.overlayWritten()
            val blob = checkNotNull(kv.value())

            store.action { text mutate "boom" }
            store.hydration.overlayWritten()

            val report = assertIs<OverlayException>(reported.single())
            assertIs<IllegalStateException>(report.cause, "core's value-free wrapper of the codec's failure")
            val chain = generateSequence<Throwable>(report) { it.cause }.joinToString { it.message.orEmpty() }
            assertFalse("secret" in chain || "boom" in chain, chain)
            assertEquals(blob, kv.value(), "the key keeps the last blob written")

            store.action { text mutate "ok again" }
            store.hydration.overlayWritten()
            assertEquals("ok again", persisted(kv)[store.text])
            store.dispose()
        }

    @Test fun aCommitWhoseFanoutSkipsTheOverlaysObserverIsWrittenWithTheNextChange() =
        runBlocking {
            val kv = RecordingKv()
            val store = Fussy(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()

            // Transformer.get fails in the fanout: no observer of the state runs.
            store.action { shown mutate "unreadable" }
            store.hydration.overlayWritten()
            assertEquals(1, reported.size, "the fanout failure is reported")
            assertEquals(0, kv.puts, "the overlay did not learn of the change")

            // The next change of a UserAuthored state writes it (its raw value).
            store.action { text mutate "next" }
            store.hydration.overlayWritten()
            assertTrue("\"shown\":\"unreadable\"" in checkNotNull(kv.value()), kv.value())
            store.dispose()
        }

    @Test fun aHandlerThatThrowsForAWritersReportIsIgnoredAndTheWriterGoesOn() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            val escaped = mutableListOf<Throwable>()
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + supervisor + CoroutineExceptionHandler { _, e -> escaped += e })
            store.bindToScope(scope)
            try {
                store.hydration.hydrate(scope)
                store.hydration.awaitSettled()
                store.uncaughtObserverHandler = { throw it }
                kv.failPut = IllegalStateException("disk full")

                store.action { note mutate "not written" }
                store.hydration.overlayWritten()
                assertEquals(emptyList(), escaped, "nothing reached the scope's exception handler")

                kv.failPut = null
                store.action { note mutate "written" }
                store.hydration.overlayWritten()
                assertEquals("written", persisted(kv)[store.note], "the writer went back to idle, and writes again")
            } finally {
                supervisor.cancel()
                store.dispose()
            }
        }

    @Test fun aBridgesInboundValueIsWrittenAndTheNextProcessRestoresIt() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            var push: (String) -> Unit = { }
            store.run {
                note observeFrom
                    Observable { observer ->
                        push = observer
                        Disposable { }
                    }
            }

            push("from the bridge")
            store.hydration.overlayWritten()
            assertEquals("from the bridge", persisted(kv)[store.note])
            store.dispose()

            val next = Reader(kv)
            next.bindToScope(this)
            next.hydration.hydrate(this)
            assertEquals("from the bridge", next.note.value)
            next.hydration.awaitSettled()
            next.dispose()
        }

    @Test fun resetThenClearOverlayReseedsTheInitialValuesOverBase() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv, seed = { note mutate "Hello," })
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            assertEquals("Hello,", store.note.value)
            store.action { note mutate "Dear Ada," }
            store.hydration.overlayWritten()

            store.reset()
            store.hydration.clearOverlay()
            store.hydration.hydrate(this)

            // The overlay stayed loaded: the seed put the reset (initial)
            // values back over base's, where a fresh install shows base's.
            assertEquals("", store.note.value)
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            store.hydration.awaitSettled()
            store.hydration.overlayWritten()
            assertNull(kv.value(), "the seed's own commit is not written")
            store.dispose()
        }

    @Test fun resetIsACommitLikeAnyOtherAndIsWritten() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv)
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.action { pinned mutate setOf("mine") }
            store.hydration.overlayWritten()

            store.reset()
            store.hydration.overlayWritten()

            assertEquals(emptySet(), persisted(kv)[store.pinned])
            assertEquals(Hydration.Detached, store.hydration.current)
            store.dispose()
        }

    @Test fun nothingIsWrittenBeforeTheFirstSeedOrAfterDispose() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to readerBlob { note mutate "persisted" }))
            val store = Reader(kv)
            store.bindToScope(this)

            // Before the seed the overlay has not loaded: what the store holds
            // is not what the user authored, and writing it would lose the blob.
            store.action { note mutate "before hydrate" }
            assertEquals(0, kv.puts)

            store.hydration.hydrate(this)
            assertEquals("persisted", store.note.value, "the seed puts the overlay over what came before")
            store.hydration.awaitSettled()

            store.action { note mutate "last" }
            store.dispose()
            store.hydration.overlayWritten()
            assertEquals(0, kv.puts, "a writer that had not run when the store was disposed writes nothing")
        }
}
