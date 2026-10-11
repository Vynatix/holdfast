@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.RestoreRejectedException
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.SnapshotMigrationException
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachments
import com.vynatix.holdfast.internalQualifiedName
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The store envelope around [states] (text by name), at schema [schema]: a blob as another writer left it. */
private fun envelope(
    states: String,
    schema: Int = 1,
): String = """{"format":"holdfast.store","v":1,"schema":$schema,"states":{$states}}"""

/** Records, per transaction id, the qualified names of the states it staged, as middleware sees them. */
private class StagedLog : Middleware<Reader>() {
    val staged = mutableMapOf<String, Set<String>>()

    override fun onTransactionCompleted(context: MiddlewareContext<Reader>) {
        val names =
            context.transaction.modifiedStates
                .mapNotNull { it.internalQualifiedName }
                .toSet()
        staged[context.transaction.id] = names
    }
}

/**
 * A reader at schema 2, which renamed `pins` (schema 1) to `pinned`, and
 * schema 1's `feed` — a name it did not declare — to its Remote `items`.
 */
private class ReaderV2(
    kv: SuspendingKvStore,
) : Store<ReaderV2>(),
    SchemaVersioned {
    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        if (from < 2) {
            view.rename("pins", "pinned")
            view.rename("feed", "items")
        }
    }

    val pinned by state(codec = PinsCodec, tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val items by state(codec = ItemsCodec, tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            overlay(kv, OVERLAY_KEY)
            refresh { } adopt { }
        }
}

/** A sketchbook whose UserAuthored family has no codec, so a blob lists it as skipped; base creates an entry. */
private class Sketchbook(
    kv: SuspendingKvStore,
) : Store<Sketchbook>() {
    val title by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val sketches by keyedState<String, String>(tags = setOf(StateTag.UserAuthored)) { "" }
    val hydration =
        hydrator {
            base { sketches["from base"] mutate "a sketch" }
            overlay(kv, OVERLAY_KEY)
            refresh { } adopt { }
        }
}

/** Seed data a `base { }` restores: a welcome draft, and bundled pins. */
private fun bundledWithWelcome(): StoreSnapshot =
    Reader(RecordingKv()).let { donor ->
        donor.action {
            drafts["welcome"] mutate "Hi"
            pinned mutate setOf("bundled")
        }
        donor.snapshot().also { donor.dispose() }
    }

/**
 * The persisted UserAuthored overlay's inbound half (issue #20, R8 and R3;
 * plan PR 14): the seed transaction runs `base { }`, then puts back the
 * overlay's UserAuthored entries, then moves to Seeded — one commit, in which
 * the overlay wins over what `base { }` restored — and a blob it cannot apply
 * is reported and kept, never written over.
 */
class HydrationOverlayTest {
    @Test fun baseOverlayAndSeededCommitTogetherAndTheOverlayWinsOverABaseRestoreOfTheSameState() =
        runBlocking {
            // The bundled seed data base { } restores holds pins too.
            val bundled =
                Reader(RecordingKv()).let { donor ->
                    donor.action {
                        pinned mutate setOf("bundled")
                        items mutate listOf("seed")
                    }
                    donor.snapshot().also { donor.dispose() }
                }
            val kv = RecordingKv(mapOf(OVERLAY_KEY to readerBlob { pinned mutate setOf("mine") }))
            val store = Reader(kv, seed = { restore(bundled) })
            store.bindToScope(this)
            val log = StagedLog()
            store.middlewares(log)
            val pins = mutableListOf<Set<String>>()
            val phaseWhenPinned = mutableListOf<Hydration>()
            store.pinned effect {
                pins += this
                phaseWhenPinned += store.hydration.current
            }

            store.hydration.hydrate(this)

            // R8's ordering: base, then the overlay — the overlay wins over
            // base's restore of the same state — in the seed transaction, which
            // also moved the phase to Seeded: one commit, so no observer ever
            // saw the bundled pins, and the one that saw the user's saw Seeded.
            assertEquals(setOf("mine"), store.pinned.value)
            assertEquals(listOf("seed"), store.items.value, "base { } ran")
            assertEquals(listOf(emptySet(), setOf("mine")), pins)
            assertEquals(listOf(Hydration.Detached, Hydration.Seeded), phaseWhenPinned)
            val seed = checkNotNull(log.staged[SEED_ID]) { "no HydrationSeed transaction: ${log.staged.keys}" }
            assertTrue("Reader.pinned" in seed && "Reader.items" in seed && "Reader.hydration" in seed, "$seed")

            // Then the refresh, which adopts into Remote states only.
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            assertEquals(listOf("fresh"), store.items.value)
            assertEquals(setOf("mine"), store.pinned.value)
            store.dispose()
        }

