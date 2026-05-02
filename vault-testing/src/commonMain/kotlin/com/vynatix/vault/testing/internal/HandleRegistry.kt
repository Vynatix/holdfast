package com.vynatix.vault.testing.internal

import com.vynatix.vault.Vault
import com.vynatix.vault.testing.Capture
import com.vynatix.vault.testing.VaultHandle

/**
 * Identity-keyed map of tracked vaults to their [VaultHandle]s, scoped to a single
 * [com.vynatix.vault.testing.VaultTestScope].
 *
 * Implemented as a linear-search list rather than a hash map because KMP doesn't
 * expose a portable identity hash code. Tests typically track 1–3 vaults, so the
 * O(n) lookup is irrelevant in practice.
 */
internal class HandleRegistry {
    private val entries: MutableList<Entry<*>> = mutableListOf()

    fun <V : Vault<V>> getOrCreate(vault: V, capture: Capture): VaultHandle<V> {
        val existing = entries.firstOrNull { it.vault === vault }
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing.handle as VaultHandle<V>
        }
        val handle = VaultHandle(vault, capture)
        entries.add(Entry(vault, handle))
        return handle
    }

    fun clear() {
        entries.clear()
    }

    private class Entry<V : Vault<V>>(val vault: V, val handle: VaultHandle<V>)
}
