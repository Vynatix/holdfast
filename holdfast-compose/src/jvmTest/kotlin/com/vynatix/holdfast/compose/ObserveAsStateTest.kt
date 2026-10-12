@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.compose

import androidx.compose.runtime.getValue
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.merged
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A reply the user drafts, the server's messages sync adopts, and what the screen shows. */
private class ReplyDraftStore : Store<ReplyDraftStore>() {
    val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
    val server by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val shown by merged(draft, server) { d, s -> if (d.isEmpty()) s else s + d }
}

/**
 * The single-mention observation entrypoint (ROADMAP 0.6.0, E1): a distinct
 * commit recomposes its reader, an equal commit does not (the `distinct`
 * default at every declaration site skips the observer fire, and the Compose
 * state itself is structural-equality policy), and a `merged` state reads
 * through it exactly as through `collectAsState` — one recomposition per
 * one-commit adoption.
 */
class ObserveAsStateTest {
    @Test
    fun aDistinctCommitRecomposesAndAnEqualCommitDoesNot() =
        runTest {
            val store = ReplyDraftStore()
            val ui = HeadlessComposition(this)
            var compositions = 0
            var rendered: List<String>? = null
            ui.setContent {
                val shown by store.shown.observeAsState()
                compositions++
                rendered = shown
            }
            assertEquals(1, compositions)
            assertEquals(emptyList(), rendered)

            store action { server mutate listOf("a") }
            ui.settle()
            assertEquals(2, compositions, "a distinct commit recomposes the single-mention reader")
            assertEquals(listOf("a"), rendered)

            // A source commit that leaves the merged value as it was: the
            // recompute publishes an equal value, so nothing invalidates.
            store action { draft mutate "" }
            ui.settle()
            assertEquals(2, compositions, "an equal commit does not recompose")

            store action { draft mutate "reply" }
            ui.settle()
            assertEquals(3, compositions)
            assertEquals(listOf("a", "reply"), rendered)
            ui.dispose()
        }
}
