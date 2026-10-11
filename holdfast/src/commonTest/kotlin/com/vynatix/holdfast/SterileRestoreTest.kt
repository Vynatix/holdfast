@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FeedStore : Store<FeedStore>() {
    var remoteInitializerRuns = 0
    var failRemoteInitializer = false

    /** The id of the transaction each run of `feed`'s initializer saw, `null` outside any. */
    val remoteInitializerContexts = mutableListOf<String?>()

    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val feed by state(codec = StringCodec, tags = setOf(StateTag.Remote)) {
        remoteInitializerRuns++
        remoteInitializerContexts += activeTransaction?.id
        check(!failRemoteInitializer) { "remote initializer fails" }
        "empty"
    }

    /** Reads another Remote state: a sterile restore gives it that state's reset value. */
    val feedSize by state(codec = IntCodec, tags = setOf(StateTag.Remote)) { feed.value.length }
    val plain by state { 0 }

    fun sync() =
        action {
            feed mutate "fetched"
            feedSize mutate 7
        }
}

/** A Remote state computed from a UserAuthored one: what a sterile restore must recompute from the restored value. */
private class AccountFeedStore : Store<AccountFeedStore>() {
    val account by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "anon" }
    val feed by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "for-${account.value}" }
}

/** A string codec that runs [onDecode] each time it decodes: while a restore of decoded text plans. */
private class HookedStringCodec(
    private val onDecode: () -> Unit,
) : StateCodec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = string.also { onDecode() }
}

/**
 * A Remote state that reaches a restored state only through an untagged one
 * no overlay holds: `prefs` (restored) → `locale` → `feed` (Remote).
 */
private class LocaleFeedStore : Store<LocaleFeedStore>() {
    /** Runs while a restore of decoded text plans, holding no lock of the store. */
    var whilePlanning: () -> Unit = {}

    val prefs by state(codec = HookedStringCodec { whilePlanning() }, tags = setOf(StateTag.UserAuthored)) { "en" }
    val locale by state { prefs.value }
    val feed by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "feed-${locale.value}" }
}

/** The persisted UserAuthored overlay of a [LocaleFeedStore] whose `prefs` is [prefs]. */
private fun localeOverlay(prefs: String): String {
    val source = LocaleFeedStore()
    source action { this.prefs mutate prefs }
    return source.snapshot(SnapshotScope.UserAuthored).encode()
}

/** Counts its decodes: a sterile restore must never decode a Remote entry. */
private class CountingIntCodec : StateCodec<Int> {
    var decodes = 0

    override fun encode(value: Int): String = IntCodec.encode(value)

    override fun decode(string: String): Int {
        decodes++
        return IntCodec.decode(string)
    }
}

private class CountedFeedStore : Store<CountedFeedStore>() {
    val sizeCodec = CountingIntCodec()
    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val feedSize by state(codec = sizeCodec, tags = setOf(StateTag.Remote)) { 5 }
}

private class RestoreIds : Middleware<FeedStore>() {
    val ids = mutableListOf<String>()

    override fun onTransactionStarted(context: MiddlewareContext<FeedStore>) {
        ids += context.transaction.id
    }
}

/**
 * A sterile restore (issue #20, R3, acceptance 3): `restore(snapshot, policy,
 * sterile = true)` drops the snapshot's Remote entries and resets every Remote
 * state to its initial value, in the restore's one transaction.
 */
class SterileRestoreTest {
    @Test fun aSterileRestoreResetsRemoteStatesToTheirInitialValues() {
        val store = FeedStore()
        store action {
            pins mutate "a,b"
            plain mutate 3
        }
        store.sync()
        val snapshot = store.snapshot()
        store action {
            pins mutate "z"
            plain mutate 9
            feed mutate "newest" // not 5 characters long, unlike its reset value
        }

        val report = store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()

        assertEquals("a,b", store.pins.value)
        assertEquals(3, store.plain.value)
        assertEquals("empty", store.feed.value, "the snapshot's synced value did not survive")
        assertEquals(5, store.feedSize.value, "a Remote initializer reads the other Remote states' reset values")
        assertEquals(setOf("feed", "feedSize"), report.sterilized)
        assertEquals(setOf("pins", "plain"), report.restored)
        assertTrue(report.issues.isEmpty(), "dropped Remote entries are not issues: ${report.issues}")
        assertTrue(report.kept.isEmpty(), "$report")
    }

