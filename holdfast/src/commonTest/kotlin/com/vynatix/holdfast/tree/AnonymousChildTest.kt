@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Stateful
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A consumer interface over an inline child: its states, and (through [Stateful]) the store that owns them. */
private interface AcDraft : Stateful {
    val text: State<String>
}

private interface AcNotes : Stateful {
    val body: State<String>
}

/**
 * Two inline children, each an anonymous `NodeStore` behind an interface,
 * declared with the type argument that names them. Neither needs a pin:
 * a `store { }` child is named by its property, never by its class.
 *
 * Without the type argument — `val draft by store { object : NodeStore(),
 * AcDraft { … } }` — the property's type would be inferred as the
 * anonymous object's own, which has two supertypes and which a non-private
 * property cannot expose: the declaration does not compile. (Kept as this
 * comment; there is no negative-compilation harness.)
 */
private class AcParent : Store<AcParent>() {
    var draftRuns = 0
    val draft by store<AcDraft> {
        draftRuns++
        object : NodeStore(), AcDraft {
            override val text by state(codec = StringCodec) { "" }
        }
    }
    val notes by store<AcNotes> {
        object : NodeStore(), AcNotes {
            override val body by state(codec = StringCodec) { "empty" }
        }
    }
}

/** Inline children (`store<I> { object : NodeStore(), I { … } }`): typed, constructed once, captured and restored. */
class AnonymousChildTest {
    @Test
    fun anInlineChildIsReachedThroughItsInterface() {
        val parent = AcParent()
        val draft: AcDraft = parent.draft
        assertEquals("", draft.text.value)
        assertIs<NodeStore>(draft.owningStore)
        assertSame(parent.tree.node, draft.owningStore.tree.parent)
    }

    @Test
    fun owningStoreOpensAnActionThatCommitsOnTheChild() {
        val parent = AcParent()
        val draft = parent.draft
        val result = draft.owningStore action { draft.text mutate "x" }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("x", draft.text.value)
        assertTrue(draft.owningStore in parent.tree.stores())
    }

    @Test
    fun twoAnonymousChildrenAreNamedByTheirPropertiesWithNoPin() {
        val parent = AcParent()
        assertEquals(listOf("draft", "notes"), parent.tree.children.map { it.name })
        val draftNode = parent.tree.nodeOf(parent.draft.owningStore)!!
        assertEquals("draft", draftNode.name)
        assertEquals(NameOrigin.Property, draftNode.nameOrigin)
        assertNotSame(parent.draft.owningStore, parent.notes.owningStore)
        val tree = parent.tree
        assertEquals(listOf("draft", "notes"), tree.stores().drop(1).map { tree.nodeOf(it)!!.name })
    }

    @Test
    fun anInlineChildIsConstructedOnce() {
        val parent = AcParent()
        val first = parent.draft
        parent.draft
        parent.tree.children
        parent.tree.stores()
        assertSame(first, parent.draft)
        assertEquals(1, parent.draftRuns)
    }

    @Test
    fun anInlineChildIsCapturedEncodedAndRestored() {
        val parent = AcParent()
        val draft = parent.draft
        draft.owningStore action { draft.text mutate "kept" }
        val captured = parent.tree.snapshot()
        assertEquals("kept", captured[draft.text])
        val text = captured.encode()
        assertTrue("\"draft\"" in text, text)

        val fresh = AcParent()
        val decoded = fresh.tree.decode(text)
        assertTrue(decoded.unresolvedPaths.isEmpty(), "${decoded.unresolvedPaths}")
        assertIs<TransactionResult.Success<*>>(fresh.tree.restore(decoded))
        assertEquals("kept", fresh.draft.text.value)
        assertEquals("empty", fresh.notes.body.value)
    }
}
