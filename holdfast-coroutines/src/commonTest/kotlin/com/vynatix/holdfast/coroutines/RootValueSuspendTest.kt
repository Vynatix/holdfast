@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.observerCount
import com.vynatix.holdfast.tree.Root
import com.vynatix.holdfast.tree.TreeSnapshot
import com.vynatix.holdfast.tree.internalSettleCount
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

private class RvLeftStore : Store<RvLeftStore>() {
    val x by state { 0 }
}

private class RvRightStore : Store<RvRightStore>() {
    val y by state { 0 }
}

private class RvRoot : Root("rv") {
    val left = RvLeftStore()
    val right = RvRightStore()
    val pair by branch(left, right)
}

/** `Root.value` and the suspending entries: one settle per outermost `suspendAtomic`/`suspendAction`, flows over it. */
class RootValueSuspendTest {
    @Test
    fun aTwoStoreSuspendAtomicFrameRecomputesOnce() =
        runBlocking {
            val root = RvRoot()
            root.value.value
            suspendAtomic(root.left, root.right) {
                root.left { x mutate 1 }
                yield()
                root.right { y mutate 2 }
            }.getOrThrow()
            assertEquals(2, root.internalSettleCount)
            assertEquals(1, root.value.value[root.left.x])
            assertEquals(2, root.value.value[root.right.y])
        }

    @Test
    fun aNestedSuspendAtomicSettlesOnce() =
        runBlocking {
            val root = RvRoot()
            root.value.value
            suspendAtomic(root.left, root.right) {
                root.left { x mutate 1 }
                suspendAtomic(root.left, root.right) {
                    yield()
                    root.right { y mutate 1 }
                }.getOrThrow()
            }.getOrThrow()
            assertEquals(2, root.internalSettleCount)
        }

    @Test
    fun aSuspendActionCommitRecomputesOnceAfterTheMutexReleases() =
        runBlocking {
            val root = RvRoot()
            root.value.value
            root.left
                .suspendAction {
                    x mutate 5
                    yield()
                    assertEquals(1, root.internalSettleCount, "nothing settles inside the body")
                }.getOrThrow()
            assertEquals(2, root.internalSettleCount)
            assertEquals(5, root.value.value[root.left.x])
        }

    @Test
    fun asFlowEmitsOneTreePerFrame() =
        runBlocking {
            val root = RvRoot()
            val trees = mutableListOf<TreeSnapshot>()
            val collector =
                launch {
                    root.value
                        .asFlow()
                        .take(3)
                        .toList(trees)
                }
            yield()
            suspendAtomic(root.left, root.right) {
                root.left { x mutate 1 }
                root.right { y mutate 1 }
            }.getOrThrow()
            yield() // The flow conflates: let the collector take each frame's tree before the next.
            suspendAtomic(root.left, root.right) {
                root.left { x mutate 2 }
                root.right { y mutate 2 }
            }.getOrThrow()
            yield()
            collector.join()
            assertEquals(listOf(0, 1, 2), trees.map { it[root.left.x] })
            assertEquals(listOf(0, 1, 2), trees.map { it[root.right.y] })
        }

    @Test
    fun asStateFlowDefaultsToTheRootsScopeAndCancellingACollectorReleasesTheObserver() =
        runBlocking {
            val root = RvRoot()
            root.value.value
            val baseline = root.value.observerCount
            val scope = CoroutineScope(Dispatchers.Unconfined + Job())
            root.bindToScope(scope)
            val flow = root.value.asStateFlow()
            assertSame(root.value.value, flow.value)
            val collector = scope.launch { flow.first { it[root.left.x] == 3 } }
            root.left action { x mutate 3 }
            collector.join()
            scope.cancel()
            yield()
            assertEquals(baseline, root.value.observerCount, "the flow's observer is released with the root's scope")
        }
}
