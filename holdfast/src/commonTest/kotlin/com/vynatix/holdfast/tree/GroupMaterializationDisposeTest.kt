@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BdAStore : Store<BdAStore>() {
    val n by state { 0 }
}

private class BdBStore : Store<BdBStore>() {
    val n by state { 0 }
}

/** A parent whose group lists [listed] when it first materializes; its leaves are named `BdA` and `BdB`. */
private class BdParent : Store<BdParent>() {
    var listed: List<Store<*>> = emptyList()
    val group by group { listed }
}

private class BdGrandparent(
    val parent: BdParent,
) : Store<BdGrandparent>() {
    val p by store { parent }
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

/** A listener ahead of the recording one that disposes [victim] when it hears [trigger] attached. */
private class BdDisposeOnAttached(
    private val trigger: String,
    private val victim: Store<*>,
) : LeafMembershipListener() {
    override fun onAttached(leaf: LeafNode) {
        if (leaf.name == trigger) victim.dispose()
    }
}

/**
 * A group member disposed while the group's materialization announces it:
 * every listener hears `onAttached` strictly before `onDetached`, and each
 * leaf of a group detaches on its own, once. Deterministic: the dispose
 * comes from a membership listener ahead of the recording one.
 */
class GroupMaterializationDisposeTest {
    @Test
    fun aStoreDisposedDuringItsOwnAnnouncementIsDetachedAfterEveryListenerHeardAttached() {
        val parent = BdParent()
        val a = BdAStore()
        parent.listed = listOf(a)
        parent.internalAddMembershipListener(BdDisposeOnAttached("BdA", a))
        val recording = BdRecording()
        parent.internalAddMembershipListener(recording)

        val group = parent.group

        assertEquals(listOf("attached BdA", "detached BdA"), recording.events)
        assertTrue(a.isDisposed)
        assertNull(parent.tree.nodeOf(a))
        assertEquals(emptyList<Store<*>>(), parent.tree.stores(group))
        assertEquals(listOf<StoreNode>(group), parent.tree.children(), "a group with no member left is still listed")
    }

    @Test
    fun aSiblingDisposedAfterRegistrationButBeforeItsAnnouncementHearsAttachedThenDetached() {
        val parent = BdParent()
        val a = BdAStore()
        val b = BdBStore()
        parent.listed = listOf(a, b)
        parent.internalAddMembershipListener(BdDisposeOnAttached("BdA", b))
        val recording = BdRecording()
        parent.internalAddMembershipListener(recording)

        val group = parent.group

        // b was registered with a, so its detach is deferred to the announcer, which tells it after b's Attached.
        assertEquals(listOf("attached BdA", "attached BdB", "detached BdB"), recording.events)
        assertNull(parent.tree.nodeOf(b))
        assertEquals(listOf<Store<*>>(a), parent.tree.stores(group))
        assertEquals(listOf(parent, a), parent.tree.stores())
    }

    @Test
    fun aStoreDisposedAfterItsAnnouncementIsDetachedOnce() {
        val parent = BdParent()
        val a = BdAStore()
        parent.listed = listOf(a)
        val recording = BdRecording()
        parent.internalAddMembershipListener(recording)
        val group = parent.group
        assertEquals(listOf("attached BdA"), recording.events)

        a.dispose()

        assertEquals(listOf("attached BdA", "detached BdA"), recording.events)
        assertNull(parent.tree.nodeOf(a))
        assertEquals(emptyList<Store<*>>(), parent.tree.stores(group))
    }

    @Test
    fun aGroupWhoseFirstMemberDisposedStillDetachesItsSecondMemberOnce() {
        val parent = BdParent()
        val a = BdAStore()
        val b = BdBStore()
        parent.listed = listOf(a, b)
        val grandparent = BdGrandparent(parent)
        val recording = BdRecording()
        grandparent.internalAddMembershipListener(recording)
        grandparent.p
        parent.group
        assertEquals(listOf("attached p", "attached BdA", "attached BdB"), recording.events)

        a.dispose()
        assertEquals(listOf("detached BdA"), recording.events.drop(3))

        // The parent's dispose releases the member still live (b) — once — and never the one already gone (a).
        parent.dispose()
        assertEquals(listOf("detached BdA", "detached p", "detached BdB"), recording.events.drop(3))
        assertNull(b.tree.parent, "b is a subtree root now")
        assertEquals(listOf<Store<*>>(grandparent), grandparent.tree.stores())

        b.dispose()
        assertEquals(6, recording.events.size, "a released member's later dispose reaches no former ancestor")
    }
}
