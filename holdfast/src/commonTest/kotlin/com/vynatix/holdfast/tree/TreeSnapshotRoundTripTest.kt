@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.RestoreIssue
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
) : Store<RtThreadStore>() {
    val title by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "thread $id" }
}

private class RtOpaqueStore : Store<RtOpaqueStore>() {
    val n by state(codec = IntCodec) { 0 }
}

/** A mid-tree store with no states: its node is a leaf carrying children (`store` body empty). */
private class RtSessionStore : Store<RtSessionStore>() {
    val threads by keyed<String, RtThreadStore> { RtThreadStore(it) }
}

/** The receiver: a parent with a state of its own, a pinned group, a child store and a codec-less keyed branch. */
private class RtApp : Store<RtApp>() {
    val opened by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "none" }
    val settingsStore = RtSettingsStore()
    val profileStore = RtProfileStore()
    val settings by group { listOf(settingsStore named "settings", profileStore) }
    val session by store { RtSessionStore() }
    val opaque by keyed<Any, RtOpaqueStore> { RtOpaqueStore() }
    val threads: KeyedBranch<String, RtThreadStore> get() = session.threads
}

/** Schema 1 of a reader, as its first release shipped it; encoded under a pinned leaf name. */
private class RtReaderV1 : Store<RtReaderV1>() {
    val fontSize by state(codec = IntCodec) { 14 }
}

/** Both reader apps carry one tree id: the receiver of a text survives the app class's "rename". */
private class RtReaderV1App :
    Store<RtReaderV1App>(),
    TreeIdentified {
    override val treeId: String get() = "reader-app"

    val v1 = RtReaderV1()
    val prefs by group { listOf(v1 named "reader") }
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

private class RtReaderV2App :
    Store<RtReaderV2App>(),
    TreeIdentified {
    override val treeId: String get() = "reader-app"

    val v2 = RtReaderV2()
    val prefs by group { listOf(v2 named "reader") }
}

/** The same store class as [RtSettingsStore], at a class-named leaf; one tree id with its "renamed" twin. */
private class RtUnpinnedApp :
    Store<RtUnpinnedApp>(),
    TreeIdentified {
    override val treeId: String get() = "settings-app"

    val settingsStore = RtSettingsStore()
    val settings by group { listOf(settingsStore) }
}

/** [RtSettingsStore] as a `store { }` child: named by its property, never by its class. */
private class RtPropertyChildApp : Store<RtPropertyChildApp>() {
    val settings by store { RtSettingsStore() }
}

private class RtRenamedSettingsStore : Store<RtRenamedSettingsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
}

/** [RtSettingsStore] at a pinned group leaf, under the tree id its "renamed" twin shares. */
private class RtPinnedApp :
    Store<RtPinnedApp>(),
    TreeIdentified {
    override val treeId: String get() = "pinned-settings-app"
    val settingsStore = RtSettingsStore()
    val settings by group { listOf(settingsStore named "settings") }
}

private class RtRenamedPinnedApp :
    Store<RtRenamedPinnedApp>(),
    TreeIdentified {
    override val treeId: String get() = "pinned-settings-app"

    val renamed = RtRenamedSettingsStore()
    val settings by group { listOf(renamed named "settings") }
}

private class RtRenamedUnpinnedApp :
    Store<RtRenamedUnpinnedApp>(),
    TreeIdentified {
    override val treeId: String get() = "settings-app"

    val renamed = RtRenamedSettingsStore()
    val settings by group { listOf(renamed) }
}

private object RtThrowingKeyCodec : StateCodec<Int> {
    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int = string.toInt()
}

private class RtIntKeyedStore(
    key: Int,
) : Store<RtIntKeyedStore>() {
    val n by state(codec = IntCodec) { key }
}

private class RtIntKeyApp : Store<RtIntKeyApp>() {
    val byInt by keyed<Int, RtIntKeyedStore>(keyCodec = RtThrowingKeyCodec) { RtIntKeyedStore(it) }
}

private class RtReplyStore(
    id: String,
) : Store<RtReplyStore>() {
    val body by state(codec = StringCodec) { "reply $id" }
}

/** A keyed store with keyed children of its own. */
private class RtForumThreadStore(
    id: String,
) : Store<RtForumThreadStore>() {
    val title by state(codec = StringCodec) { "thread $id" }
    val replies by keyed<String, RtReplyStore> { RtReplyStore(it) }
}

private class RtForumApp : Store<RtForumApp>() {
    val threads by keyed<String, RtForumThreadStore> { RtForumThreadStore(it) }
}

