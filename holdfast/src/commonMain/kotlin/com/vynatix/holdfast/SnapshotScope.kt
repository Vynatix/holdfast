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
 * | [All] | every declared state | `null` ([Redacted]) |
 * | [UserAuthored] | the states tagged [StateTag.UserAuthored] | — (never Secret) |
 * | [Raw] | every declared state | the plaintext value |
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
     * Exactly the declared states tagged [StateTag.UserAuthored]: what the
     * user authored, and what a persisted overlay writes. No `derived`
     * backing state, and never a Secret one (the two tags exclude each other).
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
 * scope captures (taking no store lock), then read one consistent cut of them.
 *
 * @throws IllegalStateException as [snapshot].
 */
internal fun Store<*>.captureSnapshot(scope: SnapshotScope): StoreSnapshot {
    checkNotDisposed()
    val schema = StoreSchema(this).version
    materializeDeclaredStates { scope.captures(it) }
    val captured = registry.materializedInOrder().filter { (decl, _) -> scope.captures(decl) }
    val values = readConsistent(captured.map { it.second })
    val content = Capture(scope)
    captured.forEachIndexed { i, (decl, _) -> content.add(decl, values[i]) }
    return StoreSnapshot(content.build(CaptureOrigin(lockOrderKey, this::class), schema))
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

    fun add(
        decl: StateDeclaration<*>,
        raw: Any,
    ) {
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
    ): CapturedContent = CapturedContent(declared, backings, origin, codecs, schema, CaptureTags(scope, secret, remote))
}
