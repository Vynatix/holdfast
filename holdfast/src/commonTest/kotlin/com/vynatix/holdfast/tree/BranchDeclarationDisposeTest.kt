@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BdLeafStore : Store<BdLeafStore>() {
    val n by state { 0 }
}

private class BdRoot : Root("bd") {
    /** Only a property to hand `provideDelegate`; the branch it binds is returned by [declare]. */
    val marker: Branch? = null

    fun declare(vararg named: Pair<Store<*>, String>): Branch {
        var declaration = branch(*named.map { it.first }.toTypedArray()).named("late")
        for ((store, name) in named) declaration = declaration.named(store, name)
        return declaration.provideDelegate(this, BdRoot::marker).getValue(this, BdRoot::marker)
    }
}

/** Records every membership event, in the order this listener hears them. */
private class BdRecording : LeafMembershipListener() {
    val events = mutableListOf<String>()

    override fun onAttached(leaf: LeafNode) {
        events += "attached ${leaf.name}"
    }

    override fun onDetached(leaf: LeafNode) {
        events += "detached ${leaf.name}"
    }
}

/**
 * A listed store disposed while its branch's attach fanout runs: every
 * listener hears `onAttached` strictly before `onDetached`, and a store
 * gone before it was announced is never announced at all. Deterministic:
 * the dispose comes from a membership listener ahead of the recording one.
 */
class BranchDeclarationDisposeTest {
    @Test
    fun aStoreDisposedDuringItsOwnAttachFanoutIsDetachedAfterEveryListenerHeardAttached() {
        val root = BdRoot()
        val a = BdLeafStore()
        root.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onAttached(leaf: LeafNode) {
                    if (leaf.name == "a") a.dispose()
                }
            },
        )
        val recording = BdRecording()
        root.internalAddMembershipListener(recording)

        val branch = root.declare(a to "a")

        assertEquals(listOf("attached a", "detached a"), recording.events)
        assertTrue(a.isDisposed)
        assertNull(root.nodeOf(a))
        assertEquals(emptyList<Store<*>>(), root.children(branch))
    }

    @Test
    fun aSiblingDisposedBeforeItsAnnouncementIsNeverAnnounced() {
        val root = BdRoot()
        val a = BdLeafStore()
        val b = BdLeafStore()
        root.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onAttached(leaf: LeafNode) {
                    if (leaf.name == "a") b.dispose()
                }
            },
        )
        val recording = BdRecording()
        root.internalAddMembershipListener(recording)

        val branch = root.declare(a to "a", b to "b")

        assertEquals(listOf("attached a"), recording.events, "b left before anyone heard of it")
        assertNull(root.nodeOf(b))
        assertEquals(listOf<Store<*>>(a), root.children(branch))
        assertEquals(listOf<StoreNode>(root, branch, root.nodeOf(a)!!), root.nodes)
    }

    @Test
    fun aStoreDisposedAfterItsAnnouncementIsDetachedOnce() {
        val root = BdRoot()
        val a = BdLeafStore()
        val recording = BdRecording()
        root.internalAddMembershipListener(recording)
        val branch = root.declare(a to "a")
        assertEquals(listOf("attached a"), recording.events)

        a.dispose()

        assertEquals(listOf("attached a", "detached a"), recording.events)
        assertNull(root.nodeOf(a))
        assertEquals(emptyList<Store<*>>(), root.children(branch))
    }
}