    @Test fun aNonSterileRestoreStillRestoresRemoteStates() {
        val store = FeedStore()
        store.sync()
        val snapshot = store.snapshot()
        store action { feed mutate "newer" }
        val report = store.restore(snapshot, RestorePolicy.Strict).getOrThrow()
        assertEquals("fetched", store.feed.value)
        assertTrue(report.sterilized.isEmpty())
    }

    @Test fun aSterileRestoreIntoAFreshStoreLeavesRemoteStatesAtTheirInitialValues() {
        val source = FeedStore()
        source action { pins mutate "p" }
        source.sync()
        val text = source.snapshot().encode(includeRemote = true)

        val fresh = FeedStore()
        assertFalse(fresh.hasState("feed"))
        val report = fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict, sterile = true).getOrThrow()

        assertTrue(fresh.hasState("feed"), "the never-read Remote state was materialized")
        assertEquals(2, fresh.remoteInitializerRuns, "materialized before the action, then re-run by the reset, as reset() does")
        assertEquals(
            listOf<String?>(null, "Restore"),
            fresh.remoteInitializerContexts,
            "materialized in the plan, outside any transaction, then re-run by the reset inside the Restore action",
        )
        assertEquals("p", fresh.pins.value)
        assertEquals("empty", fresh.feed.value)
        assertEquals(setOf("feed", "feedSize"), report.sterilized)
    }

    @Test fun aSterileRestoreOfASnapshotWithoutRemoteEntriesStillResetsThem() {
        val store = FeedStore()
        val overlay = store.snapshot(SnapshotScope.UserAuthored)
        store.sync()
        store.restore(overlay, RestorePolicy.Strict, sterile = true).getOrThrow()
        assertEquals("empty", store.feed.value)
        assertEquals(5, store.feedSize.value)
    }

    @Test fun observersFireOnlyForRemoteStatesTheResetChanges() {
        val store = FeedStore()
        store.sync()
        val snapshot = store.snapshot()
        val feedFired = mutableListOf<String>()
        store { feed effect { feedFired += this } }
        store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
        store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
        assertEquals(listOf("fetched", "empty"), feedFired, "one fire for the change, none for the no-op")
    }

    @Test fun aThrowingRemoteInitializerRollsTheWholeRestoreBack() {
        val store = FeedStore()
        store action { pins mutate "before" }
        store.sync()
        val snapshot = store.snapshot()
        store action { pins mutate "after" }
        store.failRemoteInitializer = true

        val result = store.restore(snapshot, RestorePolicy.Strict, sterile = true)

        val error = assertIs<TransactionResult.Error>(result)
        assertContains(error.exception.message.orEmpty(), "remote initializer fails")
        assertEquals("after", store.pins.value, "the restored write rolled back with it")
        assertEquals("fetched", store.feed.value)
    }

    @Test fun aThrowingNeverReadRemoteInitializerFailsBeforeAnythingChanges() {
        val source = FeedStore()
        source action { pins mutate "p" }
        val snapshot = source.snapshot(SnapshotScope.UserAuthored)
        val target = FeedStore()
        target.failRemoteInitializer = true
        target action { pins mutate "own" }

        val result = target.restore(snapshot, RestorePolicy.Strict, sterile = true)

        assertIs<TransactionResult.Error>(result)
        assertEquals("own", target.pins.value)
        assertEquals(1, target.remoteInitializerRuns, "the never-read Remote state was materialized before the action")
        assertEquals(
            listOf<String?>(null),
            target.remoteInitializerContexts,
            "the never-read Remote state's initializer ran (and failed) in the plan, before the Restore action opened",
        )
    }

    @Test fun aSterileRestoreIsOneTransactionNamedRestore() {
        val store = FeedStore()
        store.sync()
        val snapshot = store.snapshot()
        val ids = RestoreIds()
        store.middlewares(ids)
        store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
        assertEquals(listOf("Restore"), ids.ids)
    }

    @Test fun insideAnActionASterileRestoreIsASavepoint() {
        val store = FeedStore()
        store.sync()
        val snapshot = store.snapshot()
        val rolledBack =
            store action {
                val report = restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
                assertEquals(setOf("feed", "feedSize"), report.sterilized)
                assertEquals("empty", feed.value, "the action reads its savepoint's reset value")
                assertEquals(5, feedSize.value)
                error("the enclosing action fails")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals("fetched", store.feed.value, "the reset rolled back with the enclosing action")
        assertEquals(7, store.feedSize.value)

        val committed =
            store action {
                restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
                pins mutate "after"
            }
        assertIs<TransactionResult.Success<Unit>>(committed)
        assertEquals("empty", store.feed.value)
        assertEquals(5, store.feedSize.value)
        assertEquals("after", store.pins.value, "a write after the sterile restore stands")
    }

    @Test fun aNestedSterileRestoreOverridesAPendingWriteToARemoteState() {
        val store = FeedStore()
        val overlay = store.snapshot(SnapshotScope.UserAuthored)
        val result =
            store action {
                feed mutate "x"
                restore(overlay, RestorePolicy.Strict, sterile = true).getOrThrow()
                assertEquals("empty", feed.value)
            }
        result.getOrThrow()
        assertEquals("empty", store.feed.value, "the reset overrode the enclosing action's pending write")
    }

    @Test fun aRemoteInitializerReadsTheRestoredValuesNotTheCommittedOnes() {
        val store = AccountFeedStore()
        store action { account mutate "ada" }
        val snapshot = store.snapshot()
        store action {
            account mutate "bob"
            feed mutate "synced"
        }

        store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()

        assertEquals("ada", store.account.value)
        // "for-bob" would be the committed account, "for-anon" account's reset
        // value: a sterile restore resets only the Remote states.
        assertEquals("for-ada", store.feed.value, "computed from the restored account, as a fresh store holding it would")
    }

    @Test fun aSterileRestoreIntoAFreshStoreComputesRemoteStatesFromTheRestoredValues() {
        val source = AccountFeedStore()
        source action { account mutate "alice" }
        val text = source.snapshot().encode()

        val fresh = AccountFeedStore()
        fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict, sterile = true).getOrThrow()

        val plain = AccountFeedStore()
        plain.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals("alice", fresh.account.value)
        assertEquals("for-alice", fresh.feed.value)
        assertEquals(plain.feed.value, fresh.feed.value, "what a plain restore followed by a first read computes")
    }

    @Test fun aRemoteInitializerReadsTheEnclosingActionsPendingWritesAndRollsBackWithThem() {
        val store = AccountFeedStore()
        store action { account mutate "ada" }
        val snapshot = store.snapshot()
        store action {
            account mutate "bob"
            feed mutate "synced"
        }

        val rolledBack =
            store action {
                restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
                assertEquals("ada", account.value)
                assertEquals("for-ada", feed.value)
                error("the enclosing action fails")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals("bob", store.account.value, "the restored write rolled back")
        assertEquals("synced", store.feed.value, "and so did the Remote state computed from it")

        // A snapshot without an entry for `account`: the Remote initializer
        // reads the value the enclosing action holds for it.
        val committed =
            store action {
                account mutate "carl"
                restore(StoreSnapshot(emptyMap<String, Any>()), RestorePolicy.Strict, sterile = true).getOrThrow()
            }
        committed.getOrThrow()
        assertEquals("carl", store.account.value)
        assertEquals("for-carl", store.feed.value)
    }

    @Test fun derivedStatesAreNotWrittenBackEvenIntoTheStoreThatTookTheSnapshot() {
        val store = FeedStore()
        val (headline, d) = store.derived(store.feed) { feed.value.uppercase() }
        try {
            store.sync()
            val snapshot = store.snapshot() // holds the derived's "FETCHED"
            store action { feed mutate "empty" }
            assertEquals("EMPTY", headline.value)
            // The reset leaves `feed` as it is, so nothing recomputes the derived:
            // writing its captured value back would leave it stale.
            store.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
            assertEquals("EMPTY", headline.value, "never the snapshot's value computed from dropped data")
            store.restore(snapshot, RestorePolicy.Strict).getOrThrow()
            assertEquals("FETCHED", headline.value, "a plain undo still writes it back")
        } finally {
            d.dispose()
        }
    }

    @Test fun aSterileRestoreJoinsAnAtomicFrame() {
        val a = FeedStore()
        val b = FeedStore()
        a.sync()
        b.sync()
        val snapshot = a.snapshot()
        val result =
            atomic(a, b) {
                a.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
                assertEquals("empty", a.feed.value, "the frame reads the reset value")
                assertEquals(5, a.feedSize.value)
                b { feed mutate "b-frame" }
                error("the frame fails")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals("fetched", a.feed.value, "the sterile reset rolled back with the frame")
        assertEquals("fetched", b.feed.value)

        atomic(a, b) {
            a.restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
            b { feed mutate "b-frame" }
        }.getOrThrow()
        assertEquals("empty", a.feed.value)
        assertEquals(5, a.feedSize.value)
        assertEquals("b-frame", b.feed.value)
    }

    @Test fun aSterileRestoreIntoAFreshStoreComputesAStateItBringsToLifeFromTheRestoredValues() {
        val text = localeOverlay("fr")

        val fresh = LocaleFeedStore()
        val report = fresh.restore(StoreSnapshot.decode(text), RestorePolicy.Strict, sterile = true).getOrThrow()

        // Materializing `feed` in the plan first read `locale` from the
        // pre-restore "en"; the reset recomputed it from the restored prefs.
        assertEquals("fr", fresh.prefs.value)
        assertEquals("fr", fresh.locale.value, "computed from the restored prefs, not frozen at the pre-restore value")
        assertEquals("feed-fr", fresh.feed.value)
        assertEquals(setOf("prefs"), report.restored)
        assertEquals(setOf("feed"), report.sterilized)
        assertEquals(setOf("locale"), report.kept)

        val plain = LocaleFeedStore()
        plain.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals(plain.locale.value, fresh.locale.value, "what a plain restore followed by first reads computes")
        assertEquals(plain.feed.value, fresh.feed.value)
    }

    @Test fun aStateFirstReadInsideTheSterileResetIsComputedFromTheRestoredValues() {
        val store = LocaleFeedStore()
        assertEquals("feed-en", store.feed.value)
        store.removeState("locale")

        store.restore(StoreSnapshot.decode(localeOverlay("fr")), RestorePolicy.Strict, sterile = true).getOrThrow()

        // `feed`'s re-run is the first to read `locale` again, which
        // materializes it from the committed "en"; the reset recomputes it.
        assertEquals("fr", store.prefs.value)
        assertEquals("fr", store.locale.value)
        assertEquals("feed-fr", store.feed.value)
    }

    @Test fun aStateLiveBeforeASterileRestoreKeepsItsValueAsAPlainRestoreLeavesIt() {
        val text = localeOverlay("fr")
        val store = LocaleFeedStore()
        assertEquals("en", store.locale.value)

        store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict, sterile = true).getOrThrow()

        assertEquals("fr", store.prefs.value)
        assertEquals("en", store.locale.value, "neither restored nor Remote: kept")
        assertEquals("feed-en", store.feed.value, "computed from the kept locale")

        val plain = LocaleFeedStore()
        assertEquals("en", plain.locale.value)
        plain.restore(StoreSnapshot.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals(plain.locale.value, store.locale.value)
        assertEquals(plain.feed.value, store.feed.value)
    }

    @Test fun aStateWrittenAfterItCameToLifeIsNotRecomputed() {
        val store = LocaleFeedStore()
        // Runs while the restore plans, holding no lock: stands in for another
        // thread's action, the first to read `locale` (from the pre-restore
        // "en"), writing it before the restore's action opens.
        store.whilePlanning = { store action { locale mutate "de" } }

        store.restore(StoreSnapshot.decode(localeOverlay("fr")), RestorePolicy.Strict, sterile = true).getOrThrow()

        assertEquals("fr", store.prefs.value)
        assertEquals("de", store.locale.value, "a committed write is never recomputed away")
        assertEquals("feed-de", store.feed.value)
    }

    @Test fun anOpenSterileRestoreHoldsARemoteStateItLeftUnstagedAgainstRemoval() {
        val store = AccountFeedStore()
        store action { account mutate "ada" }
        val snapshot = store.snapshot() // materializes feed = "for-ada"
        store action { account mutate "bob" } // feed keeps its committed "for-ada"

        val result =
            store action {
                restore(snapshot, RestorePolicy.Strict, sterile = true).getOrThrow()
                // The reset computed "for-ada" from the restored account: equal
                // to the committed value, so nothing was staged. Only the hold
                // protects feed.
                val refused = assertFailsWith<IllegalStateException> { store.removeState("feed") }
                assertContains(refused.message.orEmpty(), "sterile restore()")
                assertFailsWith<IllegalStateException> { store.clearStates() }
                // Dropped and read here, feed would be re-created from the committed "bob".
                assertEquals("for-ada", feed.value)
            }
        result.getOrThrow()

        assertEquals("ada", store.account.value)
        assertEquals("for-ada", store.feed.value, "never re-created from pre-restore values")
        store.removeState("feed") // the hold ended with the action
        assertFalse(store.hasState("feed"))
    }

    @Test fun aSterileRestoreNeverDecodesARemoteEntry() {
        val text =
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"feedSize":"not-a-number","pins":"p"},"skipped":[]}"""

        val store = CountedFeedStore()
        val report = store.restore(StoreSnapshot.decode(text), RestorePolicy.Strict, sterile = true).getOrThrow()

        assertTrue(report.issues.isEmpty(), "$report")
        assertEquals(setOf("pins"), report.restored)
        assertTrue("feedSize" in report.sterilized)
        assertEquals("p", store.pins.value)
        assertEquals(5, store.feedSize.value)
        assertEquals(0, store.sizeCodec.decodes, "the dropped Remote entry was never decoded")

        // Restored plainly, the same entry is undecodable.
        val plain = CountedFeedStore()
        val result = plain.restore(StoreSnapshot.decode(text), RestorePolicy.Strict)
        val rejected = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(result).exception)
        assertEquals(
            listOf<RestoreIssue>(RestoreIssue.Undecodable("feedSize", "its codec threw NumberFormatException")),
            rejected.issues,
        )
    }

    @Test fun aSterileRestoreNeverTypeChecksARemoteEntry() {
        val bad = StoreSnapshot(mapOf("pins" to "p", "feedSize" to "wrong-type"))

        val store = FeedStore()
        val report = store.restore(bad, RestorePolicy.Strict, sterile = true).getOrThrow()

        assertTrue(report.issues.isEmpty(), "$report")
        assertEquals("p", store.pins.value)
        assertEquals(5, store.feedSize.value)

        // Restored plainly, the same entry fails the type witness.
        val result = FeedStore().restore(bad, RestorePolicy.Strict)
        val rejected = assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(result).exception)
        assertEquals(listOf<RestoreIssue>(RestoreIssue.TypeMismatch("feedSize", "String", "Int")), rejected.issues)
    }
}