    @Test fun onlyTheBlobsUserAuthoredEntriesAreRestored() =
        runBlocking {
            // Another writer's blob, holding a Remote and a Secret state's
            // text next to the UserAuthored ones, and a name the store dropped.
            val blob = envelope(""""gone":"x","items":"stale","note":"hello","pinned":"mine","token":"leaked"""")
            val store = Reader(RecordingKv(mapOf(OVERLAY_KEY to blob)))
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }

            store.hydration.hydrate(this)

            assertEquals(setOf("mine"), store.pinned.value)
            assertEquals("hello", store.note.value)
            assertEquals(listOf("seed"), store.items.value, "a Remote state is never restored from the overlay")
            assertEquals("", store.token.value, "nor a Secret one")
            assertEquals(emptyList(), reported, "an unknown name is ignored")
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun noBlobSeedsBaseAloneAndTheSeedsOwnCommitIsNotWritten() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv, seed = { pinned mutate setOf("bundled") })
            store.bindToScope(this)

            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            store.hydration.overlayWritten()

            assertEquals(setOf("bundled"), store.pinned.value)
            assertEquals(1, kv.gets)
            assertEquals(0, kv.puts, "the seed (and the adoption, which touches no UserAuthored state) writes nothing")
            store.dispose()
        }

    @Test fun anUnreadableBlobIsReportedKeptAndNeverOverwritten() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }

            store.hydration.hydrate(this)

            // The seed still committed base { }, without the overlay.
            assertEquals(Hydration.Seeded, store.hydration.current)
            assertEquals(listOf("seed"), store.items.value)
            val report = assertIs<OverlayException>(reported.single())
            assertEquals(OVERLAY_KEY, report.key)
            assertIs<SnapshotFormatException>(report.cause)
            assertTrue("Reader" in report.message.orEmpty() && OVERLAY_KEY in report.message.orEmpty())
            assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)

            // What the user writes now stays in memory: the blob is never written over.
            store.action { pinned mutate setOf("new") }
            store.hydration.overlayWritten()
            assertEquals("not a snapshot", kv.value())
            assertEquals(0, kv.puts)

            // A later seed reads it again, and keeps it again.
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            store.hydration.invalidate()
            store.hydration.hydrate(this)
            assertEquals(2, reported.size)
            store.action { note mutate "still in memory" }
            store.hydration.overlayWritten()
            assertEquals("not a snapshot", kv.value())
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aBlobOfANewerSchemaIsReportedAndKept() =
        runBlocking {
            val tooNew = envelope(""""pinned":"mine"""", schema = 2)
            val kv = RecordingKv(mapOf(OVERLAY_KEY to tooNew))
            val store = Reader(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }

            store.hydration.hydrate(this)

            assertEquals(emptySet(), store.pinned.value)
            val migration = assertIs<SnapshotMigrationException>(assertIs<OverlayException>(reported.single()).cause)
            assertEquals(2, migration.snapshotVersion)
            assertEquals(1, migration.storeVersion)
            store.action { pinned mutate setOf("new") }
            store.hydration.overlayWritten()
            assertEquals(tooNew, kv.value())
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aBlobTheRestoreRejectsIsReportedWithoutItsValuesAndKept() =
        runBlocking {
            // The pins' codec cannot decode this text: the restore rejects the blob.
            val rejected = envelope(""""note":"hello","pinned":"!secret-looking pins"""")
            val kv = RecordingKv(mapOf(OVERLAY_KEY to rejected))
            val store = Reader(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }

            store.hydration.hydrate(this)

            assertEquals("", store.note.value, "nothing of a rejected blob is restored")
            val report = assertIs<OverlayException>(reported.single())
            assertIs<RestoreRejectedException>(report.cause)
            val chain = generateSequence<Throwable>(report) { it.cause }.joinToString { it.message.orEmpty() }
            assertFalse("secret-looking" in chain || "hello" in chain, chain)
            store.action { note mutate "new" }
            store.hydration.overlayWritten()
            assertEquals(rejected, kv.value())
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun anOlderBlobIsMigratedBeforeItsUserAuthoredEntriesAreMatched() =
        runBlocking {
            // Written at schema 1, when the state was called `pins`, next to
            // an undeclared `feed` that the migration renames into a Remote
            // state: matched before the migration, it would be admitted.
            val kv = RecordingKv(mapOf(OVERLAY_KEY to envelope(""""feed":"stale","pins":"mine"""", schema = 1)))
            val store = ReaderV2(kv)
            store.bindToScope(this)

            store.hydration.hydrate(this)

            assertEquals(setOf("mine"), store.pinned.value)
            assertEquals(emptyList(), store.items.value, "the overlay never writes a Remote state, even one migrate renamed into")
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun anEntryTheUserEvictedNeverComesBackFromBasesSeedData() =
        runBlocking {
            val bundled = bundledWithWelcome()
            val kv = RecordingKv()
            val store = Reader(kv, seed = { restore(bundled) })
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            assertEquals("Hi", store.drafts["welcome"].value, "no blob yet: base { } alone")

            store.action { drafts.evict("welcome") }
            store.hydration.overlayWritten()
            assertEquals(emptySet(), persisted(kv).keysOf(store.drafts))

            // A later seed in this process: the held family replaces the one base restored.
            store.hydration.invalidate()
            store.hydration.hydrate(this)
            assertTrue("welcome" !in store.drafts, "the held family replaces base's")
            assertEquals(setOf("bundled"), store.pinned.value, "base's other values stand, where the user wrote none")
            store.hydration.awaitSettled()
            store.dispose()

            // A new process: the blob replaces the family base restored, and
            // the resurrected entry is never written back.
            val next = Reader(kv, seed = { restore(bundled) })
            next.bindToScope(this)
            next.hydration.hydrate(this)
            assertTrue("welcome" !in next.drafts, "the blob replaces base's family")
            next.action { note mutate "later" }
            next.hydration.overlayWritten()
            assertEquals(emptySet(), persisted(kv).keysOf(next.drafts))
            next.hydration.awaitSettled()
            next.dispose()
        }

    @Test fun aKeptBlobLeavesTheEntriesBaseCreated() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to envelope(""""drafts":{},"pinned":"!unreadable"""")))
            val bundled = bundledWithWelcome()
            val store = Reader(kv, seed = { restore(bundled) })
            store.bindToScope(this)
            store.uncaughtObserverHandler = { }

            store.hydration.hydrate(this)

            assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)
            assertEquals("Hi", store.drafts["welcome"].value, "the seed committed base { } without the blob")
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aFamilyTheBlobSkippedKeepsTheEntriesBaseCreated() =
        runBlocking {
            val blob =
                Sketchbook(RecordingKv()).let { donor ->
                    donor.action {
                        title mutate "mine"
                        sketches["mine"] mutate "not persisted: no codec"
                    }
                    donor.snapshot(SnapshotScope.UserAuthored).encode().also { donor.dispose() }
                }
            assertEquals(setOf("sketches"), StoreSnapshot.decode(blob).unencodableStateNames)
            val store = Sketchbook(RecordingKv(mapOf(OVERLAY_KEY to blob)))
            store.bindToScope(this)

            store.hydration.hydrate(this)

            assertEquals("mine", store.title.value)
            assertEquals(setOf("from base"), store.sketches.entries.keys, "a family the blob does not list is untouched")
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aHandlerThatThrowsForTheSeedsReportIsIgnoredAndFailsNothing() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv)
            store.uncaughtObserverHandler = { throw it }
            val escaped = mutableListOf<Throwable>()
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + supervisor + CoroutineExceptionHandler { _, e -> escaped += e })
            store.bindToScope(scope)
            try {
                store.hydration.hydrate(scope)

                assertEquals(Hydration.Hydrated, withTimeout(5.seconds) { store.hydration.awaitSettled() })
                assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)
                assertEquals("not a snapshot", kv.value(), "the blob was not written over")
                assertEquals(emptyList(), escaped, "nothing reached the scope's exception handler")
            } finally {
                supervisor.cancel()
                store.dispose()
            }
        }

    @Test fun aRestoreFailureOfAnotherClassIsReportedByClassOnly() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to readerBlob { pinned mutate setOf("private") }))
            val store = Reader(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.middlewares(
                object : Middleware<Reader>() {
                    override fun onTransactionCompleted(context: MiddlewareContext<Reader>) {
                        if (context.transaction.id == "Restore") throw IllegalArgumentException("pinned=private")
                    }
                },
            )

            store.hydration.hydrate(this)

            val report = assertIs<OverlayException>(reported.single())
            assertNull(report.cause, "a cause of another class may quote a value")
            assertTrue("IllegalArgumentException" in report.message.orEmpty(), report.message)
            assertFalse("private" in report.message.orEmpty(), report.message)
            assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)
            assertEquals(emptySet(), store.pinned.value)
            val blob = kv.value()
            store.action { note mutate "later" }
            store.hydration.overlayWritten()
            assertEquals(blob, kv.value(), "the blob is kept")
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aPoisonedOverlayLoadsWhenALaterSeedAppliesAReadableBlob() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv)
            store.bindToScope(this)
            store.uncaughtObserverHandler = { }
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            assertEquals(OverlayStatus.Poisoned, store.hydration.overlayStatus)

            kv.put(OVERLAY_KEY, readerBlob { pinned mutate setOf("mine") })
            store.hydration.invalidate()
            store.hydration.hydrate(this)

            assertEquals(setOf("mine"), store.pinned.value)
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            store.action { note mutate "after" }
            store.hydration.overlayWritten()
            assertEquals("after", persisted(kv)[store.note])
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aClearOverlayBetweenTheSeedsReadAndItsGateIsReadAgain() =
        runTest {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv)
            store.bindToScope(backgroundScope)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            val release = CompletableDeferred<Unit>()
            val holder = launch(start = CoroutineStart.UNDISPATCHED) { store.suspendAction { release.await() } }
            val hydrating = launch { store.hydration.hydrate(backgroundScope) }
            // hydrate() read the blob, and backs off at the gate until t = 15 ms.
            advanceTimeBy(10)
            assertEquals(1, kv.gets)
            val clearing = launch { store.hydration.clearOverlay() }
            // clearOverlay() removed the key, and backs off until t = 11 ms.
            runCurrent()
            assertEquals(1, kv.removes)
            // The holder commits and releases the store; the clear takes the
            // gate first, and the overlay stands anew.
            release.complete(Unit)
            runCurrent()
            advanceTimeBy(2)
            assertTrue(clearing.isCompleted, "the clear took the gate first")
            // hydrate()'s gate finds its read overtaken, reads again, and seeds.
            joinAll(holder, clearing, hydrating)

            assertEquals(2, kv.gets, "the seed read the key again after clearOverlay()")
            assertEquals(OverlayStatus.Loaded, store.hydration.overlayStatus)
            assertEquals(emptyList(), reported, "the removed, unreadable blob was never applied")
            store.dispose()
        }

    @Test fun aLaterSeedPutsBackTheStoresOwnValuesWithoutReadingTheKey() =
        runBlocking {
            val kv = RecordingKv()
            val store = Reader(kv, seed = { pinned mutate setOf("bundled") })
            store.bindToScope(this)
            store.hydration.hydrate(this)
            store.hydration.awaitSettled()
            store.action { pinned mutate setOf("mine") }

            store.hydration.invalidate()
            store.hydration.hydrate(this)

            assertEquals(2, store.baseRuns)
            assertEquals(setOf("mine"), store.pinned.value, "the overlay wins over base { } again")
            assertEquals(1, kv.gets, "a loaded overlay is the store's own values: the key is not read again")
            store.hydration.awaitSettled()
            store.dispose()
        }

    @Test fun aFailingReadMakesHydrateThrowAndChangesNothing() =
        runBlocking {
            val kv = RecordingKv()
            val offline = IllegalStateException("disk unavailable")
            kv.failGet = offline
            val store = Reader(kv)
            store.bindToScope(this)

            assertSame(offline, assertFailsWith<IllegalStateException> { store.hydration.hydrate(this) })
            assertEquals(Hydration.Detached, store.hydration.current)
            assertEquals(0, store.baseRuns)

            kv.failGet = null
            store.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
            store.dispose()
        }

    @Test fun aSeedRolledBackLeavesTheOverlayUnloaded() =
        runBlocking {
            val kv = RecordingKv(mapOf(OVERLAY_KEY to "not a snapshot"))
            val store = Reader(kv)
            store.bindToScope(this)
            val reported = mutableListOf<Throwable>()
            store.uncaughtObserverHandler = { reported += it }
            store.middlewares(HydrationMiddlewareLog { it == SEED_ID })

            assertFailsWith<IllegalStateException> { store.hydration.hydrate(this) }

            assertEquals(OverlayStatus.Unloaded, store.hydration.overlayStatus)
            assertEquals(emptyList(), reported, "a seed that rolled back applied nothing, so reports nothing")
            store.dispose()
        }

    @Test fun theOverlayKeyIsPinnedAndNamedByTheAttachmentsPersistenceKeys() {
        val store = Reader(RecordingKv())
        assertEquals(OVERLAY_KEY, store.hydration.overlayKey)
        assertEquals(setOf(OVERLAY_KEY), store.internalAttachments().flatMap { it.persistenceKeys }.toSet())

        val plain = FeedStore { emptyList() }
        assertNull(plain.hydration.overlayKey)
        assertEquals(emptySet(), plain.internalAttachments().flatMap { it.persistenceKeys }.toSet())

        store.dispose()
        assertEquals(OVERLAY_KEY, store.hydration.overlayKey, "keeps answering after dispose")
    }

    @Test fun overlayRefusesAnEmptyKeyASizeLimitBelowOneAndASecondOverlay() {
        class Probe(
            spec: HydrationSpec<Probe>.() -> Unit,
        ) : Store<Probe>() {
            val hydration =
                hydrator {
                    spec()
                    refresh { } adopt { }
                }
        }
        val kv = RecordingKv()
        assertFailsWith<IllegalArgumentException> { Probe { overlay(kv, "") } }
        assertFailsWith<IllegalArgumentException> { Probe { overlay(kv, OVERLAY_KEY, sizeLimit = 0) } }
        assertFailsWith<IllegalStateException> {
            Probe {
                overlay(kv, OVERLAY_KEY)
                overlay(kv, "other")
            }
        }
    }

    @Test fun noStateCanBeBothUserAuthoredAndSecretSoTheOverlaysGuardIsUnreachable() {
        // The overlay refuses a store whose UserAuthored states include a
        // Secret one (OverlayWatch.watchAll). Declarations already refuse the
        // pairing — a state's tags and a keyed family's — and a derived state
        // is never UserAuthored, so no store gets there.
        class SecretAuthored : Store<SecretAuthored>() {
            val both by state(tags = setOf(StateTag.UserAuthored, StateTag.Secret)) { "" }
        }

        class SecretFamily : Store<SecretFamily>() {
            val both by keyedState<String, String>(tags = setOf(StateTag.UserAuthored, StateTag.Secret)) { "" }
        }
        assertFailsWith<IllegalArgumentException> { SecretAuthored() }
        assertFailsWith<IllegalArgumentException> { SecretFamily() }
    }

    @Test fun aHydratorWithoutAnOverlayRefusesClearOverlay() =
        runBlocking {
            val store = FeedStore { emptyList() }
            val refused = assertFailsWith<IllegalStateException> { store.hydration.clearOverlay() }
            assertTrue("no overlay" in refused.message.orEmpty(), refused.message)
            store.dispose()
        }

    @Test fun clearOverlayIsRefusedInsideAnAction() =
        runBlocking {
            val blob = readerBlob { note mutate "kept" }
            val kv = RecordingKv(mapOf(OVERLAY_KEY to blob))
            val store = Reader(kv)
            val result = store.suspendAction { store.hydration.clearOverlay() }
            val refused = assertIs<IllegalStateException>((result as TransactionResult.Error).exception)
            assertTrue("clearOverlay()" in refused.message.orEmpty(), refused.message)
            assertEquals(0, kv.removes, "refused before the key is touched")
            assertEquals(blob, kv.value())
            store.dispose()
        }
}
