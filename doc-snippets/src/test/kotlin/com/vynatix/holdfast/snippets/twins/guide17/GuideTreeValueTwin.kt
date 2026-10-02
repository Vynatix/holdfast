// Twin of GUIDE §17.8 (the tree value). Shares the `Notes` root the §17.6
// twin declares; the test drives the block and asserts the output its
// comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#85
fun watchTheTree() {
    var settles = 0
    val watch = Notes.value effect { settles++ }                       // fires once now, with the current tree
    val n3 = Notes.byId.create("n3", ::NoteStore)                      // a leaf joined: one settle
    atomic(Notes.prefsStore, n3) {                                     // one frame over two leaves: one settle
        Notes.prefsStore { theme mutate "dark" }
        n3 { body mutate "hi" }
    }.getOrThrow()
    println(settles)                                                   // "3": the subscription, the join, the frame
    val tree = Notes.value.value
    println(tree[Notes.prefsStore.theme] to tree[n3.body])             // "(dark, hi)": never one without the other
    println(tree == Notes.snapshot())                                  // "true"
    watch.dispose()
    n3.dispose()
    Notes.reset()
}
// DOC-SNIPPET-END

class GuideTreeValueTwin {
    @Test
    fun watchTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { watchTheTree() }
        assertEquals(listOf("3", "(dark, hi)", "true"), printed)
        assertEquals("light", Notes.prefsStore.theme.value, "the twin leaves the tree reset")
        assertEquals(emptyMap(), Notes.entries(Notes.byId))
    }
}
