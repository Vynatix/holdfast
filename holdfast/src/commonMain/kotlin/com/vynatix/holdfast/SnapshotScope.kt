@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

// Snapshot scopes (issue #20, R3; plan D9, D13): which declared states a
// capture holds, and whether a Secret state's value can be read back from it.
//
// A Secret value is never redacted in memory: every capture holds its raw
// value, so `restore(snapshot)` stays lossless (the undo idiom). It is
// redacted when it is READ from a snapshot — typed reads outside a Raw
// capture return `Redacted` — and whenever a snapshot is written out:
// `encode()` writes `null` for it, and a captured snapshot's
// `render()`/`toString()` never show it, whatever the scope.

/**
 * Which states a [snapshot] captures, and whether a Secret state's value can
 * be read back from it:
 *
 * | Scope | Captures | `snapshot[secret]` |
 * |---|---|---|
 * | [All] | every declared state and keyed state family | `null` ([Redacted]) |
 * | [UserAuthored] | the states (and families) tagged [StateTag.UserAuthored] | — (never Secret) |
 * | [Raw] | every declared state and keyed state family | the plaintext value |
 *
 * Whatever the scope, the capture's [StoreSnapshot.encode] writes a Secret
 * state as `null`, its [StoreSnapshot.render] and `toString` never show it,
 * and it holds the raw value in memory, so [restore] writes it back.
 *
 * Closed but not exhaustive, like [StateTag]: match on scopes with an `else`
 * branch.
 *
 * Experimental (issue #20, R3).
 */
@ExperimentalStoreApi
abstract class SnapshotScope internal constructor(
    private val name: String,
) {
    /** The scope's name, such as `All`. */
    final override fun toString(): String = name

    /**
     * Every declared state, and the backing states of `derived` (as the
     * stable `snapshot()` captures them). A Secret state reads as [Redacted].
     */
    @ExperimentalStoreApi
    object All : SnapshotScope("All")

    /**
     * Exactly the declared states — and the keyed state families, with every
     * live entry — tagged [StateTag.UserAuthored]: what the user authored,
     * and what a persisted overlay writes. No `derived` backing state, and
     * never a Secret one (the two tags exclude each other).
     */
    @ExperimentalStoreApi
    object UserAuthored : SnapshotScope("UserAuthored")

    /**
     * What [All] captures, with Secret states readable in plaintext through
     * [StoreSnapshot.entry] and [StoreSnapshot.get] — in memory only:
     * `encode()`, `render()` and `toString()` withhold them as in any scope.
     */
    @ExperimentalStoreApi
    object Raw : SnapshotScope("Raw")
}

/** Whether a capture in this scope holds [decl]'s state. */
internal fun SnapshotScope.captures(decl: StateDeclaration<*>): Boolean =
    this !== SnapshotScope.UserAuthored || decl.kind == StateKind.Declared && StateTag.UserAuthored in decl.tags

/** Whether a typed read of a capture in this scope may return a Secret state's value. */
internal val SnapshotScope.readsSecrets: Boolean
    get() = this === SnapshotScope.Raw

/**
 * The snapshot of this store in [scope]: materialize the declared states the
 * scope captures (taking no store lock), then read one consistent cut of them
 * ([captureConsistent] of this store alone).
 *
 * @throws IllegalStateException as [snapshot].
 */
internal fun Store<*>.captureSnapshot(scope: SnapshotScope): StoreSnapshot = captureConsistent(listOf(this), scope)[0]

/**
 * The first half of capturing this store in [scope], the part that runs user
 * code: check it is not disposed, read its schema version, and materialize
 * the declared states [scope] captures. Returns the schema version, for
 * [listCapture].
 */
internal fun Store<*>.prepareCapture(scope: SnapshotScope): Int {
    checkNotDisposed()
    val schema = StoreSchema(this).version
    materializeDeclaredStates { scope.captures(it) }
    return schema
}

/**
 * The second half, which runs no user code: list the states to read — the
 * declared states [scope] captures, then every live entry of the keyed state
 * families it captures — noting first the counters that tell whether an
 * entry can have come to life unlisted ([CaptureMembership]).
 * [CapturePlan.build] turns the values one cut read for them into the
 * snapshot.
 */
