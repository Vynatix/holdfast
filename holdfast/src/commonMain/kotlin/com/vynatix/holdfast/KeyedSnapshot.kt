@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

// Keyed state families in snapshots (issue #20, R7; plan D8, D9, D11, D18).
//
// A capture holds every family its scope captures — [SnapshotScope.UserAuthored]
// only the UserAuthored ones — with every entry live at the capture's cut:
// raw values by key, read in the same consistent cut as the declared states
// (an entry an eviction retires before the cut is left out). An empty family
// is captured too. On the wire a family is a JSON object under its name in
// `states`, one member per entry: the key as its `keyCodec` encodes it, the
// value as its `codec` does, or `null` for a Secret family's withheld value.
// A family without both codecs is listed as skipped by its name.
//
// A typed read of an entry goes through the entry's state: by the family's
// name and the entry's key — for a capture, guarded by the capturing store
// instance; for decoded text, with the key encoded by the family's keyCodec
// and the value decoded by its codec.

/** What a capture holds for one keyed state family: its live entries' raw values by key, and its codecs. */
internal class CapturedFamily(
    /** Raw values by key, in the order the entries were created. */
    val entries: Map<Any, Any>,
    val codec: StateCodec<*>?,
    val keyCodec: StateCodec<*>?,
) {
    /** Whether `encode()` can write the family: it needs a codec for its values and one for its keys. */
    val encodable: Boolean get() = codec != null && keyCodec != null

    /**
     * The family's entries as `encode()` writes them — encoded key → encoded
     * value, `null` for each value when [secret] — for the family [name].
     *
     * @throws IllegalStateException if a codec throws, or two keys encode to
     *   the same text; the message names the family, never a key or value.
     */
    fun encodeEntries(
        name: String,
        secret: Boolean,
    ): Map<String, String?> {
        @Suppress("UNCHECKED_CAST")
        val keys = checkNotNull(keyCodec) as StateCodec<Any>

        @Suppress("UNCHECKED_CAST")
        val values = checkNotNull(codec) as StateCodec<Any>
        val texts = LinkedHashMap<String, String?>()
        for ((key, raw) in entries) {
            val text = encodeWith(keys, key) { "a key of keyed state family '$name': its keyCodec" }
            check(text !in texts) {
                "Cannot encode keyed state family '$name': two of its keys encode to the same text, so a decoded " +
                    "snapshot could not tell their entries apart. Its keyCodec must encode different keys differently."
            }
            // A Secret value never reaches its codec: `null` is the withheld marker.
            texts[text] =
                if (secret) null else encodeWith(values, raw) { "an entry of keyed state family '$name': its codec" }
        }
        return texts
    }
}

/** What a capture reads, in its cut, for a keyed entry an eviction has retired: the entry is left out. */
internal object RetiredEntry

/** The cut's read of [state] for a capture: its raw committed value, or [RetiredEntry]. */
internal fun capturedRaw(state: MutableState<*>): Any = if (state.retired) RetiredEntry else state.rawCurrentValue

/** Whether a capture in this scope holds [family]: every scope but UserAuthored, which holds the UserAuthored ones. */
internal fun SnapshotScope.captures(family: KeyedFamily<*, *>): Boolean =
    this !== SnapshotScope.UserAuthored || StateTag.UserAuthored in family.tags

/** [family]'s live entries, for a capture plan to read: each entry's declaration and state. */
internal fun capturedEntries(family: KeyedFamily<*, *>): List<Pair<StateDeclaration<*>, MutableState<*>>> =
    family.committedEntries().map { (_, state) -> checkNotNull(state.declaration) to state }

/** [StoreSnapshot.entry] for [state], an entry of a keyed state family, declared by [decl]. */
internal fun <T : Any> SnapshotContent.keyedEntryFor(
    state: MutableState<T>,
    decl: StateDeclaration<T>,
): SnapshotEntry<T> =
    when (this) {
        is CapturedContent -> capturedKeyedEntry(state, decl)
        is DecodedContent -> decodedKeyedEntry(state, decl)
    }

/** A capture answers its own store's entries, by family and key; a Secret family's value only in a Raw capture. */
private fun <T : Any> CapturedContent.capturedKeyedEntry(
    state: MutableState<T>,
    decl: StateDeclaration<T>,
): SnapshotEntry<T> {
    val entry = checkNotNull(decl.keyed)
    val family = entry.family
    require(originKey == null || family.store.lockOrderKey == originKey) { foreignInstanceMessage(decl) }

    @Suppress("UNCHECKED_CAST")
    val raw = families[family.name]?.entries?.get(entry.key) as T?
    return when {
        raw == null -> SnapshotEntry.Absent
        family.name in tags.secret && !tags.scope.readsSecrets -> Redacted
        else -> SnapshotEntry.Present(state.afterGet(raw))
    }
}

