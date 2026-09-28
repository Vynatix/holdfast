@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SECRET_VALUE = "hunter2-plaintext"

private class ScopePrefsStore : Store<ScopePrefsStore>() {
    val font by state(tags = setOf(StateTag.UserAuthored)) { "mono" }
    val password by state(tags = setOf(StateTag.Secret)) { SECRET_VALUE }
    val cache by state { "derived-from-network" }
}

private class ScopeSyncStore : Store<ScopeSyncStore>() {
    val etag by state(tags = setOf(StateTag.Remote)) { "" }
}

private class ScopeNoteStore(
    id: String,
    root: ScopeRoot,
) : Store<ScopeNoteStore>(root.notes.at(id)) {
    val body by state(tags = setOf(StateTag.UserAuthored)) { "note $id" }
}

private class ScopeRoot : Root("app") {
    val prefsStore = ScopePrefsStore()
    val syncStore = ScopeSyncStore()
    val prefs by branch(prefsStore).named(prefsStore, "prefs")
    val sync by branch(syncStore)
    val notes by keyed<String, ScopeNoteStore>(under = sync)
}

/** T5: scopes redact and prune the tree the way they do a single store's snapshot. */
class TreeSnapshotScopeTest {
    @Test
    fun aSecretReadsRedactedUnderAllAndItsValueUnderRaw() {
        val root = ScopeRoot()
        assertSame(Redacted, root.snapshot().entry(root.prefsStore.password))
        assertEquals(SECRET_VALUE, root.snapshot(scope = SnapshotScope.Raw)[root.prefsStore.password])
        assertEquals(SnapshotScope.Raw, root.snapshot(scope = SnapshotScope.Raw).scope)
    }

    @Test
    fun userAuthoredContainsExactlyTheTaggedStatesAndPrunesEmptyLeavesAndBranches() {
        val root = ScopeRoot()
        val note = root.notes.create("n1") { ScopeNoteStore(it, root) }
        val user = root.snapshot(scope = SnapshotScope.UserAuthored)

        val prefsLeaf = user[root.nodeOf(root.prefsStore)!!]!!
        assertEquals(setOf("font"), prefsLeaf.leaf!!.stateNames)
        assertEquals("mono", user[root.prefsStore.font])
        assertNull(user[root.prefsStore.cache])
        assertNull(user[root.prefsStore.password])

        // The sync store has no UserAuthored state: its leaf is pruned, but the
        // branch stays because a keyed note under it has one.
        assertNull(user[root.nodeOf(root.syncStore)!!])
        assertEquals(listOf("notes"), user[root.sync]!!.children.map { it.node.name })
        assertEquals("note n1", user[note.body])

        note.dispose()
        val pruned = root.snapshot(scope = SnapshotScope.UserAuthored)
        assertNull(pruned[root.sync], "a branch with nothing captured under it is pruned")
        assertEquals(listOf("prefs"), pruned.children.map { it.node.name })
    }

    @Test
    fun theRequestedNodeIsNeverPrunedEvenWhenEmpty() {
        val root = ScopeRoot()
        val empty = root.snapshot(root.sync, SnapshotScope.UserAuthored)
        assertSame(root.sync, empty.node)
        assertTrue(empty.children.isEmpty())
    }

    @Test
    fun renderAndToStringNeverContainASecretValueInAnyScope() {
        val root = ScopeRoot()
        for (scope in listOf(SnapshotScope.All, SnapshotScope.Raw, SnapshotScope.UserAuthored)) {
            val tree = root.snapshot(scope = scope)
            assertFalse(SECRET_VALUE in tree.render(), "render under $scope leaked the secret")
            assertFalse(SECRET_VALUE in tree.toString(), "toString under $scope leaked the secret")
        }
        assertTrue("<redacted>" in root.snapshot().render())
        assertEquals("TreeSnapshot(app: prefs, sync)", root.snapshot().toString())
    }

    @Test
    fun aGoldenRenderShowsNamesKindsOriginsAndRedactedSecrets() {
        val root = ScopeRoot()
        root.notes.create("n1") { ScopeNoteStore(it, root) }
        val rendered = root.snapshot().render()
        val lines = rendered.lines()
        assertEquals("TreeSnapshot (scope All)", lines[0])
        assertEquals("app (root, pinned)", lines[1])
        assertEquals("  prefs (branch, property name)", lines[2])
        assertEquals("    prefs (leaf, pinned)", lines[3])
        assertTrue(lines.any { it == "      password = <redacted>" }, rendered)
        assertTrue(lines.any { it == "      font = mono" }, rendered)
        assertTrue(lines.any { it == "  sync (branch, property name)" }, rendered)
        assertTrue(lines.any { it == "    ScopeSync (leaf, class name)" }, rendered)
        assertTrue(lines.any { it == "    notes (keyed, property name)" }, rendered)
        assertTrue(lines.any { it == "      n1 (leaf, key)" }, rendered)
        assertTrue(lines.any { it == "        body = note n1" }, rendered)
    }
}
