@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.KeyedState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TreeValueFamilyDeclarationLeafStore : Store<TreeValueFamilyDeclarationLeafStore>() {
    val n by state { 0 }
}

private class TreeValueFamilyDeclarationParent : Store<TreeValueFamilyDeclarationParent>() {
    val a by store { TreeValueFamilyDeclarationLeafStore() }
    val b by store { TreeValueFamilyDeclarationLeafStore() }
}

/**
 * A keyed state family declared on a child after the last settle — a local
 * `keyedState` delegate, still without an entry — lists that child
 * differently: the next settle recaptures the child rather than reusing a
 * capture that lacks the family, so the tree's capture agrees with the
 * store's own `snapshot()` on names, encoding and equality.
 */
class TreeValueFamilyDeclarationTest {
    @Test
    fun aFamilyDeclaredWithNoEntriesRecapturesItsStoreAtTheNextSettle() {
        val parent = TreeValueFamilyDeclarationParent()
        val tree = parent.tree
        val nodeA = tree.nodeOf(parent.a)!!
        val first = tree.value
        assertEquals(setOf("n"), first[nodeA]!!.leaf!!.stateNames)

        val docs: KeyedState<String, Int> by parent.a.keyedState { 0 }
        assertTrue(docs.entries.isEmpty())
        // A write elsewhere settles the tree; a's states are unmoved.
        parent.b action { n mutate 1 }
        val second = tree.value
        val capture = second[nodeA]!!.leaf!!

        assertEquals(setOf("n", "docs"), capture.stateNames)
        assertEquals(parent.a.snapshot().stateNames, capture.stateNames)
        assertEquals(parent.a.snapshot().encode(), capture.encode())
        assertEquals(parent.a.snapshot(), capture)
        assertEquals(emptySet(), capture.keysOf(docs))
    }
}
