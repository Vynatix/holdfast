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

private class ShLeafStore : Store<ShLeafStore>() {
    val n by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private class ShRoot : Root("sh") {
    val a = ShLeafStore()
    val b = ShLeafStore()
    val c = ShLeafStore()
    val leaves by branch(a, b, c).named(a, "a").named(b, "b").named(c, "c")
}

/** A settle recaptures only the leaves whose cut stamp moved; the rest of the tree is shared by reference. */
class RootValueSharingTest {
    private fun ShRoot.leafOf(store: Store<*>): LeafNode = nodeOf(store)!!

    @Test
    fun unchangedLeavesAreSharedByReferenceAcrossSettles() {
        val root = ShRoot()
        val first = root.value.value
        assertEquals(3, root.internalCaptureCount, "the initial capture reads every leaf")

        root.a action { n mutate 1 }
        val second = root.value.value
        assertNotSame(first, second)
        assertEquals(4, root.internalCaptureCount, "one leaf recaptured")
        assertNotSame(first[root.leafOf(root.a)]!!.leaf, second[root.leafOf(root.a)]!!.leaf)
        assertSame(first[root.leafOf(root.b)]!!.leaf, second[root.leafOf(root.b)]!!.leaf, "unchanged: the same capture")
        assertSame(first[root.leafOf(root.c)]!!.leaf, second[root.leafOf(root.c)]!!.leaf)
        assertEquals(1, second[root.a.n])
        assertEquals(0, second[root.b.n])
    }

    @Test
    fun aFrameMovesEveryParticipantsStampTogether() {
        val root = ShRoot()
        val first = root.value.value
        atomic(root.a, root.b) {
            root.a { n mutate 1 }
            root.b { n mutate 1 }
        }.getOrThrow()
        val second = root.value.value
        assertEquals(5, root.internalCaptureCount, "both participants recaptured, the third leaf reused")
        assertSame(first[root.leafOf(root.c)]!!.leaf, second[root.leafOf(root.c)]!!.leaf)
        assertEquals(1, second[root.a.n])
        assertEquals(1, second[root.b.n])
    }

    @Test
    fun aParticipantWithNoWritesKeepsItsCapture() {
        val root = ShRoot()
        val first = root.value.value
        atomic(root.a, root.b) { root.a { n mutate 1 } }.getOrThrow()
        val second = root.value.value
        assertEquals(4, root.internalCaptureCount)
        assertSame(first[root.leafOf(root.b)]!!.leaf, second[root.leafOf(root.b)]!!.leaf, "enrolled, unwritten: reused")
    }

    @Test
    fun aDeclarationChangeMarksALeafDirty() {
        val root = ShRoot()
        val first = root.value.value
        root.a.docs["k"]
        val second = root.value.value
        assertEquals(4, root.internalCaptureCount, "a keyed entry coming to life lists the leaf differently")
        assertNotSame(first[root.leafOf(root.a)]!!.leaf, second[root.leafOf(root.a)]!!.leaf)
        assertEquals(setOf("k"), second[root.leafOf(root.a)]!!.leaf!!.keysOf(root.a.docs))

        root.b action { n mutate 3 }
        val beforeRemove = root.value.value
        root.b.removeState("n")
        val third = root.value.value
        assertEquals(6, root.internalCaptureCount, "a dropped state re-materializes as a new state instance")
        assertEquals(0, third[root.b.n], "re-created from its initializer")
        assertNotSame(beforeRemove, third)
        assertNotSame(beforeRemove[root.leafOf(root.b)]!!.leaf, third[root.leafOf(root.b)]!!.leaf)
        assertSame(second[root.leafOf(root.c)]!!.leaf, third[root.leafOf(root.c)]!!.leaf)
    }

    @Test
    fun aSettleThatChangesNothingCapturedReusesEveryLeaf() {
        val root = ShRoot()
        val first = root.value.value
        root.a.docs["k"]
        root.value.value
        val captured = root.internalCaptureCount
        // A read of a live entry changes nothing; no settle, no capture.
        root.a.docs["k"]
        assertEquals(captured, root.internalCaptureCount)
        assertSame(first[root.leafOf(root.c)]!!.leaf, root.value.value[root.leafOf(root.c)]!!.leaf)
    }
}
