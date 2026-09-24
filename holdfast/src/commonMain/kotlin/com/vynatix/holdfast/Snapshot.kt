@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.RestorePolicy.IgnoreUnknown

/**
 * The state of a [Store] at one moment: the raw value of every state its
 * [SnapshotScope] captures (every declared state for [Store.snapshot]), or —
 * for a snapshot [decode]d from text — the encoded text of each state its
 * store could encode. Stored values are RAW — post-`Transformer.set` — so
 * that [Store.restore] can round-trip without re-running the transformer.
 *
 * Snapshots are NOT typed against any particular store instance. Restoring a
 * snapshot from one store into a different store is permitted: each state the
 * destination declares under a name the snapshot holds is restored, a name
 * the destination does not declare is ignored (or rejected, under
 * [RestorePolicy.Strict]), and a declared state the snapshot holds no value
 * for keeps its own. A snapshot of another schema version than the
 * destination's is migrated first, or refused (see [SchemaVersioned]).
 *
 * A keyed state family ([keyedState]) is captured with every entry live at
 * the capture — its raw values by key — under the family's name, which
 * [stateNames] lists ([keysOf] lists its keys); a restore creates the entries
 * it holds and never evicts one.
 *
 * The backing states of `derived` (and `:holdfast-coroutines`'
 * `suspendDerived`) are captured too, so an undo restores them with their
 * sources, but they are not in [stateNames] or [size], never encoded, and take
 * no part in equality: a restore writes them back only into the store
 * instance the snapshot was taken from.
 *
 * For symmetric transformers and untransformed states, the snapshot's stored
 * value is the same as `state.value`. For asymmetric transformers (e.g.
 * [com.vynatix.holdfast.crypto.EncryptingTransformer]), the snapshot stores
 * ciphertext / post-`set` form, and restore writes that form back without
 * re-encrypting. [get] reads the `Transformer.get` view, as `state.value` does.
 *
 * **Leaving memory** (experimental). A state declared with a [StateCodec]
 * (`state(codec = …) { … }`) can be written out: [encode] turns the snapshot
 * into text, and [decode] turns the text back into a snapshot that [restore]
 * accepts — into a new store instance, in another process. A state without a
 * codec is not written; its name is listed in [unencodableStateNames].
 *
 * **Tags** (experimental). A captured snapshot holds the raw value of every
 * state its scope captures, a [StateTag.Secret] state's included, so a
 * [restore] puts it back. But a Secret value is withheld wherever it would be
 * read out or written: [entry] returns [Redacted] (and [get] `null`) for it
 * unless the snapshot was captured with [SnapshotScope.Raw], and [encode],
 * [render] and [toString] of a captured snapshot never show it, in any scope
 * (a decoded snapshot knows no tags; see [render]). [encode] leaves
 * [StateTag.Remote] states out unless asked, and
 * `snapshot(SnapshotScope.UserAuthored)` captures only the states and keyed
 * state families tagged [StateTag.UserAuthored].
 *
 * **Equality.** Snapshots compare by value: two captured snapshots are equal
 * when they are at the same [schemaVersion] and hold the same state names
 * with `==` raw values, and the same keyed state families with the same keys
 * and `==` raw values (whichever store instances took them; which states
 * have a codec, and which codec, plays no part, and neither does the
 * [SnapshotScope] that captured them), and two decoded ones when
 * they hold the same encoded text. A
 * captured snapshot never equals a decoded one; compare their [encode]d text
 * instead. [toString] lists state names only, never values.
 */
