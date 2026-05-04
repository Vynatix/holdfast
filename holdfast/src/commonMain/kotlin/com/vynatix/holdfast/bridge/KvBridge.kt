package com.vynatix.vault.bridge

import com.vynatix.vault.Bridge
import com.vynatix.vault.Disposable

/**
 * Generic save-on-commit + load-on-attach [Bridge] backed by a [KvStore].
 *
 * On attach (when assigned to a state via `state bridge bridge`), the bridge's
 * [observe] is invoked once. If a previously-persisted value exists for [key]
 * in the store, it is decoded via [codec] and pushed into the state — hydrating
 * it from persistence.
 *
 * On every successful commit that mutates the state, the new raw value is
 * encoded via [codec] and written to the store under [key].
 *
 * Example for a Long balance:
 * ```
 * val kv = MultiplatformSettingsKvStore(settings)            // your real KvStore
 * vault { balanceCents bridge KvBridge(kv, "acc:1:balance", LongCodec) }
 * ```
 *
 * To detach, set the bridge to null: `vault { balanceCents bridge null }`.
 */
class KvBridge<T : Any>(private val kv: KvStore, private val key: String, private val codec: Codec<T>) : Bridge<T> {

    override fun observe(observer: (T) -> Unit): Disposable {
        // Replay the persisted value once on attach (load-on-attach).
        kv.get(key)?.let { encoded ->
            val decoded = runCatching { codec.decode(encoded) }.getOrNull()
            if (decoded != null) observer(decoded)
        }
        // The bridge does not push subsequent values on its own; it's a passive
        // store. The Disposable is a no-op since nothing was registered.
        return Disposable { /* no listener to detach */ }
    }

    override fun publish(value: T): Boolean {
        kv.put(key, codec.encode(value))
        return true
    }
}
