package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.StoreHandle
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Identity-keyed map of tracked stores to their [StoreHandle]s, scoped to a single
 * [com.vynatix.holdfast.testing.StoreTestScope].
 *
 * Implemented as a linear-search list rather than a hash map because KMP doesn't
 * expose a portable identity hash code. Tests typically track 1–3 stores, so the
 * O(n) lookup is irrelevant in practice.
 *
 * Thread-safe: [getOrCreate] is atomic, so `parallel { }` workers tracking the
 * same store, or a tree fixture tracking a keyed store from the thread that
 * created it, get exactly one handle per store.
 *
 * [onCreate] runs once for each handle this registry creates, right after it is
 * registered (under the registry's lock — keep it cheap);
 * [com.vynatix.holdfast.testing.StoreTestScope] uses it to schedule the
 * handle's end-of-test clock restore.
 */
internal class HandleRegistry(
    private val onCreate: (StoreHandle<*>) -> Unit,
) : SynchronizedObject() {
    private val entries: MutableList<Entry<*>> = mutableListOf()

    fun <V : Store<V>> getOrCreate(
        store: V,
        capture: Capture,
    ): StoreHandle<V> {
        existing(store)?.let { return it }
        // Built outside the lock: a handle's construction installs the
        // recorder and re-attaches the store's bridges, which may run user code.
        val fresh = StoreHandle(store, capture)
        val winner =
            synchronized(this) {
                existing(store) ?: fresh.also {
                    entries.add(Entry(store, it))
                    onCreate(it)
                }
            }
        // Lost the race: another thread tracked the store meanwhile; keep its handle, drop ours.
        if (winner !== fresh) fresh.disposeRecorderInternal()
        return winner
    }

    private fun entryOf(store: Store<*>): Entry<*>? = entries.firstOrNull { it.store === store }

    private fun <V : Store<V>> existing(store: V): StoreHandle<V>? =
        synchronized(this) {
            @Suppress("UNCHECKED_CAST")
            entries.firstOrNull { it.store === store }?.handle as StoreHandle<V>?
        }

    /** The handle of [store], or `null` when it was never tracked. */
    fun find(store: Store<*>): StoreHandle<*>? = synchronized(this) { entryOf(store)?.handle }

    /**
     * Snapshot of every handle this registry currently owns. Stable to iterate
     * after return; used by [com.vynatix.holdfast.testing.StoreTestScope.tearDown]
     * to aggregate the per-handle pending-error lists into a single report.
     */
    fun allHandles(): List<StoreHandle<*>> = synchronized(this) { entries.map { it.handle } }

    fun clear() {
        synchronized(this) { entries.clear() }
    }

    private class Entry<V : Store<V>>(
        val store: V,
        val handle: StoreHandle<V>,
    )
}
