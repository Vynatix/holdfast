package com.vynatix.holdfast

import kotlin.reflect.KClass

/**
 * One captured state in a [StoreSnapshot]: the raw (post-`transformer.set`)
 * value plus the runtime [KClass] it had at capture time. The class is used by
 * [restore] to reject a cross-store restore that would commit a type-mismatched
 * value into a state.
 */
internal class SnapshotEntry(
    val rawValue: Any,
    val valueClass: KClass<*>,
)

/**
 * Container interfaces whose sibling concrete implementations are the same
 * declared type. `listOf("x")` and `listOf("y", "z")` have different runtime
 * classes (a singleton list vs an array-backed list) but are both `List` — a
 * strict class-identity check would spuriously reject restoring one over the
 * other. Element-type correctness across these is the caller's responsibility.
 */
private val CONTAINER_KINDS: List<KClass<*>> =
    listOf(Collection::class, Map::class)

/**
 * Whether a snapshot [entry]'s captured value may be restored over a
 * [destination] value of the destination state's current type. Compatible when
 * either runtime type is assignable to the other, or both are instances of a
 * common [CONTAINER_KINDS] interface (sibling collection/map impls).
 */
private fun snapshotTypeIsCompatible(
    entry: SnapshotEntry,
    destination: Any,
): Boolean {
    val eitherAssignable =
        entry.valueClass.isInstance(destination) || destination::class.isInstance(entry.rawValue)
    val sameContainerKind =
        CONTAINER_KINDS.any { it.isInstance(entry.rawValue) && it.isInstance(destination) }
    return eitherAssignable || sameContainerKind
}

/**
 * Captured raw state of every registered property of a [Store] at the moment
 * [Store.snapshot] was called. Stored values are RAW — post-`transformer.set`
 * — so that [Store.restore] can round-trip without re-running the transformer.
 *
 * Snapshots are NOT typed against any particular store instance. Restoring a
 * snapshot from one store into a different store is permitted as long as the
 * destination has states with matching names; foreign state names are rejected,
 * and (by default) so are name matches whose runtime types are incompatible —
 * see [restore]'s `validateTypes` parameter.
 *
 * For symmetric transformers and untransformed states, the snapshot's stored
 * value is the same as `state.value`. For asymmetric transformers (e.g.
 * [com.vynatix.holdfast.crypto.EncryptingTransformer]), the snapshot stores
 * ciphertext / post-`set` form, and restore writes that form back without
 * re-encrypting.
 */
class StoreSnapshot internal constructor(
    internal val entries: Map<String, SnapshotEntry>,
) {
    /** Names of every state captured in this snapshot. */
    val stateNames: Set<String> get() = entries.keys

    /** Number of states in this snapshot. */
    val size: Int get() = entries.size
}

/**
 * Capture the current raw value of every registered state on this store.
 *
 * With eager registration (`by state { }` registers at construction via
 * `provideDelegate`), every declared state is present, so a snapshot of a
 * freshly-constructed store captures all of them — no explicit "touch first"
 * step is needed. (A state removed via `removeState`, or one registered
 * dynamically via an internal API, follows the registry as usual.)
 *
 * The returned snapshot is detached from the store — mutations after `snapshot()`
 * do not affect previously-captured snapshots.
 */
fun <V : Store<V>> V.snapshot(): StoreSnapshot {
    val entries = mutableMapOf<String, SnapshotEntry>()
    properties.forEach { (name, state) ->
        @Suppress("UNCHECKED_CAST")
        val ms = state as MutableState<Any>
        val raw = ms.rawCurrentValue
        entries[name] = SnapshotEntry(raw, raw::class)
    }
    return StoreSnapshot(entries.toMap())
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
 * When [validateTypes] is true (the default), each snapshot value's captured
 * runtime type is checked against the destination state's current runtime type
 * before staging; an incompatible pair (neither type assignable to the other)
 * fails the whole restore with an [IllegalStateException] naming the state, the
 * snapshot type, and the destination type — nothing is mutated and no observer
 * fires. Pass `validateTypes = false` for polymorphic states whose value may
 * legitimately change subtype across snapshot/restore (a documented limitation:
 * you then own type-correctness). The type check runs BEFORE staging and uses
 * `stagePendingRaw`, so the "rollback never re-runs `Transformer.set`" contract
 * is untouched.
 *
 * Bridges that were attached when restore is called WILL receive the restored
 * value via their `publish` (commit-time bridge fanout). To avoid this,
 * detach bridges before calling restore.
 */
fun <V : Store<V>> V.restore(
    snapshot: StoreSnapshot,
    validateTypes: Boolean = true,
): TransactionResult<Unit> =
    action {
        val txn =
            activeTransaction
                ?: error("restore must run inside an action — this should never happen since restore wraps in action")
        snapshot.entries.forEach { (name, entry) ->
            val state =
                getState(name)
                    ?: error("snapshot contains state '$name' not registered on this store")

            @Suppress("UNCHECKED_CAST")
            val ms =
                state as? MutableState<Any>
                    ?: error("snapshot state '$name' is not a MutableState")
            if (validateTypes) {
                val destination = ms.rawCurrentValue
                if (!snapshotTypeIsCompatible(entry, destination)) {
                    error(
                        "snapshot state '$name' has type ${entry.valueClass.simpleName} but the " +
                            "destination state currently holds ${destination::class.simpleName} — " +
                            "restoring it would commit a type-mismatched value. Pass " +
                            "validateTypes = false if this state is intentionally polymorphic.",
                    )
                }
            }
            txn.stagePendingRaw(ms, entry.rawValue)
        }
    }
