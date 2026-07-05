package com.vynatix.holdfast.bridge

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable

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
 * store { balanceCents bridge KvBridge(kv, "acc:1:balance", LongCodec) }
 * ```
 *
 * To detach, set the bridge to null: `store { balanceCents bridge null }`.
 *
 * ### Decode failure on load — silent drop + overwrite hazard
 *
 * If the persisted payload fails to [Codec.decode] on attach (corrupt bytes, a
 * schema change, a codec swap), the default behavior is to **silently skip**
 * hydration: the state stays at its initializer and nothing is logged. This is
 * a hazard, not a convenience — the very next commit that mutates the state
 * **overwrites the un-decodable payload with a freshly encoded value**,
 * destroying the original bytes before anyone can inspect or migrate them. Pass
 * [onDecodeError] to observe the raw encoded string (and the causing throwable)
 * at the moment the drop happens, so you can quarantine, log, or migrate it
 * before the overwrite. The hook does not change the drop behavior — the state
 * still stays at its initializer; it only gives you a chance to react.
 *
 * ### Save failure — surfaced as an Error, not a rollback
 *
 * [publish] runs during the commit fanout (observers → bridge publish → event
 * drain). A throw from [Codec.encode] or [KvStore.put] propagates out of the
 * publish phase and surfaces the surrounding transaction as
 * [com.vynatix.holdfast.TransactionResult.Error]. It does **not** roll back: by
 * the time publish runs the in-memory commit has already applied and earlier
 * fanout (observers) has already fired. Memory and the external store may
 * legitimately disagree at that point; the caller gets an `Error` naming the
 * persistence exception, not a restored prior state.
 *
 * @param kv Backing key-value store.
 * @param key Storage key under which the encoded value is persisted.
 * @param codec String-codec for [T].
 * @param onDecodeError Optional callback invoked with the raw encoded payload
 *   and the decode throwable when load-on-attach fails to decode. Defaults to
 *   `null` (silent skip). See the decode-failure note above.
 */
class KvBridge<T : Any>(
    private val kv: KvStore,
    private val key: String,
    private val codec: Codec<T>,
    private val onDecodeError: ((encoded: String, cause: Throwable) -> Unit)? = null,
) : Bridge<T> {
    // Decode can fail with any codec-specific throwable (NumberFormatException,
    // SerializationException, …); a broad catch is intentional so no corrupt
    // payload escapes the drop-or-report path.
    @Suppress("TooGenericExceptionCaught")
    override fun observe(observer: (T) -> Unit): Disposable {
        // Replay the persisted value once on attach (load-on-attach).
        kv.get(key)?.let { encoded ->
            val decoded =
                try {
                    codec.decode(encoded)
                } catch (t: Throwable) {
                    // Decode failed: hand the raw payload to the hook (if any) so the
                    // caller can quarantine it before the next commit overwrites it,
                    // then drop it (state stays at its initializer).
                    onDecodeError?.invoke(encoded, t)
                    return@let
                }
            observer(decoded)
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
