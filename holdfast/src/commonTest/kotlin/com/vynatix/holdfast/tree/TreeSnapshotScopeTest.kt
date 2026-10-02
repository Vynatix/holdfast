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

/** A group leaf with children of its own: the keyed notes hang under this store. */
private class ScopeSyncStore : Store<ScopeSyncStore>() {
    val etag by state(tags = setOf(StateTag.Remote)) { "" }
    val notes by stores<String, ScopeNoteStore> { ScopeNoteStore(it) }
}

private class ScopeNoteStore(
    id: String,
) : Store<ScopeNoteStore>() {
    val body by state(tags = setOf(StateTag.UserAuthored)) { "note $id" }
}

private class ScopeApp : Store<ScopeApp>() {
    val prefsStore = ScopePrefsStore()
    val syncStore = ScopeSyncStore()
    val prefs by stores(names = mapOf(ScopePrefsStore::class to "prefs")) { listOf(prefsStore) }
    val sync by stores { listOf(syncStore) }
    val notes: KeyedBranch<String, ScopeNoteStore> get() = syncStore.notes
}

/** T5: scopes redact and prune the tree the way they do a single store's snapshot. */
class TreeSnapshotScopeTest {
    @Test
    fun aSecretReadsRedactedUnderAllAndItsValueUnderRaw() {
        val root = ScopeApp()
        assertSame(Redacted, root.tree.snapshot().entry(root.prefsStore.password))
        assertEquals(SECRET_VALUE, root.tree.snapshot(scope = SnapshotScope.Raw)[root.prefsStore.password])
        assertEquals(SnapshotScope.Raw, root.tree.snapshot(scope = SnapshotScope.Raw).scope)
    }

    @Test
    fun userAuthoredContainsExactlyTheTaggedStatesAndPrunesEmptyLeavesAndBranches() {
        val root = ScopeApp()
        val note = root.notes.create("n1")
        val user = root.tree.snapshot(scope = SnapshotScope.UserAuthored)

        val prefsLeaf = user[root.tree.nodeOf(root.prefsStore)!!]!!
        assertEquals(setOf("font"), prefsLeaf.leaf!!.stateNames)
        assertEquals("mono", user[root.prefsStore.font])
        assertNull(user[root.prefsStore.cache])
        assertNull(user[root.prefsStore.password])

        // The sync store has no UserAuthored state: it captures nothing, but its
        // node and the group above it stay because a keyed note under it has one.
        val syncLeaf = user[root.tree.nodeOf(root.syncStore)!!]!!
        assertNull(syncLeaf.leaf)
        assertFalse(syncLeaf.hasStore)
        assertEquals(listOf("notes"), syncLeaf.children.map { it.name })
        assertEquals(listOf("ScopeSync"), user[root.sync]!!.children.map { it.name })
        assertEquals("note n1", user[note.body])

        note.dispose()
        val pruned = root.tree.snapshot(scope = SnapshotScope.UserAuthored)
        assertNull(pruned[root.sync], "a branch with nothing captured under it is pruned")
        assertNull(pruned[root.tree.nodeOf(root.syncStore)!!], "so is a store node with nothing captured at or under it")
        assertEquals(listOf("prefs"), pruned.children.map { it.name })
    }

    @Test
    fun theRequestedNodeIsNeverPrunedEvenWhenEmpty() {
        val root = ScopeApp()
        val empty = root.tree.snapshot(root.sync, SnapshotScope.UserAuthored)
        assertSame(root.sync, empty.node)
        assertTrue(empty.children.isEmpty())
    }

    @Test
    fun renderAndToStringNeverContainASecretValueInAnyScope() {
        val root = ScopeApp()
        for (scope in listOf(SnapshotScope.All, SnapshotScope.Raw, SnapshotScope.UserAuthored)) {
            val tree = root.tree.snapshot(scope = scope)
            assertFalse(SECRET_VALUE in tree.render(), "render under $scope leaked the secret")
            assertFalse(SECRET_VALUE in tree.toString(), "toString under $scope leaked the secret")
        }
        assertTrue("<redacted>" in root.tree.snapshot().render())
        assertEquals("TreeSnapshot(ScopeApp: prefs, sync)", root.tree.snapshot().toString())
    }

    @Test
    fun aGoldenRenderShowsNamesKindsOriginsAndRedactedSecrets() {
        val root = ScopeApp()
        root.notes.create("n1")
        val rendered = root.tree.snapshot().render()
        val lines = rendered.lines()
        assertEquals("TreeSnapshot (scope All)", lines[0])
        assertEquals("ScopeApp (leaf, class name)", lines[1], "the receiver, named by its class; it has no states")
        assertEquals("  prefs (branch, property name)", lines[2])
        assertEquals("    prefs (leaf, pinned)", lines[3])
        assertTrue(lines.any { it == "      password = <redacted>" }, rendered)
        assertTrue(lines.any { it == "      font = mono" }, rendered)
        assertTrue(lines.any { it == "  sync (branch, property name)" }, rendered)
        assertTrue(lines.any { it == "    ScopeSync (leaf, class name)" }, rendered)
        assertTrue(lines.any { it == "      notes (keyed, property name)" }, rendered)
        assertTrue(lines.any { it == "        n1 (leaf, key)" }, rendered)
        assertTrue(lines.any { it == "          body = note n1" }, rendered)
    }
}
