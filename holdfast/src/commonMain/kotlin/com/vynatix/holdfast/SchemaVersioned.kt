package com.vynatix.holdfast

// Schema versions of stores and their snapshots (issue #20, R2; plan D12).

/**
 * A store whose schema — its states' names, and the text their codecs write —
 * changes between releases of an app: it numbers the schema, and upcasts the
 * encoded text of an older snapshot before a restore reads it. A `Store`
 * subclass implements it:
 *
 * ```
 * class SettingsStore : Store<SettingsStore>(), SchemaVersioned {
 *     val theme by state(codec = StringCodec) { "light" }
 *     val textSize by state(codec = IntCodec) { 14 }   // "fontSize" in schema 1
 *
 *     override val schemaVersion: Int get() = 2
 *
 *     override fun migrate(from: Int, view: EncodedSnapshotView) {
 *         if (from < 2) view.rename("fontSize", "textSize")
 *     }
 * }
 * ```
 *
 * A store that does not implement it is at schema version 1, and so is every
 * snapshot it takes: its first changed schema is version 2.
 * [StoreSnapshot.schemaVersion] is the version of the store a snapshot was
 * taken from, and [StoreSnapshot.encode] writes it (`"schema":2`).
 *
 * A [restore] compares the snapshot's version with this store's before it
 * does anything else, under every [RestorePolicy]:
 *  - **The same version:** the snapshot restores as it is, and [migrate] does
 *    not run.
 *  - **An older decoded snapshot** ([StoreSnapshot.decode]): [migrate] runs
 *    once, on a copy of the snapshot's encoded text, and the restore then
 *    reads the edited copy. The snapshot itself does not change.
 *  - **A newer snapshot:** the restore fails with a
 *    [SnapshotMigrationException] naming both versions and the store, and
 *    nothing changes. A store cannot know what a later schema means.
 *  - **An older captured snapshot** (from `snapshot()` on a store of another
 *    schema): the restore fails the same way. A captured snapshot holds raw
 *    values, which [migrate] cannot edit; restore
 *    `StoreSnapshot.decode(snapshot.encode())` to migrate its encodable
 *    states.
 *
 * Experimental (issue #20, R2).
 */
@ExperimentalStoreApi
interface SchemaVersioned {
    /**
     * This store's schema version, at least 1. Raise it whenever a snapshot
     * an earlier release wrote would no longer restore as it is: a state
     * renamed or removed, or a codec whose text changed. Read by every
     * `snapshot()` and `restore`, so keep it constant (a getter returning a
     * literal). A version below 1 makes `snapshot()` throw
     * [IllegalStateException], and a restore return
     * [TransactionResult.Error] carrying one.
     */
    val schemaVersion: Int

    /**
     * Upcast [view], the encoded text of a snapshot taken at schema version
     * [from], to [schemaVersion]. A restore calls it once per older decoded
     * snapshot, with [from] below [schemaVersion], so handle every version in
     * between, oldest first: `if (from < 2) …`, then `if (from < 3) …`. The
     * view holds text, not values: a state renamed since has no codec under
     * its old name.
     *
     * It runs while the restore plans, before its action opens: at top level
     * it holds no lock of this store (called inside an action or an
     * `atomic(...)` frame, it runs under the locks those hold). It may read
     * states, and sees committed values, but may not write any store:
     * `mutate`/`update`, `action`, `atomic`, `restore`, `reset()` and `emit`
     * (and `:holdfast-coroutines`' `suspendAction`/`suspendAtomic`) throw
     * [IllegalStateException] in it.
     *
     * A throwing `migrate` fails the restore with a
     * [SnapshotMigrationException] that names the store, both versions and
     * the class of what `migrate` threw, and nothing changes. What it threw
     * is not attached, because its message may quote an encoded value; a
     * write refused inside `migrate`, or a misuse of [view], is attached,
     * because its message names only stores and states.
     */
    fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    )
}

/**
 * A [restore] could not bring a snapshot to the store's schema version (see
 * [SchemaVersioned]): the snapshot is newer than the store, or a captured one
 * of another version, or the store's [SchemaVersioned.migrate] threw. Nothing
 * was changed. The message names the store and both versions, never a state's
 * value; the cause is set only when it is a library exception whose message
 * names stores and states only (a write `migrate` attempted, a misuse of its
 * [EncodedSnapshotView]).
 *
 * Experimental (issue #20, R2).
 */
@ExperimentalStoreApi
class SnapshotMigrationException internal constructor(
    message: String,
    /** The schema version the snapshot was taken at. */
    val snapshotVersion: Int,
    /** The schema version of the store it was restored into. */
    val storeVersion: Int,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** The schema version of a store that does not implement [SchemaVersioned], and of a snapshot made by hand. */
internal const val DEFAULT_SCHEMA_VERSION = 1
