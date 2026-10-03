@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.tree.keyed
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
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
) : Store<IaKeyedStore>() {
    val title by state { "t $id" }
}

private class IaParent : Store<IaParent>() {
    val a by store { IaLeafStore() }
    val b by store { IaLeafStore() }
    val keyed by keyed<String, IaKeyedStore> { IaKeyedStore(it) }
}

/**
 * `track(tree)` is refused from inside a member's transaction — the
 * receiver's own included — or an `atomic` frame (the tree middleware ring is
 * snapshotted per transaction). A refused call must leave nothing behind: no
 * tracked store, and no membership listener that would go on tracking every
 * keyed store created later.
 */
class TreeFixtureInsideActionTest {
    @Test
    fun aTrackRefusedInsideAMemberActionLeavesNothingBehind() =
        storeTest {
            val parent = IaParent()
            val result = parent.a action { track(parent.tree) }
            val error = assertIs<TransactionResult.Error>(result)
            assertIs<IllegalStateException>(error.exception)
            assertTrue(allTrackedHandles().isEmpty(), "the refused call tracked no store")

            val k = parent.keyed.create("k")
            assertNull(handleFor(k), "the refused call left no membership listener behind")
            assertTrue(allTrackedHandles().isEmpty())

            // From outside, the same tree is tracked afresh, as if the refused call never happened.
            val tree = track(parent.tree)
            k action { title mutate "x" }
            assertEquals(listOf("Started k", "Completed k"), tree.timeline.map { "${it.phase} ${it.node.name}" })
            assertEquals(4, allTrackedHandles().size, "the receiver, a, b, k")
        }

    @Test
    fun aTrackRefusedInsideTheReceiversOwnActionLeavesNothingBehind() =
        storeTest {
            val parent = IaParent()
            val result = parent action { track(parent.tree) }
            val error = assertIs<TransactionResult.Error>(result)
            assertIs<IllegalStateException>(error.exception)
            assertTrue(allTrackedHandles().isEmpty(), "the refused call tracked no store")

            val k = parent.keyed.create("k")
            assertNull(handleFor(k), "the refused call left no membership listener behind")
        }

    @Test
    fun aTrackRefusedInsideAnAtomicFrameLeavesNothingBehind() =
        storeTest {
            val parent = IaParent()
            val result = atomic(parent.a, parent.b) { track(parent.tree) }
            val error = assertIs<TransactionResult.Error>(result)
            assertIs<IllegalStateException>(error.exception)
            assertTrue(allTrackedHandles().isEmpty(), "the refused call tracked no store")

            val k = parent.keyed.create("k")
            assertNull(handleFor(k), "the refused call left no membership listener behind")
            assertTrue(allTrackedHandles().isEmpty())
        }
}
