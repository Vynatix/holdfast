// Twin of GUIDE §17.6 (restore and reset over a subtree). Declares the
// `Notes` root the §17.7 twin shares; the test drives the block and asserts
// the output its comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.Root
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#83
class PrefsStore : Store<PrefsStore>() {
    val theme by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "light" }
    val etag by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "" }
}

class NoteStore(val id: String) : Store<NoteStore>(Notes.byId.at(id)) {
    val body by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
}

object Notes : Root("notes") {
    val prefsStore = PrefsStore()
    val prefs by branch(prefsStore).named(prefsStore, "prefs")   // pinned: this leaf is persisted (§17.7)
    val byId by keyed<String, NoteStore>()
}

fun undoAndResetTheTree() {
    val n1 = Notes.byId.create("n1", ::NoteStore)
    n1 action { body mutate "draft" }
    val before = Notes.snapshot()                                  // one consistent cut
    Notes.prefsStore action { theme mutate "dark" }
    n1 action { body mutate "final" }

    val report = Notes.restore(before).getOrThrow()                // one frame over both leaves
    println(Notes.prefsStore.theme.value)                          // "light"
    println(n1.body.value)                                         // "draft"
    println(report.perNode.keys.map { it.name })                   // "[prefs, n1]"

    Notes.prefsStore action { theme mutate "dark" }
    Notes.reset(Notes.byId).getOrThrow()                           // only the keyed subtree
    println(n1.body.value == "")                                   // "true": back to its initializer
    println(Notes.prefsStore.theme.value)                          // "dark": outside the subtree, untouched
    Notes.reset().getOrThrow()                                     // the whole tree
    println(Notes.prefsStore.theme.value)                          // "light"
    n1.dispose()
}
// DOC-SNIPPET-END

class GuideTreeRestoreTwin {
    @Test
    fun undoAndResetTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { undoAndResetTheTree() }
        assertEquals(listOf("light", "draft", "[prefs, n1]", "true", "dark", "light"), printed)
        assertEquals(emptyMap(), Notes.entries(Notes.byId), "the twin leaves no keyed store behind")
    }
}