class StoreSnapshot internal constructor(
    internal val content: SnapshotContent,
) {
    /** A snapshot made by hand from raw values, as if captured from no particular store (tests). */
    internal constructor(rawValues: Map<String, Any>) : this(CapturedContent(rawValues))

    /** A captured snapshot's raw values by name (empty for a decoded one). */
    internal val rawValues: Map<String, Any>
        get() = (content as? CapturedContent)?.rawValues ?: emptyMap()

    /**
     * Names of the states in this snapshot, except the backing states of
     * `derived`/`suspendDerived`: those are captured too but not listed (see
     * the class KDoc). A captured snapshot lists the states its
     * [SnapshotScope] captures, a keyed state family among them under its
     * name. A decoded snapshot lists every state (and family) its
     * encoded text holds or lists as skipped, [unencodableStateNames]
     * included (the states its capture's scope held, less the
     * [StateTag.Remote] ones unless it was encoded with `includeRemote`).
     */
    val stateNames: Set<String> get() = content.names

    /** Number of [stateNames]; derived backing states are not counted. */
    val size: Int get() = stateNames.size

    /**
     * The schema version of the store this snapshot was taken from: for a
     * captured snapshot, the store's [SchemaVersioned.schemaVersion] (1 for a
     * store that does not implement [SchemaVersioned]); for a decoded one, the
     * version its text records. [encode] writes it. A [restore] into a store
     * of another schema version migrates an older decoded snapshot and
     * refuses the rest (see [SchemaVersioned]).
     *
     * Experimental (issue #20, R2).
     */
    @ExperimentalStoreApi
    val schemaVersion: Int get() = content.schema

    /**
     * The [stateNames] that [encode] cannot write because their state has no
     * [StateCodec] (or, a keyed state family, no `codec` or no `keyCodec`);
     * the encoded text lists them as skipped (a
     * [StateTag.Remote] one only when encoded with `includeRemote`). A
     * decoded snapshot holds no value for them, and a restore of it leaves
     * those states as they are. Derived backing states are never listed.
     *
     * Experimental (issue #20, R1).
     */
    @ExperimentalStoreApi
    val unencodableStateNames: Set<String> get() = content.unencodable

    /**
     * This snapshot as text that [decode] reads back: each state's raw value
     * as its [StateCodec] encodes it (an encrypted state's ciphertext, not its
     * plaintext), and the names of the [unencodableStateNames]. The text is
     * canonical — states sorted by name, one fixed JSON spelling — so a
     * snapshot always encodes to the same text, on every platform. Equality
     * ignores codecs, so two equal snapshots from stores that declare
     * different codecs (or none) can encode differently: to compare what two
     * snapshots would write, compare their `encode()` text:
     *
     * `{"format":"holdfast.store","v":1,"schema":1,"states":{"count":"3"},"skipped":["draft"]}`
     *
     * A keyed state family is written as an object under its name, one
     * member per entry — the key as its `keyCodec` encodes it, the value as
     * its `codec` does — sorted by encoded key:
     * `"docs":{"a":"hello","b":"world"}`. Tags apply to every entry, and
     * never to the keys, which are always written.
     *
     * Tags decide what else is written (a captured snapshot's; decoded text
     * carries no tags, so a decoded snapshot writes back the text it holds):
     *
     * - A [StateTag.Secret] state is written as `null`, the withheld marker,
     *   in every [SnapshotScope] ([SnapshotScope.Raw] included); its codec
     *   never sees the value. A decoded snapshot reads it as [Redacted], and a
     *   restore of that text leaves the state's value as it is.
     * - A [StateTag.Remote] state is left out — neither written nor listed as
     *   skipped — unless [includeRemote] is `true`, so stale synced data does
     *   not persist; a restore of the text leaves it as it is.
     *
     * `decode(s.encode())` holds the same text as `s` for every state `encode`
     * writes: round trips are exact on that encodable projection (the Secret
     * values and, by default, the Remote states are not in it).
     *
     * Experimental (issue #20, R1 and R3).
     *
     * @throws IllegalStateException if a codec throws; the message names the
     *   state and the exception's class, but not the exception, which may quote
     *   the value. Also when two keys of a family encode to the same text.
     */
    @ExperimentalStoreApi
    fun encode(includeRemote: Boolean = false): String = encodeStoreDocument(content.toBody(includeRemote))

    /**
     * What this snapshot holds for [state], read by the state instance: its
     * value ([SnapshotEntry.Present], the `Transformer.get` view, as
     * `state.value` would read it), [SnapshotEntry.Absent] when it holds none,
     * or [Redacted] when it withholds it: a decoded snapshot's `null` text,
     * and the value of a [StateTag.Secret] state (see [State.tags]; a
     * `derived` of one included), which only a snapshot captured with
     * [SnapshotScope.Raw] returns. A Secret state's entry is [Redacted] even
     * where the snapshot's text holds a value for it, without decoding it.
     *
     * A captured snapshot answers the states of the store instance that took
     * it — a derived's state included — and throws for a state of any other
     * instance, even one of the same class. A decoded snapshot answers any
     * store's state by its name, decoding the text with that state's
     * [StateCodec]. Either kind keeps answering after its store is disposed.
     *
     * A decoded snapshot is read as its text was written: a typed read neither
     * checks its [schemaVersion] nor runs [SchemaVersioned.migrate]. Only a
     * [restore] does that, so to read an older snapshot's values under the
     * current schema, restore it and read the store.
     *
     * Experimental (issue #20, R1).
     *
     * @throws IllegalArgumentException for a state of another store instance
     *   (captured snapshots), or one no store declared (a `computed { }`).
     * @throws IllegalStateException when a decoded snapshot holds text for a
     *   state that has no codec; for an entry of a keyed state family, also
     *   when the family has no `keyCodec` (a decoded snapshot addresses
     *   entries by encoded key) or its `keyCodec` throws encoding the key.
     * @throws SnapshotFormatException when the state's codec cannot decode
     *   the text, or when a decoded snapshot holds a keyed state family, not
     *   a single value, under the state's name — or a single value under a
     *   keyed entry's family's name (the message names the state or family,
     *   never the text or a key).
     */
    @ExperimentalStoreApi
    fun <T : Any> entry(state: State<T>): SnapshotEntry<T> = content.entryFor(state)

    /**
     * The value this snapshot holds for [state], or `null` when it holds none
     * (or withholds it: a Secret state's value, outside a
     * [SnapshotScope.Raw] capture): [entry]'s [SnapshotEntry.Present] value.
     * Typed by the state: `snapshot[store.count]` is an `Int?`. Like [entry],
     * it reads a decoded snapshot's text as written, without migrating it.
     *
     * Experimental (issue #20, R1).
     *
     * @throws IllegalArgumentException as [entry].
     * @throws IllegalStateException as [entry].
     * @throws SnapshotFormatException as [entry].
     */
    @ExperimentalStoreApi
    operator fun <T : Any> get(state: State<T>): T? = (entry(state) as? SnapshotEntry.Present<T>)?.value

    /**
     * The keys this snapshot holds entries of [family] for (a keyed state
     * family, [keyedState]): for a captured snapshot, the keys of the
     * family's entries live at its cut, in the order they were created; for
     * a decoded one, the keys its text holds — a Secret family's too — decoded
     * by the family's `keyCodec`, in the text's (sorted) order. Empty when the
     * snapshot holds no entry of the family, or does not hold the family (a
     * [SnapshotScope.UserAuthored] capture of another family, say). Read one
     * entry with `snapshot[family[key]]`. Like [entry], a captured snapshot
     * answers only the families of the store instance that took it, and
     * either kind keeps answering after its store is disposed.
     *
     * Experimental (issue #20, R7).
     *
     * @throws IllegalArgumentException for a family of another store instance
     *   (captured snapshots).
     * @throws IllegalStateException when a decoded snapshot holds keys for a
     *   family that has no `keyCodec`.
     * @throws SnapshotFormatException when the family's `keyCodec` cannot
     *   decode a key, or a decoded snapshot holds a single value, not a
     *   family, under the family's name (the message names the family,
     *   never a key).
     */
    @ExperimentalStoreApi
    fun <K : Any> keysOf(family: KeyedState<K, *>): Set<K> = content.keysOf(family)

    /**
     * This snapshot as text for a person, one line per state sorted by name:
     * a captured state's raw stored value (`toString()`; an encrypted state's
     * ciphertext), a decoded state's encoded text, and the states it holds no
     * value for. A keyed state family shows its entry count, then one line
     * per entry sorted by key: the key (a captured key's `toString()`, a
     * decoded key's encoded text) and its value, `<redacted>` for a Secret
     * family's value. The key is shown even for a Secret family, so do not
     * log a render whose keys are sensitive. In a captured snapshot, a
     * [StateTag.Secret] state's value is never shown, whatever the
     * [SnapshotScope]: its line reads `<redacted>`.
     * A decoded snapshot knows no tags: it shows the text it holds, so a
     * `null` that [encode] wrote reads `<redacted>`, but text from elsewhere
     * (written before the state was tagged Secret, or by another writer) is
     * shown as it is, and [encode] writes it back. For logs and debugging;
     * the layout may change. Use [encode] for text that must be read back.
     *
     * Experimental (issue #20).
     */
    @ExperimentalStoreApi
    fun render(): String = content.render()

    /** Value equality; see the class KDoc. */
    override fun equals(other: Any?): Boolean = other is StoreSnapshot && content.valueEquals(other.content)

    override fun hashCode(): Int = content.valueHashCode()

    /** The schema version and the state names, never a value. */
    override fun toString(): String = "StoreSnapshot(schema=${content.schema}, states=${stateNames.sorted()})"

    /** Experimental (issue #20, R1): holds [decode]. */
    @ExperimentalStoreApi
    companion object {
        /**
         * The snapshot [text] encodes: text [encode] wrote, here or in another
         * process. Restore it with [restore]; read single states with [get].
         * Unknown fields in the text are ignored, so text a later Holdfast
         * writes with more fields still reads.
         *
         * Experimental (issue #20, R1).
         *
         * @throws SnapshotFormatException if [text] is not an encoded store
         *   snapshot this version reads. The message names the problem, an
         *   offset and possibly a state name, but never quotes a state's
         *   value.
         */
        @ExperimentalStoreApi
        fun decode(text: String): StoreSnapshot = StoreSnapshot(DecodedContent(decodeStoreDocument(text)))
    }
}

