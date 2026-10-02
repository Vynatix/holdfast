// Twin of GUIDE §17.10 (testing a tree). Shares the `Notes` store the §17.6
// twin declares; the test drives the block and asserts the output its
// comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.testing.matcher.shouldCommitTogether
import com.vynatix.holdfast.testing.storeTest
import com.vynatix.holdfast.testing.track
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#87
fun testTheTree() =
    storeTest {
        val tree = track(Notes.tree)                                  // Notes and every store under it; reset at teardown
        val n5 = Notes.byId.create("n5")                              // tracked as it joins
        atomic(Notes, Notes.prefs, n5) {                              // the parent's own state in the same frame
            Notes { folder mutate "archive" }
            Notes.prefs { theme mutate "dark" }
            n5 { body mutate "hi" }
        }.getOrThrow()
        println(tree.timeline.map { "${it.phase} ${it.node.name}" }) // "[Started Notes, Started prefs, Started n5, Completed Notes, Completed prefs, Completed n5]"
        println(tree.shouldCommitTogether(Notes.tree.node) == tree.committedFrameIds(Notes.tree.node).single())   // "true": one frame over all three
        println(tree.handle(n5).transactions.size)                    // "2": the store's own timeline, started and committed
        n5.dispose()
        println(tree.events(Notes.byId).size)                         // "2": a disposed store's events stay under its branch
    }
// DOC-SNIPPET-END

class GuideTreeTestingTwin {
    @Test
    fun testTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { testTheTree() }
        assertEquals(
            listOf(
                "[Started Notes, Started prefs, Started n5, Completed Notes, Completed prefs, Completed n5]",
                "true",
                "2",
                "2",
            ),
            printed,
        )
        assertEquals("light", Notes.prefs.theme.value, "teardown reset the tree")
        assertEquals("inbox", Notes.folder.value, "teardown reset the parent's own state too")
        assertEquals(emptyMap(), Notes.byId.entries())
    }
}
