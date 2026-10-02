@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CrossStore : Store<CrossStore>() {
    val n by state { 0 }
}

private class CrossKeyedStore(
    val id: String,
) : Store<CrossKeyedStore>() {
    val n by state { 0 }
}

private class CrossParent : Store<CrossParent>() {
    val base by store { CrossStore() }
    val group by stores { listOf(CrossStore()) }
    val keyed by stores<String, CrossKeyedStore> { CrossKeyedStore(it) }
}

/** A store's tree never accepts a node outside its own subtree: nodes are values, and membership is the live links. */
class CrossParentTest {
    @Test
    fun subtreeOperationsRejectANodeOfAnotherParent() {
        val first = CrossParent()
        val second = CrossParent()
        val foreignLeaf = second.tree.nodeOf(second.base)!!
        val tree = first.tree
        for (foreign in listOf(foreignLeaf, second.group, second.keyed, second.tree.node)) {
            val error = assertFailsWith<IllegalArgumentException> { tree.stores(foreign) }
            assertTrue("is not under 'CrossParent'" in error.message!!, error.message)
            assertFailsWith<IllegalArgumentException> { tree.snapshot(foreign) }
            assertFailsWith<IllegalArgumentException> { tree.reset(foreign) }
            assertFailsWith<IllegalArgumentException> { tree.verifyPersistedNames(foreign) }
        }
        assertNull(tree.nodeOf(second.base))
        assertNull(tree.nodeOf(second))
    }

    @Test
    fun aCaptureOfAnotherParentIsRefusedByRestore() {
        val first = CrossParent()
        val second = CrossParent()
        val error = assertFailsWith<IllegalArgumentException> { first.tree.restore(second.tree.snapshot()) }
        assertTrue("restore it through the store it was captured from" in error.message!!, error.message)
    }

    @Test
    fun aChildsTreeRejectsItsParentsNodesButTheParentsTreeAcceptsTheChilds() {
        val parent = CrossParent()
        val child = parent.base
        val childNode = child.tree.node
        val grand = parent.keyed.create("k")
        assertEquals(listOf<Store<*>>(child), parent.tree.stores(childNode))
        assertEquals(listOf<Store<*>>(grand), parent.tree.stores(parent.keyed))
        assertFailsWith<IllegalArgumentException> { child.tree.stores(parent.tree.node) }
        assertFailsWith<IllegalArgumentException> { child.tree.stores(parent.keyed) }
        assertNull(child.tree.nodeOf(parent))
        assertNull(child.tree.nodeOf(grand))
    }
}
