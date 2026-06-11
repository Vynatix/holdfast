package com.vynatix.holdfast

/**
 * Captured raw state of every registered property of a [Store] at the moment
 * [Store.snapshot] was called. Stored values are RAW — post-`transformer.set`
 * — so that [Store.restore] can round-trip without re-running the transformer.
 *
 * Snapshots are NOT typed against any particular store instance. Restoring a
 * snapshot from one store into a different store is permitted as long as the
 * destination has states with matching names; foreign state names are rejected.
 *
 * For symmetric transformers and untransformed states, the snapshot's stored
 * value is the same as `state.value`. For asymmetric transformers (e.g.
 * [com.vynatix.holdfast.crypto.EncryptingTransformer]), the snapshot stores
 * ciphertext / post-`set` form, and restore writes that form back without
 * re-encrypting.
 */
class StoreSnapshot internal constructor(
    internal val rawValues: Map<String, Any>,
) {
    /** Names of every state captured in this snapshot. */
    val stateNames: Set<String> get() = rawValues.keys

    /** Number of states in this snapshot. */
    val size: Int get() = rawValues.size
}

/**
 * Capture the current raw value of every registered state on this store.
 *
 * Only states that have been touched (delegate-initialized) at least once are
 * included; states whose delegate has not been read yet are absent from the
 * snapshot. Touch them explicitly first if you need them included.
 *
 * The returned snapshot is detached from the store — mutations after `snapshot()`
 * do not affect previously-captured snapshots.
 */
fun <V : Store<V>> V.snapshot(): StoreSnapshot {
    val raw = mutableMapOf<String, Any>()
    properties.forEach { (name, state) ->
        @Suppress("UNCHECKED_CAST")
        val ms = state as MutableState<Any>
        raw[name] = ms.rawCurrentValue
    }
    return StoreSnapshot(raw.toMap())
}

/**
 * Restore every state in [snapshot] into this store, atomically. Implemented
 * as a single top-level [action]: on success every state's `currentValue` is
 * set to the snapshot's raw value and observers/bridges fire once each;
 * on rollback nothing changes.
 *
 * Throws (caught by the wrapping action and surfaced as
 * [TransactionResult.Error]) if the snapshot contains a state name not
 * registered on this store.
 *
 * Bridges that were attached when restore is called WILL receive the restored
 * value via their `publish` (commit-time bridge fanout). To avoid this,
 * detach bridges before calling restore.
 */
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot): TransactionResult<Unit> =
    action {
        val txn =
            activeTransaction
                ?: error("restore must run inside an action — this should never happen since restore wraps in action")
        snapshot.rawValues.forEach { (name, rawValue) ->
            val state =
                getState(name)
                    ?: error("snapshot contains state '$name' not registered on this store")

            @Suppress("UNCHECKED_CAST")
            val ms =
                state as? MutableState<Any>
                    ?: error("snapshot state '$name' is not a MutableState")
            txn.stagePendingRaw(ms, rawValue)
        }
    }
