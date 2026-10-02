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

private class CapKeyedStore(
    id: String,
    root: CapRoot,
) : Store<CapKeyedStore>(root.keyed.at(id)) {
    val n by state { 0 }
}

private class CapRoot : Root("cap") {
    val left = CapLeftStore()
    val right = CapRightStore()
    val fragileStore = CapThrowingStore()
    val pair by branch(left, right)
    val fragile by branch(fragileStore)
    val keyed by keyed<String, CapKeyedStore>(under = pair)
}

/** `Root.snapshot` semantics that need no threads: materialization, subtree bounds, and where a capture may be taken from. */
class TreeCaptureTest {
    @Test
    fun aSnapshotOfAnUntouchedTreeMaterializesEveryDeclaredState() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        assertEquals(0, root.left.initializerRuns)
        val tree = root.snapshot()
        assertEquals(1, root.left.initializerRuns)
        assertEquals("declared, never read", tree[root.left.untouched])
        assertEquals(2, tree[root.right.y])
        assertEquals(setOf("x", "untouched"), tree[root.nodeOf(root.left)!!]!!.leaf!!.stateNames)
    }

    @Test
    fun aSubtreeSnapshotContainsOnlyThatSubtree() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        val k = root.keyed.create("k") { CapKeyedStore(it, root) }
        val pair = root.snapshot(root.pair)
        assertEquals(listOf("CapLeft", "CapRight", "keyed"), pair.children.map { it.node.name })
        assertNotNull(pair[root.nodeOf(k)!!])
        assertNull(pair[root.fragile])
        assertNull(pair[root.fragileStore.fragile])
        val leafOnly = root.snapshot(root.nodeOf(root.right)!!)
        assertTrue(leafOnly.isLeaf)
        assertEquals(2, leafOnly[root.right.y])
    }

    @Test
    fun aSnapshotFromInsideALeafObserverReadsTheCommittedValues() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        var seen: Int? = null
        root.left.x effect { if (this == 5) seen = root.snapshot()[root.left.x] }
        root.left action { x mutate 5 }
        assertEquals(5, seen, "an observer runs after the apply pass, so the cut holds the new value")
    }

    @Test
    fun aSnapshotFromAMiddlewareBeforeCommitReadsTheOldCommittedValue() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        var seen: Int? = null
        root.left.middlewares(
            object : Middleware<CapLeftStore>() {
                override fun onTransactionCompleted(context: MiddlewareContext<CapLeftStore>) {
                    seen = root.snapshot()[root.left.x]
                }
            },
        )
        root.left action { x mutate 7 }
        assertEquals(1, seen, "onTransactionCompleted runs before the commit applies")
        assertEquals(7, root.left.x.value)
    }

    @Test
    fun aSnapshotInsideAFrameHoldingHigherKeysDoesNotThrow() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        val result =
            atomic(root.left, root.right) {
                root.left { x mutate 10 }
                root.snapshot()
            }
        assertIs<TransactionResult.Success<TreeSnapshot>>(result)
        assertEquals(1, result.value[root.left.x], "inside the body the cut holds committed values only")
    }

    @Test
    fun onTheCommittingThreadASnapshotReadsCommittedNotPendingValues() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        root.left action {
            x mutate 42
            assertEquals(42, x.value, "read-your-own-writes on the owner thread")
            assertEquals(1, root.snapshot()[root.left.x], "the capture ignores the pending write")
        }
        assertEquals(42, root.snapshot()[root.left.x])
    }

    @Test
    fun aDisposedLeafIsLeftOutOfLaterCaptures() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        val k = root.keyed.create("k") { CapKeyedStore(it, root) }
        k.dispose()
        root.left.dispose()
        val tree = root.snapshot()
        assertNull(tree[root.nodeOf(root.right)!!]?.let { null })
        assertEquals(listOf("CapRight", "keyed"), tree[root.pair]!!.children.map { it.node.name })
        assertTrue(tree[root.keyed]!!.children.isEmpty())
    }

    @Test
    fun aCaptureNeverContainsAStoreStillInsideItsFactory() {
        val root = CapRoot()
        root.fragileStore.shouldThrow = false
        var duringFactory: TreeSnapshot? = null
        root.keyed.create("k") { id ->
            val store = CapKeyedStore(id, root)
            duringFactory = root.snapshot()
            store
        }
        assertTrue(duringFactory!![root.keyed]!!.children.isEmpty(), "the store was not yet promoted")
        assertEquals(1, root.snapshot()[root.keyed]!!.children.size)
    }

    @Test
    fun aThrowingInitializerPropagatesAndStaysRetryable() {
        val root = CapRoot()
        val error = assertFailsWith<IllegalStateException> { root.snapshot() }
        assertEquals("initializer refused", error.message)
        root.fragileStore.shouldThrow = false
        assertEquals("ok", root.snapshot()[root.fragileStore.fragile])
    }

    @Test
    fun aSnapshotOfAnotherRootsNodeIsRefused() {
        val root = CapRoot()
        val other = CapRoot()
        assertFailsWith<IllegalArgumentException> { root.snapshot(other.pair) }
    }
}
