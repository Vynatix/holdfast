@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class GvKeyedStore(
    id: Int,
    root: GvRoot,
) : Store<GvKeyedStore>(root.threads.at(id)) {
    val n by state { 0 }
}

private class GvRoot : Root("gv") {
    val threads by keyed<Int, GvKeyedStore>()
}

private const val GC_BUDGET_NANOS = 5_000_000_000L
private const val GC_GARBAGE_CHUNKS = 64
private const val GC_GARBAGE_CHUNK_BYTES = 256 * 1024

/**
 * A tree a `Root.value` observer or reader kept — the undo idiom — holds its
 * leaves' captures, whose cut stamps hold no state: once a keyed leaf has
 * disposed and left the tree, that tree does not keep it reachable.
 */
class RootValueGcTest {
    @Test
    fun aKeptTreeDoesNotKeepADisposedKeyedLeafReachable() {
        val root = GvRoot()
        val (tree, ref) = readDisposeAndForget(root)
        // The leaf's departure settled: the current tree no longer lists it.
        assertEquals(0, root.value.value.leafCount())
        awaitCollected(ref)
        assertNull(ref.get(), "a tree must reference no store instance: the disposed leaf stays reachable through it")
        assertEquals(1, tree.leafCount(), "the kept tree still holds the leaf's capture")
    }

    private fun TreeSnapshot.leafCount(): Int = (if (leaf != null) 1 else 0) + children.sumOf { it.leafCount() }

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun readDisposeAndForget(root: GvRoot): Pair<TreeSnapshot, WeakReference<GvKeyedStore>> {
        val store = root.threads.create(1) { GvKeyedStore(it, root) }
        store action { n mutate 1 }
        val tree = root.value.value
        assertEquals(1, tree[store.n])
        store.dispose()
        return tree to WeakReference(store)
    }

    private fun awaitCollected(ref: WeakReference<*>) {
        val deadline = System.nanoTime() + GC_BUDGET_NANOS
        while (ref.get() != null && System.nanoTime() < deadline) {
            val garbage = ArrayList<ByteArray>(GC_GARBAGE_CHUNKS)
            repeat(GC_GARBAGE_CHUNKS) { garbage += ByteArray(GC_GARBAGE_CHUNK_BYTES) }
            garbage.clear()
            System.gc()
            Thread.sleep(10)
        }
    }
}
