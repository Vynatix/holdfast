@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CotLeafStore : Store<CotLeafStore>() {
    val n by state { 0 }
}

/** Its lambda returns a store ANOTHER thread constructed while the lambda ran (a racing lazy init, say). */
private class CotParent : Store<CotParent>() {
    val elsewhere by store {
        val made = AtomicReference<CotLeafStore>()
        thread { made.set(CotLeafStore()) }.join()
        made.get()
    }
    val nested by store { CotOuter() }
}

/** A child that first-reads its own child inside its constructor: built during both runs, on this thread. */
private class CotOuter : Store<CotOuter>() {
    val inner by store { CotLeafStore() }

    init {
        inner
    }
}

class ChildOwnershipThreadTest {
    @Test
    fun aStoreConstructedOnAnotherThreadDuringTheRunIsNotOwned() {
        val parent = CotParent()
        val child = parent.elsewhere
        parent.dispose()
        assertFalse(child.isDisposed, "only stores built on the running thread are the declaration's")
        assertNull(child.tree.parent, "released as a subtree root")
        child.dispose()
    }

    @Test
    fun aNestedRunsStoreBelongsToItsOwnDeclaration() {
        val parent = CotParent()
        val outer = parent.nested
        val inner = outer.inner
        parent.dispose()
        assertTrue(outer.isDisposed, "the outer lambda built its child")
        assertTrue(inner.isDisposed, "and the outer child's dispose disposed the store its own lambda built")
    }
}
