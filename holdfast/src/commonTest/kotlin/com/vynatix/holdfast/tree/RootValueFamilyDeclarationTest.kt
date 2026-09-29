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

private class FdLeafStore : Store<FdLeafStore>() {
    val n by state { 0 }
}

private class FdRoot : Root("fd") {
    val a = FdLeafStore()
    val b = FdLeafStore()
    val leaves by branch(a, b).named(a, "a").named(b, "b")
}

/**
 * A keyed state family declared on a leaf after the last settle — a local
 * `keyedState` delegate, still without an entry — lists that leaf
 * differently: the next settle recaptures the leaf rather than reusing a
 * capture that lacks the family, so the tree's leaf agrees with the store's
 * own `snapshot()` on names, encoding and equality.
 */
class RootValueFamilyDeclarationTest {
    @Test
    fun aFamilyDeclaredWithNoEntriesRecapturesItsLeafAtTheNextSettle() {
        val root = FdRoot()
        val leafA = root.nodeOf(root.a)!!
        val first = root.value.value
        assertEquals(setOf("n"), first[leafA]!!.leaf!!.stateNames)

        val docs: KeyedState<String, Int> by root.a.keyedState { 0 }
        assertTrue(docs.entries.isEmpty())
        // A write elsewhere settles the tree; leaf a's states are unmoved.
        root.b action { n mutate 1 }
        val second = root.value.value
        val leaf = second[leafA]!!.leaf!!

        assertEquals(setOf("n", "docs"), leaf.stateNames)
        assertEquals(root.a.snapshot().stateNames, leaf.stateNames)
        assertEquals(root.a.snapshot().encode(), leaf.encode())
        assertEquals(root.a.snapshot(), leaf)
        assertEquals(emptySet(), leaf.keysOf(docs))
    }
}
