@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.observerCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private class LeakBranchStore : Store<LeakBranchStore>() {
    val n by state { 0 }
}

private class LeakThreadStore(
    val id: Int,
) : Store<LeakThreadStore>() {
    val n by state { 0 }
}

private class LeakParent : Store<LeakParent>() {
    val fixed by stores { listOf(LeakBranchStore()) }
    val threads by stores<Int, LeakThreadStore> { LeakThreadStore(it) }
}

private class CountingLeakListener : LeafMembershipListener() {
    var attached = 0
    var detached = 0

    override fun onAttached(leaf: LeafNode) {
        attached++
    }

    override fun onDetached(leaf: LeafNode) {
        detached++
    }
}

private const val CYCLES = 1_000
private const val BUDGET_SECONDS = 8

/** T3: keyed churn leaves nothing behind in the parent's registry, its listeners or the stores. */
class KeyedLifecycleLeakTest {
    @Test
    fun aThousandCreateAndDisposeCyclesLeaveTheRegistryEmpty() {
        val parent = LeakParent()
        val fixed = parent.fixed.stores.single()
        val listener = CountingLeakListener()
        parent.internalAddMembershipListener(listener)
        val started = TimeSource.Monotonic.markNow()
        var sampledObservers = 0
        repeat(CYCLES) { i ->
            val t = parent.threads.create(i)
            t action { n mutate i }
            if (i % 100 == 0) sampledObservers += t.n.observerCount
            t.dispose()
            assertNull(parent.threads[i])
        }
        val elapsed = started.elapsedNow()
        assertTrue(elapsed.inWholeSeconds < BUDGET_SECONDS, "1,000 cycles took $elapsed")
        assertTrue(parent.threads.entries.isEmpty())
        assertEquals(CYCLES, listener.attached, "one parent: each store is announced exactly once")
        assertEquals(CYCLES, listener.detached, "one parent: each store's detach is announced exactly once")
        assertEquals(0, sampledObservers, "the tree installs no observers on a keyed store")
        assertEquals(listOf(parent, fixed), parent.tree.stores())
        assertEquals(listOf<StoreNode>(parent.fixed, parent.threads), parent.tree.children)
    }

    @Test
    fun reverseOrderDisposeLeavesNothing() {
        val parent = LeakParent()
        val stores = (0 until 50).map { i -> parent.threads.create(i) }
        stores.asReversed().forEach { it.dispose() }
        assertTrue(parent.threads.entries.isEmpty())
        stores.forEach { assertNull(parent.tree.nodeOf(it)) }
        assertEquals(listOf(parent, parent.fixed.stores.single()), parent.tree.stores())
    }

    @Test
    fun groupStoresStayAttachedAcrossKeyedChurn() {
        val parent = LeakParent()
        val fixed = parent.fixed.stores.single()
        repeat(200) { i -> parent.threads.create(i).dispose() }
        assertNotNull(fixed.internalAttachment(treeMembershipKey)?.parentEdge?.value)
        assertNotNull(parent.tree.nodeOf(fixed))
        assertEquals(listOf(fixed), parent.tree.stores(parent.fixed))
    }
}
