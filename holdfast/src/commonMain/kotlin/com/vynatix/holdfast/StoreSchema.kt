@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

// The schema-version gate of restore (issue #20, R2; plan D12).
//
// A restore compares the snapshot's schema version with the target store's
// FIRST — before it materializes a target or runs a codec — so a snapshot it
// refuses runs no initializer or codec of the store and changes nothing (its
// middleware still sees one failed `Restore` transaction). An older decoded
// snapshot is upcast by the store's `migrate`, which edits a copy of the
// snapshot's encoded text (EncodedSnapshotView) in a no-write region; the plan
// then reads the edited copy as it reads any decoded body. Captured snapshots
// are never migrated: they hold raw values, and `migrate` edits text.

/**
 * [store]'s schema: its version, read once — [SchemaVersioned.schemaVersion],
 * or [DEFAULT_SCHEMA_VERSION] for a store that does not implement it.
 *
 * @throws IllegalStateException if the store declares a version below 1.
 */
internal class StoreSchema(
    private val store: Store<*>,
) {
    private val versioned = store as? SchemaVersioned

    val version: Int = versioned?.schemaVersion?.also(::checkVersion) ?: DEFAULT_SCHEMA_VERSION

    /** Refuse a captured snapshot taken at another schema version: raw values cannot be migrated. */
    fun checkCaptured(snapshotVersion: Int) {
        if (snapshotVersion == version) return
        val why =
            if (snapshotVersion > version) {
                NEWER
            } else {
                "a captured snapshot holds raw values, and $name.migrate edits encoded text. To migrate it, " +
                    "restore StoreSnapshot.decode(snapshot.encode()): its states with a codec migrate, the rest keep " +
                    "their values"
            }
        throw SnapshotMigrationException(message(snapshotVersion, why), snapshotVersion, version)
    }

    /**
     * [body] at this schema version: as it is when it is at this version
     * already; upcast by [SchemaVersioned.migrate] when it is older.
     *
     * @throws SnapshotMigrationException if [body] is newer, or `migrate`
     *   throws.
     */
    fun upcast(body: StoreBody): StoreBody {
        val from = body.schema
        if (from == version) return body
        if (from > version) throw SnapshotMigrationException(message(from, NEWER), from, version)
        // A store without SchemaVersioned is at version 1, the oldest a body can be.
        val migrating = checkNotNull(versioned) { "only a SchemaVersioned store is newer than a snapshot" }
        val view = EncodedSnapshotView(body)
        val frame = MigratingFrame(store, from, version, NoWriteRegion.current())
        val failure =
            runCatching { NoWriteRegion.runMigration(frame) { migrating.migrate(from, view) } }.exceptionOrNull()
        val upcast = view.close(version)
        if (failure != null) {
            // The library exception migrate threw, when it threw one; else the last one it caught.
            val safe = listOfNotNull(frame.refused, view.misuse)
            val cause = safe.firstOrNull { it === failure } ?: safe.firstOrNull()
            throw migrationFailure(from, failure, safeCause = cause)
        }
        return upcast
    }

    /**
     * The failure of a `migrate` that threw [failure]. Only [safeCause] — a
     * write refused inside `migrate`, or an edit its view refused, whose
     * message names stores and states only — is attached; [failure] itself is
     * attached only when it is that exception.
     */
    private fun migrationFailure(
        from: Int,
        failure: Throwable,
        safeCause: Throwable?,
    ): SnapshotMigrationException {
        val threw = "$name.migrate(from = $from) threw ${failure.describeClass()}"
        val why =
            when {
                failure === safeCause -> "$threw, attached as the cause"
                safeCause != null ->
                    "$threw, which is not attached because its message may quote an encoded state value; the " +
                        "exception refusing a write or an edit inside migrate is attached as the cause"
                else -> "$threw, which is not attached because its message may quote an encoded state value"
            }
        return SnapshotMigrationException(message(from, why), from, version, safeCause)
    }

    private val name: String get() = store.displayName

    private fun message(
        snapshotVersion: Int,
        why: String,
    ): String =
        "Cannot restore a snapshot of schema version $snapshotVersion into $name, whose schema version is " +
            "$version: $why. Nothing was changed."

    private fun checkVersion(declared: Int) {
        check(declared >= DEFAULT_SCHEMA_VERSION) {
            "$name.schemaVersion is $declared, and a schema version is at least $DEFAULT_SCHEMA_VERSION: the " +
                "version of every store that does not implement SchemaVersioned, and of the snapshots it takes. " +
                "Number a store's first changed schema ${DEFAULT_SCHEMA_VERSION + 1}."
        }
    }
}

private const val NEWER = "a store cannot read a snapshot that a later schema of it wrote"