/**
 * Capture the current raw value of every declared state on this store, at
 * the store's schema version ([StoreSnapshot.schemaVersion]: its
 * [SchemaVersioned.schemaVersion], or 1). This is the experimental
 * `snapshot(SnapshotScope.All)`: a [StateTag.Secret] state's raw value is
 * captured (so [restore] puts it back), but typed reads withhold it
 * ([StoreSnapshot.entry] returns [Redacted], [StoreSnapshot.get] `null`; see
 * [StoreSnapshot]).
 *
 * A declared state that has never been read is materialized first: its
 * initializer runs now, exactly as its first read would run it — so an
 * untouched store's snapshot already holds every state, at its initial value.
 * Every live entry of every keyed state family ([keyedState]) is captured
 * too, in the same cut; no entry is created for this.
 * `snapshot()` takes no store lock for this, but called from inside an action
 * it runs the initializer under that action's locks. An initializer run this
 * way sees committed values only (see [Store.state]). A throwing initializer
 * makes `snapshot()` throw.
 *
 * The values are one consistent cut: a commit applying while the snapshot is
 * taken is either wholly in it or not in it at all. A snapshot taken inside an
 * action captures committed values, not that action's pending writes. It never
 * blocks a writer: it retries while a commit is applying, and lists the keyed
 * entries again when entries keep coming to life while commits keep applying;
 * after a few such retries it holds the creation of new entries back for the
 * moment it takes to list them and read its cut, so it always completes.
 *
 * The returned snapshot is detached from the store — mutations after `snapshot()`
 * do not affect previously-captured snapshots.
 *
 * @throws IllegalStateException if the store is disposed, an initializer
 *   cycle is found (see [Store.state]), or the store declares a schema version
 *   below 1; an exception thrown by an initializer propagates as is.
 */
