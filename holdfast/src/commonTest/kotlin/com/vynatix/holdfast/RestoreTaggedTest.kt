@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A store with one state of each kind a tag-limited restore tells apart. */
private class Notebook : Store<Notebook>() {
    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val feed by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "" }
    val plain by state(codec = StringCodec) { "" }
    val drafts by keyedState<String, String>(
        codec = StringCodec,
        keyCodec = StringCodec,
        tags = setOf(StateTag.UserAuthored),
    ) { "" }
    val cache by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }
    val length = derived(pins) { pins.value.length }.first
}

/**
 * [Notebook] at schema 2, which renamed `notes` (schema 1) to `pins`, and two
 * names schema 1 used for states it did not declare to a Remote and an
 * untagged state: whether those are restored tells whether names are
 * matched before or after the migration.
 */
private class NotebookV2 :
    Store<NotebookV2>(),
    SchemaVersioned {
    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        if (from < 2) {
            view.rename("notes", "pins")
            view.rename("stream", "feed")
            view.rename("legacy", "plain")
        }
    }

    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val feed by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "" }
    val plain by state(codec = StringCodec) { "" }
}

/**
 * `internalRestoreTagged` (issue #20, R8/R3; plan PR 14 — the persisted
 * overlay's restore): only the entries of states and keyed families declared
 * with the tag are restored; the others are dropped silently, and a name the
 * store does not declare stays an unknown state.
 */
class RestoreTaggedTest {
    private val blob =
        """{"format":"holdfast.store","v":1,"schema":1,"states":{""" +
            """"cache":{"k":"cached"},"drafts":{"a":"alpha"},"feed":"stale","pins":"mine","plain":"other"}}"""

    @Test fun onlyTheTaggedStatesAndFamiliesAreRestored() {
        val store = Notebook()

        val restored = store.internalRestoreTagged(StoreSnapshot.decode(blob), StateTag.UserAuthored, RestorePolicy.Strict)

        assertIs<TransactionResult.Success<Unit>>(restored, "untagged entries are dropped, not issues, even under Strict")
        assertEquals("mine", store.pins.value)
        assertEquals("alpha", store.drafts["a"].value)
        assertEquals("", store.feed.value)
        assertEquals("", store.plain.value)
        assertTrue("k" !in store.cache, "an untagged family's entries are neither restored nor created")
    }

    @Test fun anUndeclaredNameIsStillAnUnknownState() {
        val store = Notebook()
        val unknown = """{"format":"holdfast.store","v":1,"schema":1,"states":{"gone":"x","pins":"mine"}}"""

        val strict = store.internalRestoreTagged(StoreSnapshot.decode(unknown), StateTag.UserAuthored, RestorePolicy.Strict)
        val rejection = assertIs<RestoreRejectedException>((strict as TransactionResult.Error).exception)
        assertEquals(listOf(RestoreIssue.UnknownState("gone")), rejection.issues)
        assertEquals("", store.pins.value)

        val ignoring =
            store.internalRestoreTagged(StoreSnapshot.decode(unknown), StateTag.UserAuthored, RestorePolicy.IgnoreUnknown)
        assertIs<TransactionResult.Success<Unit>>(ignoring)
        assertEquals("mine", store.pins.value)
    }

    @Test fun aCapturedSnapshotRestoresNoDerivedBackingAndNoUntaggedState() {
        val store = Notebook()
        store.action {
            pins mutate "before"
            plain mutate "before"
        }
        val undo = store.snapshot()
        store.action {
            pins mutate "after, longer"
            plain mutate "after"
        }
        val staged = mutableListOf<Set<String?>>()
        store.middlewares(
            object : Middleware<Notebook>() {
                override fun onTransactionCompleted(context: MiddlewareContext<Notebook>) {
                    if (context.transaction.id == "Restore") {
                        staged +=
                            context.transaction.modifiedStates
                                .map { it.internalQualifiedName }
                                .toSet()
                    }
                }
            },
        )

        store.internalRestoreTagged(undo, StateTag.UserAuthored, RestorePolicy.Strict)

        assertEquals(listOf(setOf<String?>("Notebook.pins")), staged, "not the derived's backing, not plain")
        assertEquals("before", store.pins.value)
        assertEquals("after", store.plain.value)
        assertEquals("before".length, store.length.value, "recomputed from the restored pins")
    }

