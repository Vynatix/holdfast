@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

// Restoring keyed state families (issue #20, R7; plan D10, D18).
//
// A snapshot's family restores into the store's family of the same name,
// entry by entry, while the restore plans (before its action opens): an
// entry whose key has no live entry is created now — its initializer runs,
// as a declared target's does — and its value is planned for staging raw,
// like any state's. Each entry is checked on its own — the type witness for
// a captured value, the keyCodec and codec for a decoded one — so a
// RestorePolicy tolerates or refuses each skipped entry (the issue names the
// family, never the key). A restore never evicts: a live entry whose key the
// snapshot does not hold keeps its value (evictAll() in the same action
// first, to drop them). An entry the plan created stays live if the restore
// fails, at its initial value, as a state the plan materialized does.
//
// A sterile restore drops a Remote family's entries, and resets the family's
// live entries instead (Reset.kt).

/**
 * Plans the keyed state families of one restore into [store], adding to the
 * enclosing plan's [writes], [written] entries (by family and key),
 * [restored] names and [issues]. [trusted]: the snapshot was captured by an
 * instance of the store's class, so no type witness runs.
 */
internal class KeyedRestorePlanner(
    private val store: Store<*>,
    private val sterile: Boolean,
    private val trusted: Boolean,
    private val writes: MutableList<PlannedWrite>,
    private val written: MutableSet<Pair<KeyedFamily<*, *>, Any>>,
    private val restored: MutableSet<String>,
    private val issues: MutableList<RestoreIssue>,
) {
    /** Plan the captured family [name]: create its missing entries, and check and plan each value. */
    fun planCaptured(
        name: String,
        family: CapturedFamily,
    ) {
        val target = target(name) ?: return
        for ((key, raw) in family.entries) {
            val decl = entryFor(target, key)
            val mismatch =
                if (trusted) null else typeMismatch(name, raw, checkNotNull(decl.materialized).rawCurrentValue)
            if (mismatch != null) issues += mismatch else write(decl, raw)
        }
    }

    /**
     * Plan the decoded family [name]: decode each entry's key and value with
     * the store family's codecs, creating the entries that are not live. A
     * withheld (`null`) value creates its entry and restores no value.
     */
    fun planDecoded(
        name: String,
        entries: Map<String, String?>,
    ) {
        val target = target(name) ?: return
        val keyCodec = target.spec.keyCodec
        when {
            keyCodec == null -> if (entries.isNotEmpty()) issues += RestoreIssue.Undecodable(name, NO_KEY_CODEC)
            target.spec.codec == null && entries.values.any { it != null } -> issues += RestoreIssue.NoCodec(name)
            else -> entries.forEach { (encodedKey, text) -> planDecodedEntry(name, target, keyCodec, encodedKey, text) }
        }
    }

    /** One decoded entry: its key decoded (a failure is an issue), its entry created, its value planned. */
    private fun planDecodedEntry(
        name: String,
        target: KeyedFamily<*, *>,
        keyCodec: StateCodec<*>,
        encodedKey: String,
        text: String?,
    ) {
        val key = runCatching { keyCodec.decode(encodedKey) }
        val failure = key.exceptionOrNull()
        if (failure != null) {
            issues += RestoreIssue.Undecodable(name, "an entry's key cannot be decoded: ${threw(failure, KEYS)}")
        } else {
            val decl = entryFor(target, key.getOrThrow())
            if (text != null) planDecodedValue(name, decl, text)
        }
    }

    private fun planDecodedValue(
        name: String,
        decl: StateDeclaration<*>,
        text: String,
    ) {
        val codec = checkNotNull(decl.codec)
        runCatching { codec.decode(text) }
            .onSuccess { write(decl, it) }
            .onFailure { failure ->
                val reason = "an entry's value cannot be decoded: ${threw(failure, VALUES)}"
                issues += RestoreIssue.Undecodable(name, reason)
            }
    }

    /**
     * The store's family [name], or `null`: reported as unknown when the
     * store declares none (or as not a family, when it declares a state of
     * that name), and dropped silently when a [sterile] restore resets it.
     */
    private fun target(name: String): KeyedFamily<*, *>? {
        val family = store.registry.keyed.family(name)
        when {
            family == null ->
                issues +=
                    if (store.registry.declaration(name) != null) {
                        RestoreIssue.Undecodable(name, FAMILY_REASON)
                    } else {
                        RestoreIssue.UnknownState(name)
                    }
            sterile && StateTag.Remote in family.tags -> return null
        }
        return family
    }

    private fun write(
        decl: StateDeclaration<*>,
        raw: Any,
    ) {
        val entry = checkNotNull(decl.keyed)
        writes += PlannedWrite(decl, raw)
        written += entry.family to entry.key
        restored += entry.family.name
    }
}

/**
 * The live entry of [key] in [family] — created now when it has none — by
 * its declaration. The entry's state exists when this returns.
 */
private fun entryFor(
    family: KeyedFamily<*, *>,
    key: Any,
): StateDeclaration<*> {
    @Suppress("UNCHECKED_CAST")
    val state = (family as KeyedFamily<Any, *>).stateFor(key)
    return checkNotNull(state.declaration)
}

/**
 * The state a restore's action stages [decl]'s planned value into, when
 * [decl] is a keyed entry: its state while it is live, else — evicted since
 * the plan — the key's live entry, created again if it has none. `null` for
 * any other declaration.
 */
internal fun liveEntryState(decl: StateDeclaration<*>): MutableState<*>? {
    val entry = decl.keyed ?: return null
    @Suppress("UNCHECKED_CAST")
    return decl.materialized?.takeIf { !it.retired } ?: (entry.family as KeyedFamily<Any, *>).stateFor(entry.key)
}

/**
 * The issue for a snapshot entry named [name] that this store does not
 * declare as a state: not a single value when the store declares a keyed
 * state family of that name, unknown otherwise.
 */
internal fun Store<*>.undeclaredStateIssue(name: String): RestoreIssue =
    if (registry.keyed.family(name) != null) {
        RestoreIssue.Undecodable(name, NOT_A_FAMILY_REASON)
    } else {
        RestoreIssue.UnknownState(name)
    }

/**
 * The names a restore reports on — every declared state but the derived
 * backings, and every keyed state family — each with whether it is Remote.
 */
internal fun Store<*>.reportedNames(): List<Pair<String, Boolean>> =
    declarations().filter { it.kind != StateKind.DerivedBacking }.map { it.name to it.isRemote } +
        registry.keyed.familiesInOrder().map { it.name to (StateTag.Remote in it.tags) }

/** "its [codec] threw X", naming the exception's class only: its message may quote the text. */
private fun threw(
    failure: Throwable,
    codec: String,
): String = "its $codec threw ${failure.describeClass()}"

private const val KEYS = "keyCodec"

private const val VALUES = "codec"

private const val NO_KEY_CODEC = "the family has no keyCodec to decode the snapshot's keys with"

private const val FAMILY_REASON = "the snapshot holds a keyed state family under this name, not a single value"

private const val NOT_A_FAMILY_REASON = "the snapshot holds a single value under this name, not a keyed state family"