fun <V : Store<V>> V.snapshot(): StoreSnapshot = captureSnapshot(SnapshotScope.All)

/**
 * [snapshot] in [scope]: which states the capture holds, and whether a
 * [StateTag.Secret] state's value can be read back from it.
 *
 * - [SnapshotScope.All] is the one-argument [snapshot]: every declared state;
 *   a Secret state's typed reads return [Redacted].
 * - [SnapshotScope.UserAuthored] captures exactly the declared states — and
 *   the keyed state families, with every live entry — tagged
 *   [StateTag.UserAuthored] (what a persisted overlay writes), and runs only
 *   their never-read initializers. It holds no `derived` state. Restored, it
 *   leaves every other state as it is. Entries coming to life in a family it
 *   does not capture never make it list again.
 * - [SnapshotScope.Raw] captures what [SnapshotScope.All] does, and its typed
 *   reads return a Secret state's plaintext — in memory only: its [encode]d
 *   text, [StoreSnapshot.render] and `toString` withhold Secret values as in
 *   any scope.
 *
 * Everything else — materialization, the consistent cut, the schema version,
 * detachment from later mutations — is as [snapshot] describes.
 *
 * Experimental (issue #20, R3).
 *
 * @throws IllegalStateException as [snapshot].
 */
@ExperimentalStoreApi
fun <V : Store<V>> V.snapshot(scope: SnapshotScope): StoreSnapshot = captureSnapshot(scope)

