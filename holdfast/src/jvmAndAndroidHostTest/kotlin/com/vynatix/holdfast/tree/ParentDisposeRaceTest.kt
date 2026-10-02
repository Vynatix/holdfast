@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RACE_ROUNDS = 300

private class PdrDeepStore : Store<PdrDeepStore>() {
    val n by state { 0 }
}

private class PdrMidStore : Store<PdrMidStore>() {
    val deep by store { PdrDeepStore() }

    /** Created after the value above was seeded: a deep attach the value hears announced. */
    val late by stores<Int, PdrDeepStore> { PdrDeepStore() }
}

private class PdrGrandStore : Store<PdrGrandStore>() {
    val mid by store { PdrMidStore() }
}

private class PdrTopStore : Store<PdrTopStore>() {
    val grand by store { PdrGrandStore() }
}

/** Counts every `onDetached` per leaf, by node identity. */
private class PdrDetachCounter : LeafMembershipListener() {
    val detached = ConcurrentHashMap<LeafNode, AtomicInteger>()

    override fun onDetached(leaf: LeafNode) {
        detached.computeIfAbsent(leaf) { AtomicInteger() }.incrementAndGet()
    }
}

/**
 * The residual races of a dispose walk (spec §12.4): a deep descendant
 * disposing itself while an ancestor between it and a listener disposes,
 * and an ancestor disposing while a deep attach is still being announced.
 * A listener may hear `onDetached` for the deep leaf twice, never more, and
 * a value above never keeps an edge to a store that left its subtree.
 */
class ParentDisposeRaceTest {
    private fun StoreTree.followed(): List<Store<*>> = (this as StoreTreeImpl).treeValue.node().sourceStores

    @Test
    fun aDeepDescendantDisposingWhileItsGrandparentDisposesIsDetachedAtMostTwice() =
        completesWithin(120, "deep dispose racing its grandparent's dispose") {
            repeat(RACE_ROUNDS) {
                val top = PdrTopStore()
                val topTree = top.tree
                topTree.value
                val grand = top.grand
                val deep = grand.mid.deep
                val deepNode = checkNotNull(topTree.nodeOf(deep))
                assertEquals(4, topTree.followed().size, "top, grand, mid and deep")
                val counter = PdrDetachCounter()
                val registration = top.internalAddMembershipListener(counter)
                val start = CyclicBarrier(2)
                val failures = ConcurrentLinkedQueue<Throwable>()
                val first =
                    daemon("deep", failures) {
                        start.await()
                        deep.dispose()
                    }
                val second =
                    daemon("grand", failures) {
                        start.await()
                        grand.dispose()
                    }
                first.join()
                second.join()
                assertTrue(failures.isEmpty(), failures.joinToString())
                val heard = counter.detached[deepNode]?.get() ?: 0
                assertTrue(heard in 1..2, "the deep leaf's detach was heard $heard times")
                assertEquals(listOf<Store<*>>(top), topTree.followed(), "the edge is dropped either way")
                assertEquals(listOf<Store<*>>(top), topTree.stores())
                registration.dispose()
                top.dispose()
            }
        }

    @Test
    fun anAncestorDisposingWhileADeepAttachAnnouncesLeavesNoEdgeAbove() =
        completesWithin(120, "an ancestor's dispose racing a deep attach") {
            repeat(RACE_ROUNDS) {
                val top = PdrTopStore()
                val topTree = top.tree
                topTree.value
                val grand = top.grand
                val mid = grand.mid
                assertEquals(4, topTree.followed().size, "top, grand, mid and deep")
                val start = CyclicBarrier(2)
                val failures = ConcurrentLinkedQueue<Throwable>()
                var created: PdrDeepStore? = null
                val attacher =
                    daemon("attach", failures) {
                        start.await()
                        created = mid.late.create(1)
                    }
                val disposer =
                    daemon("dispose", failures) {
                        start.await()
                        grand.dispose()
                    }
                attacher.join()
                disposer.join()
                assertTrue(failures.isEmpty(), failures.joinToString())
                assertEquals(listOf<Store<*>>(top), topTree.followed(), "no edge to a store that left the subtree")
                assertFalse(mid.isDisposed, "the released child lives on")
                assertFalse(checkNotNull(created).isDisposed, "and so does the store its create attached")
                assertEquals(listOf<Store<*>>(mid, mid.deep, created!!), mid.tree.stores(), "under the released child")
                top.dispose()
            }
        }
}
