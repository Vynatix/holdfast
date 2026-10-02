@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.keyedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private class TreeValueSharingLeafStore : Store<TreeValueSharingLeafStore>() {
    val n by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private class TreeValueSharingParent : Store<TreeValueSharingParent>() {
    val a by store { TreeValueSharingLeafStore() }
    val b by store { TreeValueSharingLeafStore() }
    val c by store { TreeValueSharingLeafStore() }
}

/**
 * A settle recaptures only the stores whose cut stamp moved; the rest of the
 * tree is shared by reference. The receiver is a store of its own subtree:
 * the first capture reads it too (its stamp, with no state, never moves).
 */
class TreeValueSharingTest {
    private fun TreeValueSharingParent.nodeOf(store: Store<*>): LeafNode = tree.nodeOf(store)!!

    @Test
    fun unchangedStoresAreSharedByReferenceAcrossSettles() {
        val parent = TreeValueSharingParent()
        val tree = parent.tree
        val first = tree.value
        assertEquals(4, tree.internalCaptureCount, "the initial capture reads the receiver and every child")

        parent.a action { n mutate 1 }
        val second = tree.value
        assertNotSame(first, second)
        assertEquals(5, tree.internalCaptureCount, "one child recaptured")
        assertNotSame(first[parent.nodeOf(parent.a)]!!.leaf, second[parent.nodeOf(parent.a)]!!.leaf)
        assertSame(
            first[parent.nodeOf(parent.b)]!!.leaf,
            second[parent.nodeOf(parent.b)]!!.leaf,
            "unchanged: the same capture",
        )
        assertSame(first[parent.nodeOf(parent.c)]!!.leaf, second[parent.nodeOf(parent.c)]!!.leaf)
        assertSame(first[tree.node]!!.leaf, second[tree.node]!!.leaf, "the receiver's own capture is reused too")
        assertEquals(1, second[parent.a.n])
        assertEquals(0, second[parent.b.n])
    }

    @Test
    fun aFrameMovesEveryParticipantsStampTogether() {
        val parent = TreeValueSharingParent()
        val tree = parent.tree
        val first = tree.value
        atomic(parent.a, parent.b) {
            parent.a { n mutate 1 }
            parent.b { n mutate 1 }
        }.getOrThrow()
        val second = tree.value
        assertEquals(6, tree.internalCaptureCount, "both participants recaptured, the third child reused")
        assertSame(first[parent.nodeOf(parent.c)]!!.leaf, second[parent.nodeOf(parent.c)]!!.leaf)
        assertEquals(1, second[parent.a.n])
        assertEquals(1, second[parent.b.n])
    }

    @Test
    fun aParticipantWithNoWritesKeepsItsCapture() {
        val parent = TreeValueSharingParent()
        val tree = parent.tree
        val first = tree.value
        atomic(parent.a, parent.b) { parent.a { n mutate 1 } }.getOrThrow()
        val second = tree.value
        assertEquals(5, tree.internalCaptureCount)
        assertSame(
            first[parent.nodeOf(parent.b)]!!.leaf,
            second[parent.nodeOf(parent.b)]!!.leaf,
            "enrolled, unwritten: reused",
        )
    }

    @Test
    fun aDeclarationChangeMarksAStoreDirty() {
        val parent = TreeValueSharingParent()
        val tree = parent.tree
        val first = tree.value
        parent.a.docs["k"]
        val second = tree.value
        assertEquals(5, tree.internalCaptureCount, "a keyed entry coming to life lists the store differently")
        assertNotSame(first[parent.nodeOf(parent.a)]!!.leaf, second[parent.nodeOf(parent.a)]!!.leaf)
        assertEquals(setOf("k"), second[parent.nodeOf(parent.a)]!!.leaf!!.keysOf(parent.a.docs))

        parent.b action { n mutate 3 }
        val beforeRemove = tree.value
        parent.b.removeState("n")
        val third = tree.value
        assertEquals(7, tree.internalCaptureCount, "a dropped state re-materializes as a new state instance")
        assertEquals(0, third[parent.b.n], "re-created from its initializer")
        assertNotSame(beforeRemove, third)
        assertNotSame(beforeRemove[parent.nodeOf(parent.b)]!!.leaf, third[parent.nodeOf(parent.b)]!!.leaf)
        assertSame(second[parent.nodeOf(parent.c)]!!.leaf, third[parent.nodeOf(parent.c)]!!.leaf)
    }

    @Test
    fun aSettleThatChangesNothingCapturedReusesEveryStore() {
        val parent = TreeValueSharingParent()
        val tree = parent.tree
        val first = tree.value
        parent.a.docs["k"]
        tree.value
        val captured = tree.internalCaptureCount
        // A read of a live entry changes nothing; no settle, no capture.
        parent.a.docs["k"]
        assertEquals(captured, tree.internalCaptureCount)
        assertSame(first[parent.nodeOf(parent.c)]!!.leaf, tree.value[parent.nodeOf(parent.c)]!!.leaf)
    }
}