/**
 * Restore [snapshot] into this store, atomically, under
 * [RestorePolicy.IgnoreUnknown]: a state name this store does not declare is
 * ignored, and a declared state the snapshot holds no value for keeps its
 * value (its observers do not fire). Implemented as one [action] (its
 * transaction id is `Restore`): on success every restored state's
 * `currentValue` is set to the snapshot's raw value and observers/bridges
 * fire once each; on rollback nothing changes. Called inside another action,
 * it is a savepoint: its writes commit, or roll back, with the enclosing
 * action.
 *
 * The user code a restore runs to plan — initializers of never-read targets
 * and the codecs decoding a decoded snapshot — runs before its action opens,
 * taking no lock of this store (called from inside another action or a frame,
 * it still holds those locks): a target state this store declares but has not
 * materialized yet is materialized then — its initializer sees committed
 * values only, never this restore's writes or those of an enclosing action —
 * and a decoded snapshot's texts are decoded by the states' codecs. A failure
 * there is reported by the action, so it rolls back like one inside it.
 * Middleware, observers and bridges run in the action, as for any action. (A
 * target that a concurrent `removeState`/`clearStates` drops after that is
 * materialized again inside the action, under its locks; an internal state
 * dropped that way fails the restore.)
 * Derived backing states in the snapshot are restored only when this is the
 * store instance that took it (undo); into any other store they are skipped,
 * and so is one that `removeState`/`clearStates` has dropped since. A keyed
 * state family's entries restore into the store's family of that name: an
 * entry whose key is not live is created (its initializer runs) before the
 * action opens, and stays live if the restore then fails; a live entry the
 * snapshot does not hold keeps its value — a restore never evicts.
 *
 * Returns [TransactionResult.Error] (nothing changed) if a target's
 * initializer fails, or if an entry this store declares cannot be restored:
 * a value whose class the state cannot hold (see the experimental overload's
 * type witness), or a decoded entry its state has no codec for or cannot
 * decode — a [RestoreRejectedException] naming each such state. Use the
 * experimental overload to choose another [RestorePolicy] and get a
 * [RestoreReport] of what was ignored. A snapshot of another schema version
 * than this store's is migrated first, or refused with a
 * [SnapshotMigrationException] (see the experimental [SchemaVersioned]).
 *
 * Bridges that were attached when restore is called WILL receive the restored
 * value via their `publish` (commit-time bridge fanout). To avoid this,
 * detach bridges before calling restore.
 *
 * @throws IllegalStateException like [Store.action]: if the store is
 *   disposed, or when called from inside a state initializer (see
 *   [Store.state]) or a schema migration.
 */
fun <V : Store<V>> V.restore(snapshot: StoreSnapshot): TransactionResult<Unit> = runRestore(snapshot, IgnoreUnknown) { }

