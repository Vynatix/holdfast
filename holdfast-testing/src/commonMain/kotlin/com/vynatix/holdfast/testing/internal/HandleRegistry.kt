package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.StoreHandle

/**
 * Identity-keyed map of tracked vaults to their [StoreHandle]s, scoped to a single
 * [com.vynatix.holdfast.testing.StoreTestScope].
 *
 * Implemented as a linear-search list rather than a hash map because KMP doesn't
 * expose a portable identity hash code. Tests typically track 1–3 vaults, so the
 * O(n) lookup is irrelevant in practice.
 */
internal class HandleRegistry {
    private val entries: MutableList<Entry<*>> = mutableListOf()

    fun <V : Store<V>> getOrCreate(store: V, capture: Capture): StoreHandle<V> {
        val existing = entries.firstOrNull { it.store === store }
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing.handle as StoreHandle<V>
        }
        val handle = StoreHandle(store, capture)
        entries.add(Entry(store, handle))
        return handle
    }

    /**
     * Snapshot of every handle this registry currently owns. Stable to iterate
     * after return; used by [com.vynatix.holdfast.testing.StoreTestScope.tearDown]
     * to aggregate the per-handle pending-error lists into a single report.
     */
    fun allHandles(): List<StoreHandle<*>> = entries.map { it.handle }

    fun clear() {
        entries.clear()
    }

    private class Entry<V : Store<V>>(val store: V, val handle: StoreHandle<V>)
}
