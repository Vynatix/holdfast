// Twin of GUIDE §16.6 (keyed state families). The block is embedded at top
// level; the test drives it and asserts the output its comments claim.
package com.vynatix.holdfast.snippets.twins.guidekeyed

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// DOC-SNIPPET holdfast/GUIDE.md#75
@OptIn(ExperimentalStoreApi::class)
class DraftsStore : Store<DraftsStore>() {
    val drafts by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }
}

@OptIn(ExperimentalStoreApi::class)
fun editThenClose(store: DraftsStore) {
    val a = store.drafts["a"]
    a effect { println("a = $this") }                      // "a = "
    store action {
        drafts["a"] mutate "hello"
        drafts["b"] mutate "world"
    }                                                       // "a = hello"
    println(store.snapshot().encode())
    // {"format":"holdfast.store","v":1,"schema":1,"states":{"drafts":{"a":"hello","b":"world"}},"skipped":[]}
    store.drafts.evict("a")                                 // a's observer is dropped, silently
    println(store.drafts.entries.keys)                      // "[b]"
    println(store.drafts["a"] === a)                        // "false": a new entry, from the initializer
}
// DOC-SNIPPET-END

class GuideKeyedStatesTwin {
    @OptIn(ExperimentalStoreApi::class)
    @Test
    fun editThenClosePrintsWhatItsCommentsClaim() {
        val store = DraftsStore()
        val printed = capturePrintln { editThenClose(store) }
        assertEquals(
            listOf(
                "a = ",
                "a = hello",
                """{"format":"holdfast.store","v":1,"schema":1,"states":{"drafts":{"a":"hello","b":"world"}},"skipped":[]}""",
                "[b]",
                "false",
            ),
            printed,
        )
        assertEquals("", store.drafts["a"].value, "the new entry holds the initializer's value")
        assertTrue("b" in store.drafts, "the other entry stayed")
    }
}