/**
 * Restore [snapshot] into this store under [policy], and report what it did:
 * the one-argument [restore] (same transaction, same order of work), with the
 * [RestorePolicy] chosen here and a [RestoreReport] as the success value —
 * the states restored, the declared states the snapshot holds no value for
 * (kept as they are), and every skipped entry as a [RestoreIssue] — and,
 * with [sterile], its Remote states reset rather than restored (below).
 *
 * An entry is skipped when this store does not declare its name
 * ([RestoreIssue.UnknownState]); when it is decoded text for a state without
 * a codec ([RestoreIssue.NoCodec]) or text its codec cannot decode
 * ([RestoreIssue.Undecodable]); or when the type witness rejects its value
 * ([RestoreIssue.TypeMismatch]). Under [policy] a skip either stands, and is
 * reported, or fails the whole restore with a [RestoreRejectedException]
 * that names each such state, never its value — nothing changes then. A
 * keyed state family's entries ([keyedState]) are checked one by one, and
 * each skipped entry is an issue naming the family, never the key:
 * [RestoreIssue.Undecodable] also when the family has no `keyCodec`, when its
 * `keyCodec` cannot decode a key, or when the snapshot holds a single value
 * under a family's name (or a family under a state's name). The entries the
 * snapshot holds that are not live are created before the action opens (and
 * stay live, at their initial values, if the restore then fails); a restore
 * never evicts one.
 *
 * The type witness: a state's declared type is erased at runtime, so a
 * captured value is checked against the class of the value the state holds.
 * The same class always fits. A different class is rejected only when either
 * class is a built-in value type (`String`, `Boolean`, `Char` or a primitive
 * number): subclasses, sealed siblings and other classes are never rejected.
 * (A state declared with a supertype of built-in types — `Any`, `Number`,
 * `Comparable` — can therefore be refused a value of another built-in type.)
 * No check runs for a captured snapshot taken by an instance of this store's
 * class (or of a superclass), or for a decoded snapshot, whose values the
 * states' own codecs produced. The skip trusts the class, not its type
 * arguments: for a generic store class (`class Box<T : Any> : Store<Box<T>>`),
 * a `Box<Int>` snapshot restores unchecked into a `Box<String>`. The restore
 * succeeds, and the wrong value surfaces later as a `ClassCastException`
 * where the state is read. The same holds for states whose declarations
 * differ between instances of one class, such as function-local delegated
 * properties. Restore such snapshots only into instances with the same type
 * arguments.
 *
 * Schema versions come first. Before anything else, the restore compares
 * [StoreSnapshot.schemaVersion] with this store's ([SchemaVersioned]; 1 for
 * a store that does not implement it). An older decoded snapshot is upcast
 * by [SchemaVersioned.migrate], on a copy of its text, and the restore then
 * reads the migrated entries. A newer snapshot, a captured one of another
 * version, or a throwing `migrate` fails the restore under every policy with
 * a [SnapshotMigrationException] naming the store and both versions, and
 * nothing changes.
 *
 * **Sterile restore.** With [sterile] `true`, synced data never survives
 * the restore: the snapshot's entries for [StateTag.Remote] states are
 * dropped (neither restored nor reported as issues), and every Remote state
 * this store declares is reset to its initial value instead, in the same
 * transaction — its initializer runs again, as [reset] runs it, and its
 * result is staged raw only where it differs from the state's value, so its
 * observers fire only if it changes. A never-read Remote state is
 * materialized first, before the action opens. A Remote initializer reads
 * the other Remote states at their reset values, this store's other declared
 * states at the values the restore leaves them (restored, recomputed as
 * below, else as the transaction holds them: an enclosing action's pending
 * write, else the committed value), and other stores' states and `derived`
 * states at committed values — so a Remote state computed from restored
 * states is what a fresh store holding the restored values computes. So is
 * a declared state the restore itself brings to life: one neither restored
 * nor Remote that nothing had read before the restore, which the restore
 * first materializes from pre-restore values (a target whose entry it skips
 * or that holds no value, or a state a Remote initializer reads, directly or
 * through other states). The same reset re-runs its initializer, reading as
 * a Remote initializer does, so it holds what a first read after the restore
 * computes — unless a write has committed to it since it came to life. A
 * state live before the restore keeps its value, as a plain restore leaves
 * it. `derived` states are not written back, even into the store that took
 * the snapshot: they recompute from the restored sources once the restore
 * commits. The report lists the reset Remote states in
 * [RestoreReport.sterilized]. A [StateTag.Remote] keyed state family is
 * sterilized the same way: the snapshot's entries of it are dropped, and its
 * live entries (only those: none is created) are reset from its initializer
 * given each key, as [reset] resets them — an entry the restore's
 * transaction has staged for eviction is left to that eviction; the family's
 * name is in [RestoreReport.sterilized]. As after [reset], `removeState`/`clearStates`
 * refuse a state the reset re-ran until the restore's transaction (or the
 * action or frame it joined) ends. A throwing initializer, or an initializer
 * cycle, rolls the whole restore back: nothing changes.
 *
 * Experimental (issue #20, R1; schema versions R2; sterile R3).
 *
 * @throws IllegalStateException like [Store.action]: if the store is
 *   disposed, or when called from inside a state initializer or a
 *   [SchemaVersioned.migrate].
 */
@ExperimentalStoreApi
fun <V : Store<V>> V.restore(
    snapshot: StoreSnapshot,
    policy: RestorePolicy,
    sterile: Boolean = false,
): TransactionResult<RestoreReport> = runRestore(snapshot, policy, sterile) { it }