internal fun Store<*>.listCapture(
    scope: SnapshotScope,
    schema: Int,
): CapturePlan {
    checkNotDisposed()
    val keyed = registry.keyed
    val commits = keyed.commitsApplied
    val declaredFamilies = keyed.familiesDeclaredVersion
    val captured = registry.materializedInOrder().filter { (decl, _) -> scope.captures(decl) }
    val families = keyed.familiesInOrder().filter { scope.captures(it) }
    val versions = LongArray(families.size)
    val entries =
        families.flatMapIndexed { i, family ->
            versions[i] = family.createdVersion
            capturedEntries(family)
        }
    val membership = CaptureMembership(this, commits, declaredFamilies, families, versions)
    return CapturePlan(this, scope, schema, captured + entries, families, membership)
}

/**
 * The counters a [listCapture] noted before it listed: the store's
 * [KeyedRegistry.commitsApplied] and [KeyedRegistry.familiesDeclaredVersion],
 * then each captured family's [KeyedFamily.createdVersion].
 */
internal class CaptureMembership(
    private val store: Store<*>,
    private val commits: Long,
    private val declaredFamilies: Long,
    private val families: List<KeyedFamily<*, *>>,
    private val versions: LongArray,
) {
    /** Whether a commit of the store has applied since. */
    fun committedSince(): Boolean = store.registry.keyed.commitsApplied != commits

    /** Whether a family was declared, or an entry of a captured family came to life, since. */
    fun grewSince(): Boolean =
        store.registry.keyed.familiesDeclaredVersion != declaredFamilies ||
            families.indices.any { families[it].createdVersion != versions[it] }
}

/** One store's part of a capture: what [listCapture] listed, until a cut reads [states]. */
internal class CapturePlan(
    private val store: Store<*>,
    private val scope: SnapshotScope,
    private val schema: Int,
    private val captured: List<Pair<StateDeclaration<*>, MutableState<*>>>,
    private val families: List<KeyedFamily<*, *>>,
    /** What tells whether the listing can have missed an entry. */
    val membership: CaptureMembership,
) {
    /** The states to read: the declared states in declaration order, then the keyed entries. */
    val states: List<MutableState<*>> = captured.map { it.second }

    /** The snapshot holding [values], the raw values one consistent cut read for [states]. */
    fun build(values: List<Any>): StoreSnapshot {
        val content = Capture(scope)
        families.forEach(content::addFamily)
        captured.forEachIndexed { i, (decl, _) -> content.add(decl, values[i]) }
        return StoreSnapshot(content.build(CaptureOrigin(store.lockOrderKey, store::class), schema))
    }
}

/** The [CapturedContent] of one capture, state by state. */
private class Capture(
    private val scope: SnapshotScope,
) {
    private val declared = LinkedHashMap<String, Any>()
    private val backings = LinkedHashMap<String, Any>()
    private val codecs = HashMap<String, StateCodec<*>>()
    private val secret = HashSet<String>()
    private val remote = HashSet<String>()
    private val families = LinkedHashMap<String, Pair<KeyedFamily<*, *>, LinkedHashMap<Any, Any>>>()

    /** Capture [family], with no entry yet: an empty family is captured too. */
    fun addFamily(family: KeyedFamily<*, *>) {
        families[family.name] = family to LinkedHashMap()
        if (StateTag.Secret in family.tags) secret += family.name
        if (StateTag.Remote in family.tags) remote += family.name
    }

    fun add(
        decl: StateDeclaration<*>,
        raw: Any,
    ) {
        val entry = decl.keyed
        if (entry != null) {
            // Evicted by a commit the cut includes: not in the snapshot.
            if (raw !== RetiredEntry) families.getValue(entry.family.name).second[entry.key] = raw
            return
        }
        if (decl.kind == StateKind.DerivedBacking) {
            backings[decl.name] = raw
            return
        }
        declared[decl.name] = raw
        decl.codec?.let { codecs[decl.name] = it }
        if (StateTag.Secret in decl.tags) secret += decl.name
        if (StateTag.Remote in decl.tags) remote += decl.name
    }

    fun build(
        origin: CaptureOrigin,
        schema: Int,
    ): CapturedContent {
        val captured =
            families.mapValues { (_, captured) ->
                val (family, entries) = captured
                CapturedFamily(entries, family.spec.codec, family.spec.keyCodec)
            }
        return CapturedContent(declared, backings, origin, codecs, schema, CaptureTags(scope, secret, remote), captured)
    }
}
