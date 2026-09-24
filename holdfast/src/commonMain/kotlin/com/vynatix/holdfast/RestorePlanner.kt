@file:OptIn(StoreInternalApi::class, ExperimentalStoreApi::class)

package com.vynatix.holdfast

// restore(): decide everything first, then stage raw in one action (issue #20,
// R1; plan D10).
//
// The PLAN runs before the restore's action opens — at top level, holding no
// lock of the store — because it runs user code: it materializes every target
// state a never-read one would need (its initializer), and decodes a decoded
// snapshot's texts (the states' codecs). It also checks each captured value
// against its target (the type witness below) and applies the policy. The
// ACTION then only stages the planned raw values, or throws the planned
// failure, so a rejected restore changes nothing and middleware still sees it
// as one failed transaction — inside an atomic(...) frame, one that aborts the
// frame.
//
// The type witness. A state's declared type is erased at runtime, so a restore
// cannot ask whether a value fits it. What it can see is the class of the value
// the state holds now. A value whose class is that same class fits. Otherwise
// it is rejected only when either class is a built-in value type (String,
// Boolean, Char or a primitive number): a state holding one of those and a
// value of another class share no declared type short of `Any`, `Comparable`,
// `Number` or the like. Two other classes — sealed siblings, subclasses, two
// List implementations — are never rejected: nothing tells a sibling from a
// stranger without the declared type. The witness skips values it can trust
// by construction: a captured snapshot taken by an instance of the target's
// class (or of a superclass of it) — the class's declarations produced its
// values — and a decoded snapshot's values, which the target state's own
// codec produced. The class is erased, so a generic store's type arguments
// are not compared: a `Box<Int>` snapshot restores unchecked into a
// `Box<String>`, and the wrong value surfaces as a ClassCastException where
// the state is read. The same holds for states whose declarations differ
// between instances of one class (function-local delegated properties).
//
// A target that removeState/clearStates drops after the plan is resolved
// again by the action: a declared state is materialized again there (its
// initializer then runs under the action's locks), a dropped derived backing
// is skipped, and a dropped internal state — which loses its declaration too
// — fails the restore.

/** One raw value a restore stages into [decl]'s state. [backing]: a derived backing state (same-instance undo). */
private class PlannedWrite(
    val decl: StateDeclaration<*>,
    val raw: Any,
    val backing: Boolean = false,
)

/**
 * Restore [snapshot] into this store under [policy], as one action whose value
 * is [result] of the restore's report: plan, then stage.
 *
 * @throws IllegalStateException like [Store.action]: when the store is
 *   disposed, or inside a state initializer.
 */
internal fun <V : Store<V>, R> V.runRestore(
    snapshot: StoreSnapshot,
    policy: RestorePolicy,
    result: (RestoreReport) -> R,
): TransactionResult<R> {
    checkNotDisposed()
    NoWriteRegion.refuse { "restore $displayName" }
    val planner = RestorePlanner(this, snapshot.content)
    // A failing target initializer is carried into the action, which reports
    // it like one inside the restore, whatever the policy.
    val failure = runCatching { planner.plan() }.exceptionOrNull() ?: planner.rejection(policy)
    return action(Restore(planner.writes, failure) { result(planner.report()) })
}

/**
 * The body of a restore's action. A class rather than a lambda so the
 * transaction's id — the body's simple name — reads `Restore`.
 */
private class Restore<V : Store<V>, R>(
    private val writes: List<PlannedWrite>,
    private val failure: Throwable?,
    private val result: () -> R,
) : (V) -> R {
    override fun invoke(store: V): R {
        failure?.let { throw it }
        val txn = checkNotNull(store.activeTransaction) { "restore() stages into the action it runs in" }
        for (write in writes) {
            // Resolved again, for a state removeState/clearStates dropped since
            // the plan: a declared one is materialized again (its initializer
            // then runs here, under the action's locks); a dropped derived
            // backing is skipped; a dropped internal state lost its
            // declaration with it, so it fails the restore.
            val state =
                write.decl.materialized ?: when {
                    write.backing -> continue
                    write.decl.kind == StateKind.Declared -> materialize(write.decl)
                    else -> error("${store.displayName} state '${write.decl.name}' was removed while restore() ran")
                }
            txn.stagePendingRaw(state, write.raw)
        }
        return result()
    }
}

