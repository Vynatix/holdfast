@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.observerCount
import com.vynatix.holdfast.tree.TreeSnapshot
import com.vynatix.holdfast.tree.group
import com.vynatix.holdfast.tree.internalSettleCount
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class TreeValueSuspendLeftStore : Store<TreeValueSuspendLeftStore>() {
    val x by state { 0 }
}

private class TreeValueSuspendRightStore : Store<TreeValueSuspendRightStore>() {
    val y by state { 0 }
}

private class TreeValueSuspendParent : Store<TreeValueSuspendParent>() {
    val left = TreeValueSuspendLeftStore()
    val right = TreeValueSuspendRightStore()
    val pair by group { listOf(left, right) }
}

/**
 * A `tree` handle's value and the suspending entries: one settle per
 * outermost `suspendAtomic`/`suspendAction`, flows over it.
 */
class TreeValueSuspendTest {
    @Test
    fun aTwoStoreSuspendAtomicFrameRecomputesOnce() =
        runBlocking {
            val parent = TreeValueSuspendParent()
            val tree = parent.tree
            tree.value
            suspendAtomic(parent.left, parent.right) {
                parent.left { x mutate 1 }
                yield()
                parent.right { y mutate 2 }
            }.getOrThrow()
            assertEquals(2, tree.internalSettleCount)
            assertEquals(1, tree.value[parent.left.x])
            assertEquals(2, tree.value[parent.right.y])
        }

    @Test
    fun aNestedSuspendAtomicSettlesOnce() =
        runBlocking {
            val parent = TreeValueSuspendParent()
            val tree = parent.tree
            tree.value
            suspendAtomic(parent.left, parent.right) {
                parent.left { x mutate 1 }
                suspendAtomic(parent.left, parent.right) {
                    yield()
                    parent.right { y mutate 1 }
                }.getOrThrow()
            }.getOrThrow()
            assertEquals(2, tree.internalSettleCount)
        }

    @Test
    fun aSuspendActionCommitRecomputesOnceAfterTheMutexReleases() =
        runBlocking {
            val parent = TreeValueSuspendParent()
            val tree = parent.tree
            tree.value
            parent.left
                .suspendAction {
                    x mutate 5
                    yield()
                    assertEquals(1, tree.internalSettleCount, "nothing settles inside the body")
                }.getOrThrow()
            assertEquals(2, tree.internalSettleCount)
            assertEquals(5, tree.value[parent.left.x])
        }

    @Test
    fun asFlowEmitsOneTreePerFrame() =
        runBlocking {
            val parent = TreeValueSuspendParent()
            val tree = parent.tree
            val trees = mutableListOf<TreeSnapshot>()
            val collector =
                launch {
                    tree
                        .asFlow()
                        .take(3)
                        .toList(trees)
                }
            yield()
            suspendAtomic(parent.left, parent.right) {
                parent.left { x mutate 1 }
                parent.right { y mutate 1 }
            }.getOrThrow()
            yield() // The flow conflates: let the collector take each frame's tree before the next.
            suspendAtomic(parent.left, parent.right) {
                parent.left { x mutate 2 }
                parent.right { y mutate 2 }
            }.getOrThrow()
            yield()
            collector.join()
            assertEquals(listOf(0, 1, 2), trees.map { it[parent.left.x] })
            assertEquals(listOf(0, 1, 2), trees.map { it[parent.right.y] })
        }

    @Test
    fun asStateFlowDefaultsToTheReceiversScopeAndCancellingACollectorReleasesTheObserver() =
        runBlocking {
            val parent = TreeValueSuspendParent()
            val tree = parent.tree
            tree.value
            val baseline = tree.observerCount
            val scope = CoroutineScope(Dispatchers.Unconfined + Job())
            parent.bindToScope(scope)
            val flow = tree.asStateFlow()
            assertSame(tree.value, flow.value)
            val collector = scope.launch { flow.first { it[parent.left.x] == 3 } }
            parent.left action { x mutate 3 }
            collector.join()
            scope.cancel()
            yield()
            assertEquals(baseline, tree.observerCount, "the flow's observer is released with the receiver's scope")
        }
}
