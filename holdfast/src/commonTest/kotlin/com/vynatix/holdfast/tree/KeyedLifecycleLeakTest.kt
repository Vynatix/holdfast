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
    id: Int,
    root: LeakRoot,
) : Store<LeakThreadStore>(root.threads.at(id)) {
    val n by state { 0 }
}

private class LeakRoot : Root() {
    val fixed by branch(LeakBranchStore())
    val threads by keyed<Int, LeakThreadStore>()
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

/** T3: keyed churn leaves nothing behind in the registry, the listeners or the stores. */
class KeyedLifecycleLeakTest {
    @Test
    fun aThousandCreateAndDisposeCyclesLeaveTheRegistryEmpty() {
        val root = LeakRoot()
        val listener = CountingLeakListener()
        root.internalAddMembershipListener(listener)
        val started = TimeSource.Monotonic.markNow()
        var sampledObservers = 0
        repeat(CYCLES) { i ->
            val t = root.threads.create(i) { LeakThreadStore(it, root) }
            t action { n mutate i }
            if (i % 100 == 0) sampledObservers += t.n.observerCount
            t.dispose()
            assertNull(root[root.threads, i])
        }
        val elapsed = started.elapsedNow()
        assertTrue(elapsed.inWholeSeconds < BUDGET_SECONDS, "1,000 cycles took $elapsed")
        assertTrue(root.entries(root.threads).isEmpty())
        assertEquals(CYCLES, listener.attached)
        assertEquals(CYCLES, listener.detached)
        assertEquals(0, sampledObservers, "the tree installs no observers on a keyed store")
        assertEquals(listOf<Store<*>>(root.fixed.stores.single()), root.children(root))
        assertEquals(2, root.nodes.count { it is LeafNode || it is KeyedBranch<*, *> })
    }

    @Test
    fun reverseOrderDisposeLeavesNothing() {
        val root = LeakRoot()
        val stores = (0 until 50).map { i -> root.threads.create(i) { LeakThreadStore(it, root) } }
        stores.asReversed().forEach { it.dispose() }
        assertTrue(root.entries(root.threads).isEmpty())
        stores.forEach { assertNull(root.nodeOf(it)) }
    }

    @Test
    fun branchStoresStayAttachedAcrossKeyedChurn() {
        val root = LeakRoot()
        val fixed = root.fixed.stores.single()
        repeat(200) { i -> root.threads.create(i) { LeakThreadStore(it, root) }.dispose() }
        assertNotNull(fixed.internalAttachment(treeMembershipKey))
        assertNotNull(root.nodeOf(fixed))
        assertEquals(listOf<Store<*>>(fixed), root.children(root.fixed))
    }
}