/**
 * Decoded text answers any store's entries by family name and encoded key,
 * decoding the value with the family's codec — never a Secret family's.
 */
private fun <T : Any> DecodedContent.decodedKeyedEntry(
    state: MutableState<T>,
    decl: StateDeclaration<T>,
): SnapshotEntry<T> {
    val entry = checkNotNull(decl.keyed)

    @Suppress("UNCHECKED_CAST")
    val family = entry.family as KeyedFamily<Any, *>
    val texts = familyTexts(family) ?: return SnapshotEntry.Absent
    val key = encodeWith(keyCodecOf(family), entry.key) { "a key of ${family.qualifiedName}: its keyCodec" }
    val text = texts[key]
    return when {
        key !in texts -> SnapshotEntry.Absent
        text == null || StateTag.Secret in family.tags -> Redacted
        else -> {
            val codec =
                checkNotNull(decl.codec) {
                    "Cannot read an entry of ${family.qualifiedName} from a decoded snapshot: the snapshot holds its " +
                        "encoded text, but the family has no codec to decode it with. Declare it with one: " +
                        "keyedState(codec = …, keyCodec = …) { … }."
                }
            SnapshotEntry.Present(state.afterGet(decodeText(decl, codec, text)))
        }
    }
}

/** [StoreSnapshot.keysOf] for this content. */
internal fun <K : Any> SnapshotContent.keysOf(family: KeyedState<K, *>): Set<K> {
    val impl = family as KeyedFamily<K, *>
    return when (this) {
        is CapturedContent -> {
            require(originKey == null || impl.store.lockOrderKey == originKey) {
                "Cannot read the keys of ${impl.qualifiedName} from this snapshot: it was captured from another " +
                    "${impl.store.displayName} instance, and a captured snapshot answers only the families of the " +
                    "store instance that took it. Read it through that instance's family, or decode its encode() to " +
                    "read it by name."
            }
            @Suppress("UNCHECKED_CAST")
            (families[impl.name]?.entries?.keys as Set<K>?)?.toCollection(LinkedHashSet()) ?: emptySet()
        }
        is DecodedContent -> {
            val texts = familyTexts(impl)?.takeIf { it.isNotEmpty() } ?: return emptySet()
            val codec = keyCodecOf(impl)
            texts.keys.mapTo(LinkedHashSet()) { text ->
                runCatching { codec.decode(text) }.getOrElse { failure ->
                    throw SnapshotFormatException(
                        "Cannot decode a key of ${impl.qualifiedName} from the snapshot: its keyCodec threw " +
                            "${failure.describeClass()}. The exception is not attached, because its message may " +
                            "quote the encoded key.",
                    )
                }
            }
        }
    }
}

/**
 * The entries decoded text holds for [family] (encoded key → text or
 * `null`), or `null` when it holds none.
 *
 * @throws SnapshotFormatException when the text holds a single state's value
 *   under the family's name.
 */
private fun DecodedContent.familyTexts(family: KeyedFamily<*, *>): Map<String, String?>? {
    body.families[family.name]?.let { return it }
    if (family.name in body.states) {
        throw SnapshotFormatException(
            "Cannot read ${family.qualifiedName} from the decoded snapshot: the snapshot holds a single value under " +
                "'${family.name}', not a keyed state family.",
        )
    }
    return null
}

/** [family]'s keyCodec, or a teaching failure: decoded text addresses entries by encoded key. */
private fun <K : Any> keyCodecOf(family: KeyedFamily<K, *>): StateCodec<K> =
    checkNotNull(family.spec.keyCodec) {
        "Cannot read ${family.qualifiedName} from a decoded snapshot: a decoded snapshot addresses a family's " +
            "entries by their encoded keys, and the family has no keyCodec. Declare it with one: " +
            "keyedState(codec = …, keyCodec = …) { … }."
    }

/**
 * [value] encoded by [codec]. A throwing codec is reported naming [what]
 * ("a key of …: its keyCodec") and the exception's class, without the
 * exception, whose message may quote the value.
 */
private inline fun <V : Any> encodeWith(
    codec: StateCodec<V>,
    value: V,
    what: () -> String,
): String =
    runCatching { codec.encode(value) }.getOrElse { failure ->
        error(
            "Cannot encode ${what()} threw ${failure.describeClass()}. The exception is not attached, because its " +
                "message may quote the value.",
        )
    }
