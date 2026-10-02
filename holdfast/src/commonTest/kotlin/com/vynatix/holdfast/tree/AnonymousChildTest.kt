@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.NodeStore
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.StringCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A consumer interface over an inline child: its states and a writing method. */
private interface AcDraft {
    val text: State<String>

    fun edit(value: String)
}

private interface AcNotes {
    val body: State<String>
}

/** Not a store: `store { }` refuses it at materialization. */
private interface AcNotAStore {
    val label: String
}

private class AcBadParent : Store<AcBadParent>() {
    val bad by store<AcNotAStore> {
        object : AcNotAStore {
            override val label = "x"
        }
    }
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

            override fun edit(value: String) {
                text mutate value
            }
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
        assertIs<NodeStore>(draft)
        assertSame(parent.tree.node, (draft as NodeStore).tree.parent)
    }

    @Test
    fun anInlineChildIsWrittenThroughItsInterfaceOrACastToItsStore() {
        val parent = AcParent()
        val draft = parent.draft
        draft.edit("w")
        assertEquals("w", draft.text.value)
        val result = (draft as NodeStore) action { draft.text mutate "x" }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("x", draft.text.value)
        assertTrue(draft in parent.tree.stores())
    }

    @Test
    fun aStoreChildThatIsNotAStoreFailsWithATeachingMessage() {
        val parent = AcBadParent()
        val failure = assertFailsWith<IllegalStateException> { parent.bad }
        assertTrue("store { } must produce a Store" in failure.message.orEmpty(), failure.message)
        assertTrue("object : NodeStore(), YourInterface" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun twoAnonymousChildrenAreNamedByTheirPropertiesWithNoPin() {
        val parent = AcParent()
        assertEquals(listOf("draft", "notes"), parent.tree.children().map { it.name })
        val draftNode = parent.tree.nodeOf(parent.draft as NodeStore)!!
        assertEquals("draft", draftNode.name)
        assertEquals(NameOrigin.Property, draftNode.nameOrigin)
        assertNotSame<Any>(parent.draft, parent.notes)
        val tree = parent.tree
        assertEquals(listOf("draft", "notes"), tree.stores().drop(1).map { tree.nodeOf(it)!!.name })
    }

    @Test
    fun anInlineChildIsConstructedOnce() {
        val parent = AcParent()
        val first = parent.draft
        parent.draft
        parent.tree.children()
        parent.tree.stores()
        assertSame(first, parent.draft)
        assertEquals(1, parent.draftRuns)
    }

    @Test
    fun anInlineChildIsCapturedEncodedAndRestored() {
        val parent = AcParent()
        val draft = parent.draft
        draft.edit("kept")
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
