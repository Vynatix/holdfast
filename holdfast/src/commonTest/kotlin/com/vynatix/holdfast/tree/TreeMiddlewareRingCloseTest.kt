@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.setOuterMiddlewareUnchecked
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RcLeafStore : Store<RcLeafStore>() {
    val n by state { 0 }
}

private class RcRingRoot : Root("rc") {
    val a = RcLeafStore()
    val leaf by branch(a).named(a, "a")
}

/** A member of a leaf's outer ring that no tree ring made: what another root's ring puts there. */
private class RcForeign : Middleware<Nothing>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<Nothing>) {
        started++
    }
}

private class RcTrace : TreeMiddleware() {
    val events = mutableListOf<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "completed ${node.name}"
    }
}

/** `Root.dispose()` unwinds the tree ring by identity: a leaf's outer ring is never replaced as a whole. */
class TreeMiddlewareRingCloseTest {
    @Test
    fun closeRemovesOnlyItsOwnAdaptersFromALeafsOuterRing() {
        val root = RcRingRoot()
        val trace = RcTrace()
        root.middlewares(trace)
        val foreign = RcForeign()
        root.a.setOuterMiddlewareUnchecked(root.treeMiddleware.adaptersOf(root.a) + foreign)
        root.a action { }
        assertEquals(1, foreign.started)
        assertEquals(listOf("started a", "completed a"), trace.events)

        root.dispose()

        root.a action { }
        assertEquals(2, foreign.started, "a ring member this tree did not make is left in place")
        assertEquals(2, trace.events.size, "the tree's own adapters are gone")
        assertEquals(listOf<Any>(foreign), root.a.snapshotMiddleware())
        assertTrue(root.treeMiddleware.adaptersOf(root.a).isEmpty())
    }
}