/** Runs [during] when its store disposes — before the store's tree state hears of it (attached first). */
private class RtDisposeHook(
    private val during: () -> Unit,
) : StoreAttachment {
    override fun onStoreDisposed() = during()
}

private val rtDisposeHookKey = StoreAttachmentKey<RtDisposeHook>("round-trip-dispose-hook")

/** A mid-tree child whose dispose runs [duringDispose] while it is disposing: `isDisposed`, still listed by its parent. */
private class RtMidStore : Store<RtMidStore>() {
    var duringDispose: (() -> Unit)? = null
    val n by state(codec = IntCodec) { 0 }

    init {
        internalAttachIfAbsent(rtDisposeHookKey) { RtDisposeHook { duringDispose?.invoke() } }
    }
}

private class RtMidApp : Store<RtMidApp>() {
    val label by state(codec = StringCodec) { "a" }
    val mid by store { RtMidStore() }
}

/** T5: `decode(encode(tree))` gives the tree back — names only for structure, bodies verbatim, everything typed again. */
class TreeSnapshotRoundTripTest {
    private fun filled(): RtApp {
        val root = RtApp()
        root action { opened mutate "inbox" }
        root.settingsStore action {
            theme mutate "dark"
            size mutate 16
            token mutate "rotated"
            etag mutate "e1"
        }
        root.profileStore action { name mutate "ada" }
        root.threads.create("t1")
        root.threads.create("t2") action { title mutate "second" }
        return root
    }

    @Test
    fun decodeOfEncodeIsEncodableEqualButNotValueEqualWhenSecretsOrRemoteExist() {
        val root = filled()
        val captured = root.tree.snapshot()
        val decoded = root.tree.decode(captured.encode())
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
        val decoded = root.tree.decode(root.tree.snapshot().encode())
        assertEquals("dark", decoded[root.settingsStore.theme])
        assertEquals(16, decoded[root.settingsStore.size])
        assertEquals("ada", decoded[root.profileStore.name])
        assertEquals("second", decoded[root.threads["t2"]!!.title])
        assertNull(decoded[root.settingsStore.token], "encoded as null: withheld")
        assertNull(decoded[root.settingsStore.etag], "Remote omitted by default")
        assertNull(decoded[root.settingsStore.scratch], "a codec-less state is never encoded")
    }

