@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.tree.Root
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class IaLeafStore : Store<IaLeafStore>() {
    val n by state { 0 }
}

private class IaKeyedStore(
    id: String,
    root: IaRoot,
) : Store<IaKeyedStore>(root.keyed.at(id)) {
    val title by state { "t $id" }
}

private class IaRoot : Root("ia") {
    val a = IaLeafStore()
    val b = IaLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
    val keyed by keyed<String, IaKeyedStore>()
}

/**
 * `trackTree` is refused from inside a leaf's transaction or an `atomic`
 * frame (the tree middleware ring is snapshotted per transaction). A refused
 * call must leave nothing behind: no tracked leaf, and no membership
 * listener that would go on tracking every keyed store created later.
 */
class TreeFixtureInsideActionTest {
    @Test
    fun aTrackTreeRefusedInsideALeafActionLeavesNothingBehind() =
        storeTest {
            val root = IaRoot()
            val result = root.a action { trackTree(root) }
            val error = assertIs<TransactionResult.Error>(result)
            assertIs<IllegalStateException>(error.exception)
            assertTrue(allTrackedHandles().isEmpty(), "the refused call tracked no leaf")

            val k = root.keyed.create("k") { IaKeyedStore(it, root) }
            assertNull(handleFor(k), "the refused call left no membership listener behind")
            assertTrue(allTrackedHandles().isEmpty())

            // From outside, the same root is tracked afresh, as if the refused call never happened.
            val tree = trackTree(root)
            k action { title mutate "x" }
            assertEquals(listOf("Started k", "Completed k"), tree.timeline.map { "${it.phase} ${it.node.name}" })
            assertEquals(3, allTrackedHandles().size, "a, b, k")
        }

    @Test
    fun aTrackTreeRefusedInsideAnAtomicFrameLeavesNothingBehind() =
        storeTest {
            val root = IaRoot()
            val result = atomic(root.a, root.b) { trackTree(root) }
            val error = assertIs<TransactionResult.Error>(result)
            assertIs<IllegalStateException>(error.exception)
            assertTrue(allTrackedHandles().isEmpty(), "the refused call tracked no leaf")

            val k = root.keyed.create("k") { IaKeyedStore(it, root) }
            assertNull(handleFor(k), "the refused call left no membership listener behind")
            assertTrue(allTrackedHandles().isEmpty())
        }
}