    @Test fun namesAreMatchedAfterTheMigration() {
        val store = NotebookV2()
        // Schema 1's `legacy` and `stream` are undeclared names there, which a
        // filter run before the migration would admit, then rename into the
        // untagged `plain` and the Remote `feed`, and restore.
        val v1 =
            """{"format":"holdfast.store","v":1,"schema":1,"states":""" +
                """{"legacy":"x","notes":"mine","stream":"stale"}}"""

        val restored = store.internalRestoreTagged(StoreSnapshot.decode(v1), StateTag.UserAuthored, RestorePolicy.Strict)

        assertIs<TransactionResult.Success<Unit>>(restored)
        assertEquals("mine", store.pins.value)
        assertEquals("", store.feed.value, "renamed into a Remote state: never restored")
        assertEquals("", store.plain.value, "renamed into an untagged state: never restored")
    }

    @Test fun aTaggedRestoreReplacesEachTaggedFamilyTheSnapshotListsAndNoOther() {
        val store = Notebook()
        store.action {
            drafts["a"] mutate "old"
            drafts["gone"] mutate "evicted by the restore"
            cache["k"] mutate "kept"
        }

        val restored = store.internalRestoreTagged(StoreSnapshot.decode(blob), StateTag.UserAuthored, RestorePolicy.Strict)

        assertIs<TransactionResult.Success<Unit>>(restored)
        assertEquals(setOf("a"), store.drafts.entries.keys, "the family holds the snapshot's keys and no others")
        assertEquals("alpha", store.drafts["a"].value)
        assertEquals("kept", store.cache["k"].value, "an untagged family is never touched")
    }

    @Test fun aFamilyTheSnapshotDoesNotListKeepsItsEntries() {
        val store = Notebook()
        store.action { drafts["mine"] mutate "kept" }
        val noFamilies = """{"format":"holdfast.store","v":1,"schema":1,"states":{"pins":"p"}}"""

        store.internalRestoreTagged(StoreSnapshot.decode(noFamilies), StateTag.UserAuthored, RestorePolicy.Strict)

        assertEquals("p", store.pins.value)
        assertEquals("kept", store.drafts["mine"].value)
    }

    @Test fun anEntryAnEnclosingTransactionCreatedIsEvictedByTheReplace() {
        val store = Notebook()
        val empty = """{"format":"holdfast.store","v":1,"schema":1,"states":{"drafts":{}}}"""

        store.action {
            drafts["new"] mutate "created in this action"
            internalRestoreTagged(StoreSnapshot.decode(empty), StateTag.UserAuthored, RestorePolicy.Strict)
        }

        assertTrue("new" !in store.drafts)
    }

    @Test fun aRejectedTaggedRestoreEvictsNothing() {
        val store = Notebook()
        store.action { drafts["mine"] mutate "kept" }
        val rejected = """{"format":"holdfast.store","v":1,"schema":1,"states":{"drafts":{},"gone":"x"}}"""

        val result = store.internalRestoreTagged(StoreSnapshot.decode(rejected), StateTag.UserAuthored, RestorePolicy.Strict)

        assertIs<RestoreRejectedException>((result as TransactionResult.Error).exception)
        assertEquals("kept", store.drafts["mine"].value)
    }

    @Test fun targetsLimitWhatIsWrittenAndAListedEntryIsKeptEitherWay() {
        val store = Notebook()
        store.action {
            pins mutate "untouched"
            drafts["a"] mutate "old"
            drafts["b"] mutate "not a target"
            drafts["c"] mutate "evicted"
        }
        val listed =
            """{"format":"holdfast.store","v":1,"schema":1,"states":""" +
                """{"drafts":{"a":"alpha","b":"beta"},"pins":"mine"}}"""
        val target = store.drafts["a"]

        store.internalRestoreTagged(StoreSnapshot.decode(listed), StateTag.UserAuthored, RestorePolicy.Strict) {
            it === target
        }

        assertEquals("alpha", store.drafts["a"].value)
        assertEquals("not a target", store.drafts["b"].value, "listed, so kept; not a target, so not written")
        assertTrue("c" !in store.drafts, "not listed: evicted, targets or not")
        assertEquals("untouched", store.pins.value)
    }
}
