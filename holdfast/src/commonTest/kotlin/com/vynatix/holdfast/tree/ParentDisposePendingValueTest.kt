@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class ParentDisposePendingValueLeafStore : Store<ParentDisposePendingValueLeafStore>() {
    val n by state { 0 }
}

private class ParentDisposePendingValueKeyedStore(
    id: String,
) : Store<ParentDisposePendingValueKeyedStore>() {
    val title by state { "thread $id" }
}

private class ParentDisposePendingValueParent : Store<ParentDisposePendingValueParent>() {
    val left = ParentDisposePendingValueLeafStore()
    val leaf by store { left }
    val keyed by keyed<String, ParentDisposePendingValueKeyedStore> { ParentDisposePendingValueKeyedStore(it) }
}

/**
 * A parent's `dispose()` while a store's join or leave is still pending its
 * settle (it happened inside an entry, so the recompute was queued, not
 * run): the last tree stays readable through a `tree` handle taken before
 * the dispose, as `StoreTree`'s value documents.
 */
class ParentDisposePendingValueTest {
    @Test
    fun aJoinPendingAtDisposeLeavesTheLastTreeReadable() {
        val parent = ParentDisposePendingValueParent()
        val tree = parent.tree
        parent.leaf
        parent.left action { n mutate 3 }
        val last = tree.value
        var created: ParentDisposePendingValueKeyedStore? = null
        parent.left action {
            created = parent.keyed.create("k")
            parent.dispose()
        }
        val after = tree.value
        assertSame(last, after, "frozen at the last settled tree")
        assertEquals(3, after[parent.left.n])
        assertSame(last, tree.value, "and every later read answers the same tree")
        assertTrue(created!!.isDisposed, "the keyed store that joined is disposed with its parent once the action settled")
        assertFailsWith<IllegalStateException> { parent.tree }
    }

    @Test
    fun aLeavePendingAtDisposeLeavesTheLastTreeReadable() {
        val parent = ParentDisposePendingValueParent()
        val tree = parent.tree
        parent.leaf
        val k = parent.keyed.create("k")
        // The state handle, taken while k is live: its delegate refuses reads once k is disposed, a capture does not.
        val title = k.title
        val last = tree.value
        assertEquals("thread k", last[title])
        parent.left action {
            k.dispose()
            parent.dispose()
        }
        val after = tree.value
        assertSame(last, after, "frozen at the last settled tree")
        assertEquals("thread k", after[title], "the captured values of a store that left stay readable")
        assertSame(last, tree.value)
        assertFailsWith<IllegalStateException> { parent.tree }
    }
}
