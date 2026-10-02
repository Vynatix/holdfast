@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.effect
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TreeValueCoalescingLeftStore : Store<TreeValueCoalescingLeftStore>() {
    val x by state { 0 }
    val strict by state(distinct = true) { 0 }
    val loose by state(distinct = false) { "same" }
}

private class TreeValueCoalescingRightStore : Store<TreeValueCoalescingRightStore>() {
    val y by state { 0 }
}

private class TreeValueCoalescingDerivedStore : Store<TreeValueCoalescingDerivedStore>() {
    val n by state { 0 }
    val twice: State<Int>
    val twiceHandle: Disposable

    init {
        val (state, handle) = derived(n) { n.value * 2 }
        twice = state
        twiceHandle = handle
    }
}

private class TreeValueCoalescingParent : Store<TreeValueCoalescingParent>() {
    val left = TreeValueCoalescingLeftStore()
    val right = TreeValueCoalescingRightStore()
    val pair by stores { listOf(left, right) }
}

private class TreeValueCoalescingDerivedParent : Store<TreeValueCoalescingDerivedParent>() {
    val derivedStore = TreeValueCoalescingDerivedStore()
    val leaf by store { derivedStore }
}

private class TreeValueCoalescingHostLog<V : Store<V>> : Middleware<V>() {
    var completed = 0

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        completed++
    }
}

/** A `tree` handle's value recomputes once per outermost entry, after every lock releases. */
class TreeValueCoalescingTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun StoreTree.seen(): MutableList<TreeSnapshot> {
        val seen = mutableListOf<TreeSnapshot>()
        disposables += this effect { seen += this }
        return seen
    }

    @Test
    fun aTwoStoreAtomicFrameRecomputesValueExactlyOnce() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val hostLog = TreeValueCoalescingHostLog<TreeValueHost>()
        (tree as StoreTreeImpl).treeValue().host.middlewares(hostLog)
        val seen = tree.seen()
        assertEquals(1, seen.size, "the baseline, read after subscribing")
        assertEquals(1, tree.internalSettleCount, "the initial capture")

        atomic(parent.left, parent.right) {
            parent.left { x mutate 1 }
            parent.right { y mutate 2 }
        }.getOrThrow()

        assertEquals(2, tree.internalSettleCount, "one recompute for the whole frame")
        assertEquals(1, hostLog.completed, "one recompute transaction on the host")
        assertEquals(2, seen.size)
        assertEquals(1, seen[1][parent.left.x])
        assertEquals(2, seen[1][parent.right.y])
        assertSame(seen[1], tree.value)
    }

    @Test
    fun aSingleCommitOutsideAFrameRecomputesOnce() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val seen = tree.seen()
        parent.left action { x mutate 5 }
        assertEquals(2, tree.internalSettleCount)
        assertEquals(2, seen.size)
        assertEquals(5, seen.last()[parent.left.x])
    }

    @Test
    fun aDistinctDedupedCommitProducesNoRecompute() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val seen = tree.seen()
        parent.left action { strict mutate 0 }
        assertEquals(1, tree.internalSettleCount, "the write changed nothing: no edge is told")
        assertEquals(1, seen.size)
    }

    @Test
    fun anEqualValueCommitOnANonDistinctStoreSettlesButDoesNotFire() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val seen = tree.seen()
        parent.left action { loose mutate "same" }
        assertEquals(2, tree.internalSettleCount, "the commit fired, so the value recomputed")
        assertEquals(1, seen.size, "the tree is value-equal: the distinct backing does not fire")
    }

    @Test
    fun nestedActionsAndNestedSameFlavourFramesSettleOnceAtTheOutermostExit() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val seen = tree.seen()
        parent.left action {
            x mutate 1
            parent.right action { y mutate 1 }
            x mutate 2
            assertEquals(1, tree.internalSettleCount, "nothing settles inside the outermost entry")
        }
        assertEquals(2, tree.internalSettleCount)
        assertEquals(2, seen.size)

        atomic(parent.left, parent.right) {
            parent.left { x mutate 3 }
            atomic(parent.left, parent.right) { parent.right { y mutate 3 } }.getOrThrow()
        }.getOrThrow()
        assertEquals(3, tree.internalSettleCount, "a nested same-flavour frame settles with the outermost")
        assertEquals(3, seen.size)
        assertEquals(3, seen.last()[parent.left.x])
        assertEquals(3, seen.last()[parent.right.y])
    }

    @Test
    fun restoreAndResetOverASubtreeRecomputeOnce() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val seen = tree.seen()
        val before = tree.snapshot()
        parent.left action { x mutate 7 }
        parent.right action { y mutate 8 }
        assertEquals(3, tree.internalSettleCount)

        tree.restore(before).getOrThrow()
        assertEquals(4, tree.internalSettleCount, "one frame, one settle")
        assertEquals(before, tree.value)

        parent.left action { x mutate 9 }
        tree.reset().getOrThrow()
        assertEquals(6, tree.internalSettleCount)
        assertEquals(0, seen.last()[parent.left.x])
    }

    @Test
    fun rollbackProducesNoRecompute() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val seen = tree.seen()
        val result =
            parent.left action {
                x mutate 1
                error("veto")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(1, tree.internalSettleCount)
        assertEquals(1, seen.size)
    }

    @Test
    fun aValueObserverThatWritesToAChildTriggersAFollowUpSettleNotAReentrantOne() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        var wrote = false
        val trees = mutableListOf<Int?>()
        disposables +=
            tree effect {
                trees += this[parent.right.y]
                if (!wrote && this[parent.left.x] == 1) {
                    wrote = true
                    parent.right action { y mutate 42 }
                }
            }
        parent.left action { x mutate 1 }
        assertEquals(3, tree.internalSettleCount, "the observer's write settles after the current settle")
        assertEquals(listOf<Int?>(0, 0, 42), trees)
        assertEquals(42, tree.value[parent.right.y])
    }

    @Test
    fun aThrowingValueObserverReachesTheReceiversHandler() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        val caught = mutableListOf<Throwable>()
        parent.uncaughtObserverHandler = { caught += it }
        var fires = 0
        disposables += tree effect { if (++fires > 1) error("observer failed") }
        parent.left action { x mutate 1 }
        assertEquals(1, caught.size)
        assertEquals("observer failed", caught.single().message)
        assertEquals(1, tree.value[parent.left.x], "the settle committed regardless")
    }

    @Test
    fun aLegacyDerivedOnAChildSettlesOncePerSourceCommitWithItsRecomputeIncluded() {
        val parent = TreeValueCoalescingDerivedParent()
        val tree = parent.tree
        val seen = tree.seen()
        parent.derivedStore action { n mutate 3 }
        assertEquals(6, parent.derivedStore.twice.value)
        assertEquals(
            2,
            tree.internalSettleCount,
            "the legacy derived recomputes in the action's drain, inside the same settle scope: one settle",
        )
        assertEquals(6, seen.last()[parent.derivedStore.twice], "and the settled tree already holds its value")
    }

    @Test
    fun theValueIsOneConsistentCaptureEqualToASnapshot() {
        val parent = TreeValueCoalescingParent()
        val tree = parent.tree
        tree.seen()
        parent.left action { x mutate 4 }
        assertEquals(tree.snapshot(), tree.value)
        assertTrue(tree.value == tree.snapshot(), "full value equality")
    }
}
