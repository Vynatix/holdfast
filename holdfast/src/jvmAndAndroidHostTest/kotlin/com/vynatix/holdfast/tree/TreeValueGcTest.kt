@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.awaitCollected
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TreeValueGcKeyedStore(
    id: Int,
) : Store<TreeValueGcKeyedStore>() {
    val n by state { id }
}

private class TreeValueGcParent : Store<TreeValueGcParent>() {
    val threads by keyed<Int, TreeValueGcKeyedStore> { TreeValueGcKeyedStore(it) }
}

/**
 * A tree a `tree` handle's observer or reader kept — the undo idiom — holds
 * its stores' captures, whose cut stamps hold no state, and nodes that let go
 * of their store once it disposed: once a keyed store has disposed and left
 * the tree, that tree does not keep it reachable. The wait is
 * `awaitCollected` (`GcSupport.kt`).
 */
class TreeValueGcTest {
    @Test
    fun aKeptTreeDoesNotKeepADisposedKeyedStoreReachable() {
        val parent = TreeValueGcParent()
        val (kept, ref) = readDisposeAndForget(parent)
        // The store's departure settled: the current tree no longer lists it.
        assertEquals(0, parent.tree.value.entriesOf(parent.threads))
        assertTrue(awaitCollected(ref), "the disposed keyed store was not collected within the budget")
        assertNull(ref.get(), "a tree must reference no store instance: the disposed store stays reachable through it")
        assertEquals(1, kept.entriesOf(parent.threads), "the kept tree still holds the store's capture")
    }

    private fun TreeSnapshot.entriesOf(branch: KeyedBranch<*, *>): Int = this[branch]?.children?.count { it.hasStore } ?: 0

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun readDisposeAndForget(parent: TreeValueGcParent): Pair<TreeSnapshot, WeakReference<TreeValueGcKeyedStore>> {
        val store = parent.threads.create(1)
        store action { n mutate 2 }
        val tree = parent.tree.value
        assertEquals(2, tree[store.n])
        store.dispose()
        return tree to WeakReference(store)
    }
}