/** Plans one restore of [content] into [store]: what to stage, and what to report. */
private class RestorePlanner(
    private val store: Store<*>,
    private val content: SnapshotContent,
) {
    val writes = ArrayList<PlannedWrite>()
    private val restored = LinkedHashSet<String>()
    private val issues = ArrayList<RestoreIssue>()

    /**
     * A captured snapshot an instance of the target's class (or a superclass)
     * took: the class's declarations produced its values. The class is erased,
     * so a generic store's type arguments are not compared.
     */
    private val trusted = (content as? CapturedContent)?.originClass?.isInstance(store) == true

    /** Materialize every target and decide every entry. Throws what a target's initializer throws. */
    fun plan() {
        when (content) {
            is CapturedContent -> {
                content.rawValues.forEach { (name, raw) -> planCaptured(name, raw) }
                if (content.originKey == store.lockOrderKey) planBackings(content)
            }
            is DecodedContent -> {
                content.body.states.forEach { (name, text) -> planDecoded(name, text) }
                content.body.families.keys.forEach { name ->
                    if (target(name) != null) issues += RestoreIssue.Undecodable(name, FAMILY_REASON)
                }
            }
        }
    }

    /** The failure [policy] makes of the issues found, or `null` if it tolerates them all. */
    fun rejection(policy: RestorePolicy): RestoreRejectedException? =
        issues
            .filterNot { policy.tolerates(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { RestoreRejectedException(store.displayName, policy, it) }

    /** What the restore did, once it has staged [writes]. */
    fun report(): RestoreReport {
        val skipped = issues.mapTo(HashSet()) { it.stateName }
        val kept =
            store
                .declarations()
                .filter { it.kind != StateKind.DerivedBacking && it.name !in restored && it.name !in skipped }
                .mapTo(LinkedHashSet()) { it.name }
        return RestoreReport(restored.toSet(), kept, issues.toList())
    }

    private fun planCaptured(
        name: String,
        raw: Any,
    ) {
        val decl = target(name) ?: return
        val mismatch = if (trusted) null else typeMismatch(name, raw, materialize(decl).rawCurrentValue)
        if (mismatch != null) issues += mismatch else write(decl, raw)
    }

    /** A withheld value (`null` text) has nothing to restore: its state keeps its value. */
    private fun planDecoded(
        name: String,
        text: String?,
    ) {
        val decl = target(name) ?: return
        val codec = decl.codec
        when {
            text == null -> Unit
            codec == null -> issues += RestoreIssue.NoCodec(name)
            else ->
                runCatching { codec.decode(text) }
                    .onSuccess { write(decl, it) }
                    .onFailure { issues += RestoreIssue.Undecodable(name, "its codec threw ${it.describeClass()}") }
        }
    }

    /** Derived backing states, restored only into the store that captured them (undo), if they still exist. */
    private fun planBackings(content: CapturedContent) {
        content.derivedBackingValues.forEach { (name, raw) ->
            val decl = store.registry.declaration(name)?.takeIf { it.kind == StateKind.DerivedBacking }
            if (decl?.materialized != null) writes += PlannedWrite(decl, raw, backing = true)
        }
    }

    /**
     * The declared state [name] names, materialized; or `null`, reported as
     * unknown, when this store declares no such state (a derived backing's
     * name included: those are private to their store).
     */
    private fun target(name: String): StateDeclaration<*>? {
        val decl = store.registry.declaration(name)?.takeIf { it.kind != StateKind.DerivedBacking }
        if (decl == null) issues += RestoreIssue.UnknownState(name) else materialize(decl)
        return decl
    }

    private fun write(
        decl: StateDeclaration<*>,
        raw: Any,
    ) {
        writes += PlannedWrite(decl, raw)
        restored += decl.name
    }
}

private const val FAMILY_REASON = "the snapshot holds a keyed state family under this name, not a single value"

/** A value class without a simple name (an anonymous object), as [RestoreIssue.TypeMismatch] names it. */
private const val ANONYMOUS = "<anonymous>"

/** The type witness (see the top of this file): a [RestoreIssue.TypeMismatch], or `null` when [raw] may fit. */
private fun typeMismatch(
    name: String,
    raw: Any,
    current: Any,
): RestoreIssue.TypeMismatch? {
    val fits = raw::class == current::class || !raw.isBuiltinValue() && !current.isBuiltinValue()
    if (fits) return null
    return RestoreIssue.TypeMismatch(name, raw.describeClass(ANONYMOUS), current.describeClass(ANONYMOUS))
}

private fun Any.isBuiltinValue(): Boolean =
    when (this) {
        is String, is Boolean, is Char, is Byte, is Short, is Int, is Long, is Float, is Double -> true
        else -> false
    }
