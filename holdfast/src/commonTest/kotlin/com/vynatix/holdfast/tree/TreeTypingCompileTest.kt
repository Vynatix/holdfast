@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Stateful
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private class TypingThreadStore(
    val id: String,
) : Store<TypingThreadStore>() {
    val title by state { "" }
}

private class TypingSettingsStore : Store<TypingSettingsStore>() {
    val theme by state { "light" }
    val threads by stores<String, TypingThreadStore> { TypingThreadStore(it) }
}

private class TypingProfileStore : Store<TypingProfileStore>()

/** A consumer interface over an inline child. */
private interface TypingDraft : Stateful {
    val text: State<String>
}

private class TypingParent : Store<TypingParent>() {
    val settings by store { TypingSettingsStore() }
    val group by stores { listOf(TypingProfileStore()) }
    val draft by store<TypingDraft> {
        object : NodeStore(), TypingDraft {
            override val text by state { "" }
        }
    }
}

private class OtherTypingParent : Store<OtherTypingParent>() {
    val other by store { TypingSettingsStore() }
}

/** How many times [TypingPinned] was initialized; a class literal must never do it. */
private var typingPinnedInits = 0

private object TypingPinned : Store<TypingPinned>() {
    init {
        typingPinnedInits++
    }
}

private class TypingPinnedParent : Store<TypingPinnedParent>() {
    val pinned by stores(names = mapOf(TypingPinned::class to "p")) { listOf(TypingPinned) }
}

/**
 * T7.1 positive compile fixtures: the tree is typed end to end. Each
 * assignment below has an explicit static type that would not compile if
 * the API leaked `Any`, `Store<*>` where the store type is known, or a
 * string where a node is expected. The negative cases (a string into a
 * subtree operation, a node of another subtree, a factory returning another
 * store class) are enforced by signature review plus `CrossParentTest` and
 * `KeyedMembershipTest`; no negative-compilation harness is used. An inline
 * child is typed by the type argument it is declared with
 * (`store<TypingDraft> { … }`; see `AnonymousChildTest`).
 */
class TreeTypingCompileTest {
    @Test
    fun typedCreateLookupAndEntriesReturnTheDeclaredStoreType() {
        val parent = TypingParent()
        val settings: TypingSettingsStore = parent.settings
        val created: TypingThreadStore = settings.threads.create("a")
        val shared: TypingThreadStore = settings.threads.getOrCreate("a")
        val looked: TypingThreadStore? = settings.threads["a"]
        val entries: Map<String, TypingThreadStore> = settings.threads.entries
        assertSame(created, looked)
        assertSame(created, shared)
        assertEquals(setOf("a"), entries.keys)
    }

    @Test
    fun subtreeOperationsTakeAndAnswerNodeValues() {
        val parent = TypingParent()
        val group: Branch = parent.group
        val keyed: KeyedBranch<String, TypingThreadStore> = parent.settings.threads
        val node: StoreNode = keyed
        val stores: List<Store<*>> = parent.tree.stores(node)
        val children: List<StoreNode> = parent.tree.children
        val leaf: LeafNode? = parent.tree.nodeOf(parent.settings)
        assertSame(leaf, keyed.parent)
        assertSame(parent.tree.node, group.parent)
        assertEquals(0, stores.size, "no keyed store has been created yet")
        assertEquals(listOf("settings", "group", "draft"), children.map { it.name })
    }

    @Test
    fun anInlineChildIsTypedByItsDeclaredInterface() {
        val parent = TypingParent()
        val draft: TypingDraft = parent.draft
        val text: State<String> = draft.text
        draft.owningStore action { draft.text mutate "x" }
        assertEquals("x", text.value)
    }

    @Test
    fun aPinByClassNeverInitializesAnObject() {
        val parent = TypingPinnedParent()
        assertEquals(0, typingPinnedInits, "declaring the pin touched no object")
        assertEquals("p", parent.pinned.leafName(TypingPinned))
        assertEquals(1, typingPinnedInits)
    }

    @Test
    fun aNodeOfAnotherParentIsRejectedByValueNotByName() {
        val parent = TypingParent()
        val other = OtherTypingParent()
        assertFailsWith<IllegalArgumentException> { parent.tree.stores(other.other.threads) }
        assertFailsWith<IllegalArgumentException> { parent.tree.stores(other.tree.node) }
    }
}
