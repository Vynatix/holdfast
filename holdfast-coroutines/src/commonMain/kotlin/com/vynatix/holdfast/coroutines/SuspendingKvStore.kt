package com.vynatix.vault.coroutines

/**
 * Suspending peer of `com.vynatix.vault.bridge.KvStore`. Same shape — `get`, `put`,
 * `remove`, `snapshot` — lifted into the suspending world. Designed for backends
 * whose I/O is genuinely async: DataStore, SQLDelight, Realm, network-backed stores.
 *
 * Implementations should not block the calling thread. `null` from `get` means
 * "no value persisted for this key".
 *
 * The companion sync `KvStore` remains the right choice for in-memory or fast-blocking
 * backends. Use this interface only when await-completion semantics are needed
 * (consumed by `:vault-coroutines.suspendAction` via `SuspendingBridge`).
 */
interface SuspendingKvStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)

    /**
     * Returns a defensive copy of the store's contents. Mutating the returned map
     * does not affect the store.
     */
    suspend fun snapshot(): Map<String, String>
}
