@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

/**
 * Captured raw state of every declared property of a [Store] at the moment
 * [Store.snapshot] was called. Stored values are RAW — post-`transformer.set`
 * — so that [Store.restore] can round-trip without re-running the transformer.
 *
 * Snapshots are NOT typed against any particular store instance. Restoring a
 * snapshot from one store into a different store is permitted as long as the
 * destination declares states with matching names; foreign state names are
 * rejected.
 *
 * The backing states of `derived` (and `:holdfast-coroutines`'
 * `suspendDerived`) are captured too, so an undo restores them with their
 * sources, but they are not in [stateNames] or [size]: a restore writes them
 * back only into the store instance the snapshot was taken from.
 *
 * For symmetric transformers and untransformed states, the snapshot's stored
 * value is the same as `state.value`. For asymmetric transformers (e.g.
 * [com.vynatix.holdfast.crypto.EncryptingTransformer]), the snapshot stores
 * ciphertext / post-`set` form, and restore writes that form back without
 * re-encrypting.
 */
class StoreSnapshot internal constructor(
    /** Declared (and internally registered) states by name. */
    internal val rawValues: Map<String, Any>,
    /** Derived backing states by name; restored only into the store with [originKey]. */
    internal val derivedBackingValues: Map<String, Any> = emptyMap(),
    /** [Store.lockOrderKey] of the store captured, or `null` for a snapshot made by hand. */
    internal val originKey: Long? = null,
) {
    /**
     * Names of the states captured in this snapshot, except the backing states
     * of `derived`/`suspendDerived`: those are captured too but not listed (see
     * the class KDoc). These are the names [restore] requires the target store
     * to declare.
     */
    val stateNames: Set<String> get() = rawValues.keys

    /** Number of [stateNames]; derived backing states are not counted. */
    val size: Int get() = rawValues.size
}

/**
 * Capture the current raw value of every declared state on this store.
 *
 * A declared state that has never been read is materialized first: its
 * initializer runs now, exactly as its first read would run it — so an
 * untouched store's snapshot already holds every state, at its initial value.
 * `snapshot()` takes no store lock for this, but called from inside an action
 * it runs the initializer under that action's locks. An initializer sees
 * committed values only (see [Store.state]). A throwing initializer makes
 * `snapshot()` throw.
 *
 * The values are one consistent cut: a commit applying while the snapshot is
 * taken is either wholly in it or not in it at all. A snapshot taken inside an
 * action captures committed values, not that action's pending writes.
 *
 * The returned snapshot is detached from the store — mutations after `snapshot()`
 * do not affect previously-captured snapshots.
 *
 * @throws IllegalStateException if the store is disposed, or an initializer
 *   cycle is found (see [Store.state]); an exception thrown by an initializer
 *   propagates as is.
 */
fun <V : Store<V>> V.snapshot(): StoreSnapshot {
    materializeDeclaredStates()
    val captured = registry.materializedInOrder()
    val values = readConsistent(captured.map { it.second })
    val declared = LinkedHashMap<String, Any>()
    val backings = LinkedHashMap<String, Any>()
    captured.forEachIndexed { i, (decl, _) ->
        val into = if (decl.kind == StateKind.DerivedBacking) backings else declared
        into[decl.name] = values[i]
    }
    return StoreSnapshot(declared, backings, originKey = lockOrderKey)
}

/**
 * Restore every state in [snapshot] into this store, atomically. Implemented
 * as one [action]: on success every state's `currentValue` is set to the
 * snapshot's raw value and observers/bridges fire once each; on rollback
 * nothing changes. Called inside another action, it is a savepoint: its
 * writes commit, or roll back, with the enclosing action.
 *
 * A target state this store declares but has not materialized yet is
 * materialized first, inside the action (under its lock) and before anything
 * is staged; like every initializer, it sees committed values only, never
 * this restore's writes or those of an enclosing action. Derived backing
 * states in the snapshot are restored only when this is the store instance
 * that took it (undo); into any other store they are skipped, and so is one
 * that `removeState`/`clearStates` has dropped since.
 *
 * Throws (caught by the wrapping action and surfaced as
 * [TransactionResult.Error]) if the snapshot contains a state name this store
 * does not declare, or if a target's initializer fails.
 *
 * Bridges that were attached when restore is called WILL receive the restored
 * value via their `publish` (commit-time bridge fanout). To avoid this,
 * detach bridges before calling restore.
 *
 * @throws IllegalStateException like [Store.action]: if the store is
 *   disposed, or when called from inside a state initializer (see
 *   [Store.state]).
 */
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot): TransactionResult<Unit> {
    val sameInstance = snapshot.originKey == lockOrderKey
    return action {
        val txn =
            activeTransaction
                ?: error("restore must run inside an action — this should never happen since restore wraps in action")
        val targets = snapshot.rawValues.map { (name, rawValue) -> restoreTarget(name) to rawValue }
        targets.forEach { (state, rawValue) -> txn.stagePendingRaw(state, rawValue) }
        if (sameInstance) {
            snapshot.derivedBackingValues.forEach { (name, rawValue) ->
                // Gone only if clearStates()/removeState() dropped it since; nothing to restore then.
                registry.declaration(name)?.materialized?.let { txn.stagePendingRaw(it, rawValue) }
            }
        }
    }
}

/** The live state [name] names on this store, materialized from its declaration if it is not live yet. */
private fun Store<*>.restoreTarget(name: String): MutableState<*> {
    val decl = registry.declaration(name) ?: error("snapshot contains state '$name' not registered on this store")
    return decl.materialized ?: materialize(decl)
}
