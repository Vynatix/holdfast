// Twin of GUIDE §17.1 (declaring a root, keyed stores through create). The
// block is embedded at top level; the test drives it and asserts the output
// its comments claim. Module-wide opt-in stands in for the `-opt-in` flag
// the chapter assumes.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.Root
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#81
class SettingsStore : Store<SettingsStore>() {
    val theme by state { "light" }
}

class SessionStore : Store<SessionStore>() {
    val user by state { "" }
}

// The class header takes the branch's token; the store can only be built
// through `create`/`getOrCreate` on the branch it belongs to.
class ThreadStore(val id: String) : Store<ThreadStore>(App.threads.at(id)) {
    val title by state { "thread $id" }
}

object App : Root("app") {
    val settings by branch(SettingsStore())
    val session by branch(SessionStore()).named("session")
    val threads by keyed<String, ThreadStore>(under = session)   // targets declared above their users
}

fun useTheTree() {
    val t1 = App.threads.create("t1", ::ThreadStore)          // live once create returns
    val again = App.threads.getOrCreate("t1", ::ThreadStore)   // the same store, factory not run
    println(again === t1)                                       // "true"
    println(App.nodeOf(t1)?.name)                               // "t1"
    println(App.children(App.session).size)                     // "2": SessionStore, then t1
    println(runCatching { ThreadStore("bare") }.isFailure)      // "true": at(id) is valid only inside create
    t1.dispose()                                                // leaves the tree
    println(App[App.threads, "t1"])                             // "null"
}
// DOC-SNIPPET-END

class GuideTreeDeclarationTwin {
    @Test
    fun useTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { useTheTree() }
        assertEquals(listOf("true", "t1", "2", "true", "null"), printed)
        assertEquals("session", App.session.name)
        assertEquals("Settings", App.settings.leafName(App.settings.stores.single()))
    }
}
