package com.vynatix.vault.bridge

/**
 * Backend-agnostic key-value store contract used by [KvBridge].
 *
 * Implementations may be in-memory (see [InMemoryKvStore]), backed by
 * NSUserDefaults on iOS, SharedPreferences/DataStore on Android, the
 * filesystem, a remote service — anything that can `get(key) -> String?` and
 * `put(key, value)`.
 *
 * `null` from `get` means "no value persisted for this key" (load-on-attach
 * is a no-op for that state).
 */
interface KvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun snapshot(): Map<String, String>
}

/**
 * Trivial in-memory [KvStore] implementation. Useful for tests, dev, or as the
 * "session" tier in a multi-tier persistence stack. Not thread-safe — wrap with
 * synchronization if needed across threads.
 */
class InMemoryKvStore : KvStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun snapshot(): Map<String, String> = map.toMap()
}
