// Twin of GUIDE §16.5 (derived states and `merged`). The block is embedded at
// top level; the test drives it and asserts the output its comments claim.
package com.vynatix.holdfast.snippets.twins.guidederivedstate

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.merged
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#73
@OptIn(ExperimentalStoreApi::class)
class NotesStore : Store<NotesStore>() {
    val pinned by state(tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val fetched by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val shown by merged(pinned, fetched) { pins, all -> all.sortedByDescending { it in pins } }
    val count by derivedState(shown) { shown.value.size }
}

@OptIn(ExperimentalStoreApi::class)
fun pinThenAdopt(notes: NotesStore) {
    val sub = notes.shown effect { println(this) }   // "[]"
    notes action { pinned mutate setOf("b") }         // shown is still [], so nothing prints
    notes action {                                    // an adoption: sync writes fetched only
        fetched mutate listOf("a")
        fetched update { it + "b" }
    }                                                 // "[b, a]": one recompute for the commit
    println(notes.count.value)                        // "2"
    sub.dispose()
}
// DOC-SNIPPET-END

class GuideDerivedStatesTwin {
    @Test
    fun pinThenAdoptPrintsTheMergedValueOncePerChange() {
        val notes = NotesStore()
        val printed = capturePrintln { pinThenAdopt(notes) }
        assertEquals(listOf("[]", "[b, a]", "2"), printed)
        assertEquals(setOf("b"), notes.pinned.value, "the adoption left the user's pins alone")
    }
}
