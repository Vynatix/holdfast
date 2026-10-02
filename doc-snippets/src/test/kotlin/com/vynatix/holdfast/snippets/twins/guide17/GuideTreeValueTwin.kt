// Twin of GUIDE §17.8 (the tree value). Shares the `Notes` store the §17.6
// twin declares; the test drives the block and asserts the output its
// comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#85
fun watchTheTree() {
    var settles = 0
    val watch = Notes.tree effect { settles++ }                         // fires once now, with the current tree
    val n3 = Notes.byId.create("n3")                                    // a store joined: one settle
    atomic(Notes.prefs, n3) {                                           // one frame over two stores: one settle
        Notes.prefs { theme mutate "dark" }
        n3 { body mutate "hi" }
    }.getOrThrow()
    println(settles)                                                    // "3": the subscription, the join, the frame
    val tree = Notes.tree.value
    println(tree[Notes.prefs.theme] to tree[n3.body])                   // "(dark, hi)": never one without the other
    println(tree == Notes.tree.snapshot())                              // "true"
    watch.dispose()
    n3.dispose()
    Notes.tree.reset()
}
// DOC-SNIPPET-END

class GuideTreeValueTwin {
    @Test
    fun watchTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { watchTheTree() }
        assertEquals(listOf("3", "(dark, hi)", "true"), printed)
        assertEquals("light", Notes.prefs.theme.value, "the twin leaves the tree reset")
        assertEquals(emptyMap(), Notes.byId.entries())
    }
}
