// Twin of GUIDE §17.1 (declaring children with store { } / stores { }, keyed
// stores through the declared factory, an inline child). The block is
// embedded at top level; the test drives it and asserts the output its
// comments claim. Module-wide opt-in stands in for the `-opt-in` flag the
// chapter assumes.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Stateful
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.NameOrigin
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.stores
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#81
class SettingsStore : Store<SettingsStore>() {
    val theme by state { "light" }
}

class SignInStore : Store<SignInStore>() {
    val email by state { "" }
}

class ProfileStore : Store<ProfileStore>() {
    val bio by state { "" }
}

class ThreadStore(val id: String) : Store<ThreadStore>() {       // an ordinary store: no token, no tree argument
    val title by state { "thread $id" }
}

interface Draft : Stateful {                                     // the typed face of an inline child
    val text: State<String>
}

object App : Store<App>() {
    val openThreads by state { emptySet<String>() }                         // a parent is a store: it has states
    val settings by store { SettingsStore() }                              // one child, node "settings"
    val session by stores { listOf(SignInStore(), ProfileStore()) }        // a group: leaves "SignIn", "Profile"
    val threads by stores<String, ThreadStore> { id -> ThreadStore(id) }   // keyed: the factory is declared once
    val draft by store<Draft> { object : NodeStore(), Draft { override val text by state { "" } } }   // the type argument is required
}

fun useTheTree() {
    println(App.tree.children.map { it.name })              // "[settings, session, threads, draft]": the lambdas run now
    val t1 = App.threads.create("t1")                         // runs the factory; live once create returns
    println(App.threads.getOrCreate("t1") === t1)             // "true": the same store, the factory not run again
    println(App.tree.nodeOf(t1)?.name)                        // "t1"
    println(App.tree.stores(App.session).size)                // "2": SignIn, then Profile
    App.draft.owningStore action { App.draft.text mutate "hi" }   // an inline child, reached through Stateful
    println(App.draft.text.value)                             // "hi"
    println(App.tree.nodeOf(ThreadStore("bare")))             // "null": built outside the factory, it is in no tree
    t1.dispose()                                              // leaves the tree
    println(App.threads["t1"])                                // "null"
}
// DOC-SNIPPET-END

class GuideTreeDeclarationTwin {
    @Test
    fun useTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { useTheTree() }
        assertEquals(listOf("[settings, session, threads, draft]", "true", "t1", "2", "hi", "null", "null"), printed)
        assertEquals("SignIn", App.session.leafName(App.session.stores.first()))
        assertEquals("Profile", App.session.leafName(App.session.stores.last()))
        assertEquals("App", App.tree.node.name, "a store nobody declared is named by its class")
        assertEquals(NameOrigin.ClassName, App.tree.node.nameOrigin)
        assertEquals(NameOrigin.Property, App.settings.tree.node.nameOrigin)
        assertEquals(App.tree.node, App.settings.tree.parent, "a store { } child hangs under its parent's node")
    }
}
