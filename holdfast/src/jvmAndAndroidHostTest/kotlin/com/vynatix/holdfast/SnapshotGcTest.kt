@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class GcSnapStore : Store<GcSnapStore>() {
    val n by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private const val GC_BUDGET_NANOS = 5_000_000_000L
private const val GC_GARBAGE_CHUNKS = 64
private const val GC_GARBAGE_CHUNK_BYTES = 256 * 1024

/**
 * A captured snapshot references no store instance: it holds names, raw
 * values, codecs and the store's key and class, never a state (which holds
 * its store). So a snapshot taken before `dispose()` — the undo idiom keeps a
 * stack of them — cannot keep the disposed store, or an entry evicted since,
 * reachable.
 */
class SnapshotGcTest {
    @Test
    fun aSnapshotDoesNotKeepADisposedStoreReachable() {
        val (snapshot, ref) = snapshotDisposeAndForget()
        awaitCollected(ref)
        assertNull(ref.get(), "a snapshot references no store instance: the disposed store is reachable through it")
        assertEquals(setOf("n", "docs"), snapshot.stateNames, "the snapshot outlives its store")
    }

    /** A frame of its own, so no interpreter-local slot of the test method keeps the store alive. */
    private fun snapshotDisposeAndForget(): Pair<StoreSnapshot, WeakReference<GcSnapStore>> {
        val store = GcSnapStore()
        store.docs["k"]
        store action { n mutate 1 }
        val snapshot = store.snapshot()
        store.dispose()
        return snapshot to WeakReference(store)
    }

    @Test
    fun aSnapshotDoesNotKeepAnEvictedKeyedEntryReachable() {
        val store = GcSnapStore()
        val (snapshot, ref) = snapshotEvictAndForget(store)
        awaitCollected(ref)
        assertNull(ref.get(), "a snapshot references no state instance: the evicted entry is reachable through it")
        assertEquals(setOf("k"), snapshot.keysOf(store.docs), "the snapshot still holds the entry's value")
    }

    private fun snapshotEvictAndForget(store: GcSnapStore): Pair<StoreSnapshot, WeakReference<State<Int>>> {
        val entry = store.docs["k"]
        val snapshot = store.snapshot()
        store.docs.evict("k")
        return snapshot to WeakReference(entry)
    }

    /** Poll under allocation pressure for a few seconds: a cleared reference ends the wait early. */
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
