@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

// Typed reads of a snapshot through State instances (issue #20, R1; plan D8):
// `snapshot[store.count]` has the state's own type, and no name is written.

/**
 * What a [StoreSnapshot] holds for one state: [StoreSnapshot.entry]'s answer.
 *
 * Experimental (issue #20, R1).
 */
@ExperimentalStoreApi
sealed interface SnapshotEntry<out T : Any> {
    /**
     * The snapshot holds a value for the state: [value] is what the state's
     * `value` would read for it — the `Transformer.get` view of the stored
     * raw value, so an `EncryptingTransformer` state reads plaintext.
     */
    data class Present<out T : Any>(
        val value: T,
    ) : SnapshotEntry<T>

    /**
     * The snapshot holds no value for the state: it was not declared when the
     * snapshot was taken, or (decoded snapshots) it was not in the encoded
     * text or its store could not encode it.
     */
    data object Absent : SnapshotEntry<Nothing>
}

/**
 * The snapshot holds a value for the state but withholds it: an encoded
 * snapshot's `null` for that state. The same marker stands in for a withheld
 * value wherever one is shown.
 *
 * Experimental (issue #20).
 */
@ExperimentalStoreApi
data object Redacted : SnapshotEntry<Nothing>

/** [StoreSnapshot.entry] for this content. */
internal fun <T : Any> SnapshotContent.entryFor(state: State<T>): SnapshotEntry<T> {
    val readable = readableState(state)
    val decl = checkNotNull(readable.declaration)
    return when (this) {
        is CapturedContent -> capturedEntry(readable, decl)
        is DecodedContent -> decodedEntry(readable, decl)
    }
}

/** [state] as a store-declared [MutableState], or a teaching failure. */
private fun <T : Any> readableState(state: State<T>): MutableState<T> {
    @Suppress("UNCHECKED_CAST")
    val readable = state as? MutableState<T>
    require(readable != null && readable.declaration != null) {
        "Cannot read this State from a snapshot: a snapshot holds the states a store declares " +
            "(`val x by state { … }`) and the states of derived(...), and this State is neither — a computed { } " +
            "state, for example, is recomputed on every read and has no value of its own to capture."
    }
    return readable
}

/**
 * A captured snapshot answers the states of the store instance that took it
 * (or, made by hand, any state by name); a derived's state reads its backing.
 */
private fun <T : Any> CapturedContent.capturedEntry(
    state: MutableState<T>,
    decl: StateDeclaration<T>,
): SnapshotEntry<T> {
    require(originKey == null || decl.store.lockOrderKey == originKey) { foreignInstanceMessage(decl) }
    val values = if (decl.kind == StateKind.DerivedBacking) derivedBackingValues else rawValues

    @Suppress("UNCHECKED_CAST")
    val raw = values[decl.name] as T? ?: return SnapshotEntry.Absent
    return SnapshotEntry.Present(state.afterGet(raw))
}

/**
 * A decoded snapshot answers any store's states by name, decoding the text
 * with the reading state's codec. Derived states are never encoded.
 */
private fun <T : Any> DecodedContent.decodedEntry(
    state: MutableState<T>,
    decl: StateDeclaration<T>,
): SnapshotEntry<T> {
    val name = decl.name
    return when {
        decl.kind == StateKind.DerivedBacking || name !in body.states && name !in body.families -> SnapshotEntry.Absent
        name in body.families -> throw familyMismatch(decl)
        else -> {
            val text = body.states[name]
            if (text == null) Redacted else SnapshotEntry.Present(state.afterGet(decodeText(decl, codecOf(decl), text)))
        }
    }
}

/** [decl]'s codec, or a teaching failure: a decoded snapshot has text for it that nothing can decode. */
private fun <T : Any> codecOf(decl: StateDeclaration<T>): StateCodec<T> =
    checkNotNull(decl.codec) {
        "Cannot read ${decl.qualifiedName} from a decoded snapshot: the snapshot holds its encoded text, but the " +
            "state has no codec to decode it with. Declare it with one: state(codec = …) { … }."
    }

/**
 * [text] decoded by [codec] for [decl]'s state. A throwing codec becomes a
 * [SnapshotFormatException] that names the state and the exception's class
 * but carries neither the exception nor the text: either may quote the value.
 */
internal fun <T : Any> decodeText(
    decl: StateDeclaration<*>,
    codec: StateCodec<T>,
    text: String,
): T =
    runCatching { codec.decode(text) }.getOrElse { failure ->
        throw SnapshotFormatException(
            "Cannot decode ${decl.qualifiedName} from the snapshot: its codec threw ${failure.describeClass()}. " +
                "The exception is not attached, because its message may quote the encoded value.",
        )
    }

private fun familyMismatch(decl: StateDeclaration<*>): SnapshotFormatException =
    SnapshotFormatException(
        "Cannot read ${decl.qualifiedName} from the decoded snapshot: the snapshot holds a keyed state family " +
            "under '${decl.name}', not a single value.",
    )

private fun foreignInstanceMessage(decl: StateDeclaration<*>): String =
    "Cannot read ${decl.qualifiedName} from this snapshot: it was captured from another " +
        "${decl.store.displayName} instance, and a captured snapshot answers only the states of the store " +
        "instance that took it (reading by instance, not by name). Read it through that instance's states, or " +
        "decode its encode() to read it by name."

/**
 * The simple name of this object's class, for a message that must not quote
 * the object itself; [anonymous] for a class without one.
 */
internal fun Any.describeClass(anonymous: String = "an exception"): String = this::class.simpleName ?: anonymous
