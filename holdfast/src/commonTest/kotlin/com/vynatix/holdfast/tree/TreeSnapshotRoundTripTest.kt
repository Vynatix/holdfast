@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestoreIssue
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RT_SECRET = "rt-secret-plaintext"

private class RtSettingsStore : Store<RtSettingsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
    val size by state(codec = IntCodec) { 14 }
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { RT_SECRET }
    val etag by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "e0" }
    val scratch by state { "no codec" }
}

private class RtProfileStore : Store<RtProfileStore>() {
    val name by state(codec = StringCodec) { "guest" }
}

private class RtThreadStore(
    id: String,
    root: RtRoot,
) : Store<RtThreadStore>(root.threads.at(id)) {
    val title by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "thread $id" }
}

private class RtOpaqueStore(
    key: Any,
    root: RtRoot,
) : Store<RtOpaqueStore>(root.opaque.at(key)) {
    val n by state(codec = IntCodec) { 0 }
}

private class RtRoot : Root("app") {
    val settingsStore = RtSettingsStore()
    val profileStore = RtProfileStore()
    val settings by branch(settingsStore, profileStore).named(settingsStore, "settings")
    val session by branch()
    val threads by keyed<String, RtThreadStore>(under = session)
    val opaque by keyed<Any, RtOpaqueStore>()
}

/** Schema 1 of a reader, as its first release shipped it; encoded under a pinned leaf name. */
private class RtReaderV1 : Store<RtReaderV1>() {
    val fontSize by state(codec = IntCodec) { 14 }
}

private class RtReaderV1Root : Root("reader") {
    val v1 = RtReaderV1()
    val prefs by branch(v1).named(v1, "reader")
}

/** Schema 2 renames `fontSize` to `textSize`; the same tree shape, a newer store. */
private class RtReaderV2 :
    Store<RtReaderV2>(),
    SchemaVersioned {
    val textSize by state(codec = IntCodec) { 14 }
    val migrations = mutableListOf<Int>()
    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        migrations += from
        if (from < 2) view.rename("fontSize", "textSize")
    }
}

private class RtReaderV2Root : Root("reader") {
    val v2 = RtReaderV2()
    val prefs by branch(v2).named(v2, "reader")
}

/** The same store class as [RtSettingsStore], at a class-named leaf. */
private class RtUnpinnedRoot : Root("app") {
    val store = RtSettingsStore()
    val settings by branch(store)
}

private class RtRenamedSettingsStore : Store<RtRenamedSettingsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
}

private class RtRenamedPinnedRoot : Root("app") {
    val store = RtRenamedSettingsStore()
    val settings by branch(store).named(store, "settings")
}

private class RtRenamedUnpinnedRoot : Root("app") {
    val store = RtRenamedSettingsStore()
    val settings by branch(store)
}

private object RtThrowingKeyCodec : StateCodec<Int> {
    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int = string.toInt()
}

private class RtIntKeyedStore(
    key: Int,
    root: RtIntKeyRoot,
) : Store<RtIntKeyedStore>(root.byInt.at(key)) {
    val n by state(codec = IntCodec) { key }
}

private class RtIntKeyRoot : Root("ints") {
    val byInt by keyed<Int, RtIntKeyedStore>(keyCodec = RtThrowingKeyCodec)
}

/** T5: `decode(encode(tree))` gives the tree back — names only for structure, bodies verbatim, everything typed again. */
class TreeSnapshotRoundTripTest {
    private fun filled(): RtRoot {
        val root = RtRoot()
        root.settingsStore action {
            theme mutate "dark"
            size mutate 16
            token mutate "rotated"
            etag mutate "e1"
        }
        root.profileStore action { name mutate "ada" }
        root.threads.create("t1") { RtThreadStore(it, root) }
        root.threads.create("t2") { RtThreadStore(it, root) } action { title mutate "second" }
        return root
    }

