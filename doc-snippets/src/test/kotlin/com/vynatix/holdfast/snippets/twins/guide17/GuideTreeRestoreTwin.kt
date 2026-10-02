// Twin of GUIDE §17.6 (restore and reset over a subtree). Declares the
// `Notes` store the §17.7–§17.10 twins share; the test drives the block and
// asserts the output its comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.stores
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#83
class PrefsStore : Store<PrefsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
    val etag by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "" }
}

class NoteStore(val id: String) : Store<NoteStore>() {
    val body by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
}

object Notes : Store<Notes>() {
    val folder by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "inbox" }   // the parent's own state
    val prefs by store { PrefsStore() }                                    // node "prefs", named by its property
    val byId by stores<String, NoteStore> { id -> NoteStore(id) }
}

fun undoAndResetTheTree() {
    val n1 = Notes.byId.create("n1")
    n1 action { body mutate "draft" }
    val before = Notes.tree.snapshot()                             // one consistent cut: Notes, prefs, n1
    Notes action { folder mutate "archive" }
    Notes.prefs action { theme mutate "dark" }
    n1 action { body mutate "final" }

    val report = Notes.tree.restore(before).getOrThrow()           // one frame over all three
    println(Notes.folder.value)                                    // "inbox": the parent is restored with its children
    println(Notes.prefs.theme.value)                               // "light"
    println(n1.body.value)                                         // "draft"
    println(report.perNode.keys.map { it.name })                   // "[Notes, prefs, n1]"

    Notes.prefs action { theme mutate "dark" }
    Notes.tree.reset(Notes.byId).getOrThrow()                      // only the keyed subtree
    println(n1.body.value == "")                                   // "true": back to its initializer
    println(Notes.prefs.theme.value)                               // "dark": outside the subtree, untouched
    Notes.tree.reset().getOrThrow()                                // the parent and its whole subtree
    println(Notes.prefs.theme.value)                               // "light"
    n1.dispose()
}
// DOC-SNIPPET-END

class GuideTreeRestoreTwin {
    @Test
    fun undoAndResetTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { undoAndResetTheTree() }
        assertEquals(listOf("inbox", "light", "draft", "[Notes, prefs, n1]", "true", "dark", "light"), printed)
        assertEquals("inbox", Notes.folder.value, "the twin leaves the parent reset")
        assertEquals(emptyMap(), Notes.byId.entries, "the twin leaves no keyed store behind")
    }
}
