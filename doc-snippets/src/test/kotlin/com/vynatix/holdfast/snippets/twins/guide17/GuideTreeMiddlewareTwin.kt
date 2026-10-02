// Twin of GUIDE §17.9 (tree middleware). Shares the `Notes` store the §17.6
// twin declares; the test drives the block and asserts the output its
// comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#86
class Audit : TreeMiddleware() {
    val log = mutableListOf<String>()

    override fun onTransactionCompleted(node: StoreNode, context: Middleware.MiddlewareContext<*>) {
        log += "${node.name} ${if (context.transaction.frameId != null) "in a frame" else "alone"}"
    }
}

fun auditTheTree() {
    val audit = Audit()
    Notes.tree.middlewares(audit)                                  // Notes and every store under it, now and later
    val n4 = Notes.byId.create("n4")                               // attached after install: covered
    Notes action { folder mutate "archive" }                       // the parent's own transactions too
    Notes.prefs action { theme mutate "dark" }
    atomic(Notes.prefs, n4) { n4 { body mutate "x" } }.getOrThrow()
    println(audit.log)                                             // "[Notes alone, prefs alone, prefs in a frame, n4 in a frame]"
    println(Notes.tree.removeMiddleware(audit))                    // "true": no new observation from here on
    n4.dispose()
    Notes.tree.reset()
}
// DOC-SNIPPET-END

class GuideTreeMiddlewareTwin {
    @Test
    fun auditTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { auditTheTree() }
        assertEquals(listOf("[Notes alone, prefs alone, prefs in a frame, n4 in a frame]", "true"), printed)
        assertEquals("light", Notes.prefs.theme.value, "the twin leaves the tree reset")
        assertEquals("inbox", Notes.folder.value)
        assertEquals(emptyMap(), Notes.byId.entries)
    }
}
