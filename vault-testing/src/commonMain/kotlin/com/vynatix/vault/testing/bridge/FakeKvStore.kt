package com.vynatix.vault.testing.bridge

import com.vynatix.vault.bridge.KvBridge
import com.vynatix.vault.bridge.KvStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe, in-memory [KvStore] for unit-testing [KvBridge]-based vaults
 * without touching the real filesystem (or any platform key-value backend).
 *
 * Functionally equivalent to [com.vynatix.vault.bridge.InMemoryKvStore] except:
 *   - All operations are guarded by a [SynchronizedObject] lock so [KvBridge]
 *     callers may publish from background dispatchers without corrupting state.
 *   - [contents] returns a defensive snapshot, decoupled from later mutations.
 *   - [clear] resets the store to empty for test setup/teardown.
 *
 * Typical use:
 * ```
 * @Test
 * fun persistsViaKvBridge() = vaultTest {
 *     val kv = FakeKvStore()
 *     val ctr = track(SettingsVault().also {
 *         it { theme bridge KvBridge(kv, "theme", StringCodec) }
 *     })
 *     ctr.action { theme mutate "dark" }
 *     assertEquals("dark", kv.contents["theme"])
 * }
 * ```
 */
class FakeKvStore : KvStore {

    private val lock = SynchronizedObject()
    private val store: MutableMap<String, String> = mutableMapOf()

    /**
     * Defensive snapshot of the current store contents. The returned map is a
     * copy taken under the lock; subsequent mutations to the [FakeKvStore] do
     * not affect previously-returned snapshots.
     */
    val contents: Map<String, String>
        get() = synchronized(lock) { store.toMap() }

    /** Reset the store to empty. Useful for per-test setup/teardown. */
    fun clear() {
        synchronized(lock) { store.clear() }
    }

    override fun get(key: String): String? = synchronized(lock) { store[key] }

    override fun put(key: String, value: String) {
        synchronized(lock) { store[key] = value }
    }

    override fun remove(key: String) {
        synchronized(lock) { store.remove(key) }
    }

    override fun snapshot(): Map<String, String> = synchronized(lock) { store.toMap() }
}