    @Test
    fun decodeOfEncodeIsEncodableEqualButNotValueEqualWhenSecretsOrRemoteExist() {
        val root = filled()
        val captured = root.snapshot()
        val decoded = root.decode(captured.encode())
        assertTrue(decoded.equalsEncodable(captured), "the encodable projection survives the round trip")
        assertTrue(captured.equalsEncodable(decoded))
        assertNotEquals(captured, decoded, "a Secret and a Remote value are not encoded, so full equality fails")
        assertEquals(captured.node, decoded.node)
        assertEquals(captured.scope, decoded.scope)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths)
    }

    @Test
    fun theDecodedTreeReadsTypedValuesThroughTheDeclaringStoresCodecs() {
        val root = filled()
        val decoded = root.decode(root.snapshot().encode())
        assertEquals("dark", decoded[root.settingsStore.theme])
        assertEquals(16, decoded[root.settingsStore.size])
        assertEquals("ada", decoded[root.profileStore.name])
        assertEquals("second", decoded[root[root.threads, "t2"]!!.title])
        assertNull(decoded[root.settingsStore.token], "encoded as null: withheld")
        assertNull(decoded[root.settingsStore.etag], "Remote omitted by default")
        assertNull(decoded[root.settingsStore.scratch], "a codec-less state is never encoded")
    }

    @Test
    fun theGoldenTextIsCanonicalAndALeafBodyIsTheStoresOwnEncoding() {
        val root = filled()
        val text = root.snapshot().encode()
        val settingsBody = root.settingsStore.snapshot().encode()
        val profileBody = root.profileStore.snapshot().encode()
        val t1Body = root[root.threads, "t1"]!!.snapshot().encode()
        val t2Body = root[root.threads, "t2"]!!.snapshot().encode()
        val expected =
            """{"format":"holdfast.tree","v":1,"scope":"All","path":[],"tree":{"kind":"root","children":{""" +
                """"session":{"kind":"branch","children":{"threads":{"kind":"keyed","entries":{"t1":$t1Body,"t2":$t2Body}}}},""" +
                """"settings":{"kind":"branch","children":{"RtProfile":{"kind":"leaf","store":$profileBody},""" +
                """"settings":{"kind":"leaf","store":$settingsBody}}}}},"skipped":[["opaque"]]}"""
        assertEquals(expected, text)
        assertContains(settingsBody, """"token":null""", message = "a Secret encodes as null")
        assertFalse("etag" in settingsBody, "Remote is omitted")
        assertContains(root.snapshot().encode(includeRemote = true), """"etag":"e1"""")
    }

    @Test
    fun aKeyedBranchWithoutAKeyCodecIsSkippedAndReportedWithoutBreakingTheRoundTrip() {
        val root = filled()
        root.opaque.create(Any()) { RtOpaqueStore(it, root) }
        val captured = root.snapshot()
        val text = captured.encode()
        assertContains(text, """"skipped":[["opaque"]]""")
        val decoded = root.decode(text)
        assertTrue(decoded.equalsEncodable(captured))
        assertNull(decoded[root.opaque], "the skipped branch is not in the decoded tree")
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "a branch this root skips on encode is not 'unresolved'")
    }

    @Test
    fun unknownTreePathsLandInUnresolvedPathsWhileUnknownStateNamesAreRestoreIssues() {
        val root = filled()
        val text = root.snapshot().encode()
        val withExtras =
            text
                .replace(
                    """"session":{"kind":"branch","children":{""",
                    """"gone":{"kind":"branch","children":{}},"session":{"kind":"branch","children":{"lost":{"kind":"keyed","entries":{}},""",
                ).replace(""""states":{"name":"ada"""", """"states":{"name":"ada","nick":"x"""")
        val decoded = root.decode(withExtras)
        assertEquals(listOf(listOf("gone"), listOf("session", "lost")), decoded.unresolvedPaths)
        val report = root.restore(decoded).getOrThrow()
        val issue = report.issues.single()
        assertIs<RestoreIssue.UnknownState>(issue)
        assertEquals("nick", issue.stateName)
        assertEquals(decoded.unresolvedPaths, report.unresolvedPaths)
    }

    @Test
    fun aDecodedTreeRetainsLeafBodiesSoMigrateRunsAtRestore() {
        val v1 = RtReaderV1Root()
        v1.v1 action { fontSize mutate 20 }
        val text = v1.snapshot().encode()

        val v2 = RtReaderV2Root()
        val decoded = v2.decode(text)
        assertEquals(emptyList<Int>(), v2.v2.migrations, "decode runs no store code")
        v2.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf(1), v2.v2.migrations, "migrate ran once, at restore, from schema 1")
        assertEquals(20, v2.v2.textSize.value)
    }

    @Test
    fun keyedLeavesEncodeUnderTheirKeysAndDecodeAgainstLiveStoresOrPendingKeys() {
        val root = filled()
        val text = root.snapshot().encode()
        root[root.threads, "t2"]!!.dispose()
        val decoded = root.decode(text)
        assertEquals(setOf("t2"), decoded.pendingKeys(root.threads))
        assertEquals(emptySet<Any>(), decoded.pendingKeys(root.opaque))
        val threads = decoded[root.threads]!!
        assertEquals(listOf("t1", "t2"), threads.children.map { it.node.name })
        val t1 = root[root.threads, "t1"]!!
        assertEquals("thread t1", decoded[t1.title])
        val pendingLeaf = threads.children.last().node as LeafNode
        assertEquals("t2", pendingLeaf.key)
        assertEquals(NameOrigin.Key, pendingLeaf.nameOrigin)
        assertNull(pendingLeaf.store)
    }

    @Test
    fun wrongFormatAndVersionAreRejectedWithoutQuotingValues() {
        val root = filled()
        val text = root.snapshot().encode()
        val badFormat = assertFailsWith<SnapshotFormatException> { root.decode(text.replace("holdfast.tree", "holdfast.store")) }
        assertContains(badFormat.message!!, "holdfast.tree")
        val badVersion = assertFailsWith<SnapshotFormatException> { root.decode(text.replace(""""v":1,"scope"""", """"v":2,"scope"""")) }
        assertContains(badVersion.message!!, "version")
        assertFailsWith<SnapshotFormatException> { root.decode("[]") }
        assertFailsWith<SnapshotFormatException> { root.decode("""{"format":"holdfast.tree","v":1}""") }
        assertFailsWith<SnapshotFormatException> {
            root.decode("""{"format":"holdfast.tree","v":1,"scope":"Nope","tree":{"kind":"root","children":{}}}""")
        }
        for (failure in listOf(badFormat, badVersion)) {
            var chain: Throwable? = failure
            while (chain != null) {
                assertFalse("dark" in chain.message.orEmpty() || "ada" in chain.message.orEmpty(), "no value text in the chain")
                chain = chain.cause
            }
        }
    }

    @Test
    fun aKeyCodecThatThrowsOnDecodeIsAFormatError() {
        val root = RtIntKeyRoot()
        root.byInt.create(7) { RtIntKeyedStore(it, root) }
        val text = root.snapshot().encode()
        assertContains(text, """"entries":{"7":""")
        val failure = assertFailsWith<SnapshotFormatException> { root.decode(text.replace(""""7":""", """"seven":""")) }
        assertContains(failure.message!!, "byInt")
        assertFalse("seven" in failure.message!!, "the offending key text is not quoted")
    }

    @Test
    fun encodeUsesThePinnedNameNotTheClassName() {
        val root = filled()
        val text = root.snapshot().encode()
        assertContains(text, """"settings":{"kind":"leaf"""")
        assertFalse("RtSettings" in text, "the pinned leaf never shows its class name")
        assertContains(text, """"RtProfile":{"kind":"leaf"""", message = "an unpinned leaf is written under its class-derived name")
    }

    @Test
    fun aClassRenamedStoreDecodesOnlyThroughAPin() {
        val original = RtUnpinnedRoot()
        original.store action { theme mutate "dark" }
        val text = original.snapshot().encode()
        assertContains(text, """"RtSettings":""")

        val renamedUnpinned = RtRenamedUnpinnedRoot()
        val lost = renamedUnpinned.decode(text)
        assertEquals(listOf(listOf("settings", "RtSettings")), lost.unresolvedPaths, "the class-derived name no longer resolves")
        assertNull(lost[renamedUnpinned.store.theme])

        val pinnedOriginal = RtRoot()
        pinnedOriginal.settingsStore action { theme mutate "dark" }
        val pinnedText = pinnedOriginal.snapshot(scope = SnapshotScope.UserAuthored).encode()
        val renamedPinned = RtRenamedPinnedRoot()
        val found = renamedPinned.decode(pinnedText)
        assertEquals(emptyList<List<String>>(), found.unresolvedPaths)
        assertEquals("dark", found[renamedPinned.store.theme])
    }

    @Test
    fun encodeUnderUserAuthoredFailsFastOnAClassDerivedLeafWhileAllDoesNot() {
        val root = RtUnpinnedRoot()
        val failure = assertFailsWith<IllegalStateException> { root.snapshot(scope = SnapshotScope.UserAuthored).encode() }
        assertContains(failure.message!!, "named by its store's class")
        assertContains(failure.message!!, "RtSettings")
        assertContains(root.snapshot().encode(), """"RtSettings":""")
        // A UserAuthored capture of a tree whose persisted leaves are pinned encodes.
        val pinned = filled()
        val text = pinned.snapshot(scope = SnapshotScope.UserAuthored).encode()
        assertContains(text, """"scope":"UserAuthored"""")
        assertFalse("RtProfile" in text, "the profile has no UserAuthored state: pruned, so its class name never matters")
        assertEquals(SnapshotScope.UserAuthored, pinned.decode(text).scope)
    }

    @Test
    fun aSubtreeEncodesWithItsPathAndDecodesToThatNode() {
        val root = filled()
        val text = root.snapshot(root.session).encode()
        assertContains(text, """"path":["session"]""")
        val decoded = root.decode(text)
        assertEquals(root.session, decoded.node)
        assertTrue(decoded.equalsEncodable(root.snapshot(root.session)))
        val leafText = root.snapshot(root.nodeOf(root.profileStore)!!).encode()
        assertContains(leafText, """"path":["settings","RtProfile"]""")
        val leaf = root.decode(leafText)
        assertTrue(leaf.isLeaf)
        assertEquals("ada", leaf[root.profileStore.name])
    }
}
