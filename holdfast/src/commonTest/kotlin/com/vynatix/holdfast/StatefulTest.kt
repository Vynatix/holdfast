@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Dynamic store tree, PR M-1: every store is a Stateful whose owningStore is
// itself, and NodeStore is the base an anonymous inline child is declared with.

private class StatefulPlainStore : Store<StatefulPlainStore>() {
    val n by state { 0 }
}

private sealed class StatefulPing {
    data object Sent : StatefulPing()
}

private class StatefulPingStore : EventfulStore<StatefulPingStore, StatefulPing>() {
    val n by state { 0 }
}

/** A consumer interface over an inline child: its states, and the store that owns them. */
private interface Draft : Stateful {
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

class StatefulTest {
    @Test
    fun aStatefulPlainStoreIsItsOwnOwningStore() {
        val store = StatefulPlainStore()
        assertSame(store, store.owningStore)
        val asStateful: Stateful = store
        assertSame(store, asStateful.owningStore)
    }

    @Test
    fun anEventfulStoreIsItsOwnOwningStore() {
        val store = StatefulPingStore()
        assertSame(store, store.owningStore)
        val asStateful: Stateful = store
        assertSame(store, asStateful.owningStore)
    }

    @Test
    fun anInlineChildRoundTripsThroughItsOwnMethod() {
        val draft = newDraft()
        assertEquals("", draft.text.value)
        draft.set("x")
        assertEquals("x", draft.text.value)
    }

    @Test
    fun anInlineChildIsWritableThroughOwningStoreFromOutside() {
        val draft = newDraft()
        val result = draft.owningStore action { draft.text mutate "y" }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals("y", draft.text.value)
        assertIs<NodeStore>(draft.owningStore)
        assertTrue(draft === draft.owningStore)
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
        assertSame(empty, empty.owningStore)
    }
}