    @Test
    fun theGoldenTextIsCanonicalAndALeafBodyIsTheStoresOwnEncoding() {
        val root = filled()
        val text = root.tree.snapshot().encode()
        val appBody = root.snapshot().encode()
        val sessionBody = root.session.snapshot().encode()
        val settingsBody = root.settingsStore.snapshot().encode()
        val profileBody = root.profileStore.snapshot().encode()
        val t1Body = root.threads["t1"]!!.snapshot().encode()
        val t2Body = root.threads["t2"]!!.snapshot().encode()
        val expected =
            """{"format":"holdfast.tree","v":1,"receiver":"RtApp","scope":"All","path":[],""" +
                """"tree":{"kind":"leaf","store":$appBody,"children":{""" +
                """"session":{"kind":"leaf","store":$sessionBody,"children":{"threads":{"kind":"keyed","entries":{""" +
                """"t1":{"kind":"leaf","store":$t1Body},"t2":{"kind":"leaf","store":$t2Body}}}}},""" +
                """"settings":{"kind":"branch","children":{"RtProfile":{"kind":"leaf","store":$profileBody},""" +
                """"settings":{"kind":"leaf","store":$settingsBody}}}}},"skipped":[["opaque"]]}"""
        assertEquals(expected, text)
        assertContains(appBody, """"opened":"inbox"""", message = "the receiver's own states are its node's body")
        assertContains(settingsBody, """"token":null""", message = "a Secret encodes as null")
        assertFalse("etag" in settingsBody, "Remote is omitted")
        assertContains(root.tree.snapshot().encode(includeRemote = true), """"etag":"e1"""")
    }

    @Test
    fun aKeyedBranchWithoutAKeyCodecIsSkippedAndReportedWithoutBreakingTheRoundTrip() {
        val root = filled()
        root.opaque.create(Any())
        val captured = root.tree.snapshot()
        val text = captured.encode()
        assertContains(text, """"skipped":[["opaque"]]""")
        val decoded = root.tree.decode(text)
        assertTrue(decoded.equalsEncodable(captured))
        assertNull(decoded[root.opaque], "the skipped branch is not in the decoded tree")
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "a branch this receiver skips on encode is not 'unresolved'")
    }

    @Test
    fun unknownTreePathsLandInUnresolvedPathsWhileUnknownStateNamesAreRestoreIssues() {
        val root = filled()
        val text = root.tree.snapshot().encode()
        val sessionBody = root.session.snapshot().encode()
        val withExtras =
            text
                .replace(
                    """"session":{"kind":"leaf","store":$sessionBody,"children":{""",
                    """"gone":{"kind":"branch","children":{}},""" +
                        """"session":{"kind":"leaf","store":$sessionBody,"children":{"lost":{"kind":"keyed","entries":{}},""",
                ).replace(""""states":{"name":"ada"""", """"states":{"name":"ada","nick":"x"""")
        val decoded = root.tree.decode(withExtras)
        assertEquals(listOf(listOf("gone"), listOf("session", "lost")), decoded.unresolvedPaths)
        val report = root.tree.restore(decoded).getOrThrow()
        val issue = report.issues.single()
        assertIs<RestoreIssue.UnknownState>(issue)
        assertEquals("nick", issue.stateName)
        assertEquals(decoded.unresolvedPaths, report.unresolvedPaths)
    }

    @Test
    fun aDecodedTreeRetainsLeafBodiesSoMigrateRunsAtRestore() {
        val v1 = RtReaderV1App()
        v1.v1 action { fontSize mutate 20 }
        val text = v1.tree.snapshot().encode()

        val v2 = RtReaderV2App()
        val decoded = v2.tree.decode(text)
        assertEquals(emptyList<Int>(), v2.v2.migrations, "decode runs no store code")
        v2.tree.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf(1), v2.v2.migrations, "migrate ran once, at restore, from schema 1")
        assertEquals(20, v2.v2.textSize.value)
    }

    @Test
    fun keyedLeavesEncodeUnderTheirKeysAndDecodeAgainstLiveStoresOrPendingKeys() {
        val root = filled()
        val text = root.tree.snapshot().encode()
        root.threads["t2"]!!.dispose()
        val decoded = root.tree.decode(text)
        assertEquals(setOf("t2"), decoded.pendingKeys(root.threads))
        assertEquals(emptySet<Any>(), decoded.pendingKeys(root.opaque))
        val threads = decoded[root.threads]!!
        assertEquals(listOf("t1", "t2"), threads.children.map { it.name })
        val t1 = root.threads["t1"]!!
        assertEquals("thread t1", decoded[t1.title])
        val pendingLeaf = threads.children.last().node as LeafNode
        assertEquals("t2", pendingLeaf.key)
        assertEquals(NameOrigin.Key, pendingLeaf.nameOrigin)
        assertNull(pendingLeaf.store)
    }

    @Test
    fun wrongFormatAndVersionAreRejectedWithoutQuotingValues() {
        val root = filled()
        val text = root.tree.snapshot().encode()
        val badFormat = assertFailsWith<SnapshotFormatException> { root.tree.decode(text.replace("holdfast.tree", "holdfast.store")) }
        assertContains(badFormat.message!!, "holdfast.tree")
        val badVersion =
            assertFailsWith<SnapshotFormatException> { root.tree.decode(text.replace(""""v":1,"receiver"""", """"v":2,"receiver"""")) }
        assertContains(badVersion.message!!, "version")
        assertFailsWith<SnapshotFormatException> { root.tree.decode("[]") }
        assertFailsWith<SnapshotFormatException> { root.tree.decode("""{"format":"holdfast.tree","v":1}""") }
        assertFailsWith<SnapshotFormatException> {
            root.tree.decode("""{"format":"holdfast.tree","v":1,"scope":"Nope","tree":{"kind":"leaf"}}""")
        }
        val rootKind =
            assertFailsWith<SnapshotFormatException>("a tree has no root kind any more") {
                root.tree.decode("""{"format":"holdfast.tree","v":1,"scope":"All","path":[],"tree":{"kind":"root","children":{}}}""")
            }
        assertContains(rootKind.message!!, "unknown node kind")
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
        val root = RtIntKeyApp()
        root.byInt.create(7)
        val text = root.tree.snapshot().encode()
        assertContains(text, """"entries":{"7":{"kind":"leaf","store":""", message = "a keyed entry is a node object")
        val failure = assertFailsWith<SnapshotFormatException> { root.tree.decode(text.replace(""""7":""", """"seven":""")) }
        assertContains(failure.message!!, "byInt")
        assertFalse("seven" in failure.message!!, "the offending key text is not quoted")
    }

    @Test
    fun encodeUsesThePinnedNameNotTheClassName() {
        val root = filled()
        val text = root.tree.snapshot().encode()
        assertContains(text, """"settings":{"kind":"leaf"""")
        assertFalse("RtSettings" in text, "the pinned leaf never shows its class name")
        assertContains(text, """"RtProfile":{"kind":"leaf"""", message = "an unpinned leaf is written under its class-derived name")
    }

    @Test
    fun aClassRenamedStoreDecodesOnlyThroughAPin() {
        val original = RtUnpinnedApp()
        original.settingsStore action { theme mutate "dark" }
        val text = original.tree.snapshot().encode()
        assertContains(text, """"RtSettings":""")

        val renamedUnpinned = RtRenamedUnpinnedApp()
        val lost = renamedUnpinned.tree.decode(text)
        assertEquals(listOf(listOf("settings", "RtSettings")), lost.unresolvedPaths, "the class-derived name no longer resolves")
        assertNull(lost[renamedUnpinned.renamed.theme])

        val pinnedOriginal = RtPinnedApp()
        pinnedOriginal.settingsStore action { theme mutate "dark" }
        val pinnedText = pinnedOriginal.tree.snapshot(scope = SnapshotScope.UserAuthored).encode()
        val renamedPinned = RtRenamedPinnedApp()
        val found = renamedPinned.tree.decode(pinnedText)
        assertEquals(emptyList<List<String>>(), found.unresolvedPaths)
        assertEquals("dark", found[renamedPinned.renamed.theme])
    }

    @Test
    fun encodeUnderUserAuthoredFailsFastOnAClassDerivedLeafWhileAllDoesNot() {
        val root = RtUnpinnedApp()
        val failure = assertFailsWith<IllegalStateException> { root.tree.snapshot(scope = SnapshotScope.UserAuthored).encode() }
        assertContains(failure.message!!, "named by its store's class")
        assertContains(failure.message!!, "RtSettings")
        assertContains(root.tree.snapshot().encode(), """"RtSettings":""")
        // A UserAuthored capture of a tree whose persisted leaves are pinned encodes.
        val pinned = filled()
        val text = pinned.tree.snapshot(scope = SnapshotScope.UserAuthored).encode()
        assertContains(text, """"scope":"UserAuthored"""")
        assertFalse("RtProfile" in text, "the profile has no UserAuthored state: pruned, so its class name never matters")
        assertEquals(SnapshotScope.UserAuthored, pinned.tree.decode(text).scope)
    }

    @Test
    fun aSubtreeEncodesWithItsPathAndDecodesToThatNode() {
        val root = filled()
        val session = root.tree.nodeOf(root.session)!!
        val text = root.tree.snapshot(session).encode()
        assertContains(text, """"path":["session"]""")
        val decoded = root.tree.decode(text)
        assertEquals(session, decoded.node)
        assertTrue(decoded.equalsEncodable(root.tree.snapshot(session)))
        val leafText = root.tree.snapshot(root.tree.nodeOf(root.profileStore)!!).encode()
        assertContains(leafText, """"path":["settings","RtProfile"]""")
        val leaf = root.tree.decode(leafText)
        assertTrue(leaf.isLeaf)
        assertEquals("ada", leaf[root.profileStore.name])
    }

    @Test
    fun aKeptCaptureEncodesTheCapturedNamesAfterItsOwnerDisposed() {
        val root = filled()
        val settingsLeaf = root.tree.nodeOf(root.settingsStore)!!
        val group = root.tree.snapshot(root.settings)
        val whole = root.tree.snapshot()
        val groupText = group.encode()
        val wholeText = whole.encode()
        val rendered = whole.render()
        root.dispose()

        assertNull(settingsLeaf.parent, "the live node: released as a subtree root")
        assertEquals("RtSettings", settingsLeaf.name, "named by its class again")
        assertFalse(root.settingsStore.isDisposed, "the tree disposes no child")
        assertEquals(groupText, group.encode(), "the kept group capture writes the captured leaf names")
        assertContains(group.encode(), """"settings":{"kind":"leaf"""")
        assertFalse("RtSettings" in group.encode())
        assertEquals(wholeText, whole.encode())
        assertEquals(rendered, whole.render(), "names and origins as captured")
        assertEquals("settings", whole[settingsLeaf]!!.name)
        assertEquals("dark", whole[root.settingsStore.theme])
    }

    @Test
    fun decodeOnAFreshParentWhoseChildrenWereNeverReadMaterializesThemFirst() {
        val text = filled().tree.snapshot().encode()
        val fresh = RtApp()
        val decoded = fresh.tree.decode(text)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "every declared child resolves")
        assertEquals("dark", decoded[fresh.settingsStore.theme])
        assertEquals("inbox", decoded[fresh.opened])
        assertEquals(setOf("t1", "t2"), decoded.pendingKeys(fresh.threads), "no keyed store exists yet")

        fresh.tree.restore(decoded).getOrThrow()
        assertEquals("dark", fresh.settingsStore.theme.value)
        assertEquals("ada", fresh.profileStore.name.value)
        assertEquals("inbox", fresh.opened.value)
        assertNull(fresh.threads["t1"], "a restore creates no keyed store")
    }

    @Test
    fun aKeyedStoreWithKeyedChildrenOfItsOwnRoundTrips() {
        val forum = RtForumApp()
        val thread = forum.threads.create("42")
        thread action { title mutate "hello" }
        val reply = thread.replies.create("7")
        reply action { body mutate "first!" }
        val captured = forum.tree.snapshot()
        val forumBody = forum.snapshot().encode()
        val threadBody = thread.snapshot().encode()
        val replyBody = reply.snapshot().encode()
        assertEquals(
            """{"format":"holdfast.tree","v":1,"receiver":"RtForumApp","scope":"All","path":[],""" +
                """"tree":{"kind":"leaf","store":$forumBody,""" +
                """"children":{"threads":{"kind":"keyed","entries":{"42":{"kind":"leaf","store":$threadBody,""" +
                """"children":{"replies":{"kind":"keyed","entries":{"7":{"kind":"leaf","store":$replyBody}}}}}}}}},""" +
                """"skipped":[]}""",
            captured.encode(),
            "keyed entries are node objects, and a keyed leaf carries its own children",
        )
        val replyText = forum.tree.snapshot(forum.tree.nodeOf(reply)!!).encode()
        assertContains(replyText, """"path":["threads","42","replies","7"]""")

        thread action { title mutate "edited" }
        reply action { body mutate "edited" }
        val decoded = forum.tree.decode(captured.encode())
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths)
        assertTrue(decoded.equalsEncodable(captured))
        forum.tree.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("hello", thread.title.value)
        assertEquals("first!", reply.body.value)
        assertSame(
            reply,
            forum.tree
                .decode(replyText)
                .node
                .let { (it as LeafNode).store },
        )
    }

    @Test
    fun afterAKeyedParentDisposesItsKeyIsPendingAndItsChildrenAreUnresolved() {
        val forum = RtForumApp()
        val thread = forum.threads.create("42")
        thread action { title mutate "hello" }
        thread.replies.create("7") action { body mutate "first!" }
        val text = forum.tree.snapshot().encode()
        thread.dispose()

        val decoded = forum.tree.decode(text)
        assertEquals(setOf("42"), decoded.pendingKeys(forum.threads))
        // A pending (store-less) leaf has no declarations to resolve its children against: each
        // encoded entry of its keyed child is listed, so the caller sees which nested keys drop.
        assertEquals(listOf(listOf("threads", "42", "replies", "7")), decoded.unresolvedPaths)
        val recreated = forum.threads.create("42")
        assertIs<TransactionResult.Error>(forum.tree.restore(decoded, RestorePolicy.Strict), "Strict refuses the unresolved path")
        forum.tree.restore(decoded).getOrThrow()
        assertEquals("hello", recreated.title.value, "the parent body restores under IgnoreUnknown")
        assertNull(recreated.replies["7"], "its children are skipped")

        // Created BEFORE the decode, the nested store resolves and restores under Strict.
        val recreatedReply = recreated.replies.create("7")
        val again = forum.tree.decode(text)
        assertEquals(emptyList<List<String>>(), again.unresolvedPaths)
        forum.tree.restore(again, RestorePolicy.Strict).getOrThrow()
        assertEquals("first!", recreatedReply.body.value)
    }

    @Test
    fun aTopLevelPersistedStoreWithNoPinEncodesWhileTheSameStoreInAGroupIsRefused() {
        // As a receiver, its class-derived name is its identity: flagged (a class rename breaks decode), but it
        // encodes and decodes through another instance of the same class.
        val alone = RtSettingsStore()
        alone action { theme mutate "dark" }
        assertEquals(
            listOf(NamingIssue.Kind.ReceiverNameIsClassDerived),
            alone.tree.verifyPersistedNames().map { it.kind },
        )
        val text = alone.tree.snapshot(scope = SnapshotScope.UserAuthored).encode()
        assertContains(text, """"path":[],"tree":{"kind":"leaf","store":""")
        val elsewhere = RtSettingsStore()
        elsewhere.tree.restore(elsewhere.tree.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals("dark", elsewhere.theme.value)

        // As a `store { }` child, it is named by its property: the property name is flagged (pin it with named =),
        // and so is the class-named top-level receiver; never a class-derived leaf.
        val child = RtPropertyChildApp()
        assertEquals(
            setOf(NamingIssue.Kind.PropertyDerivedNameOnPersistedSubtree, NamingIssue.Kind.ReceiverNameIsClassDerived),
            child.tree
                .verifyPersistedNames()
                .map { it.kind }
                .toSet(),
        )
        assertContains(child.tree.snapshot(scope = SnapshotScope.UserAuthored).encode(), """"settings":{"kind":"leaf"""")

        // In a group, the same store sits at a class-named leaf: flagged and refused.
        val grouped = RtUnpinnedApp()
        val issues = grouped.tree.verifyPersistedNames().filter { it.kind == NamingIssue.Kind.ClassDerivedNameOnPersistedStore }
        assertEquals(listOf<StoreNode>(grouped.tree.nodeOf(grouped.settingsStore)!!), issues.map { it.node })
        val refused = assertFailsWith<IllegalStateException> { grouped.tree.snapshot(scope = SnapshotScope.UserAuthored).encode() }
        assertContains(refused.message!!, "leaf 'RtSettings' under 'settings' is named by its store's class")
    }

    @Test
    fun anAnonymousReceiverEncodesUnderItsNodeNameAndRoundTrips() {
        val anon =
            object : NodeStore() {
                val opened by state(codec = StringCodec) { "none" }
                val profile by store { RtProfileStore() }
            }
        anon action { anon.opened mutate "inbox" }
        anon.profile action { name mutate "ada" }
        val captured = anon.tree.snapshot()
        assertEquals(anon.tree.node.name, captured.name)
        assertEquals(NameOrigin.ClassName, anon.tree.node.nameOrigin)
        val anonBody = anon.snapshot().encode()
        val profileBody = anon.profile.snapshot().encode()
        assertEquals(
            """{"format":"holdfast.tree","v":1,"receiver":"Store","scope":"All","path":[],""" +
                """"tree":{"kind":"leaf","store":$anonBody,""" +
                """"children":{"profile":{"kind":"leaf","store":$profileBody}}},"skipped":[]}""",
            captured.encode(),
            "an anonymous receiver is identified by its node name, 'Store', and needs no pin",
        )

        anon action { anon.opened mutate "elsewhere" }
        anon.profile action { name mutate "bob" }
        val decoded = anon.tree.decode(captured.encode())
        assertTrue(decoded.equalsEncodable(captured))
        anon.tree.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("inbox", anon.opened.value)
        assertEquals("ada", anon.profile.name.value)
    }

    @Test
    fun decodeAndRestoreOnALiveReceiverWhileAMidTreeChildIsDisposingNeverThrowDisposed() {
        val app = RtMidApp()
        val mid = app.mid
        val midNode = app.tree.nodeOf(mid)!!
        app action { label mutate "b" }
        val captured = app.tree.snapshot()
        val text = captured.encode()
        app action { label mutate "c" }

        var decoded: TreeSnapshot? = null
        var report: TreeRestoreReport? = null
        var strict: TransactionResult<TreeRestoreReport>? = null
        var thrown: Throwable? = null
        mid.duringDispose = {
            runCatching {
                assertTrue(mid.isDisposed)
                decoded = app.tree.decode(text)
                report = app.tree.restore(checkNotNull(decoded)).getOrThrow()
                strict = app.tree.restore(captured, RestorePolicy.Strict)
            }.onFailure { thrown = it }
        }
        mid.dispose()

        assertNull(thrown, "decode and restore on the live receiver never throw for a disposing child: $thrown")
        assertNotNull(decoded)
        val restored = checkNotNull(report)
        assertTrue(
            midNode in restored.skipped || listOf("mid") in restored.unresolvedPaths,
            "the disposing child's path is skipped or unresolved: $restored",
        )
        assertEquals(listOf<StoreNode>(app.tree.node), restored.perNode.keys.toList(), "the receiver itself restored")
        assertEquals("b", app.label.value)
        assertIs<TransactionResult.Error>(strict, "Strict refuses the skipped child, as a result, never a throw")
    }
}
