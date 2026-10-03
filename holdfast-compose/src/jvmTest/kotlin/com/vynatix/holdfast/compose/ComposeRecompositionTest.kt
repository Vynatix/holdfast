@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.compose

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.merged
import com.vynatix.holdfast.tree.group
import com.vynatix.holdfast.tree.internalSettleCount
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A reply the user drafts, the server's messages sync adopts, and what the screen shows. */
private class ReplyStore : Store<ReplyStore>() {
    val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
    val server by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val unrelated by state { 0 }
    val shown by merged(draft, server) { d, s -> if (d.isEmpty()) s else s + d }
}

/**
 * A `merged` state observed through the Compose adapter (issue #20, R6,
 * acceptance 2): a one-commit adoption recomposes the reading composable
 * exactly once, and a commit that leaves the merged value as it was does not
 * recompose it at all. Runs a real, headless [androidx.compose.runtime.Recomposer].
 */
class ComposeRecompositionTest {
    @Test
    fun aOneCommitAdoptionOfAMergedStateRecomposesOnce() =
        runTest {
            val store = ReplyStore()
            val ui = HeadlessComposition(this)
            var compositions = 0
            var rendered: List<String>? = null
            ui.setContent {
                val shown = store.collectAsState(store.shown)
                compositions++
                rendered = shown.value
            }
            assertEquals(1, compositions)
            assertEquals(emptyList(), rendered)

            // An adoption: several writes to the remote state, one commit.
            store action {
                server mutate listOf("a")
                server update { it + "b" }
                server update { it + "c" }
            }
            ui.settle()

            assertEquals(2, compositions, "one adoption commit recomposes once")
            assertEquals(listOf("a", "b", "c"), rendered)
            ui.dispose()
        }

    @Test
    fun aCommitThatLeavesTheMergedValueAsItWasDoesNotRecompose() =
        runTest {
            val store = ReplyStore()
            val ui = HeadlessComposition(this)
            var compositions = 0
            ui.setContent {
                store.collectAsState(store.shown).value
                compositions++
            }

            store action { unrelated mutate 1 } // Not a source.
            ui.settle()
            store action { server mutate emptyList() } // A source, but the same merged value.
            ui.settle()

            assertEquals(1, compositions)
            store action { draft mutate "reply" }
            ui.settle()
            assertEquals(2, compositions)
            ui.dispose()
        }

    @Test
    fun aTreeValueRecomposesOnceForATwoStoreFrame() =
        runTest {
            val parent = TreeParentStore()
            val ui = HeadlessComposition(this)
            var compositions = 0
            var rendered: Pair<Int?, Int?>? = null
            ui.setContent {
                val tree = parent.tree.collectAsState()
                compositions++
                rendered = tree.value[parent.left.x] to tree.value[parent.right.y]
            }
            assertEquals(1, compositions)
            assertEquals(0 to 0, rendered)

            atomic(parent.left, parent.right) {
                parent.left { x mutate 1 }
                parent.right { y mutate 2 }
            }.getOrThrow()
            ui.settle()

            assertEquals(2, compositions, "one settle of the tree recomposes once")
            assertEquals(1 to 2, rendered)
            ui.dispose()
        }

    @Test
    fun theInitialCompositionReadBuildsAnUnreadTree() =
        runTest {
            val parent = TreeParentStore()
            val tree = parent.tree
            assertEquals(0, tree.internalSettleCount, "declaring the children captured nothing")
            val ui = HeadlessComposition(this)
            var rendered: Int? = null
            ui.setContent { rendered = tree.collectAsState().value[parent.left.x] }
            assertEquals(1, tree.internalSettleCount, "the first composition read built the tree")
            assertEquals(0, rendered)
            ui.dispose()
        }
}

private class TreeLeftStore : Store<TreeLeftStore>() {
    val x by state { 0 }
}

private class TreeRightStore : Store<TreeRightStore>() {
    val y by state { 0 }
}

private class TreeParentStore : Store<TreeParentStore>() {
    val left = TreeLeftStore()
    val right = TreeRightStore()
    val pair by group { listOf(left, right) }
}
