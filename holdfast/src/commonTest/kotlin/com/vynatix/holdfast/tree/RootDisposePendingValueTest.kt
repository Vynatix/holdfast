@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class DpLeafStore : Store<DpLeafStore>() {
    val n by state { 0 }
}

private class DpKeyedStore(
    id: String,
    root: DpRoot,
) : Store<DpKeyedStore>(root.keyed.at(id)) {
    val title by state { "thread $id" }
}

private class DpRoot : Root("dp") {
    val left = DpLeafStore()
    val leaf by branch(left)
    val keyed by keyed<String, DpKeyedStore>()
}

/**
 * `Root.dispose()` while a leaf's join or leave is still pending its settle
 * (it happened inside an entry, so the recompute was queued, not run): the
 * last tree stays readable, as `Root.value` and `Root.dispose` document.
 */
class RootDisposePendingValueTest {
    @Test
    fun aJoinPendingAtDisposeLeavesTheLastTreeReadable() {
        val root = DpRoot()
        root.left action { n mutate 3 }
        val last = root.value.value
        root.left action {
            root.keyed.create("k") { DpKeyedStore(it, root) }
            root.dispose()
        }
        val after = root.value.value
        assertSame(last, after, "frozen at the last settled tree")
        assertEquals(3, after[root.left.n])
        assertSame(last, root.value.value, "and every later read answers the same tree")
    }

    @Test
    fun aLeavePendingAtDisposeLeavesTheLastTreeReadable() {
        val root = DpRoot()
        val k = root.keyed.create("k") { DpKeyedStore(it, root) }
        // The state handle, taken while k is live: its delegate refuses reads once k is disposed, a capture does not.
        val title = k.title
        val last = root.value.value
        assertEquals("thread k", last[title])
        root.left action {
            k.dispose()
            root.dispose()
        }
        val after = root.value.value
        assertSame(last, after, "frozen at the last settled tree")
        assertEquals("thread k", after[title], "the captured values of a leaf that left stay readable")
        assertSame(last, root.value.value)
    }
}
