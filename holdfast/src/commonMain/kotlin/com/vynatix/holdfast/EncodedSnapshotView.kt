package com.vynatix.holdfast

/**
 * The encoded text of a snapshot being migrated, which
 * [SchemaVersioned.migrate] edits: each state's entry by name, as its codec
 * wrote it. The view holds text, never decoded values: a state renamed since
 * the snapshot was taken has no codec under its old name, so only its text can
 * move.
 *
 * An entry is the codec text of a state's raw value, or `null` for a value the
 * snapshot withholds ([Redacted] when read): a restore leaves a withheld
 * state as it is. A state the snapshot's store could not encode (one without
 * a codec, listed in [StoreSnapshot.unencodableStateNames]) has no entry.
 * After `migrate` returns, the restore reads the edited entries as it reads
 * any decoded snapshot, with the store's codecs and its [RestorePolicy]: an
 * entry under a name the store does not declare is a
 * [RestoreIssue.UnknownState], and text a codec cannot read is a
 * [RestoreIssue.Undecodable].
 *
 * The view is a copy: editing it never changes the [StoreSnapshot]. It is
 * valid only while `migrate` runs, and only on that thread; an edit after
 * `migrate` returns throws [IllegalStateException]. [toString] names states,
 * never their text.
 *
 * Experimental (issue #20, R2).
 */
@ExperimentalStoreApi
class EncodedSnapshotView internal constructor(
    body: StoreBody,
) {
    private val entries = LinkedHashMap(body.states)
    private val familyTexts = body.families
    private val skipped = body.skipped
    private var open = true

    /** Set when an edit is refused; a failing migration may attach it, since it quotes no value. */
    internal var misuse: IllegalArgumentException? = null
        private set

    /** The names of the states the snapshot holds an entry for, text or withheld, sorted. A copy. */
    val stateNames: Set<String> get() = entries.keys.sorted().toSet()

    /**
     * The keyed state families the snapshot holds, by name: read-only for
     * now. Families are a later part of issue #20 (R7); until they land, a
     * family comes only from text a later Holdfast wrote.
     */
    val families: Families = Families(familyTexts.keys.sorted().toSet())

    /** Whether the snapshot holds an entry for state [name]: text, or a withheld value. */
    operator fun contains(name: String): Boolean = name in entries

    /**
     * The codec text the snapshot holds for state [name]: `null` when it
     * withholds the value, or holds no entry ([contains] tells which).
     */
    operator fun get(name: String): String? = entries[name]

    /**
     * Set state [name]'s entry to [text]: text the codec of the store's state
     * [name] decodes, or `null` to withhold the value (the restore then leaves
     * the state as it is). Replaces any entry [name] had.
     *
     * @throws IllegalArgumentException if the snapshot holds a keyed state
     *   family under [name].
     */
    fun put(
        name: String,
        text: String?,
    ) {
        checkEditable(name)
        entries[name] = text
    }

    /** Remove state [name]'s entry, so the restore leaves that state as it is. Whether there was one. */
    fun remove(name: String): Boolean {
        checkOpen()
        val had = name in entries
        entries.remove(name)
        return had
    }

    /**
     * Move the entry of state [from] to state [to] — a state renamed between
     * schema versions — replacing any entry [to] had. Returns `false`, and
     * changes nothing, when the snapshot holds no entry for [from].
     *
     * @throws IllegalArgumentException if the snapshot holds a keyed state
     *   family under [to].
     */
    fun rename(
        from: String,
        to: String,
    ): Boolean {
        checkEditable(to)
        if (from !in entries) return false
        if (from != to) entries[to] = entries.remove(from)
        return true
    }

    /** The state and family names, never their text. */
    override fun toString(): String = "EncodedSnapshotView(states=$stateNames, families=${families.names})"

    /**
     * The keyed state families of an [EncodedSnapshotView].
     *
     * Experimental (issue #20, R2): members to read and edit families come
     * with keyed states (R7).
     */
    @ExperimentalStoreApi
    class Families internal constructor(
        /** The names of the families the snapshot holds, sorted. */
        val names: Set<String>,
    ) {
        override fun toString(): String = "Families(names=$names)"
    }

    /** The edited body, at schema version [schema]; the view refuses edits from now on. */
    internal fun close(schema: Int): StoreBody {
        open = false
        return StoreBody(schema, entries.toMap(), familyTexts, skipped - entries.keys)
    }

    private fun checkOpen() {
        check(open) {
            "This EncodedSnapshotView is closed: it is valid only while the migrate(from, view) it was passed " +
                "runs. Edit the snapshot inside migrate."
        }
    }

    private fun checkEditable(name: String) {
        checkOpen()
        if (name !in familyTexts) return
        val refused =
            IllegalArgumentException(
                "Cannot give state '$name' an entry: the snapshot holds a keyed state family under that name, and " +
                    "a name holds either one state's entry or one family.",
            )
        misuse = refused
        throw refused
    }
}
