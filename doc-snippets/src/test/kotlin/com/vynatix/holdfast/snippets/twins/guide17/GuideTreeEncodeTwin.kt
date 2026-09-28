// Twin of GUIDE §17.7 (encoding, names and the persisted-name self-check).
// Shares the `Notes` root the §17.6 twin declares; the test drives both
// blocks in order and asserts the output their comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// DOC-SNIPPET holdfast/GUIDE.md#84
fun persistTheTree(): String {
    val n2 = Notes.byId.create("n2", ::NoteStore)
    n2 action { body mutate "remember me" }
    Notes.prefsStore action { theme mutate "dark" }
    println(Notes.verifyPersistedNames())                                    // "[]": every persisted leaf is pinned or keyed
    val text = Notes.snapshot(scope = SnapshotScope.UserAuthored).encode()   // names for structure; each leaf its store's body
    n2.dispose()
    return text
}

fun rehydrateTheTree(text: String) {
    val tree = Notes.decode(text)                                            // resolves nodes; runs no store code
    println(tree.pendingKeys(Notes.byId))                                    // "[n2]": a body with no live store yet
    tree.pendingKeys(Notes.byId).forEach { Notes.byId.create(it, ::NoteStore) }   // the process-death idiom
    val report = Notes.restore(tree, RestorePolicy.Strict).getOrThrow()
    println(Notes[Notes.byId, "n2"]?.body?.value)                            // "remember me"
    println(report.unresolvedPaths)                                          // "[]"
    Notes[Notes.byId, "n2"]?.dispose()
    Notes.reset()
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
        assertTrue(text.startsWith("""{"format":"holdfast.tree","v":1,"scope":"UserAuthored""""), text)
        assertTrue(""""prefs":{"kind":"leaf"""" in text, "the pinned leaf name, never the class name")
        assertTrue("PrefsStore" !in text && "Prefs\"" !in text, "no class-derived name in the persisted text")
        assertEquals("light", Notes.prefsStore.theme.value, "the twin leaves the tree reset")
        assertEquals(emptyMap(), Notes.entries(Notes.byId))
    }
}
