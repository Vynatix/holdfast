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

private class VcLeftStore : Store<VcLeftStore>() {
    val x by state { 0 }
    val strict by state(distinct = true) { 0 }
    val loose by state(distinct = false) { "same" }
}

private class VcRightStore : Store<VcRightStore>() {
    val y by state { 0 }
}

private class VcDerivedHostStore : Store<VcDerivedHostStore>() {
    val n by state { 0 }
    val twice: State<Int>
    val twiceHandle: Disposable

    init {
        val (state, handle) = derived(n) { n.value * 2 }
        twice = state
        twiceHandle = handle
    }
}

private class VcRoot : Root("vc") {
    val left = VcLeftStore()
    val right = VcRightStore()
    val pair by branch(left, right)
}

private class VcHostLog<V : Store<V>> : Middleware<V>() {
    var completed = 0

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        completed++
    }
}

/** T4 value: `Root.value` recomputes once per outermost entry, after every lock releases. */
class RootValueCoalescingTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest
    fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun Root.seen(): MutableList<TreeSnapshot> {
        val seen = mutableListOf<TreeSnapshot>()
        disposables += value effect { seen += this }
        return seen
    }

    @Test
    fun aTwoStoreAtomicFrameRecomputesValueExactlyOnce() {
        val root = VcRoot()
        val hostLog = VcHostLog<RootHost>()
        root.rootValue.host.middlewares(hostLog)
        val seen = root.seen()
        assertEquals(1, seen.size, "the baseline, read after subscribing")
        assertEquals(1, root.internalSettleCount, "the initial capture")

        atomic(root.left, root.right) {
            root.left { x mutate 1 }
            root.right { y mutate 2 }
        }.getOrThrow()

        assertEquals(2, root.internalSettleCount, "one recompute for the whole frame")
        assertEquals(1, hostLog.completed, "one recompute transaction on the host")
        assertEquals(2, seen.size)
        assertEquals(1, seen[1][root.left.x])
        assertEquals(2, seen[1][root.right.y])
        assertSame(seen[1], root.value.value)
    }

    @Test
    fun aSingleCommitOutsideAFrameRecomputesOnce() {
        val root = VcRoot()
        val seen = root.seen()
        root.left action { x mutate 5 }
        assertEquals(2, root.internalSettleCount)
        assertEquals(2, seen.size)
        assertEquals(5, seen.last()[root.left.x])
    }

    @Test
    fun aDistinctDedupedCommitProducesNoRecompute() {
        val root = VcRoot()
        val seen = root.seen()
        root.left action { strict mutate 0 }
        assertEquals(1, root.internalSettleCount, "the write changed nothing: no edge is told")
        assertEquals(1, seen.size)
    }

    @Test
    fun anEqualValueCommitOnANonDistinctLeafSettlesButDoesNotFire() {
        val root = VcRoot()
        val seen = root.seen()
        root.left action { loose mutate "same" }
        assertEquals(2, root.internalSettleCount, "the commit fired, so the value recomputed")
        assertEquals(1, seen.size, "the tree is value-equal: the distinct backing does not fire")
    }

    @Test
    fun nestedActionsAndNestedSameFlavourFramesSettleOnceAtTheOutermostExit() {
        val root = VcRoot()
        val seen = root.seen()
        root.left action {
            x mutate 1
            root.right action { y mutate 1 }
            x mutate 2
            assertEquals(1, root.internalSettleCount, "nothing settles inside the outermost entry")
        }
        assertEquals(2, root.internalSettleCount)
        assertEquals(2, seen.size)

        atomic(root.left, root.right) {
            root.left { x mutate 3 }
            atomic(root.left, root.right) { root.right { y mutate 3 } }.getOrThrow()
        }.getOrThrow()
        assertEquals(3, root.internalSettleCount, "a nested same-flavour frame settles with the outermost")
        assertEquals(3, seen.size)
        assertEquals(3, seen.last()[root.left.x])
        assertEquals(3, seen.last()[root.right.y])
    }

    @Test
    fun restoreAndResetOverASubtreeRecomputeOnce() {
        val root = VcRoot()
        val seen = root.seen()
        val before = root.snapshot()
        root.left action { x mutate 7 }
        root.right action { y mutate 8 }
        assertEquals(3, root.internalSettleCount)

        root.restore(before).getOrThrow()
        assertEquals(4, root.internalSettleCount, "one frame, one settle")
        assertEquals(before, root.value.value)

        root.left action { x mutate 9 }
        root.reset().getOrThrow()
        assertEquals(6, root.internalSettleCount)
        assertEquals(0, seen.last()[root.left.x])
    }

    @Test
    fun rollbackProducesNoRecompute() {
        val root = VcRoot()
        val seen = root.seen()
        val result =
            root.left action {
                x mutate 1
                error("veto")
            }
        assertIs<TransactionResult.Error>(result)
        assertEquals(1, root.internalSettleCount)
        assertEquals(1, seen.size)
    }

    @Test
    fun aValueObserverThatWritesToALeafTriggersAFollowUpSettleNotAReentrantOne() {
        val root = VcRoot()
        var wrote = false
        val trees = mutableListOf<Int?>()
        disposables +=
            root.value effect {
                trees += this[root.right.y]
                if (!wrote && this[root.left.x] == 1) {
                    wrote = true
                    root.right action { y mutate 42 }
                }
            }
        root.left action { x mutate 1 }
        assertEquals(3, root.internalSettleCount, "the observer's write settles after the current settle")
        assertEquals(listOf<Int?>(0, 0, 42), trees)
        assertEquals(42, root.value.value[root.right.y])
    }

    @Test
    fun aThrowingValueObserverReachesTheRootsHandler() {
        val root = VcRoot()
        val caught = mutableListOf<Throwable>()
        root.uncaughtObserverHandler = { caught += it }
        var fires = 0
        disposables += root.value effect { if (++fires > 1) error("observer failed") }
        root.left action { x mutate 1 }
        assertEquals(1, caught.size)
        assertEquals("observer failed", caught.single().message)
        assertEquals(1, root.value.value[root.left.x], "the settle committed regardless")
    }

    @Test
    fun aLegacyDerivedOnALeafSettlesOncePerSourceCommitWithItsRecomputeIncluded() {
        val root = VcDerivedRoot()
        val seen = root.seen()
        root.host action { n mutate 3 }
        assertEquals(6, root.host.twice.value)
        assertEquals(
            2,
            root.internalSettleCount,
            "the legacy derived recomputes in the action's drain, inside the same settle scope: one settle",
        )
        assertEquals(6, seen.last()[root.host.twice], "and the settled tree already holds its value")
    }

    @Test
    fun theValueIsOneConsistentCaptureEqualToASnapshot() {
        val root = VcRoot()
        root.seen()
        root.left action { x mutate 4 }
        assertEquals(root.snapshot(), root.value.value)
        assertTrue(root.value.value == root.snapshot(), "full value equality")
    }
}

private class VcDerivedRoot : Root("vcd") {
    val host = VcDerivedHostStore()
    val leaf by branch(host)
}
