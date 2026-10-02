@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GcSnapStore : Store<GcSnapStore>() {
    val n by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

/**
 * A captured snapshot references no store instance: it holds names, raw
 * values, codecs and the store's key and class, never a state (which holds
 * its store). So a snapshot taken before `dispose()` — the undo idiom keeps a
 * stack of them — cannot keep the disposed store, or an entry evicted since,
 * reachable. The wait is `awaitCollected` (`GcSupport.kt`), shared by every
 * GC-dependent test in this source set.
 */
class SnapshotGcTest {
    @Test
    fun aSnapshotDoesNotKeepADisposedStoreReachable() {
        val (snapshot, ref) = snapshotDisposeAndForget()
        assertTrue(awaitCollected(ref), "the disposed store was not collected within the budget")
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
        assertTrue(awaitCollected(ref), "the evicted entry was not collected within the budget")
        assertNull(ref.get(), "a snapshot references no state instance: the evicted entry is reachable through it")
        assertEquals(setOf("k"), snapshot.keysOf(store.docs), "the snapshot still holds the entry's value")
    }

    private fun snapshotEvictAndForget(store: GcSnapStore): Pair<StoreSnapshot, WeakReference<State<Int>>> {
        val entry = store.docs["k"]
        val snapshot = store.snapshot()
        store.docs.evict("k")
        return snapshot to WeakReference(entry)
    }
}
