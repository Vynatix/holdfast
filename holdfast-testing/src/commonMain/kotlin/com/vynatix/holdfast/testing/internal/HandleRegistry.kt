package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.HoldfastHandle

/**
 * Identity-keyed map of tracked vaults to their [HoldfastHandle]s, scoped to a single
 * [com.vynatix.holdfast.testing.HoldfastTestScope].
 *
 * Implemented as a linear-search list rather than a hash map because KMP doesn't
 * expose a portable identity hash code. Tests typically track 1–3 vaults, so the
 * O(n) lookup is irrelevant in practice.
 */
internal class HandleRegistry {
    private val entries: MutableList<Entry<*>> = mutableListOf()

    fun <V : Holdfast<V>> getOrCreate(vault: V, capture: Capture): HoldfastHandle<V> {
        val existing = entries.firstOrNull { it.vault === vault }
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing.handle as HoldfastHandle<V>
        }
        val handle = HoldfastHandle(vault, capture)
        entries.add(Entry(vault, handle))
        return handle
    }

    /**
     * Snapshot of every handle this registry currently owns. Stable to iterate
     * after return; used by [com.vynatix.holdfast.testing.HoldfastTestScope.tearDown]
     * to aggregate the per-handle pending-error lists into a single report.
     */
    fun allHandles(): List<HoldfastHandle<*>> = entries.map { it.handle }

    fun clear() {
        entries.clear()
    }

    private class Entry<V : Holdfast<V>>(val vault: V, val handle: HoldfastHandle<V>)
}
