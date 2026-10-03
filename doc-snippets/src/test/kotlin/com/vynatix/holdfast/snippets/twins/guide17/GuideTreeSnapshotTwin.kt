// Twin of GUIDE §17.5 (tree snapshots and typed reads). Shares the `App`
// store the §17.1 twin declares; the test drives the block and asserts the
// output its comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#82
fun readTheTree() {
    val t2 = App.threads.create("t2")
    val title = t2.title
    t2 action { title mutate "hello" }
    val tree = App.tree.snapshot()                             // one consistent cut: App and every live store under it
    val read: String? = tree[title]                            // typed by the state; null outside the capture
    println(read)                                              // "hello"
    println(tree[App.threads]?.children?.map { it.name })      // "[t2]"
    println(App.tree.snapshot(App.threads)[title] == read)     // "true": a subtree capture
    println(tree[App.openThreads])                             // "[]": the parent's own state
    println(tree == App.tree.snapshot())                       // "true": value equality
    t2.dispose()
    println(tree[title])                                       // "hello": the capture outlives the store
}
// DOC-SNIPPET-END

class GuideTreeSnapshotTwin {
    @Test
    fun readTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { readTheTree() }
        assertEquals(listOf("hello", "[t2]", "true", "[]", "true", "hello"), printed)
        assertEquals(null, App.threads["t2"], "the twin leaves no keyed store behind")
    }
}
