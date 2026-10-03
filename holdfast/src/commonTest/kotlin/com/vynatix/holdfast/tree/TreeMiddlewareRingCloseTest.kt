@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RcLeafStore : Store<RcLeafStore>() {
    val n by state { 0 }
}

/** A mid-tree store: one child of its own. */
private class RcMidStore : Store<RcMidStore>() {
    val n by state { 0 }
    val leaf by store(named = "a", onParentDispose = KeyedDisposal.Release) { RcLeafStore() }
}

private class RcRingParent : Store<RcRingParent>() {
    val mid by store(onParentDispose = KeyedDisposal.Release) { RcMidStore() }
}

/** A consumer middleware of the leaf itself: never part of any tree ring. */
private class RcConsumer : Middleware<RcLeafStore>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<RcLeafStore>) {
        started++
    }
}

private class RcTrace(
    private val tag: String,
    private val events: MutableList<String>,
) : TreeMiddleware() {
    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "$tag started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "$tag completed ${node.name}"
    }
}

/**
 * A store's dispose retires the tree middleware it installed — and every
 * ancestor's — on the children it releases: a released child is a subtree
 * root, so its outer ring keeps only what it installed itself. The leaf's
 * own consumer middleware is never touched.
 */
class TreeMiddlewareRingCloseTest {
    @Test
    fun disposingAParentRetiresItsRingOnFormerChildrenAndKeepsTheChildsOwn() {
        val parent = RcRingParent()
        val mid = parent.mid
        val a = mid.leaf
        val consumer = RcConsumer().also { a.middlewares(it) }
        val shared = mutableListOf<String>()
        val own = RcTrace("own", shared)
        a.tree.middlewares(own)
        val fromMid = RcTrace("mid", shared)
        mid.tree.middlewares(fromMid)
        val fromParent = RcTrace("parent", shared)
        parent.tree.middlewares(fromParent)
        a action { }
        assertEquals(1, consumer.started)
        assertEquals(
            listOf(
                "parent started a",
                "mid started a",
                "own started a",
                "own completed a",
                "mid completed a",
                "parent completed a",
            ),
            shared,
            "the root-most installer is outermost",
        )
        val before = a.treeRingAdapters()
        assertEquals(listOf<TreeMiddleware>(own, fromMid, fromParent), before.map { it.middleware })

        mid.dispose()

        assertTrue(before.single { it.middleware === fromMid }.isRetired, "the disposed store's adapter is retired")
        assertTrue(before.single { it.middleware === fromParent }.isRetired, "the grandparent's too: a left its subtree")
        assertFalse(before.single { it.middleware === own }.isRetired, "the child's own install stays")
        assertEquals(listOf<TreeMiddleware>(own), a.treeRingAdapters().map { it.middleware })
        shared.clear()
        a action { }
        assertEquals(2, consumer.started, "the leaf's own consumer middleware is left in place")
        // a is a released subtree root now, named by its class.
        assertEquals(listOf("own started RcLeaf", "own completed RcLeaf"), shared, "neither ancestor's ring fires any more")
        assertEquals(listOf<Any>(consumer) + a.treeRingAdapters(), a.snapshotMiddleware())
        assertTrue(mid.treeRingAdapters().isEmpty(), "the disposed store's own ring is gone")
        assertFalse(parent.tree.removeMiddleware(fromMid), "never installed on the parent")
        assertTrue(parent.tree.removeMiddleware(fromParent), "still installed on the live parent")
    }
}
