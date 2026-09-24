@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.compose

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.merged
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
}
