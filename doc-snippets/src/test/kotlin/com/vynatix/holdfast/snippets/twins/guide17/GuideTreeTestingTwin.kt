// Twin of GUIDE §17.10 (testing a tree). Shares the `Notes` root the §17.6
// twin declares; the test drives the block and asserts the output its
// comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.testing.matcher.shouldCommitTogether
import com.vynatix.holdfast.testing.storeTest
import com.vynatix.holdfast.testing.trackTree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#87
fun testTheTree() =
    storeTest {
        val tree = trackTree(Notes)                                   // every leaf, now and later; reset at teardown
        val n5 = Notes.byId.create("n5", ::NoteStore)                 // tracked as it joins
        atomic(Notes.prefsStore, n5) {
            Notes.prefsStore { theme mutate "dark" }
            n5 { body mutate "hi" }
        }.getOrThrow()
        println(tree.timeline.map { "${it.phase} ${it.node.name}" }) // "[Started prefs, Started n5, Completed prefs, Completed n5]"
        println(tree.shouldCommitTogether(Notes) == tree.committedFrameIds(Notes).single())   // "true": one frame over both
        println(tree.handle(n5).transactions.size)                    // "2": the leaf's own timeline, started and committed
        n5.dispose()
        println(tree.events(Notes.byId).size)                         // "2": a disposed leaf's events stay under its branch
    }
// DOC-SNIPPET-END

class GuideTreeTestingTwin {
    @Test
    fun testTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { testTheTree() }
        assertEquals(
            listOf("[Started prefs, Started n5, Completed prefs, Completed n5]", "true", "2", "2"),
            printed,
        )
        assertEquals("light", Notes.prefsStore.theme.value, "teardown reset the tree")
        assertEquals(emptyMap(), Notes.entries(Notes.byId))
    }
}
