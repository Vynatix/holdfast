@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class CapLeftStore : Store<CapLeftStore>() {
    var initializerRuns = 0
    val x by state {
        initializerRuns++
        1
    }
    val untouched by state { "declared, never read" }
}

private class CapRightStore : Store<CapRightStore>() {
    val y by state { 2 }
}

private class CapThrowingStore : Store<CapThrowingStore>() {
    var shouldThrow = true
    val fragile by state {
        if (shouldThrow) error("initializer refused")
        "ok"
    }
}

private class CapKeyedStore : Store<CapKeyedStore>() {
    val n by state { 0 }
}

/** A mid-tree child holding two store children and a keyed branch, so a subtree capture of it has all three kinds. */
private class CapPairStore : Store<CapPairStore>() {
    /** Runs inside the keyed factory, after the store is built and before it attaches. */
    var insideFactory: (() -> Unit)? = null
    val left by store { CapLeftStore() }
    val right by store { CapRightStore() }
    val keyed by keyed<String, CapKeyedStore> { CapKeyedStore().also { insideFactory?.invoke() } }
}

private class CapApp : Store<CapApp>() {
    val pair by store { CapPairStore() }
    val fragileStore = CapThrowingStore()
    val fragile by group { listOf(fragileStore) }
    val left: CapLeftStore get() = pair.left
    val right: CapRightStore get() = pair.right
    val keyed: KeyedBranch<String, CapKeyedStore> get() = pair.keyed
}

/** `tree.snapshot` semantics that need no threads: materialization, subtree bounds, and where a capture may be taken from. */
class TreeCaptureTest {
    @Test
    fun aSnapshotOfAnUntouchedTreeMaterializesEveryDeclaredState() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        assertEquals(0, root.left.initializerRuns)
        val tree = root.tree.snapshot()
        assertEquals(1, root.left.initializerRuns)
        assertEquals("declared, never read", tree[root.left.untouched])
        assertEquals(2, tree[root.right.y])
        assertEquals(setOf("x", "untouched"), tree[root.tree.nodeOf(root.left)!!]!!.leaf!!.stateNames)
    }

    @Test
    fun aSubtreeSnapshotContainsOnlyThatSubtree() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        val k = root.keyed.create("k")
        val pair = root.tree.snapshot(root.tree.nodeOf(root.pair)!!)
        assertEquals(listOf("left", "right", "keyed"), pair.children.map { it.name })
        assertNotNull(pair[root.tree.nodeOf(k)!!])
        assertNull(pair[root.fragile])
        assertNull(pair[root.fragileStore.fragile])
        val leafOnly = root.tree.snapshot(root.tree.nodeOf(root.right)!!)
        assertTrue(leafOnly.isLeaf)
        assertEquals(2, leafOnly[root.right.y])
    }

    @Test
    fun aSnapshotFromInsideALeafObserverReadsTheCommittedValues() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        var seen: Int? = null
        root.left.x effect { if (this == 5) seen = root.tree.snapshot()[root.left.x] }
        root.left action { x mutate 5 }
        assertEquals(5, seen, "an observer runs after the apply pass, so the cut holds the new value")
    }

    @Test
    fun aSnapshotFromAMiddlewareBeforeCommitReadsTheOldCommittedValue() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        var seen: Int? = null
        root.left.middlewares(
            object : Middleware<CapLeftStore>() {
                override fun onTransactionCompleted(context: MiddlewareContext<CapLeftStore>) {
                    seen = root.tree.snapshot()[root.left.x]
                }
            },
        )
        root.left action { x mutate 7 }
        assertEquals(1, seen, "onTransactionCompleted runs before the commit applies")
        assertEquals(7, root.left.x.value)
    }

    @Test
    fun aSnapshotInsideAFrameHoldingHigherKeysDoesNotThrow() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        val result =
            atomic(root.left, root.right) {
                root.left { x mutate 10 }
                root.tree.snapshot()
            }
        assertIs<TransactionResult.Success<TreeSnapshot>>(result)
        assertEquals(1, result.value[root.left.x], "inside the body the cut holds committed values only")
    }

    @Test
    fun onTheCommittingThreadASnapshotReadsCommittedNotPendingValues() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        root.left action {
            x mutate 42
            assertEquals(42, x.value, "read-your-own-writes on the owner thread")
            assertEquals(1, root.tree.snapshot()[root.left.x], "the capture ignores the pending write")
        }
        assertEquals(42, root.tree.snapshot()[root.left.x])
    }

    @Test
    fun aDisposedLeafIsLeftOutOfLaterCaptures() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        val k = root.keyed.create("k")
        k.dispose()
        root.left.dispose()
        val tree = root.tree.snapshot()
        assertNull(tree[root.tree.nodeOf(root.right)!!]?.let { null })
        assertEquals(listOf("right", "keyed"), tree[root.tree.nodeOf(root.pair)!!]!!.children.map { it.name })
        assertTrue(tree[root.keyed]!!.children.isEmpty())
    }

    @Test
    fun aCaptureNeverContainsAStoreStillInsideItsFactory() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        var duringFactory: TreeSnapshot? = null
        root.pair.insideFactory = { duringFactory = root.tree.snapshot() }
        root.keyed.create("k")
        assertTrue(duringFactory!![root.keyed]!!.children.isEmpty(), "the store was not yet promoted")
        assertEquals(
            1,
            root.tree
                .snapshot()[root.keyed]!!
                .children.size,
        )
    }

    @Test
    fun aThrowingInitializerPropagatesAndStaysRetryable() {
        val root = CapApp()
        val error = assertFailsWith<IllegalStateException> { root.tree.snapshot() }
        assertEquals("initializer refused", error.message)
        root.fragileStore.shouldThrow = false
        assertEquals("ok", root.tree.snapshot()[root.fragileStore.fragile])
    }

    @Test
    fun aSnapshotOfAnotherReceiversNodeIsRefused() {
        val root = CapApp()
        val other = CapApp()
        assertFailsWith<IllegalArgumentException> { root.tree.snapshot(other.fragile) }
        assertFailsWith<IllegalArgumentException> { root.tree.snapshot(other.tree.nodeOf(other.pair)!!) }
        assertFailsWith<IllegalArgumentException> { root.tree.snapshot(other.tree.node) }
    }

    @Test
    fun aMidTreeChildsOwnTreeCapturesItsSubtreeWithItselfAsTheReceiver() {
        val root = CapApp()
        root.fragileStore.shouldThrow = false
        val pair = root.pair
        val mid = pair.tree.snapshot()
        assertSame(pair.tree.node, mid.node)
        assertEquals("pair", mid.name, "the receiver's node keeps the name of its place under its parent")
        assertEquals(listOf("left", "right", "keyed"), mid.children.map { it.name })
        assertEquals(2, mid[root.right.y])
        assertFailsWith<IllegalArgumentException>("the parent's group is not under the child") {
            pair.tree.snapshot(root.fragile)
        }
    }
}
