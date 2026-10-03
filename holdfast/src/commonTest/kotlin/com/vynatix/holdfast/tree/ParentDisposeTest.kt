@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.internalAttachments
import com.vynatix.holdfast.observerCount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class DsLeafStore : Store<DsLeafStore>() {
    val n by state { 0 }
}

private class DsParent : Store<DsParent>() {
    val left = DsLeafStore()
    val leaf by store { left }
}

private class DsAdopter(
    adopted: DsLeafStore,
) : Store<DsAdopter>() {
    val leaf by store { adopted }
}

/**
 * A parent's dispose and its tree value: frozen, observers dropped, the host
 * gone; the children released as subtree roots, never disposed, and free to
 * be adopted again.
 */
class ParentDisposeTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    @Test
    fun theLastTreeStaysReadableAndObserversAreDropped() {
        val parent = DsParent()
        val tree = parent.tree
        parent.leaf
        parent.left action { n mutate 3 }
        var fires = 0
        disposables += tree effect { fires++ }
        val last = tree.value
        assertEquals(1, tree.observerCount)

        parent.dispose()

        assertSame(last, tree.value, "frozen at the last settled tree")
        assertEquals(3, last[parent.left.n])
        assertEquals(0, tree.observerCount, "the value's observers are dropped")
        assertTrue(tree.internalHost().isDisposed)
        assertFalse(parent.left.isDisposed, "children are never disposed by their parent")
        parent.left action { n mutate 4 }
        assertEquals(1, fires, "no observer fires after dispose")
        assertSame(last, tree.value, "and nothing recomputes")
        assertEquals(0, tree.internalHost().internalAttachments().size)
        assertFailsWith<IllegalStateException> { parent.tree }
    }

    @Test
    fun aFirstReadOfTheValueOfADisposedParentThrows() {
        val parent = DsParent()
        val tree = parent.tree
        parent.dispose()
        val failure = assertFailsWith<IllegalStateException> { tree.value }
        assertTrue("disposed" in failure.message!!)
        assertFailsWith<IllegalStateException> { tree effect { } }
        assertFailsWith<IllegalStateException> { tree.internalSettleNow() }
    }

    @Test
    fun disposeIsIdempotentAndDropsTheValuesEdgesOnEveryStore() {
        val parent = DsParent()
        val tree = parent.tree
        tree.value
        val node = (tree as StoreTreeImpl).treeValue().node()
        assertEquals(listOf(parent, parent.left), node.sourceStores)
        parent.dispose()
        parent.dispose()
        assertEquals(emptyList<Store<*>>(), node.sourceStores)
    }

    @Test
    fun aReleasedChildKeepsItsStoreAndItsOwnTreeAndBecomesASubtreeRoot() {
        val parent = DsParent()
        val left = parent.leaf
        val parentNode = parent.tree.node
        val leftNode = left.tree.node
        assertSame(parentNode, leftNode.parent)
        assertEquals("leaf", leftNode.name)

        parent.dispose()

        assertSame(leftNode, left.tree.node, "a store's node is the same for its whole life")
        assertNull(leftNode.parent)
        assertEquals("DsLeaf", leftNode.name)
        assertEquals(NameOrigin.ClassName, leftNode.nameOrigin)
        assertNull(left.internalAttachment(treeMembershipKey)?.parentEdge?.value)
        assertSame(left, leftNode.store)
        assertEquals(listOf<Store<*>>(left), left.tree.stores())
        assertNull(parentNode.store, "the disposed parent's node drops its store")
        assertEquals("DsParent", parentNode.name, "and keeps its own place")
    }

    @Test
    fun aReleasedChildCanBeAdoptedByAnotherParent() {
        val parent = DsParent()
        val left = parent.leaf
        left action { n mutate 7 }
        parent.dispose()

        val adopter = DsAdopter(left)
        assertSame(left, adopter.leaf)
        assertSame(adopter.tree.node, left.tree.parent)
        assertEquals("leaf", left.tree.node.name)
        assertEquals(NameOrigin.Property, left.tree.node.nameOrigin)
        assertEquals(listOf(adopter, left), adopter.tree.stores())
        assertEquals(7, adopter.tree.snapshot()[left.n], "adopted with its state")
    }

    @Test
    fun aChildDisposedBeforeItsParentLeavesNothingForTheParentsDisposeToRelease() {
        val parent = DsParent()
        val left = parent.leaf
        val detached = ArrayList<String>()
        parent.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onDetached(leaf: LeafNode) {
                    detached += leaf.name
                }
            },
        )
        left.dispose()
        assertEquals(listOf("leaf"), detached)
        assertEquals(listOf<Store<*>>(parent), parent.tree.stores())
        parent.dispose()
        assertEquals(listOf("leaf"), detached, "a parent's dispose tells its own listeners nothing")
    }
}
