// Twin of GUIDE §17.7 (encoding, names and the persisted-name self-check).
// Shares the `Notes` store the §17.6 twin declares; the test drives both
// blocks in order and asserts the output their comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// DOC-SNIPPET holdfast/GUIDE.md#84
fun persistTheTree(): String {
    val n2 = Notes.byId.create("n2")
    n2 action { body mutate "remember me" }
    Notes.prefs action { theme mutate "dark" }
    println(Notes.tree.verifyPersistedNames())                                    // "[]": Notes has a tree id, prefs and byId pins, n2 its key
    val text = Notes.tree.snapshot(scope = SnapshotScope.UserAuthored).encode()   // names for structure; each store its own body
    n2.dispose()
    return text
}

fun rehydrateTheTree(text: String) {
    val tree = Notes.tree.decode(text)                                            // resolves nodes; runs no store code
    println(tree.pendingKeys(Notes.byId))                                         // "[n2]": a body with no live store yet
    tree.pendingKeys(Notes.byId).forEach { Notes.byId.create(it) }                // the process-death idiom
    val report = Notes.tree.restore(tree, RestorePolicy.Strict).getOrThrow()
    println(Notes.byId["n2"]?.body?.value)                                        // "remember me"
    println(report.unresolvedPaths)                                               // "[]"
    Notes.byId["n2"]?.dispose()
    Notes.tree.reset()
}
// DOC-SNIPPET-END

class GuideTreeEncodeTwin {
    @Test
    fun persistAndRehydratePrintWhatTheirCommentsClaim() {
        var text = ""
        val printed =
            capturePrintln {
                text = persistTheTree()
                rehydrateTheTree(text)
            }
        assertEquals(listOf("[]", "[n2]", "remember me", "[]"), printed)
        assertTrue(
            text.startsWith("""{"format":"holdfast.tree","v":1,"receiver":"notes","scope":"UserAuthored","path":[],"tree":{"kind":"leaf","store":"""),
            text,
        )
        assertTrue(""""prefs":{"kind":"leaf"""" in text, "the child's property name, never its class name")
        assertTrue(""""byId":{"kind":"keyed","entries":{"n2":{"kind":"leaf"""" in text, text)
        assertTrue("PrefsStore" !in text && "Prefs\"" !in text, "no class-derived name in the persisted text")
        assertTrue("Notes" !in text, "the receiver is identified by its tree id, never its class name")
        assertEquals("light", Notes.prefs.theme.value, "the twin leaves the tree reset")
        assertEquals("inbox", Notes.folder.value)
        assertEquals(emptyMap(), Notes.byId.entries())
    }
}
