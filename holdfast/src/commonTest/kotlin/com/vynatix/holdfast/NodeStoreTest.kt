@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// NodeStore: the non-recursive base an anonymous inline child is declared
// with, behind a plain consumer interface.

/** A consumer interface over an inline child: its states and a writing method. */
private interface Draft {
    val text: State<String>

    fun set(v: String)
}

private fun newDraft(): Draft =
    object : NodeStore(), Draft {
        override val text by state { "" }

        override fun set(v: String) {
            // `action`'s receiver is the NodeStore, and @StoreActionDsl hides the
            // anonymous object's own members behind it, so the state is reached
            // through an explicit reference to the object.
            val draft = this
            action { draft.text mutate v }
        }
    }

class NodeStoreTest {
    @Test
    fun anInlineChildRoundTripsThroughItsOwnMethod() {
        val draft = newDraft()
        assertEquals("", draft.text.value)
        draft.set("x")
        assertEquals("x", draft.text.value)
    }

    @Test
    fun anInlineChildIsWritableFromOutsideThroughACastToItsStore() {
        val draft = newDraft()
        assertIs<NodeStore>(draft)
        val result = (draft as NodeStore) action { draft.text mutate "y" }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("y", draft.text.value)
    }

    @Test
    fun anAnonymousNodeStoreSnapshotsRestoresAndResets() {
        val child =
            object : NodeStore() {
                val count by state { 0 }
                val label by state { "idle" }
            }
        child action {
            child.count mutate 1
            child.label mutate "busy"
        }
        val snapshot = child.snapshot()
        assertEquals(setOf("count", "label"), snapshot.stateNames)
        assertEquals(1, snapshot[child.count])
        assertEquals("busy", snapshot[child.label])

        assertIs<TransactionResult.Success<*>>(child.reset())
        assertEquals(0, child.count.value)
        assertEquals("idle", child.label.value)

        assertIs<TransactionResult.Success<*>>(child.restore(snapshot))
        assertEquals(1, child.count.value)
        assertEquals("busy", child.label.value)
    }

    @Test
    fun anAnonymousNodeStoreWithNoStatesDisposesCleanly() {
        val empty = object : NodeStore() {}
        assertFalse(empty.isDisposed)
        assertTrue(empty.properties.isEmpty())
        empty.dispose()
        assertTrue(empty.isDisposed)
        empty.dispose()
        assertTrue(empty.isDisposed)
    }
}
